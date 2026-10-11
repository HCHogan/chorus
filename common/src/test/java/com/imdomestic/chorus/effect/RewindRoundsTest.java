package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RewindRoundsTest {
    static final String D = "chorus_d2:rewind_rounds_";
    static Map<String, Measure> calibration(int groups, double threshold, double adaptive) {
        return Map.of("groups", new Measure(groups, Unit.COUNT), "minimum_fired_fraction", new Measure(threshold, Unit.MULTIPLIER),
                "ignore_seconds", new Measure(.25, Unit.SECOND), "settlement_seconds", new Measure(3, Unit.SECOND),
                "receipt_seconds", new Measure(3, Unit.SECOND), "adaptive_fraction", new Measure(adaptive, Unit.MULTIPLIER));
    }
    static JsonObject weapon(int capacity, int magazine, int reserve, int pellets, int groups) throws Exception {
        var data = json("rewind_weapon"); var w = data.getAsJsonArray("weapons").get(0).getAsJsonObject(); var ammo = w.getAsJsonObject("ammunition");
        ammo.addProperty("capacity", capacity); ammo.addProperty("magazine", magazine);
        if (reserve < 0) ammo.addProperty("reserves", "unlimited");
        else { ammo.getAsJsonObject("reserves").addProperty("rounds", reserve); ammo.getAsJsonObject("reserves").addProperty("capacity", Math.max(1000, reserve)); }
        var fire = w.getAsJsonObject("fire"); var original = fire.getAsJsonArray("on_fire"); var steps = new JsonArray();
        for (int i = 0; i < 3; i++) steps.add(original.get(i).deepCopy());
        for (int g = 0; g < groups; g++) {
            var begin = original.get(3).getAsJsonObject().deepCopy(); begin.addProperty("as", "group" + g);
            begin.getAsJsonObject("action").getAsJsonObject("pellets").addProperty("value", pellets); steps.add(begin);
            for (int p = 0; p < pellets; p++) {
                var flight = original.get(4).getAsJsonObject().deepCopy(); flight.getAsJsonObject("shot").addProperty("binding", "group" + g);
                flight.getAsJsonObject("shot").getAsJsonObject("pellet").addProperty("value", p); steps.add(flight);
            }
        }
        fire.add("on_fire", steps); return data;
    }
    static class Harness {
        final CompiledEffects program; EffectSession session; int sequence; final int pellets, groups;
        final Map<String, Measure> parameters; final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final List<Action.CueCommand> cues = new ArrayList<>(); final List<DamageCommand> damage = new ArrayList<>();
        DamageReceipt.Outcome outcome = DamageReceipt.Outcome.APPLIED; boolean failDamage, failRefill, reject;
        Harness() throws Exception { this(10, 10, 1000, 1, 1, .285); }
        Harness(int capacity, int magazine, int reserve, int pellets, int groups, double threshold) throws Exception {
            this(weapon(capacity, magazine, reserve, pellets, groups), pellets, groups, threshold);
        }
        Harness(JsonObject data, int pellets, int groups, double threshold) throws Exception {
            this.pellets = pellets; this.groups = groups; parameters = calibration(groups, threshold, .1633);
            program = CompiledEffects.link(List.of(load("rewind_rounds").program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow()));
            session = new EffectSession(program, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world);
            equip("normal", "enhanced", "primary");
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(WeaponFireTest.POINT));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch,
                        reject ? ProjectileFlight.Outcome.REJECTED : ProjectileFlight.Outcome.LAUNCHED, reject ? Optional.empty() : Optional.of("flight/" + launches.size())); }
                case DamageCommand d -> {
                    damage.add(d); if (failDamage) throw new IllegalStateException("unknown physical hit");
                    yield new DamageReceipt(request.id().toString(), outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? d.amount() : 0, Optional.empty(), false);
                }
                case WeaponReload.Verify q -> new WeaponReload.Verified(q, true);
                case Action.CueCommand cue -> { cues.add(cue); if (failRefill && cue.cue().equals("test:refilled")) throw new IllegalStateException("unknown refill reaction"); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void until(long time) { session.observe(time, List.of()); }
        Loadout.Gear gear(String id, String variant) {
            var values = new HashMap<>(parameters); if (variant.equals("adaptive")) values.put("adaptive_fraction", new Measure(.14, Unit.MULTIPLIER));
            return new Loadout.Gear(id, "test:rifle", Map.of("perk", variant), values);
        }
        void equip(String a, String b, String drawn) {
            var next = new Loadout(Map.of("test:primary", gear("a", a), "test:secondary", gear("b", b)), Optional.of("test:" + drawn));
            session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal());
        }
        AmmoState ammo(String id) { return state().ammunition().get(id); }
        int mag(String id) { return ammo(id).magazine(); }
        Optional<BuffInstance> buff(String kind, String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + kind) && b.origin().weapon().equals(weapon)).findFirst(); }
        double count(String name, String weapon) { return buff("counter", weapon).map(b -> b.components().numbers().get(name)).orElse(0.0); }
        long cues(String id) { return cues.stream().filter(c -> c.cue().equals("test:" + id)).count(); }
        WeaponFire.Outcome fire() {
            var request = new WeaponFire.Request("player", "trigger/" + ++sequence); var outcome = ((WeaponFire.Receipt) program.fire(state(), request).result()).outcome();
            session.start(now(), request.signal()); return outcome;
        }
        void impact(int index, boolean hit) {
            session.start(now(), launches.get(index).finish(new ProjectileFlight.Impact(hit ? ProjectileFlight.End.ENTITY : ProjectileFlight.End.EXPIRED,
                    WeaponFireTest.POINT, hit ? Optional.of("enemy") : Optional.empty(), 0, 0, 0, 0)));
        }
        void shoot(boolean hit) { until(now() + 300_000); int begin = launches.size(); assertEquals(WeaponFire.Outcome.ACCEPTED, fire()); for (int i = begin; i < launches.size(); i++) impact(i, hit); }
        void shots(int n) { for (int i = 0; i < n; i++) shoot(true); }
        void reload() { session.start(now(), new WeaponReload.Request("player", "reload/" + ++sequence).signal()); }
        void amount(String type, String weapon, int amount) {
            session.start(now(), new RuleEngine.Signal("test:" + type, new EffectEvent("player", "", new BuffInstance.Origin("player", "input", weapon, ""), Set.of(), Map.of("amount", new Measure(amount, Unit.ROUND)))));
        }
        void hit(String owner, String weapon, String id, Set<String> tags, DamageReceipt.Outcome outcome) {
            session.observe(now(), DamageFacts.from(new DamageCommand("enemy", new BuffInstance.Origin(owner, "indirect", weapon, ""), 1, "minecraft:generic", tags, Set.of(), false),
                    new DamageReceipt(id, outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? 1 : 0, Optional.empty(), false)));
        }
        void hit(String id) { hit("player", "a", id, Set.of("chorus:weapon_damage"), DamageReceipt.Outcome.APPLIED); }
        void restore() {
            var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
            session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 256, saved, this::world);
        }
    }
    @Test void ordinaryAndEnhancedRefillFromTheirOwnReservesWithoutReloadFacts() throws Exception {
        var h = new Harness(); h.shots(10); assertEquals(6, h.mag("a")); assertEquals(994, h.ammo("a").reserve().orElseThrow().rounds());
        assertEquals(0, h.count("hits", "a")); assertEquals(0, h.count("fired", "a")); assertEquals(1, h.cues("refilled")); assertEquals(0, h.cues("reloaded"));
        h.equip("normal", "enhanced", "secondary"); h.shots(10); assertEquals(7, h.mag("b")); assertEquals(993, h.ammo("b").reserve().orElseThrow().rounds()); assertEquals(6, h.mag("a"));
    }
    @Test void finalMissStillSettlesButFinalHitIsIncludedBeforeRounding() throws Exception {
        var hit = new Harness(4, 4, 30, 1, 1, .285); hit.shots(3); hit.shoot(true); assertEquals(3, hit.mag("a"));
        var miss = new Harness(4, 4, 30, 1, 1, .285); miss.shots(3); miss.shoot(false); assertEquals(2, miss.mag("a")); assertEquals(3, miss.damage.size());
    }
    @Test void firingThresholdUsesPaidRoundsAndExplicitConflictingCalibration() throws Exception {
        for (double threshold : List.of(.285, .2875)) {
            var h = new Harness(200, 57, 1000, 1, 1, threshold); h.shots(57);
            assertEquals(threshold == .285 ? 35 : 0, h.mag("a"));
        }
        var tooFew = new Harness(10, 2, 30, 1, 1, .285); tooFew.shots(2); assertEquals(0, tooFew.mag("a")); assertEquals(2, tooFew.count("fired", "a"));
        var data = weapon(10, 3, 30, 1, 1); data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonObject("cost").addProperty("value", 3);
        var multi = new Harness(data, 1, 1, .285); multi.shoot(true); assertEquals(1, multi.mag("a"));
        var free = weapon(10, 0, 30, 1, 1); free.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonObject("cost").addProperty("value", 0);
        var noCost = new Harness(free, 1, 1, .285); noCost.shoot(true); assertEquals(0, noCost.mag("a")); assertEquals(0, noCost.count("fired", "a"));
    }
    @Test void adaptiveBurstCountsEachBoltAndKeepsItsEnhancedCoefficientExplicit() throws Exception {
        var h = new Harness(11, 11, 1000, 3, 1, .285); h.equip("adaptive", "adaptive_enhanced", "primary"); h.shots(11);
        assertEquals(5, h.mag("a")); assertEquals(33, h.damage.size()); h.equip("adaptive", "adaptive_enhanced", "secondary"); h.shots(11); assertEquals(6, h.mag("b"));
        var exact = new Harness(100, 100, 1000, 1, 1, .285); exact.equip("enhanced", "none", "primary"); exact.shots(100); assertEquals(70, exact.mag("a"));
    }
    @Test void everyDeclaredGroupMustReportBeforeTheFinalTriggerCanRefill() throws Exception {
        var h = new Harness(10, 3, 1000, 3, 2, .285); h.shots(2); h.until(h.now() + 300_000); int first = h.launches.size(); h.fire();
        for (int i = first; i < first + 3; i++) h.impact(i, true);
        assertEquals(0, h.mag("a")); assertEquals(15, h.count("hits", "a")); assertEquals(1, h.buff("pending", "a").orElseThrow().components().numbers().get("groups"));
        for (int i = first + 5; i >= first + 3; i--) h.impact(i, true);
        assertEquals(10, h.mag("a")); assertEquals(990, h.ammo("a").reserve().orElseThrow().rounds()); assertTrue(h.buff("pending", "a").isEmpty());
    }
    @Test void indirectWeaponDamageCountsButDuplicateWrongCreditAndIneligibleOutcomesDoNot() throws Exception {
        var h = new Harness(10, 3, 1000, 1, 1, .285);
        h.hit("player", "a", "explosion", Set.of("chorus:weapon_damage", "chorus:explosive_perk_damage"), DamageReceipt.Outcome.APPLIED); h.hit("explosion");
        h.hit("other", "a", "other", Set.of("chorus:weapon_damage"), DamageReceipt.Outcome.APPLIED);
        h.hit("player", "b", "b", Set.of("chorus:weapon_damage"), DamageReceipt.Outcome.APPLIED);
        h.hit("player", "a", "ability", Set.of("chorus:ability_damage"), DamageReceipt.Outcome.APPLIED);
        for (var outcome : List.of(DamageReceipt.Outcome.BLOCKED, DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED)) h.hit("player", "a", outcome.name(), Set.of("chorus:weapon_damage"), outcome);
        assertEquals(1, h.count("hits", "a")); assertEquals(1, h.count("hits", "b")); h.shots(3); assertEquals(3, h.mag("a"));
    }
    @Test void ignoredHitsAreStillRememberedAndTheConfiguredSuppressionBoundaryIsHalfOpen() throws Exception {
        var h = new Harness(4, 4, 30, 1, 1, .285); h.shots(4); long proc = h.now();
        h.hit("ignored"); h.until(proc + 249_999); h.hit("near"); assertEquals(0, h.count("hits", "a"));
        h.until(proc + 250_000); h.hit("ignored"); h.hit("near"); assertEquals(0, h.count("hits", "a")); h.hit("new"); assertEquals(1, h.count("hits", "a"));
        h.until(h.now() + 3_000_000); assertTrue(h.buff("receipts", "a").isEmpty());
    }
    @Test void stowRetainsCountersWhileManualReloadResetsCountsAndInvalidatesPendingFire() throws Exception {
        var h = new Harness(10, 3, 1000, 1, 1, .285); h.shots(2); h.equip("normal", "enhanced", "secondary"); h.until(h.now() + 5_000_000);
        assertEquals(2, h.count("hits", "a")); h.equip("normal", "enhanced", "primary"); h.fire(); int last = h.launches.size() - 1;
        h.reload(); h.until(h.now() + 200_000); assertEquals(10, h.mag("a")); assertEquals(0, h.count("hits", "a")); assertTrue(h.buff("pending", "a").isEmpty());
        h.impact(last, true); assertEquals(10, h.mag("a")); assertEquals(1, h.cues("reloaded")); assertEquals(1, h.cues("refilled"));
    }
    @Test void zeroTransferDoesNotPretendToProcAndLaterReserveGainDoesNotReplaySettlement() throws Exception {
        var h = new Harness(2, 2, 0, 1, 1, .285); h.shots(2); assertEquals(0, h.mag("a")); assertEquals(2, h.count("hits", "a")); assertTrue(h.buff("ignore", "a").isEmpty());
        h.amount("reserve", "a", 1); assertEquals(0, h.mag("a")); h.amount("generate", "a", 1); h.shoot(true);
        assertEquals(1, h.mag("a")); assertEquals(0, h.ammo("a").reserve().orElseThrow().rounds()); assertEquals(0, h.count("hits", "a"));
    }
    @Test void unlimitedReservesAllowLegitimateRepeatedRefillsWithoutHistoryGrowth() throws Exception {
        var h = new Harness(5, 5, -1, 1, 1, .285); h.shots(5); assertEquals(3, h.mag("a")); h.shots(3); assertEquals(2, h.mag("a"));
        for (int i = 0; i < 12; i++) { h.shots(2); assertEquals(2, h.mag("a")); }
        assertTrue(h.ammo("a").reserve().isEmpty()); h.until(h.now() + 3_000_000);
        assertTrue(h.buff("receipts", "a").isEmpty()); assertTrue(h.state().timers().isEmpty()); assertEquals(0, h.cues("reloaded"));
    }
    @Test void pendingThresholdKeepsAcceptedCapacityWhileRefillUsesCurrentBaseCapacity() throws Exception {
        var h = new Harness(10, 3, 30, 1, 1, .285);
        var source = new EffectSource("capacity", "test:extended_mag", "player", new BuffInstance.Origin("player", "capacity", "a", ""), Set.of());
        h.session.start(h.now(), SourceChange.bind(source)); h.shots(2); h.until(h.now() + 300_000); h.fire();
        h.session.start(h.now(), SourceChange.remove("capacity")); h.impact(h.launches.size() - 1, true); assertEquals(0, h.mag("a"));
        var inverse = new Harness(10, 3, 30, 1, 1, .285); inverse.shots(2); inverse.until(inverse.now() + 300_000); inverse.fire();
        inverse.session.start(inverse.now(), SourceChange.bind(source)); inverse.impact(inverse.launches.size() - 1, true); assertEquals(2, inverse.mag("a"));
    }
    @Test void incompleteGroupsDoNotRefillAndDetachmentRemovesPendingIdentity() throws Exception {
        var incomplete = new Harness(4, 4, 30, 1, 1, .285); incomplete.shots(3); incomplete.until(incomplete.now() + 300_000); incomplete.fire();
        incomplete.until(incomplete.now() + 2_000_000); assertEquals(0, incomplete.mag("a")); assertTrue(incomplete.buff("pending", "a").isEmpty());
        var detached = new Harness(4, 4, 30, 1, 1, .285); detached.shots(3); detached.until(detached.now() + 300_000); detached.fire();
        detached.equip("none", "enhanced", "primary"); detached.impact(detached.launches.size() - 1, true); assertEquals(0, detached.mag("a")); assertTrue(detached.buff("counter", "a").isEmpty());
    }
    @Test void checkpointRestoresOutstandingGroupAndItsCounterWithoutRepeatingTheRefill() throws Exception {
        var h = new Harness(4, 4, 30, 1, 1, .285); h.shots(3); h.until(h.now() + 300_000); h.fire(); int last = h.launches.size() - 1;
        h.restore(); h.impact(last, true); assertEquals(3, h.mag("a")); assertEquals(1, h.cues("refilled"));
        h.restore(); h.impact(last, true); assertEquals(3, h.mag("a")); assertEquals(1, h.cues("refilled"));
    }
    @Test void unknownPhysicalResultsKeepCommittedAmmoAndNeverInventOrReplayARefill() throws Exception {
        var damage = new Harness(4, 4, 30, 1, 1, .285); damage.shots(3); damage.until(damage.now() + 300_000); damage.fire(); damage.failDamage = true;
        assertThrows(IllegalStateException.class, () -> damage.impact(damage.launches.size() - 1, true)); assertEquals(0, damage.mag("a")); assertEquals(0, damage.cues("refilled"));
        var refill = new Harness(4, 4, 30, 1, 1, .285); refill.shots(3); refill.until(refill.now() + 300_000); refill.fire(); refill.failRefill = true;
        assertThrows(IllegalStateException.class, () -> refill.impact(refill.launches.size() - 1, true)); assertEquals(3, refill.mag("a")); assertEquals(27, refill.ammo("a").reserve().orElseThrow().rounds());
        assertThrows(IllegalStateException.class, () -> refill.until(refill.now() + 1_000_000)); assertEquals(1, refill.cues("refilled"));
    }
    @Test void missingAndNonIntegralCalibrationCannotBeHiddenByDefaults() throws Exception {
        var h = new Harness();
        for (String missing : h.parameters.keySet()) {
            var values = new HashMap<>(h.parameters); values.remove(missing);
            var gear = new Loadout.Gear("x", "test:rifle", Map.of("perk", "normal"), values);
            assertThrows(IllegalArgumentException.class, () -> h.program.changeEquipment(h.state(), new EquipmentChange("other", Loadout.EMPTY, new Loadout(Map.of("test:primary", gear), Optional.of("test:primary")))));
        }
        var values = new HashMap<>(h.parameters); values.put("groups", new Measure(1.5, Unit.COUNT));
        var gear = new Loadout.Gear("x", "test:rifle", Map.of("perk", "normal"), values);
        assertThrows(IllegalArgumentException.class, () -> h.program.changeEquipment(h.state(), new EquipmentChange("other", Loadout.EMPTY, new Loadout(Map.of("test:primary", gear), Optional.of("test:primary")))));
    }
}
