package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRequestSessionTest.ORIGIN;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.AbilityLoadout;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceReadViewTest {
    static final String SLOT = "test:slot";
    static JsonObject data() {
        return JsonParser.parseString("""
            {"version":"resource-views","resources":[
              {"id":"test:energy","capacity":2,"initial":2,"resizable":true,"gain_profile":"test:gain"},
              {"id":"test:melee","capacity":1,"initial":1},
              {"id":"test:progress","capacity":1,"initial":0},
              {"id":"test:uses","capacity":3,"initial":3}
            ],"abilities":[
              {"id":"test:old","slot":"test:slot","tags":["test:old"],"cost":{"resource":"test:energy","amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"}},"on_use":[]},
              {"id":"test:current","slot":"test:slot","tags":["test:current"],"cost":{"resource":"test:uses","amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"}},"recharge_resource":"test:progress","on_use":[]}
            ],"profiles":[
              {"id":"test:charge","version":"resource-views","input_unit":"charge_fraction","steps":[{"type":"chorus:apply","id":"add","operation":"add","group":{"name":"add","reduction":"sum"}}]},
              {"id":"test:second","version":"resource-views","input_unit":"charge_fraction","steps":[{"type":"chorus:apply","id":"add","operation":"add","group":{"name":"add","reduction":"sum"}}]},
              {"id":"test:points","version":"resource-views","input_unit":"stat_point","steps":[{"type":"chorus:apply","id":"add","operation":"add","group":{"name":"add","reduction":"sum"}}]},
              {"id":"test:capacity","version":"resource-views","input_unit":"round","steps":[{"type":"chorus:apply","id":"add","operation":"add","group":{"name":"add","reduction":"sum"}}]},
              {"id":"test:gain","version":"resource-views","input_unit":"charge_fraction","steps":[{"type":"chorus:apply","id":"gain","operation":"multiply","group":{"name":"gain","reduction":"sum"}}]}
            ],"bundles":[{"id":"test:query","modifiers":[
              {"id":"charge","profile":"test:charge","stage":"add","group":"add","op":"add","value":{"type":"chorus:resource","resource":"test:energy","target":"source_owner"},"stacking_key":"test:charge","reference":"Synthetic remote resource query","confidence":"assumed"},
              {"id":"second","profile":"test:second","stage":"add","group":"add","op":"add","value":{"type":"chorus:profile_value","profile":"test:charge","input":{"type":"chorus:constant","value":0,"unit":"charge_fraction"}},"stacking_key":"test:second","reference":"Synthetic nested resource query","confidence":"assumed"},
              {"id":"points","profile":"test:points","stage":"add","group":"add","op":"add","value":{"type":"chorus:scale","of":{"type":"chorus:profile_value","profile":"test:charge","input":{"type":"chorus:constant","value":0,"unit":"charge_fraction"}},"factor":100,"from":"charge_fraction","to":"stat_point"},"stacking_key":"test:points","reference":"Synthetic attribute dependency","confidence":"assumed"},
              {"id":"capacity","profile":"test:capacity","stage":"add","group":"add","op":"add","value":{"type":"chorus:scale","of":{"type":"chorus:attribute","profile":"test:points","input":{"type":"chorus:constant","value":0,"unit":"stat_point"}},"factor":0.1,"from":"stat_point","to":"round"},"stacking_key":"test:capacity","reference":"Synthetic ammunition dependency","confidence":"assumed"},
              {"id":"gain","profile":"test:gain","stage":"gain","group":"gain","op":"multiply","value":{"type":"chorus:scale","of":{"type":"chorus:resource","resource":"test:energy","target":"source_owner"},"factor":1,"from":"charge_fraction","to":"delta"},"stacking_key":"test:gain","reference":"Synthetic recipient gain dependency","confidence":"assumed"}
            ]}]}
            """).getAsJsonObject();
    }
    static CompiledEffects compile(JsonObject data) { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow(); }
    static EffectSource source(String holder) { return new EffectSource("query/" + holder, "test:query", holder, ORIGIN, Set.of()); }
    static RuleEngine.Context context(String holder, Map<String, RuleEngine.ActionResult> bindings) {
        var event = new EffectEvent(HOLDER, HOLDER, ORIGIN, Set.of(), Map.of());
        return new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0, new RuleEngine.Signal("test:query", event)), "query", source(holder), bindings);
    }
    static final class Fixture {
        final CompiledEffects program;
        final Map<String, EffectSession> sessions = new TreeMap<>();
        final ResourceSessionAuthority host;
        Fixture() { this(data()); }
        Fixture(JsonObject data) {
            program = compile(data);
            var a = initial(0).withSource(source(HOLDER)).withSource(source("local"))
                    .withResource(new ResourceState(new ResourceState.Key("local", ENERGY.resource()), 0, 2, 0))
                    .withAbilities(HOLDER, new AbilityLoadout(Map.of(SLOT, "test:old")))
                    .withAmmo(new AmmoState("weapon", 2, 6, Optional.empty(), Optional.of(new AmmoState.CapacityProfile(HOLDER, "test:capacity"))));
            var b = empty(50_000_000).withAbilities(HOLDER, new AbilityLoadout(Map.of(SLOT, "test:current")));
            Map.of(A, a, B, b).forEach((domain, state) -> sessions.put(domain,
                    new EffectSession(program, EffectCheckpointTest.clock(), 1, state, _ -> { throw new AssertionError("Unexpected world call"); })));
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>();
            sessions.forEach((domain, session) -> bindings.put(domain, new ResourceSessionAuthority.Binding(program, session)));
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings); host.claim(HOLDER, A);
            host.commit(host.prepareTransfer(HOLDER, B));
        }
        EffectState state(String domain) { return sessions.get(domain).state().engine().domain(); }
        Evaluation evaluation(ResourceReadView view, String holder) { return program.resourceEvaluation(view, context(holder, Map.of()), Map.of()); }
    }
    static Value.Constant charge(double amount) { return new Value.Constant(amount, Unit.CHARGE); }
    static Value.Resource resource(Value.ResourceField field) { return new Value.Resource(ENERGY.resource(), Evaluation.Target.SELF, field); }
    @Test void resourceFieldsReadTheCurrentOwnerWithoutCopyingOrRebasingAccounts() {
        var f = new Fixture(); var view = f.host.readView(A); var e = f.evaluation(view, HOLDER);
        assertEquals(.4, resource(Value.ResourceField.VALUE).evaluate(e).value()); assertEquals(3, resource(Value.ResourceField.CAPACITY).evaluate(e).value());
        assertEquals(2.6, resource(Value.ResourceField.MISSING).evaluate(e).value()); assertEquals(50_000_000, e.resource(ENERGY.resource(), Evaluation.Target.SELF).timeMicros());
        assertFalse(f.state(A).resources().containsKey(ENERGY)); assertSame(f.state(A), e.state());
        assertFalse(f.sessions.get(B).inboxPending());
        var comparison = new Condition.Compare(resource(Value.ResourceField.VALUE), Condition.Comparison.GT, charge(.3));
        assertTrue(comparison.test(e));
    }
    @Test void frozenViewsDoNotFollowLaterCreditsAndUnmanagedLocalAccountsStayLocal() {
        var f = new Fixture(); var old = f.host.readView(A); f.host.grant(ENERGY, .6, .6); var next = f.host.readView(A);
        assertEquals(.4, f.evaluation(old, HOLDER).resource(ENERGY.resource(), Evaluation.Target.SELF).value());
        assertEquals(1, f.evaluation(next, HOLDER).resource(ENERGY.resource(), Evaluation.Target.SELF).value());
        assertEquals(0, f.evaluation(next, "local").resource(ENERGY.resource(), Evaluation.Target.SELF).value());
        assertEquals(B, old.owner(HOLDER).domain()); assertEquals(A, old.owner("local").domain());
        f.host.commit(f.host.prepareTransfer(HOLDER, A));
        assertEquals(B, old.owner(HOLDER).domain()); assertEquals(A, f.host.readView(A).owner(HOLDER).domain());
    }
    @Test void abilityObservationUsesTheRecipientsSelectionAndBothOfItsAccounts() {
        var f = new Fixture(); var e = f.evaluation(f.host.readView(A), HOLDER);
        assertEquals("test:old", f.state(A).abilities().get(HOLDER).slots().get(SLOT));
        assertTrue(new Condition.SelectedAbilityTag(SLOT, "test:current", Evaluation.Target.SELF).test(e));
        assertTrue(new Condition.SelectedAbilityResource(SLOT, "test:progress", Evaluation.Target.SELF).test(e));
        assertFalse(new Condition.SelectedAbilityTag(SLOT, "test:old", Evaluation.Target.SELF).test(e));
        assertEquals(1, new Value.AbilityEnergy(SLOT, Evaluation.Target.SELF, "value").evaluate(e).value());
        assertEquals(.4, new Value.AbilityEnergy(SLOT, Evaluation.Target.SELF, "recharge_value").evaluate(e).value());
        var result = (RuleEngine.Local<EffectState>) new EnergyActions.ObserveAbility(SLOT, Evaluation.Target.SELF).execute(e);
        var observed = (EnergyActions.AbilityObservation) result.result(); assertEquals(Optional.of("test:current"), observed.ability());
        assertEquals(50_000_000, observed.observed().timeMicros()); assertEquals(observed.observed().timeMicros(), observed.rechargeObserved().timeMicros());
        assertSame(e.state(), result.state()); assertTrue(result.emitted().isEmpty());
    }
    @Test void nestedProfilesAttributesAndAmmoCapacityShareOneExplicitView() {
        var f = new Fixture(); var view = f.host.readView(A); var e = f.evaluation(view, HOLDER);
        assertEquals(.4, new Value.ProfileValue("test:second", charge(0), Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(40, new Value.Attribute("test:points", new Value.Constant(0, Unit.STAT_POINT), Evaluation.Target.SELF).evaluate(e).value());
        assertEquals(10, new Value.Ammo(Evaluation.Target.THIS_WEAPON, AmmoState.Field.CAPACITY).evaluate(e).value());
        var pipeline = f.program.calculatePipeline(e.state(), HOLDER, e.event(), List.of("test:charge", "test:second"), new Measure(0, Unit.CHARGE), Optional.of(view));
        assertEquals(.8, pipeline.output().value()); assertEquals(6, e.state().ammunition().get("weapon").capacity());
        assertThrows(IllegalArgumentException.class, () -> f.program.profileValue(e.state(), HOLDER, "test:charge", new Measure(0, Unit.CHARGE), NumericQuery.Path.empty()));
    }
    @Test void localGainProfilesCanReadRemoteDependenciesWithoutMutatingTheRemoteBalance() {
        var f = new Fixture(); var e = f.evaluation(f.host.readView(A), "local");
        var action = new EnergyActions.Grant(ENERGY.resource(), Evaluation.Target.SELF, charge(1), EnergyGains.Basis.BASE, Map.of(), Set.of(), Map.of());
        var result = (RuleEngine.Local<EffectState>) action.execute(e);
        assertEquals(1.4, result.state().resources().get(new ResourceState.Key("local", ENERGY.resource())).value());
        assertEquals(.4, f.state(B).resources().get(ENERGY).value()); assertFalse(result.state().resources().containsKey(ENERGY));
        assertEquals(0, f.state(A).resources().get(new ResourceState.Key("local", ENERGY.resource())).value());
    }
    @Test void everyLocalResourceWriteRejectsABorrowedAccountIncludingInitializationAndRefunds() {
        var f = new Fixture(); var view = f.host.readView(A); var e = f.evaluation(view, HOLDER);
        var actions = List.<Action>of(new Action.InitializeResource(ENERGY.resource(), Evaluation.Target.SELF),
                new Action.GrantResource(ENERGY.resource(), Evaluation.Target.SELF, charge(1), Optional.empty()),
                new Action.SpendResource(ENERGY.resource(), Evaluation.Target.SELF, charge(.1), "cost"),
                new Action.GrantFullCharge(ENERGY.resource(), Evaluation.Target.SELF, new Value.Constant(1, Unit.COUNT)),
                new ResourceCapacityActions.Resize(ENERGY.resource(), Evaluation.Target.SELF, charge(4)),
                new LinkedRechargeActions.Complete("test:progress", "test:uses", Evaluation.Target.SELF),
                new EnergyActions.Grant(ENERGY.resource(), Evaluation.Target.SELF, charge(1), EnergyGains.Basis.FIXED, Map.of(), Set.of(), Map.of()),
                new EnergyActions.GrantAbility(SLOT, Evaluation.Target.SELF, charge(1), EnergyGains.Basis.FIXED, Map.of(), Set.of(), Map.of()));
        for (var action : actions) assertThrows(IllegalStateException.class, () -> action.execute(e), action.getClass().getSimpleName());
        var paid = Resources.spend(view.account(ENERGY), "paid", .1);
        var refund = f.program.resourceEvaluation(view, context(HOLDER, Map.of("paid", paid)), Map.of("paid", ResultShape.RESOURCE_SPEND));
        assertThrows(IllegalStateException.class, () -> new Action.RefundCost("paid", new Value.Constant(1, Unit.MULTIPLIER)).execute(refund));
        var handle = new RetainedCosts.Handle("handle", new Resources.CostReceipt("paid", ENERGY, .1, .1), 0, 1_000_000);
        var retained = f.program.resourceEvaluation(view, context(HOLDER, Map.of("handle", handle)), Map.of("handle", ResultShape.RETAINED_COST));
        assertThrows(IllegalStateException.class, () -> new Action.RefundRetainedCost("handle", new Value.Constant(1, Unit.MULTIPLIER)).execute(retained));
        assertEquals(.4, f.state(B).resources().get(ENERGY).value()); assertFalse(f.state(A).resources().containsKey(ENERGY));
    }
    @Test void aViewCannotBeAttachedToAnUnrelatedOrModifiedDomainSnapshot() {
        var f = new Fixture(); var view = f.host.readView(A); var e = f.evaluation(view, HOLDER);
        assertThrows(IllegalArgumentException.class, () -> new Evaluation(e.state().withMode(EffectState.Mode.PVP), e.context(), e.buffs(), e.results(), e.resources(), e.retainedResults(), e.program(), Optional.of(view)));
        assertThrows(IllegalArgumentException.class, () -> view.require(f.program.program(), f.state(B)));
        var other = data(); other.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("initial", 1);
        assertThrows(IllegalArgumentException.class, () -> compile(other).resourceEvaluation(view, context(HOLDER, Map.of()), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> view.account(new ResourceState.Key(HOLDER, "test:missing")));
    }
    @Test void provisionalAmmoQueriesKeepForeignResourcesFrozenAndCannotForgeBalancesOrTime() {
        var f = new Fixture(); var view = f.host.readView(A); var e = f.evaluation(view, HOLDER);
        var ammo = new AmmoState("new_weapon", 0, 5, Optional.empty(), Optional.of(new AmmoState.CapacityProfile(HOLDER, "test:capacity")));
        var candidate = e.state().withAmmo(ammo); assertEquals(9, e.ammoView(candidate, ammo).capacity());
        assertSame(e.state(), view.state()); assertFalse(f.state(A).ammunition().containsKey("new_weapon"));
        assertEquals(.4, view.withLocalState(candidate).account(ENERGY).value());
        var localKey = new ResourceState.Key("local", ENERGY.resource());
        assertThrows(IllegalArgumentException.class, () -> view.withLocalState(candidate.withResource(new ResourceState(localKey, 1, 2, 0))));
        assertThrows(IllegalArgumentException.class, () -> view.withLocalState(empty(1)));
        assertEquals(.4, f.state(B).resources().get(ENERGY).value());
    }
    @Test void activeWorldCallbacksCanCaptureAViewButWrongOrExpiredCapabilitiesCannot() {
        var f = ResourceRequestSessionTest.fixture(); f.move(B); var requests = new ArrayList<RuleEngine.WorldRequest>();
        f.callback = (caller, request, receipt) -> {
            requests.add(request); var view = f.host.readView(caller, request);
            assertSame(f.state(caller), view.state()); assertEquals(.4, view.account(ENERGY).value());
            assertThrows(IllegalStateException.class, () -> f.host.readView(caller));
            assertThrows(IllegalStateException.class, () -> f.host.readView(B, request)); return receipt;
        };
        f.request(A, new ResourceRequests.Read(ENERGY));
        assertThrows(IllegalStateException.class, () -> f.host.readView(A, requests.getFirst())); assertTrue(f.state(A).resources().isEmpty());
    }
    @Test void resourceViewsDoNotResetNumericDependencyPathsOrLimitUnrelatedQueries() {
        var data = data(); var value = new Value.ProfileValue("test:charge", charge(0), Evaluation.Target.SELF);
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject()
                .add("value", EffectCodecs.VALUE.encodeStart(JsonOps.INSTANCE, value).getOrThrow());
        var f = new Fixture(data); var e = f.evaluation(f.host.readView(A), HOLDER);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> value.evaluate(e)).getMessage().contains("Circular numeric profile"));
        assertEquals(.4, resource(Value.ResourceField.VALUE).evaluate(e).value());
    }
}
