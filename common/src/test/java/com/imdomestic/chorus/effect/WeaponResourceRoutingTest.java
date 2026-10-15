package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRoutingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponResourceRoutingTest {
    static JsonObject scaled(double factor, String unit) { return WorldResourceQueryTest.scale(resource(), factor, unit); }
    static JsonObject timing() { var t = new JsonObject(); t.add("value", scaled(1, "second")); return t; }
    static JsonObject data() {
        var d = object("""
            {"version":"weapon-resource-test","resources":[{"id":"test:energy","capacity":1,"initial":0.25}],
             "equipment":{"slots":[{"id":"test:primary","accepts":["test:weapon"],"weapon":true},
               {"id":"test:secondary","accepts":["test:weapon"],"weapon":true}],
               "items":[{"id":"test:rifle","tags":["test:weapon"]}]},
             "weapons":[{"item":"test:rifle","ammunition":{"capacity":10,"magazine":3,"reserves":{"rounds":30,"capacity":30},"capacity_profile":"test:capacity"},
               "reload":{},"handling":{},"aiming":{},"fire":{"on_fire":[{"type":"chorus:play_cue","cue":"test:fired"}]}}],
             "profiles":[{"id":"test:capacity","version":"weapon-resource-test","input_unit":"round","steps":[
               {"type":"chorus:apply","id":"capacity","operation":"multiply","group":{"name":"capacity","reduction":"product"}},
               {"type":"chorus:round","id":"integer","rounding":"ceil"}]}],
             "bundles":[{"id":"test:observer","modifiers":[]}]}
            """);
        var w = weapon(d); w.add("reload", timing());
        w.getAsJsonObject("handling").add("ready", timing()); w.getAsJsonObject("handling").add("stow", timing());
        w.getAsJsonObject("aiming").add("enter", timing()); w.getAsJsonObject("aiming").add("exit", timing());
        w.getAsJsonObject("fire").add("cost", scaled(4, "round")); w.getAsJsonObject("fire").add("interval", scaled(1, "second"));
        w.getAsJsonObject("fire").add("if", compare(resource(), 0));
        var modifier = object("""
            {"id":"capacity","profile":"test:capacity","stage":"capacity","group":"capacity","op":"multiply",
             "stacking_key":"test:capacity","reference":"Synthetic remote-resource test","confidence":"assumed"}
            """);
        modifier.add("value", scaled(1, "delta")); observer(d).getAsJsonArray("modifiers").add(modifier); return d;
    }
    static JsonObject weapon(JsonObject data) { return data.getAsJsonArray("weapons").get(0).getAsJsonObject(); }
    static JsonObject observer(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject(); }
    static void reward(JsonObject data, String event) {
        var r = rule("reward", array(pool("grant_resource", "requested", charge(.25)))); r.addProperty("on", event);
        observer(data).add("rules", array(r));
    }
    static void gate(JsonObject data, String action) {
        var gate = object("{\"id\":\"energy\",\"action\":\"" + action + "\"}"); gate.add("if", compare(resource(), .25));
        observer(data).add("action_gates", array(gate));
    }
    static JsonObject charged(boolean repeat, String trigger) {
        var d = data(); var charging = object("{\"minimum\":{\"value\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"}}}");
        charging.add("full", timing()); charging.addProperty("repeat", repeat); charging.addProperty("trigger", trigger);
        charging.add("if", compare(resource(), 0)); weapon(d).add("charging", charging); return d;
    }
    static Loadout pair(String drawn) {
        return new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of()),
                "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of())), Optional.of("test:" + drawn));
    }
    static final class Fixture {
        final CompiledEffects program;
        final Map<String, EffectSession> sessions = new TreeMap<>();
        ResourceSessionAuthority host; Loadout physical = Loadout.EMPTY;
        final List<RuleEngine.WorldCommand> physicalCalls = new ArrayList<>();
        final List<EffectState> fired = new ArrayList<>(); final List<ActionRoutes.Receipt> receipts = new ArrayList<>();
        boolean allowed = true, failVerification, failFire, failApplication;
        Fixture(JsonObject data) {
            program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow().withResourceRouting();
            var source = new EffectSource("observer", "test:observer", HOLDER, new BuffInstance.Origin(HOLDER, "observer", "", ""), Set.of());
            sessions.put(A, new EffectSession(program, EffectCheckpointTest.clock(), 1, empty(0).withSource(source), r -> world(A, r)));
            sessions.put(B, new EffectSession(program, EffectCheckpointTest.clock(), 1, account(empty(50_000_000), HOLDER, "energy", .25, 1), r -> world(B, r)));
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>(); sessions.forEach((id, s) -> bindings.put(id, new ResourceSessionAuthority.Binding(program, s)));
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings); host.claim(HOLDER, B);
        }
        RuleEngine.ActionResult world(String domain, RuleEngine.WorldRequest request) {
            if (request.command() instanceof ResourceRequests.Request) {
                var result = host.execute(domain, request);
                if (result instanceof ActionRoutes.Receipt receipt) {
                    receipts.add(receipt);
                    assertEquals(receipt, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(receipt), ResourceRequests.Receipt.class));
                }
                return result;
            }
            physicalCalls.add(request.command());
            if (failVerification && (request.command() instanceof WeaponReload.Verify || request.command() instanceof WeaponAim.Verify || request.command() instanceof WeaponCharge.Verify))
                throw new IllegalStateException("unknown verification");
            return switch (request.command()) {
                case WeaponReload.Verify q -> new WeaponReload.Verified(q, allowed);
                case WeaponAim.Verify q -> new WeaponAim.Verified(q, allowed);
                case WeaponCharge.Verify q -> new WeaponCharge.Verified(q, allowed);
                case WeaponHandling.Apply q -> {
                    assertEquals(physical, q.change().before()); if (allowed) physical = q.change().after();
                    if (failApplication) throw new IllegalStateException("unknown physical switch");
                    yield new WeaponHandling.Applied(q, allowed);
                }
                case Action.CueCommand cue -> {
                    assertEquals("test:fired", cue.cue()); fired.add(state());
                    if (failFire) throw new IllegalStateException("unknown fired world outcome"); yield RuleEngine.Empty.INSTANCE;
                }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return sessions.get(A).state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void start(RuleEngine.Signal signal) { sessions.get(A).start(now(), signal); }
        void equip(String drawn) {
            var next = pair(drawn); physical = next;
            start(new EquipmentChange(HOLDER, state().equipment().getOrDefault(HOLDER, Loadout.EMPTY), next).signal());
        }
        void advance(long time) { host.drain(20); sessions.get(A).observe(time, List.of()); }
        void grant() { host.grant(ENERGY, .25, .25); }
        int ammo() { return state().ammunition().get("a").magazine(); }
        ResourceReadView view() { return host.readView(A); }
        void restore() {
            host.drain(20); var saved = host.checkpoint();
            saved = CheckpointArchive.decode(CheckpointArchive.coordinated(ResourceCheckpointTest.entries(saved), saved).encode()).resourceCheckpoint().orElseThrow();
            var policies = new TreeMap<String, ResourceSessionAuthority.RestoreBinding>();
            for (var id : sessions.keySet()) policies.put(id, new ResourceSessionAuthority.RestoreBinding(program, EffectCheckpointTest.clock(), 1, r -> world(id, r)));
            var restored = ResourceSessionAuthority.restore(saved, policies); host = restored.authority(); restored.bindings().forEach((id, b) -> sessions.put(id, b.session()));
        }
    }
    @Test void equipmentAndFireUseRemoteCapacityCostConditionAndFrozenInterval() {
        var f = new Fixture(data()); f.equip("primary"); var before = f.state();
        var prepared = f.program.fire(f.view(), new WeaponFire.Request(HOLDER, "fire"));
        assertEquals(WeaponFire.Outcome.ACCEPTED, ((WeaponFire.Receipt) prepared.result()).outcome()); assertSame(before, f.state());
        f.start(new WeaponFire.Request(HOLDER, "fire").signal()); assertEquals(prepared.state(), f.state());
        assertEquals(2, f.ammo()); assertEquals(250_000, f.state().shots().get("a").readyAt());
        assertEquals(2, f.fired.getFirst().ammunition().get("a").magazine()); assertEquals(13, WeaponFireTest.accepted(prepared).event().numbers().get("capacity").value());
        f.grant(); f.advance(250_000); f.start(new WeaponFire.Request(HOLDER, "second").signal());
        assertEquals(0, f.ammo()); assertEquals(750_000, f.state().shots().get("a").readyAt());
        assertTrue(f.state().resources().isEmpty()); assertEquals(50_000_000, f.host.snapshot().domain(B).buffs().timeMicros());
    }
    @Test void reloadKeepsAcceptedDurationButUsesCurrentRemoteCapacityAtCompletion() {
        var f = new Fixture(data()); f.equip("primary"); f.start(new WeaponReload.Request(HOLDER, "reload").signal());
        assertEquals(250_000, f.state().reloads().get(HOLDER).dueAt()); f.grant(); f.advance(249_999); assertEquals(3, f.ammo());
        f.advance(250_000); assertEquals(15, f.ammo()); assertEquals(18, f.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertTrue(f.state().reloads().isEmpty()); assertEquals(1, f.physicalCalls.size());
    }
    @Test void insertionReactionsChangeTheNextTimingAndPortionBeforeItIsScheduled() {
        var d = data(); var insert = new JsonObject(); insert.add("rounds", scaled(4, "round")); insert.add("repeat", timing());
        weapon(d).getAsJsonObject("reload").add("insert", insert); reward(d, "chorus:reload_finished");
        var f = new Fixture(d); f.equip("primary"); f.start(new WeaponReload.Request(HOLDER, "reload").signal()); f.advance(250_000);
        assertEquals(4, f.ammo()); var second = f.state().reloads().get(HOLDER);
        assertEquals(750_000, second.dueAt()); assertEquals(2, second.portion().orElseThrow().rounds());
        assertTrue(f.sessions.get(B).inboxPending()); f.advance(750_000); assertEquals(6, f.ammo());
        assertEquals(1_500_000, f.state().reloads().get(HOLDER).dueAt());
    }
    @Test void switchingRebasesTentativeGearAndSamplesReadyAfterStowReactions() {
        var d = data(); reward(d, "chorus:weapon_handling_finished"); var f = new Fixture(d); f.equip("primary");
        f.start(new WeaponHandling.Request(HOLDER, "switch", Optional.of("test:secondary")).signal());
        assertEquals(250_000, f.state().handling().get(HOLDER).dueAt()); f.advance(250_000);
        var ready = f.state().handling().get(HOLDER); assertEquals(WeaponHandling.Phase.READY, ready.phase()); assertEquals(750_000, ready.dueAt());
        assertEquals(f.physical, f.state().equipment().get(HOLDER)); f.advance(750_000);
        assertEquals(Optional.of("test:secondary"), f.physical.drawn()); assertEquals(f.physical, f.state().equipment().get(HOLDER));
        assertTrue(f.state().handling().isEmpty()); assertEquals(2, f.physicalCalls.size());
    }
    @Test void aimCompletionChecksCurrentResourcesAndReleaseUsesItsOwnTiming() {
        var d = data(); gate(d, "weapon_aim"); var f = new Fixture(d); f.equip("primary");
        f.start(new WeaponAim.Request(HOLDER, "aim", true).signal()); f.grant(); f.advance(250_000); assertTrue(f.state().aiming().isEmpty());
        var plain = new Fixture(data()); plain.equip("primary"); plain.start(new WeaponAim.Request(HOLDER, "aim", true).signal()); plain.advance(250_000);
        assertEquals(WeaponAim.Phase.AIMED, plain.state().aiming().get(HOLDER).phase()); plain.grant();
        plain.start(new WeaponAim.Request(HOLDER, "release", false).signal()); assertEquals(750_000, plain.state().aiming().get(HOLDER).dueAt());
        plain.advance(750_000); assertTrue(plain.state().aiming().isEmpty());
    }
    @Test void repeatedFullChargeResamplesAfterRecoveryAndJointRestoreDoesNotReplayTheFirstShot() {
        var d = charged(true, "full"); reward(d, WeaponFire.ACCEPTED); var f = new Fixture(d); f.equip("primary");
        f.start(new WeaponCharge.Request(HOLDER, "hold", WeaponCharge.Edge.PRESS).signal()); f.advance(250_000);
        assertEquals(2, f.ammo()); assertEquals(WeaponCharge.Phase.RECOVERING, f.state().charging().get(HOLDER).phase());
        f.restore(); assertEquals(1, f.fired.size()); f.advance(500_000);
        assertEquals(1_000_000, f.state().charging().get(HOLDER).fullAt()); f.advance(1_000_000);
        assertEquals(0, f.ammo()); assertEquals(2, f.fired.size()); assertEquals(1, f.state().charging().get(HOLDER).cycle());
    }
    @Test void partialReleaseUsesCurrentCostWithTheOriginallyAcceptedChargeTiming() {
        var f = new Fixture(charged(false, "release")); f.equip("primary");
        f.start(new WeaponCharge.Request(HOLDER, "hold", WeaponCharge.Edge.PRESS).signal()); var accepted = f.state().charging().get(HOLDER);
        f.grant(); f.advance(125_000); f.start(new WeaponCharge.Request(HOLDER, "hold", WeaponCharge.Edge.RELEASE).signal());
        assertEquals(250_000, accepted.fullAt()); assertEquals(1, f.ammo()); assertEquals(625_000, f.state().shots().get("a").readyAt());
        assertTrue(f.state().charging().isEmpty()); assertEquals(1, f.fired.size());
    }
    @Test void changedResourcesCanDenyCompletionWithoutSpendingAmmoOrContinuingReload() {
        for (var kind : List.of("weapon_reload", "weapon_fire")) {
            var d = kind.equals("weapon_fire") ? charged(false, "full") : data(); gate(d, kind); var f = new Fixture(d); f.equip("primary");
            f.start(kind.equals("weapon_fire") ? new WeaponCharge.Request(HOLDER, "hold", WeaponCharge.Edge.PRESS).signal() : new WeaponReload.Request(HOLDER, "reload").signal());
            f.grant(); f.advance(250_000); assertEquals(3, f.ammo()); assertTrue(f.fired.isEmpty()); assertTrue(f.state().charging().isEmpty()); assertTrue(f.state().reloads().isEmpty());
        }
    }
    @Test void unknownFireAndPhysicalSwitchPreserveCommittedWorkAndCannotBeCheckpointed() {
        var f = new Fixture(data()); f.equip("primary"); f.failFire = true;
        assertThrows(IllegalStateException.class, () -> f.start(new WeaponFire.Request(HOLDER, "fire").signal()));
        assertEquals(2, f.ammo()); assertEquals(1, f.fired.size()); assertThrows(IllegalStateException.class, f.host::checkpoint);
        assertThrows(IllegalStateException.class, () -> f.advance(1_000_000)); assertEquals(1, f.fired.size());
        var switched = new Fixture(data()); switched.equip("primary"); switched.failApplication = true;
        switched.start(new WeaponHandling.Request(HOLDER, "switch", Optional.of("test:secondary")).signal());
        assertThrows(IllegalStateException.class, () -> switched.advance(250_000));
        assertTrue(switched.physical.drawn().isEmpty()); assertEquals(Optional.of("test:primary"), switched.state().equipment().get(HOLDER).drawn());
        assertEquals(1, switched.physicalCalls.size()); assertThrows(IllegalStateException.class, switched.host::checkpoint);
    }
    @Test void explicitHostQueriesAgreeWithRoutedInputWithoutMutatingEitherDomain() {
        for (String kind : List.of("reload", "aim", "charge", "switch")) {
            var f = new Fixture(kind.equals("charge") ? charged(false, "release") : data()); f.equip("primary");
            var a = f.sessions.get(A).state(); var b = f.sessions.get(B).state(); var view = f.view();
            RuleEngine.Local<EffectState> plan; RuleEngine.Signal signal;
            switch (kind) {
                case "reload" -> { var r = new WeaponReload.Request(HOLDER, "r"); plan = f.program.reload(view, r); signal = r.signal(); }
                case "aim" -> { var r = new WeaponAim.Request(HOLDER, "r", true); plan = f.program.aim(view, r); signal = r.signal(); }
                case "charge" -> { var r = new WeaponCharge.Request(HOLDER, "r", WeaponCharge.Edge.PRESS); plan = f.program.charge(view, r); signal = r.signal(); }
                default -> { var r = new WeaponHandling.Request(HOLDER, "r", Optional.of("test:secondary")); plan = f.program.switchWeapon(view, r); signal = r.signal(); }
            }
            assertTrue(f.program.usesHandling(view, HOLDER, Optional.of("test:secondary")));
            assertSame(a, f.sessions.get(A).state()); assertSame(b, f.sessions.get(B).state()); f.start(signal);
            assertEquals(plan.state(), f.state()); assertSame(b, f.sessions.get(B).state());
        }
        var f = new Fixture(data()); var changed = data(); changed.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("initial", .5);
        var other = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, changed).getOrThrow().withResourceRouting();
        assertThrows(IllegalArgumentException.class, () -> other.fire(f.view(), new WeaponFire.Request(HOLDER, "empty")));
    }
    @Test void nestedProfilesShareTheSameRemoteInputAcrossAllWeaponTimingQueries() {
        var d = charged(false, "release");
        d.getAsJsonArray("profiles").add(object("""
            {"id":"test:timing","version":"weapon-resource-test","input_unit":"second","steps":[
              {"type":"chorus:apply","id":"time","operation":"multiply","group":{"name":"time","reduction":"product"}}]}
            """));
        var modifier = object("""
            {"id":"time","profile":"test:timing","stage":"time","group":"time","op":"multiply",
             "stacking_key":"test:time","reference":"Synthetic remote-resource timing","confidence":"assumed"}
            """); modifier.add("value", scaled(1, "delta")); observer(d).getAsJsonArray("modifiers").add(modifier);
        var w = weapon(d); w.getAsJsonObject("reload").addProperty("profile", "test:timing");
        for (var key : List.of("ready", "stow")) w.getAsJsonObject("handling").getAsJsonObject(key).addProperty("profile", "test:timing");
        for (var key : List.of("enter", "exit")) w.getAsJsonObject("aiming").getAsJsonObject(key).addProperty("profile", "test:timing");
        w.getAsJsonObject("charging").getAsJsonObject("full").addProperty("profile", "test:timing");
        w.getAsJsonObject("fire").addProperty("interval_profile", "test:timing");
        var f = new Fixture(d); f.equip("primary"); var view = f.view();
        assertEquals(312_500, ((WeaponReload.Receipt) f.program.reload(view, new WeaponReload.Request(HOLDER, "r")).result()).plan().orElseThrow().dueAt());
        assertEquals(312_500, ((WeaponAim.Receipt) f.program.aim(view, new WeaponAim.Request(HOLDER, "a", true)).result()).plan().orElseThrow().dueAt());
        assertEquals(312_500, ((WeaponHandling.Receipt) f.program.switchWeapon(view, new WeaponHandling.Request(HOLDER, "s", Optional.of("test:secondary"))).result()).plan().orElseThrow().dueAt());
        f.start(new WeaponCharge.Request(HOLDER, "c", WeaponCharge.Edge.PRESS).signal());
        assertEquals(312_500, f.state().charging().get(HOLDER).fullAt()); f.advance(125_000);
        f.start(new WeaponCharge.Request(HOLDER, "c", WeaponCharge.Edge.RELEASE).signal()); assertEquals(437_500, f.state().shots().get("a").readyAt());
    }
    @Test void unknownSecondInsertionVerificationKeepsTheFirstTransferAndDoesNotRetry() {
        var d = data(); var insert = new JsonObject(); insert.add("rounds", scaled(4, "round")); insert.add("repeat", timing());
        weapon(d).getAsJsonObject("reload").add("insert", insert); var f = new Fixture(d); f.equip("primary");
        f.start(new WeaponReload.Request(HOLDER, "reload").signal()); f.advance(250_000); assertEquals(4, f.ammo());
        f.failVerification = true; assertThrows(IllegalStateException.class, () -> f.advance(500_000));
        assertEquals(4, f.ammo()); assertEquals(29, f.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(2, f.physicalCalls.size()); assertThrows(IllegalStateException.class, f.host::checkpoint);
        assertThrows(IllegalStateException.class, () -> f.advance(1_000_000)); assertEquals(2, f.physicalCalls.size());
    }
    @Test void missingViewCannotBypassValidationThroughEmptyOrCancellationPaths() {
        var f = new Fixture(data()); var state = f.state();
        assertThrows(IllegalStateException.class, () -> f.program.changeEquipment(state, new EquipmentChange(HOLDER, Loadout.EMPTY, pair("primary"))));
        assertThrows(IllegalStateException.class, () -> f.program.fire(state, new WeaponFire.Request(HOLDER, "fire")));
        assertThrows(IllegalStateException.class, () -> f.program.reload(state, new WeaponReload.Request(HOLDER, "reload")));
        assertThrows(IllegalStateException.class, () -> f.program.charge(state, new WeaponCharge.Request(HOLDER, "charge", WeaponCharge.Edge.CANCEL)));
        assertThrows(IllegalStateException.class, () -> f.program.aim(state, new WeaponAim.Request(HOLDER, "aim", false)));
        assertThrows(IllegalStateException.class, () -> f.program.switchWeapon(state, new WeaponHandling.Request(HOLDER, "switch", Optional.empty())));
        assertThrows(IllegalStateException.class, () -> f.program.usesHandling(state, HOLDER, Optional.empty()));
        assertThrows(IllegalStateException.class, () -> f.program.cancelCharge(state, HOLDER, "cancel"));
        assertThrows(IllegalStateException.class, () -> f.program.cancelAim(state, HOLDER, "cancel"));
        assertEquals(WeaponFire.Outcome.EMPTY_HANDS, ((WeaponFire.Receipt) f.program.fire(f.view(), new WeaponFire.Request(HOLDER, "empty")).result()).outcome());
        assertEquals(state, f.program.cancelCharge(f.view(), HOLDER, "cancel").state()); assertEquals(state, f.program.cancelAim(f.view(), HOLDER, "cancel").state());
    }
}
