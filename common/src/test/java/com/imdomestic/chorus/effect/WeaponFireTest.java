package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeaponFireTest {
    static final WorldPosition POINT = new WorldPosition("world", 2, 40, 3);
    static CompiledEffects program(JsonObject data) throws Exception { return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), load("kill_clip").program())); }
    static JsonObject config(JsonObject data) { return data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire"); }
    static Loadout pair(String drawn) { return WeaponReloadTest.pair(drawn); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<EffectState> atLaunch = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<Double> damage = new ArrayList<>(); final List<String> cues = new ArrayList<>();
        int sequence; boolean failLaunch, rejectLaunch;
        Harness() throws Exception { this(program(json("weapon_fire")), EffectState.empty()); }
        Harness(CompiledEffects program, EffectState initial) {
            this.program = program;
            session = new EffectSession(engine(program), initial, request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(POINT));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> {
                    launches.add(launch); atLaunch.add(state());
                    if (failLaunch) throw new IllegalStateException("unknown launch outcome");
                    yield new ProjectileFlight.Receipt(launch, rejectLaunch ? ProjectileFlight.Outcome.REJECTED : ProjectileFlight.Outcome.LAUNCHED,
                            rejectLaunch ? Optional.empty() : Optional.of("projectile-" + launches.size()));
                }
                case DamageCommand d -> {
                    hits.add(d); double value = program.outgoing(state(), d, d.amount()).orElseThrow().output().value(); damage.add(value);
                    yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, value, Optional.empty(), false);
                }
                case WeaponReload.Verify query -> new WeaponReload.Verified(query, true);
                case HealingCommand heal -> HealingReceipt.unapplied(request.id().toString(), heal, HealingReceipt.Outcome.MISSING);
                case Action.CueCommand cue -> { cues.add(cue.cue()); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(request.command());
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        int magazine(String weapon) { return state().ammunition().get(weapon).magazine(); }
        void equip(Loadout loadout) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout).signal()); }
        WeaponFire.Receipt fire() {
            var request = new WeaponFire.Request("player", "trigger-" + ++sequence); var resolved = program.fire(state(), request);
            session.start(now(), request.signal()); return (WeaponFire.Receipt) resolved.result();
        }
        void reload() { session.start(now(), new WeaponReload.Request("player", "reload-" + ++sequence).signal()); }
        void until(long time) { session.observe(time, List.of()); }
        void finish(int index, long time) { session.start(time, launches.get(index).finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, POINT, Optional.of("enemy"), 0, 0, 0, time - atLaunch.get(index).buffs().timeMicros()))); }
    }
    @Test void acceptanceCommitsAmmoAndPerInstanceCooldownBeforePhysicalLaunchAndRejectsEarlyRequests() throws Exception {
        var h = new Harness(); assertEquals(WeaponFire.Outcome.EMPTY_HANDS, h.fire().outcome()); h.equip(pair("primary"));
        var accepted = h.fire(); var shot = accepted.shot().orElseThrow(); assertEquals(150_000, shot.readyAt());
        assertEquals(0, h.magazine("a")); assertEquals(1, h.magazine("b")); assertEquals(12, h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(shot, h.atLaunch.getFirst().shots().get("a")); assertEquals(0, h.atLaunch.getFirst().ammunition().get("a").magazine());
        assertEquals(List.of("test:ammo_spent", "test:fire_accepted"), h.cues); assertTrue(h.hits.isEmpty());
        var before = h.state(); assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire().outcome()); assertEquals(before, h.state());
        h.until(149_999); assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire().outcome());
        h.until(150_000); assertEquals(WeaponFire.Outcome.NO_AMMUNITION, h.fire().outcome()); assertEquals(1, h.launches.size());
    }
    @Test void firingAnotherWeaponAndStowRedrawCannotResetPreviousWeaponCooldownOrRetargetFlight() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); var first = h.fire().shot().orElseThrow(); h.equip(pair("secondary")); h.fire();
        h.equip(pair("primary")); assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire().outcome());
        h.equip(Loadout.EMPTY); h.equip(pair("primary")); assertEquals(first, h.state().shots().get("a"));
        h.finish(1, 50_000); h.finish(0, 100_000); assertEquals(List.of("b", "a"), h.hits.stream().map(hit -> hit.source().weapon()).toList());
        assertEquals(List.of(10.0, 10.0), h.damage); assertEquals(first.origin(), h.hits.getLast().source());
        assertTrue(h.hits.getLast().tags().contains("chorus:weapon_damage")); assertEquals(Set.of("chorus:weapon_kill"), h.hits.getLast().killTags());
        h.equip(Loadout.EMPTY); var other = new Loadout(Map.of("test:primary", WeaponReloadTest.gun("a")), Optional.of("test:primary"));
        h.session.start(h.now(), new EquipmentChange("other", Loadout.EMPTY, other).signal());
        assertEquals(WeaponFire.Outcome.COOLDOWN, ((WeaponFire.Receipt) h.program.fire(h.state(), new WeaponFire.Request("other", "other-shot")).result()).outcome());
    }
    @Test void onlyAcceptedFireCancelsReloadAndRejectedAttemptsLeaveItsDeadlineIntact() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); h.reload(); var reload = h.state().reloads().get("player"); h.fire();
        assertTrue(h.state().reloads().isEmpty()); assertFalse(h.state().timers().containsKey(reload.timerId()));
        h.reload(); var pending = h.state().reloads().get("player");
        assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire().outcome()); assertEquals(pending, h.state().reloads().get("player"));
        h.until(150_000); assertEquals(WeaponFire.Outcome.NO_AMMUNITION, h.fire().outcome()); assertEquals(pending, h.state().reloads().get("player"));
        h.until(200_000); assertEquals(5, h.magazine("a")); assertEquals(7, h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(WeaponFire.Outcome.ACCEPTED, h.fire().outcome()); assertEquals(4, h.magazine("a"));
    }
    @Test void explicitFreeFireIsStillRateLimitedAndDoesNotInventAmmoChanges() throws Exception {
        var data = json("weapon_fire"); config(data).getAsJsonObject("cost").addProperty("value", 0);
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("magazine", 0);
        var h = new Harness(program(data), EffectState.empty()); h.equip(pair("primary")); var first = h.fire().shot().orElseThrow();
        assertEquals(0, first.cost().applied()); assertEquals(List.of("test:fire_accepted"), h.cues); assertEquals(0, h.magazine("a"));
        assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire().outcome()); h.until(150_000); assertEquals(WeaponFire.Outcome.ACCEPTED, h.fire().outcome());
    }
    @Test void failedConditionMissingBehaviorAndMultiRoundShortfallArePureRejections() throws Exception {
        var data = json("weapon_fire"); config(data).add("if", JsonParser.parseString("{\"type\":\"chorus:event_tag\",\"tag\":\"test:missing\"}"));
        var h = new Harness(program(data), EffectState.empty()); h.equip(pair("primary")); h.reload(); var before = h.state();
        assertEquals(WeaponFire.Outcome.CONDITION, h.fire().outcome()); assertEquals(before, h.state()); assertTrue(h.launches.isEmpty());
        h.equip(new Loadout(Map.of("test:primary", new Loadout.Gear("c", "test:primary_ammo", Map.of())), Optional.of("test:primary")));
        assertEquals(WeaponFire.Outcome.NOT_CONFIGURED, h.fire().outcome());
        var multiple = json("weapon_fire"); config(multiple).getAsJsonObject("cost").addProperty("value", 2);
        var multi = new Harness(program(multiple), EffectState.empty()); multi.equip(pair("primary")); var unchanged = multi.state();
        assertEquals(WeaponFire.Outcome.NO_AMMUNITION, multi.fire().outcome()); assertEquals(unchanged, multi.state());
    }
    @Test void intervalProfileIsFrozenBeforeShotAndInvalidRuntimeIntervalCannotConsumeAmmoOrCancelReload() throws Exception {
        var h = new Harness(); h.equip(pair("primary"));
        var source = new EffectSource("fast", "test:fast", "player", new BuffInstance.Origin("player", "fast", "", ""), Set.of());
        h.session.start(0, SourceChange.bind(source)); var first = h.fire().shot().orElseThrow(); assertEquals(75_000, first.readyAt());
        assertEquals(.15, first.input().value()); assertEquals(.075, first.interval().value()); assertEquals(1, first.calculation().orElseThrow().inputs().contributions().size());
        h.session.start(0, SourceChange.remove("fast")); h.until(74_999); assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire().outcome());
        h.until(75_000); assertEquals(WeaponFire.Outcome.NO_AMMUNITION, h.fire().outcome());
        var data = json("weapon_fire"); config(data).getAsJsonObject("interval").addProperty("value", 0);
        var bad = new Harness(program(data), EffectState.empty()); bad.equip(pair("primary")); bad.reload(); var before = bad.state();
        assertThrows(IllegalArgumentException.class, () -> bad.program.fire(before, new WeaponFire.Request("player", "invalid"))); assertEquals(before, bad.state());
        assertEquals(1, WeaponFire.micros(new Measure(.0000001, Unit.SECOND))); assertEquals(333_334, WeaponFire.micros(new Measure(1.0 / 3, Unit.SECOND)));
    }
    @Test void rejectedLaunchIsChargedWithoutHitWhileUnknownLaunchCannotBeRetriedOrRefundedImplicitly() throws Exception {
        var h = new Harness(); h.equip(pair("primary")); h.rejectLaunch = true; assertEquals(WeaponFire.Outcome.ACCEPTED, h.fire().outcome());
        assertEquals(0, h.magazine("a")); assertTrue(h.hits.isEmpty()); assertTrue(h.session.state().idle()); assertFalse(h.cues.contains("test:shot_fired"));
        var unknown = new Harness(); unknown.equip(pair("primary")); unknown.failLaunch = true;
        assertThrows(IllegalStateException.class, unknown::fire); assertEquals(0, unknown.magazine("a")); assertEquals(1, unknown.state().shots().size());
        assertTrue(unknown.session.state().engine().pending().isPresent()); assertThrows(IllegalStateException.class, () -> unknown.until(200_000)); assertEquals(1, unknown.launches.size());
    }
    @Test void ordinaryAmmoSpendDoesNotFireAndCodecEnforcesExplicitCostsIntervalsAndDetachedBodies() throws Exception {
        var h = new Harness(); h.equip(pair("primary"));
        var origin = new BuffInstance.Origin("player", "a", "a", "");
        h.session.start(0, new RuleEngine.Signal("test:spend", new EffectEvent("player", "", origin, Set.of(), Map.of("rounds", new Measure(1, Unit.ROUND)))));
        assertEquals(0, h.magazine("a")); assertTrue(h.state().shots().isEmpty() && h.launches.isEmpty()); assertEquals(List.of("test:ammo_spent"), h.cues);
        var p = h.program.program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        for (String fault : List.of("missing", "fraction", "negative", "unit", "zero_interval", "profile", "unknown", "timer", "source_after")) {
            var data = json("weapon_fire"); var fire = config(data);
            switch (fault) {
                case "missing" -> fire.remove("cost");
                case "fraction" -> fire.getAsJsonObject("cost").addProperty("value", .5);
                case "negative" -> fire.getAsJsonObject("cost").addProperty("value", -1);
                case "unit" -> fire.getAsJsonObject("cost").addProperty("unit", "damage");
                case "zero_interval" -> { fire.remove("interval_profile"); fire.getAsJsonObject("interval").addProperty("value", 0); }
                case "profile" -> fire.addProperty("interval_profile", "test:missing");
                case "unknown" -> fire.addProperty("unlimited", true);
                case "timer" -> fire.getAsJsonArray("on_fire").add(JsonParser.parseString("{\"type\":\"chorus:cancel_timer\",\"name\":\"foo\"}"));
                case "source_after" -> fire.getAsJsonArray("on_fire").add(JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"},\"lifetime\":\"source\",\"do\":[]}"));
            }
            assertThrows(RuntimeException.class, () -> program(data), fault);
        }
    }
}
