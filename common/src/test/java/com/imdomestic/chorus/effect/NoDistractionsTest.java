package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.Hitscan;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class NoDistractionsTest {
    static final String D = "chorus_d2:", B = D + "no_distractions";
    static final BuffInstance.Origin ATTACKER = new BuffInstance.Origin("enemy", "attack", "enemy_gun", "");
    static CompiledEffects program(JsonObject consumer) throws Exception {
        var fragments = new ArrayList<EffectProgram>(); fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, consumer).getOrThrow());
        for (String fixture : List.of("no_distractions", "weapon_flinch", "weapon_stats", "character_stats")) fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(fixture)).getOrThrow());
        return CompiledEffects.link(fragments);
    }
    static Loadout.Gear gun(String id, String variant, double stability) {
        return new Loadout.Gear(id, "test:rifle", Map.of("perk", variant), Map.of("stability", new Measure(stability, Unit.STAT_POINT), "enhanced_delay", new Measure(.9, Unit.SECOND)));
    }
    static Loadout pair(String slot, double health, double stability) {
        return new Loadout(Map.of("test:primary", gun("a", "normal", stability), "test:secondary", gun("b", "enhanced", stability),
                "test:helmet", new Loadout.Gear("helmet", "test:helmet", Map.of(), Map.of("health", new Measure(health, Unit.STAT_POINT)))), Optional.of("test:" + slot));
    }
    static class Harness extends WeaponHandlingTest.Harness {
        final List<AimImpulse.Receipt> kicks = new ArrayList<>();
        AimImpulse.Rotation rotation = new AimImpulse.Rotation(0, 0);
        boolean unknown;
        Harness() throws Exception { this(json("no_distractions_weapon"), EffectState.Mode.PVE, 100, 50); }
        Harness(JsonObject data, EffectState.Mode mode, double health, double stability) throws Exception {
            super(program(data)); session = new EffectSession(program, EffectCheckpointTest.clock(), 256, EffectState.empty().withMode(mode), this::world);
            equip(pair("primary", health, stability));
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case WeaponAim.Verify q -> new WeaponAim.Verified(q, true);
                case PositionQuery q -> new PositionQuery.Result(q, Optional.of(HitscanTest.START));
                case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("world", 1, 0, 0)));
                case Hitscan.Query q -> HitscanTest.contact(q, Hitscan.Outcome.RANGE);
                case AimImpulse.Command q -> {
                    var change = new AimImpulse.Change(rotation, rotation.add(q)); rotation = change.after();
                    var receipt = new AimImpulse.Receipt(q, change.changed() ? AimImpulse.Outcome.APPLIED : AimImpulse.Outcome.UNCHANGED, Optional.of(change));
                    kicks.add(receipt); if (unknown) throw new IllegalStateException("Unknown flinch write"); yield receipt;
                }
                default -> super.world(request);
            };
        }
        void aim(boolean pressed) { session.start(now(), new WeaponAim.Request("player", "aim/" + ++sequence, pressed).signal()); }
        WeaponFire.Receipt fire() {
            var result = program.fire(state(), new WeaponFire.Request("player", "fire/" + ++sequence));
            session.observe(now(), result.emitted(), new WeaponFire.Commit(state(), result.state())); return (WeaponFire.Receipt) result.result();
        }
        void source(String id, String bundle, String owner, String weapon, Map<String, Measure> parameters) {
            session.start(now(), SourceChange.bind(new EffectSource(id, bundle, owner, new BuffInstance.Origin(owner, id, weapon, ""), Set.of(), parameters)));
        }
        boolean active(String weapon) { return state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals(B) && b.origin().weapon().equals(weapon)); }
        long due() { return state().timers().values().stream().filter(t -> t.signal().type().equals(B + "_ready")).mapToLong(EffectState.Timer::dueAt).findFirst().orElse(-1); }
        void incoming(String victim, boolean declared, DamageReceipt.Outcome outcome, double loss) {
            var command = new DamageCommand(victim, ATTACKER, 1, "minecraft:generic", declared ? Set.of(D + "flinch_input") : Set.of(), Set.of(), false,
                    Optional.empty(), Optional.empty(), new ImpactData(Map.of("flinch_yaw", new Measure(2, Unit.DEGREE), "flinch_pitch", new Measure(-10, Unit.DEGREE))), Optional.empty());
            var receipt = new DamageReceipt("hit/" + ++sequence, outcome, 0, 0, loss, Optional.empty(), false);
            rotation = new AimImpulse.Rotation(0, 0); session.observe(now(), DamageFacts.from(command, receipt));
        }
        double hit() { incoming("player", true, DamageReceipt.Outcome.APPLIED, 1); return kicks.getLast().command().pitch(); }
    }
    @Test void weaponFamiliesHealthAndAdsMultiplyWithoutLosingTheBelowTwentyPenalty() throws Exception {
        var families = Map.of(.25, List.of("auto_rifle", "smg", "bow", "trace_rifle"), .2, List.of("pulse_rifle", "scout_rifle", "sidearm", "machine_gun"),
                .15, List.of("hand_cannon"), .1, List.of("fusion_rifle", "shotgun", "sniper_rifle", "grenade_launcher", "linear_fusion_rifle", "rocket_launcher"));
        for (var group : families.entrySet()) for (String family : group.getValue()) for (var mode : EffectState.Mode.values())
            for (double stability : List.of(0., 20., 50., 100.)) for (double health : List.of(0., 50., 100., 200.)) {
                var data = json("no_distractions_weapon"); var tags = new JsonArray(); tags.add("test:weapon"); tags.add(D + family);
                data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().add("tags", tags);
                var h = new Harness(data, mode, health, stability); double factor = 1 - (stability - 20) / 80 * group.getKey();
                assertEquals(-10 * factor, h.hit(), 1e-9, family + " hip"); h.aim(true); h.until(199_999);
                assertEquals(-10 * factor, h.hit(), 1e-9, "Health waits for completed ADS"); h.until(200_000);
                assertEquals(-10 * factor * (1 - Math.min(health, 100) * .001), h.hit(), 1e-9, family + " ADS");
                assertEquals(ATTACKER, h.kicks.getLast().command().origin());
            }
    }
    @Test void normalAndEnhancedTimersBeginAtInputAndActiveBenefitSurvivesFiring() throws Exception {
        for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(); h.equip(pair(enhanced ? "secondary" : "primary", 100, 50)); h.aim(true);
            long deadline = enhanced ? 900_000 : 1_000_000; String gun = enhanced ? "b" : "a";
            assertEquals(deadline, h.due()); h.until(deadline - 1); assertFalse(h.active(gun)); h.until(deadline); assertTrue(h.active(gun));
            assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
            assertEquals(WeaponFire.Outcome.ACCEPTED, h.fire().outcome()); assertTrue(h.active(gun)); assertEquals(-1, h.due());
            h.until(deadline + 150_000); h.fire(); assertTrue(h.active(gun)); assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
            h.aim(false); assertFalse(h.active(gun)); assertEquals(-10 * .90625, h.hit(), 1e-9);
        }
    }
    @Test void onlyAcceptedShotsRestartPendingWaitAndEmptyMagazineCanStillActivate() throws Exception {
        var h = new Harness(); h.aim(true); h.until(400_000); h.fire(); assertEquals(1_400_000, h.due());
        h.until(450_000); assertEquals(WeaponFire.Outcome.COOLDOWN, h.fire().outcome()); assertEquals(1_400_000, h.due());
        h.until(1_399_999); assertFalse(h.active("a")); h.until(1_400_000); assertTrue(h.active("a"));
        var data = json("no_distractions_weapon"); data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").addProperty("magazine", 0);
        var empty = new Harness(data, EffectState.Mode.PVE, 100, 50); empty.aim(true);
        assertEquals(WeaponFire.Outcome.NO_AMMUNITION, empty.fire().outcome()); assertEquals(1_000_000, empty.due()); empty.until(1_000_000); assertTrue(empty.active("a"));
    }
    @Test void releaseSwitchAndPerkRemovalInvalidateOldTimersAndDoNotBorrowFromAnotherWeapon() throws Exception {
        var h = new Harness(); h.aim(true); h.until(500_000); h.aim(false); assertEquals(-1, h.due());
        h.until(1_000_000); assertFalse(h.active("a")); h.equip(pair("secondary", 100, 50)); h.aim(true); h.until(1_900_000);
        assertTrue(h.active("b")); assertFalse(h.active("a")); h.equip(pair("primary", 100, 50)); assertFalse(h.active("b"));
        h.aim(true); h.until(2_900_000); assertTrue(h.active("a"));
        var slots = new HashMap<>(h.physical.slots()); slots.put("test:primary", gun("a", "none", 50)); h.equip(new Loadout(slots, h.physical.drawn()));
        assertFalse(h.active("a")); assertEquals(-1, h.due()); h.until(4_000_000); assertFalse(h.active("a"));
    }
    @Test void dynamicStabilityUsesTheVictimsHeldWeaponAndCapsBeforeFlinchConversion() throws Exception {
        var h = new Harness(); h.aim(true); h.until(200_000);
        h.source("bonus", "test:stability_bonus", "player", "a", Map.of("points", new Measure(200, Unit.STAT_POINT)));
        h.source("other", "test:stability_bonus", "player", "b", Map.of("points", new Measure(-100, Unit.STAT_POINT)));
        h.source("alien", "test:stability_bonus", "enemy", "a", Map.of("points", new Measure(-100, Unit.STAT_POINT)));
        assertEquals(-10 * .75 * .9, h.hit(), 1e-9);
        h.session.start(h.now(), SourceChange.remove("bonus")); assertEquals(-10 * .90625 * .9, h.hit(), 1e-9);
        h.source("health-duplicate", D + "health_flinch", "player", "", Map.of()); assertEquals(-10 * .90625 * .9, h.hit(), 1e-9);
    }
    @Test void onlyDeclaredPositiveDamageToTheHolderCausesFlinchAndUnknownWritesStopOnce() throws Exception {
        var h = new Harness();
        h.incoming("other", true, DamageReceipt.Outcome.APPLIED, 1); h.incoming("player", false, DamageReceipt.Outcome.APPLIED, 1);
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.BLOCKED)) h.incoming("player", true, outcome, 0);
        assertTrue(h.kicks.isEmpty()); h.unknown = true;
        assertThrows(IllegalStateException.class, h::hit); assertEquals(1, h.kicks.size()); assertFalse(h.cues.contains("test:flinch_applied"));
        assertThrows(IllegalStateException.class, h::hit); assertEquals(1, h.kicks.size());
    }
    @Test void pendingTimerAndActiveWeaponBenefitRoundtripThroughDomainStorage() throws Exception {
        var h = new Harness(); h.aim(true); h.until(400_000);
        var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint()));
        h.session = EffectSession.restore(h.program, EffectCheckpointTest.clock(), 256, saved, h::world);
        h.until(999_999); assertFalse(h.active("a")); h.until(1_000_000); assertTrue(h.active("a")); h.hit();
        var active = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint()));
        h.session = EffectSession.restore(h.program, EffectCheckpointTest.clock(), 256, active, h::world);
        h.fire(); assertTrue(h.active("a")); assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
        assertEquals(h.program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.program.program()).getOrThrow()).getOrThrow());
    }
    @Test void windupUsesInputTimeWhileResistanceWaitsForCompletedAdsAndCalibrationIsRequired() throws Exception {
        var data = json("no_distractions_weapon");
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("aiming").getAsJsonObject("enter").getAsJsonObject("value").addProperty("value", 2);
        var h = new Harness(data, EffectState.Mode.PVE, 100, 50); h.aim(true); h.until(1_000_000);
        assertTrue(h.active("a")); assertEquals(WeaponAim.Phase.ENTERING, h.state().aiming().get("player").phase());
        assertEquals(-10 * .90625, h.hit(), 1e-9); h.until(2_000_000); assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
        for (String fault : List.of("missing", "zero", "unit")) {
            var invalid = new Harness(); var parameters = new HashMap<>(gun("a", "enhanced", 50).parameters());
            if (fault.equals("missing")) parameters.remove("enhanced_delay");
            else parameters.put("enhanced_delay", new Measure(fault.equals("zero") ? 0 : .9, fault.equals("unit") ? Unit.METER : Unit.SECOND));
            var slots = new HashMap<>(invalid.physical.slots()); slots.put("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", "enhanced"), parameters));
            assertThrows(RuntimeException.class, () -> invalid.equip(new Loadout(slots, invalid.physical.drawn())));
        }
    }
}
