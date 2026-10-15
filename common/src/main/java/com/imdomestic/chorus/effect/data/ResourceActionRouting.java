package com.imdomestic.chorus.effect.data;

import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import java.util.function.Function;

/** Prepare ordinary DSL operations against one frozen authority snapshot; publication remains the coordinator's job. */
final class ResourceActionRouting {
    private ResourceActionRouting() {}
    static boolean supports(Action action) {
        return action instanceof Action.InitializeResource || action instanceof Action.GrantResource
                || action instanceof Action.SpendResource || action instanceof Action.RefundCost
                || action instanceof Action.RefundRetainedCost || action instanceof Action.GrantFullCharge
                || action instanceof ResourceCapacityActions.Resize || action instanceof ResourceCapacityActions.ResizeAbility
                || action instanceof LinkedRechargeActions.Complete || action instanceof EnergyActions.Grant
                || action instanceof EnergyActions.GrantAbility || action instanceof EnergyActions.ObserveAbility
                || action instanceof Action.CaptureValue;
    }
    private static double number(Value value, Evaluation e, Unit unit) {
        var measured = value.evaluate(e); Validation.same(measured.unit(), unit); return measured.value();
    }
    private static ResourceRoutes.Plan write(ResourceRequests.Request request, Function<ResourceRequests.Receipt, RuleEngine.ActionResult> result) {
        return ResourceRoutes.Plan.write(request, result);
    }
    static ResourceRoutes.Plan prepare(Action action, Evaluation e) {
        if (action instanceof Action.InitializeResource a) {
            var key = new ResourceState.Key(e.target(a.target()), a.resource()); e.resourceDefinition(a.resource());
            e.resourceView().orElseThrow().owner(key.holder());
            return write(new ResourceMutations.Initialize(key, e.origin()), r -> {
                var initialized = (ResourceMutations.Initialized) r; return new Action.Initialized(initialized.account(), initialized.created());
            });
        }
        if (action instanceof Action.GrantResource a) {
            var account = e.resource(a.resource(), a.target()); var requested = number(a.requested(), e, Unit.CHARGE);
            var scaled = a.scaled().map(v -> number(v, e, Unit.CHARGE)).orElse(requested);
            return write(new ResourceRequests.Grant(account.key(), requested, scaled, e.origin()), r -> ((ResourceRequests.Granted) r).grant());
        }
        if (action instanceof Action.SpendResource a)
            return write(new ResourceMutations.Spend(e.resource(a.resource(), a.target()).key(), number(a.amount(), e, Unit.CHARGE), e.origin()), r -> ((ResourceMutations.Spent) r).spent());
        if (action instanceof Action.RefundCost a)
            return write(new ResourceMutations.RefundPayment(e.cost(a.cost()).operation(), number(a.fraction(), e, Unit.MULTIPLIER), e.origin()), r -> ((ResourceMutations.PaymentRefunded) r).refund());
        if (action instanceof Action.RefundRetainedCost a) {
            e.results().get(a.cost()).requireRetainedCost(); var handle = (RetainedCosts.Handle) e.context().bindings().get(a.cost());
            return write(new ResourceRequests.Refund(e.resourceView().orElseThrow().domain(), handle, number(a.fraction(), e, Unit.MULTIPLIER), e.origin()),
                    r -> new RetainedCosts.Refunded(((ResourceRequests.Refunded) r).refund()));
        }
        if (action instanceof Action.GrantFullCharge a) {
            var account = e.resource(a.resource(), a.target()); double count = number(a.charges(), e, Unit.COUNT);
            Numbers.nonnegative(count, "full charges");
            if (count > Integer.MAX_VALUE || count != Math.rint(count)) throw new IllegalArgumentException("Expected integral full charges");
            return write(new ResourceMutations.FullCharges(account.key(), (int) count, e.origin()), r -> ((ResourceMutations.FullGranted) r).grant());
        }
        if (action instanceof ResourceCapacityActions.Resize a)
            return write(new ResourceMutations.Resize(e.resource(a.resource(), a.target()).key(), number(a.capacity(), e, Unit.CHARGE), e.origin()), r -> ((ResourceMutations.Resized) r).resize());
        if (action instanceof ResourceCapacityActions.ResizeAbility a) {
            var observed = EnergyActions.observeAbility(e, a.slot(), a.target());
            if (observed.account().isEmpty()) return ResourceRoutes.Plan.query(new ResourceCapacityActions.AbilityResult(observed.holder(), a.slot(), observed.ability(),
                    observed.ability().isEmpty() ? ResourceCapacityActions.AbilityOutcome.NO_SELECTION : ResourceCapacityActions.AbilityOutcome.NO_RESOURCE, Optional.empty()));
            if (!e.resourceDefinition(observed.observed().key().resource()).resizable())
                return ResourceRoutes.Plan.query(new ResourceCapacityActions.AbilityResult(observed.holder(), a.slot(), observed.ability(), ResourceCapacityActions.AbilityOutcome.NOT_RESIZABLE, Optional.empty()));
            return write(new ResourceMutations.Resize(observed.observed().key(), number(a.capacity(), e, Unit.CHARGE), e.origin()),
                    r -> new ResourceCapacityActions.AbilityResult(observed.holder(), a.slot(), observed.ability(), ResourceCapacityActions.AbilityOutcome.RESIZED, Optional.of(((ResourceMutations.Resized) r).resize())));
        }
        if (action instanceof LinkedRechargeActions.Complete a) {
            var meter = e.resource(a.progress(), a.target()); var uses = e.resource(a.resource(), a.target());
            var completed = LinkedRecharge.complete(meter, uses).completed();
            var amount = completed ? a.amount().map(v -> number(v, e, Unit.CHARGE)) : Optional.<Double>empty();
            return write(new ResourceMutations.CompleteRecharge(meter.key(), uses.key(), amount, e.origin()), r -> ((ResourceMutations.Recharged) r).recharge());
        }
        if (action instanceof EnergyActions.Grant a) {
            var scaling = EnergyActions.scale(e, a.resource(), a.target(), a.amount(), a.basis(), a.referenceFactors(), a.tags(), a.numbers(), true);
            return energy(e, e.resource(a.resource(), a.target()), scaling, gain -> gain);
        }
        if (action instanceof EnergyActions.GrantAbility a) {
            var observed = EnergyActions.observeAbility(e, a.slot(), a.target());
            Function<EnergyActions.Gain, RuleEngine.ActionResult> wrap = gain -> new EnergyActions.AbilityResult(observed.holder(), a.slot(), observed.ability(), EnergyActions.AbilityOutcome.GRANTED, Optional.of(gain));
            if (observed.account().isEmpty()) return ResourceRoutes.Plan.query(new EnergyActions.AbilityResult(observed.holder(), a.slot(), observed.ability(),
                    observed.ability().isEmpty() ? EnergyActions.AbilityOutcome.NO_SELECTION : EnergyActions.AbilityOutcome.NO_RESOURCE, Optional.empty()));
            if (observed.separateRecharge() && observed.observed().value() == observed.observed().capacity())
                return ResourceRoutes.Plan.query(new EnergyActions.AbilityResult(observed.holder(), a.slot(), observed.ability(), EnergyActions.AbilityOutcome.ALREADY_FULL, Optional.empty()));
            var definition = e.selectedAbility(observed.holder(), a.slot()).orElseThrow();
            var scaling = EnergyActions.scale(e, observed.rechargeObserved().key().resource(), a.target(), a.amount(), a.basis(), a.referenceFactors(), a.tags(), a.numbers(), true);
            if (definition.rechargeYieldProfile().isEmpty()) return energy(e, observed.rechargeObserved(), scaling, wrap);
            Optional<CalculationProfile.Result> yield = Optional.empty();
            if (CyclicRecharge.completes(observed.rechargeObserved(), scaling.scaled())) {
                var view = e.resourceView().orElseThrow().owner(observed.holder());
                var query = ResourceFacts.change(observed.rechargeObserved(), observed.rechargeObserved(), e.origin(), "grant");
                yield = Optional.of(e.program().orElseThrow().calculate(view.state(), observed.holder(), query,
                        definition.rechargeYieldProfile().orElseThrow(), new Measure(1, Unit.CHARGE), List.of(), Optional.of(view)));
                Validation.same(yield.orElseThrow().output().unit(), Unit.CHARGE);
            }
            var actualYield = yield;
            return write(new ResourceMutations.GrantCyclic(observed.rechargeObserved().key(), observed.observed().key(), scaling.normalized().requested(), scaling.scaled(),
                    yield.map(r -> r.output().value()).orElse(0.0), e.origin()), r -> wrap.apply(new EnergyActions.CyclicResult(scaling, ((ResourceMutations.CyclicGranted) r).recharge(), actualYield)));
        }
        if (action instanceof EnergyActions.ObserveAbility a) return ResourceRoutes.Plan.query(EnergyActions.observeAbility(e, a.slot(), a.target()));
        if (action instanceof Action.CaptureValue) return ResourceRoutes.Plan.query(((RuleEngine.Local<?>) action.execute(e)).result());
        throw new IllegalArgumentException("Unsupported resource action: " + action.getClass().getName());
    }
    private static ResourceRoutes.Plan energy(Evaluation e, ResourceState account, EnergyActions.Scaling scaling, Function<EnergyActions.Gain, RuleEngine.ActionResult> wrap) {
        return write(new ResourceRequests.Grant(account.key(), scaling.normalized().requested(), scaling.scaled(), e.origin()),
                r -> wrap.apply(new EnergyActions.Result(scaling.normalized(), scaling.recipient(), scaling.calculation(), ((ResourceRequests.Granted) r).grant())));
    }
}
