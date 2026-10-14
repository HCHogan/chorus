package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Immutable owner-routed primitives. Expressions and gain/yield profiles are resolved by the caller. */
public final class ResourceMutations {
    private ResourceMutations() {}
    public sealed interface Request extends ResourceRequests.Request permits Initialize, Spend, FullCharges, Resize, CompleteRecharge, GrantCyclic, RefundPayment {}
    public record Initialize(ResourceState.Key account, BuffInstance.Origin origin) implements Request {
        public Initialize { address(account, origin); }
    }
    public record Spend(ResourceState.Key account, double amount, BuffInstance.Origin origin) implements Request {
        public Spend { address(account, origin); Numbers.nonnegative(amount, "resource cost"); }
    }
    public record FullCharges(ResourceState.Key account, int count, BuffInstance.Origin origin) implements Request {
        public FullCharges { address(account, origin); if (count < 0) throw new IllegalArgumentException("Negative full charges"); }
    }
    public record Resize(ResourceState.Key account, double capacity, BuffInstance.Origin origin) implements Request {
        public Resize { address(account, origin); Numbers.nonnegative(capacity, "resource capacity"); if (capacity == 0) throw new IllegalArgumentException("Zero resource capacity"); }
    }
    public record CompleteRecharge(ResourceState.Key progress, ResourceState.Key uses, Optional<Double> amount, BuffInstance.Origin origin) implements Request {
        public CompleteRecharge { pair(progress, uses, origin); Objects.requireNonNull(amount); amount.ifPresent(v -> Numbers.nonnegative(v, "recharge yield")); }
    }
    public record GrantCyclic(ResourceState.Key progress, ResourceState.Key uses, double requested, double scaled, double cycleYield, BuffInstance.Origin origin) implements Request {
        public GrantCyclic {
            pair(progress, uses, origin); Numbers.nonnegative(requested, "requested energy");
            Numbers.nonnegative(scaled, "scaled energy"); Numbers.nonnegative(cycleYield, "recharge yield");
        }
    }
    /** The payment must already exist in the active caller frame. A string alone never authorizes credit. */
    public record RefundPayment(String payment, double fraction, BuffInstance.Origin origin) implements Request {
        public RefundPayment {
            if (payment == null || payment.isBlank()) throw new IllegalArgumentException("Missing payment identity");
            Objects.requireNonNull(origin); Numbers.fraction(fraction, "refund fraction");
        }
    }
    private static void address(ResourceState.Key account, BuffInstance.Origin origin) { Objects.requireNonNull(account); Objects.requireNonNull(origin); }
    private static void pair(ResourceState.Key progress, ResourceState.Key uses, BuffInstance.Origin origin) {
        address(progress, origin); Objects.requireNonNull(uses);
        if (!progress.holder().equals(uses.holder()) || progress.equals(uses)) throw new IllegalArgumentException("Recharge needs distinct accounts of one holder");
    }
    public sealed interface Receipt extends ResourceRequests.Receipt permits Initialized, Spent, FullGranted, Resized, Recharged, CyclicGranted, PaymentRefunded {
        ResourceAuthority.Lease lease();
        @Override default Optional<ResourceAuthority.Lease> recipient() { return Optional.of(lease()); }
    }
    public record Initialized(Initialize request, ResourceDefinition definition, Optional<ResourceState> before,
            ResourceState account, ResourceAuthority.Lease lease) implements Receipt {
        public Initialized {
            Objects.requireNonNull(request); Objects.requireNonNull(definition); Objects.requireNonNull(before); Objects.requireNonNull(lease);
            definition.validate(account);
            if (!request.account().equals(account.key()) || !account.equals(before.orElseGet(() -> definition.initialize(account.key().holder(), account.timeMicros()))))
                throw new IllegalArgumentException("Initialization receipt differs from request");
        }
        public boolean created() { return before.isEmpty(); }
    }
    public record Spent(Spend request, ResourceState before, Resources.SpendResult spent, ResourceAuthority.Lease lease,
            String caller, RuleEngine.OperationId operation) implements Receipt, Resources.CostResult {
        public Spent {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            if (!request.account().equals(before.key()) || !spent.equals(Resources.spend(before, payment(caller, operation), request.amount())))
                throw new IllegalArgumentException("Spend receipt differs from request");
        }
        @Override public Resources.CostReceipt receipt() { return spent.receipt(); }
    }
    public record FullGranted(FullCharges request, ResourceResult grant, ResourceAuthority.Lease lease) implements Receipt {
        public FullGranted {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            if (!request.account().equals(grant.before().key()) || !grant.equals(Resources.grantFullCharges(grant.before(), request.count())))
                throw new IllegalArgumentException("Full-charge receipt differs from request");
        }
    }
    public record Resized(Resize request, Resources.ResizeResult resize, ResourceAuthority.Lease lease) implements Receipt {
        public Resized {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            if (!request.account().equals(resize.before().key()) || !resize.equals(Resources.resize(resize.before(), request.capacity())))
                throw new IllegalArgumentException("Resize receipt differs from request");
        }
    }
    public record Recharged(CompleteRecharge request, LinkedRecharge.Result recharge, ResourceAuthority.Lease lease) implements Receipt {
        public Recharged {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            var meter = recharge.progressBefore(); var uses = recharge.charges().before();
            var expected = request.amount().map(amount -> LinkedRecharge.complete(meter, uses, amount)).orElseGet(() -> LinkedRecharge.complete(meter, uses));
            if (!request.progress().equals(meter.key()) || !request.uses().equals(uses.key()) || !recharge.equals(expected))
                throw new IllegalArgumentException("Linked recharge receipt differs from request");
        }
    }
    public record CyclicGranted(GrantCyclic request, CyclicRecharge.Result recharge, ResourceAuthority.Lease lease) implements Receipt {
        public CyclicGranted {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            if (!request.progress().equals(recharge.progressBefore().key()) || !request.uses().equals(recharge.uses().before().key())
                    || !recharge.equals(CyclicRecharge.grant(recharge.progressBefore(), recharge.uses().before(), request.scaled(), request.cycleYield())))
                throw new IllegalArgumentException("Cyclic recharge receipt differs from request");
        }
    }
    public record PaymentRefunded(RefundPayment request, Resources.CostReceipt before, Resources.RefundResult refund,
            ResourceAuthority.Lease lease) implements Receipt, Resources.CostResult {
        public PaymentRefunded {
            Objects.requireNonNull(request); Objects.requireNonNull(lease);
            if (!request.payment().equals(before.operation()) || !refund.equals(Resources.refund(refund.grant().before(), before, request.fraction())))
                throw new IllegalArgumentException("Payment refund receipt differs from request");
        }
        @Override public Resources.CostReceipt receipt() { return refund.receipt(); }
    }
    /** Domain and monotonic frame identity prevent different callers from creating the same refundable payment. */
    public static String payment(String caller, RuleEngine.OperationId operation) {
        new ResourceAuthority.Lease(caller, 1); Objects.requireNonNull(operation);
        if (operation.frame() < 1 || operation.pc() < 0 || operation.invocation() < 0) throw new IllegalArgumentException("Invalid resource operation identity");
        return "resource/domain/" + caller + "/frame/" + operation.frame() + "/pc/" + operation.pc() + "/invocation/" + operation.invocation();
    }
    public record Applied(ResourceAuthority.Worlds worlds, Receipt receipt) {}
    private static ResourceDefinition definition(ResourceAuthority.Partition owner, String id) {
        return owner.program().resources().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown authoritative resource: " + id));
    }
    private static void meter(ResourceAuthority.Partition owner, ResourceState.Key progress) {
        var definition = definition(owner, progress.resource());
        if (definition.capacity() != 1 || definition.resizable()) throw new IllegalArgumentException("Recharge progress must be fixed at one unit");
    }
    /** Pure input includes the actual active frame ledger, never an arbitrary caller-supplied refund amount. */
    public static Applied apply(ResourceAuthority.Worlds worlds, String caller, RuleEngine.OperationId operation, Request request,
            Map<String, RuleEngine.ActionResult> retained) {
        payment(caller, operation); worlds.domain(caller);
        Resources.CostReceipt cost = null;
        if (request instanceof RefundPayment refund) {
            if (!(retained.get(refund.payment()) instanceof Resources.CostResult result) || !result.receipt().operation().equals(refund.payment()))
                throw new IllegalArgumentException("Refund requires a payment in the active frame");
            cost = Resources.latestClaim(result.receipt(), retained.values());
        }
        ResourceState.Key key = switch (request) {
            case Initialize r -> r.account(); case Spend r -> r.account(); case FullCharges r -> r.account(); case Resize r -> r.account();
            case CompleteRecharge r -> r.progress(); case GrantCyclic r -> r.progress(); case RefundPayment ignored -> cost.account();
        };
        var owner = worlds.directory().owner(key.holder()); var lease = owner.lease();
        var before = worlds.domain(lease.domain()); var after = before; Receipt receipt;
        if (request instanceof Initialize r) {
            var definition = definition(owner, key.resource()); var existing = Optional.ofNullable(owner.accounts().get(key.resource()));
            var account = existing.orElseGet(() -> definition.initialize(key.holder(), owner.timeMicros()));
            receipt = new Initialized(r, definition, existing, account, lease);
            if (existing.isEmpty()) after = before.withResource(account);
        } else {
            var account = owner.account(key.resource());
            switch (request) {
                case Spend r -> {
                    var result = Resources.spend(account, payment(caller, operation), r.amount());
                    receipt = new Spent(r, account, result, lease, caller, operation); after = before.withResource(result.after());
                }
                case FullCharges r -> {
                    var result = Resources.grantFullCharges(account, r.count());
                    receipt = new FullGranted(r, result, lease); after = before.withResource(result.after());
                }
                case Resize r -> {
                    if (!definition(owner, key.resource()).resizable()) throw new IllegalArgumentException("Resource is not resizable: " + key.resource());
                    var result = Resources.resize(account, r.capacity());
                    receipt = new Resized(r, result, lease); after = before.withResource(result.after());
                }
                case CompleteRecharge r -> {
                    meter(owner, key); var uses = owner.account(r.uses().resource());
                    var result = r.amount().map(amount -> LinkedRecharge.complete(account, uses, amount)).orElseGet(() -> LinkedRecharge.complete(account, uses));
                    receipt = new Recharged(r, result, lease); after = before.withResource(result.progressAfter()).withResource(result.charges().after());
                }
                case GrantCyclic r -> {
                    meter(owner, key); var result = CyclicRecharge.grant(account, owner.account(r.uses().resource()), r.scaled(), r.cycleYield());
                    receipt = new CyclicGranted(r, result, lease); after = before.withResource(result.progressAfter()).withResource(result.uses().after());
                }
                case RefundPayment r -> {
                    var result = Resources.refund(account, cost, r.fraction());
                    receipt = new PaymentRefunded(r, cost, result, lease); after = before.withResource(result.grant().after());
                }
                case Initialize ignored -> throw new AssertionError("Initialization already handled");
            }
        }
        return new Applied(ResourceAuthority.publish(worlds, lease.domain(), before, after), receipt);
    }
    public static List<RuleEngine.Signal> facts(Receipt receipt) {
        var facts = new ArrayList<RuleEngine.Signal>();
        switch (receipt) {
            case Initialized r -> {
                if (r.created()) facts.add(new RuleEngine.Signal("chorus:resource_initialized", ResourceFacts.change(
                        new ResourceState(r.account().key(), 0, r.account().capacity(), r.account().timeMicros()), r.account(), r.request().origin(), "initialize")));
            }
            case Spent r -> {
                if (r.spent().receipt().paid() > 0) {
                    var fact = ResourceFacts.change(r.before(), r.spent().after(), r.request().origin(), "spend");
                    facts.add(new RuleEngine.Signal("chorus:resource_spent", fact)); facts.add(new RuleEngine.Signal("chorus:resource_changed", fact));
                }
            }
            case FullGranted r -> {
                var fact = ResourceFacts.granted(r.grant(), r.request().origin(), "full_charge");
                facts.add(new RuleEngine.Signal("chorus:resource_granted", fact));
                if (r.grant().before().value() != r.grant().after().value()) facts.add(new RuleEngine.Signal("chorus:resource_changed", fact));
            }
            case Resized r -> {
                if (r.resize().changed()) {
                    var fact = ResourceFacts.resized(r.resize(), r.request().origin());
                    facts.add(new RuleEngine.Signal("chorus:resource_capacity_changed", fact));
                    if (r.resize().discarded() > 0) facts.add(new RuleEngine.Signal("chorus:resource_changed", fact));
                }
            }
            case Recharged r -> facts.addAll(ResourceFacts.completed(r.recharge(), r.request().origin()));
            case CyclicGranted r -> {
                var recharge = r.recharge(); boolean observed = recharge.uses().before().value() < recharge.uses().before().capacity()
                        && CyclicRecharge.completes(recharge.progressBefore(), r.request().scaled());
                facts.addAll(ResourceFacts.cyclicSignals(recharge, r.request().requested(), observed, r.request().origin()));
            }
            case PaymentRefunded r -> {
                var fact = ResourceFacts.refunded(r.refund(), r.request().origin()); facts.add(new RuleEngine.Signal("chorus:resource_refunded", fact));
                if (r.refund().grant().before().value() != r.refund().grant().after().value()) facts.add(new RuleEngine.Signal("chorus:resource_changed", fact));
            }
        }
        return List.copyOf(facts);
    }
}
