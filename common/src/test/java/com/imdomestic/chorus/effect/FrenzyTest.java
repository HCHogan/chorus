package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FrenzyTest {
    static CompiledEffects program() throws Exception { return link("weapon_stats", "frenzy", "frenzy_weapon", "frenzy_test_calibration"); }
    static Loadout gear(String normal, String drawn) { return RampageTest.loadout(normal, drawn); }
    static class Harness {
        final CompiledEffects program = program(); final EffectSession session; int sequence;
        Harness() throws Exception { this(EffectState.Mode.PVE); }
        Harness(EffectState.Mode mode) throws Exception {
            session = new EffectSession(engine(program), EffectState.empty().withMode(mode), request -> new WeaponReload.Verified((WeaponReload.Verify) request.command(), true));
            equip(gear("normal", "primary"));
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void until(long time) { session.observe(time, List.of()); assertTrue(session.state().engine().failure().isEmpty()); }
        void equip(Loadout next) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); }
        void damage(long time, String actor, String victim, double loss, DamageReceipt.Outcome outcome, Set<String> tags) {
            var command = new DamageCommand(victim, new BuffInstance.Origin(actor, "attack", "other-weapon", ""), 1, "minecraft:generic", tags, Set.of(), false);
            session.observe(time, DamageFacts.from(command, new DamageReceipt("hit/" + ++sequence, outcome, 0, loss, 0, Optional.empty(), false)));
            assertTrue(session.state().engine().failure().isEmpty());
        }
        void hit(long time) { damage(time, "player", "enemy", 1, DamageReceipt.Outcome.APPLIED, Set.of()); }
        void incoming(long time) { damage(time, "enemy", "player", 1, DamageReceipt.Outcome.APPLIED, Set.of()); }
        Optional<BuffInstance> buff(String name, String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:frenzy_" + name) && b.origin().weapon().equals(weapon)).findFirst(); }
        boolean active(String weapon) { return buff("active", weapon).isPresent(); }
        double query(String name, String weapon) {
            boolean damage = name.equals("damage"); var origin = new BuffInstance.Origin("player", "query", weapon, "");
            return program.calculate(state(), "player", new EffectEvent("player", "enemy", origin, Set.of("chorus:weapon_damage"), Map.of()),
                    damage ? "test:weapon_damage" : "chorus_d2:weapon_" + name, new Measure(damage ? 100 : 10, damage ? Unit.DAMAGE : Unit.STAT_POINT), List.of()).output().value();
        }
        void activate() { hit(0); incoming(4_000_000); hit(8_000_000); until(12_000_000); }
    }
    @Test void twelveContinuousSecondsActivateWithoutANewHitAndAllThreeBenefitsUseTheirOwnProfiles() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode); h.hit(0); h.incoming(4_000_000); h.hit(8_000_000); h.until(11_999_999);
            assertFalse(h.active("a")); assertFalse(h.active("b")); h.until(12_000_000);
            for (String weapon : List.of("a", "b")) {
                assertTrue(h.active(weapon)); assertEquals(115, h.query("damage", weapon), 1e-9);
                assertEquals(100, h.query("handling", weapon)); assertEquals(100, h.query("reload", weapon));
                assertEquals(19_000_000, h.buff("active", weapon).orElseThrow().deadline());
                assertTrue(h.buff("contact", weapon).isEmpty() && h.buff("windup", weapon).isEmpty());
            }
            assertEquals(100, h.query("damage", "unrelated")); assertEquals(10, h.query("handling", "unrelated"));
            h.until(18_999_999); assertTrue(h.active("a")); h.until(19_000_000); assertFalse(h.active("a"));
        }
    }
    @Test void fiveSecondGapRestartsNormalProgressButEnhancedFivePointFiveWindowSurvives() throws Exception {
        var h = new Harness(); h.hit(0); h.hit(5_250_000); h.incoming(10_000_000); h.until(12_000_000);
        assertFalse(h.active("a")); assertTrue(h.active("b")); assertEquals(17_250_000, h.buff("windup", "a").orElseThrow().deadline());
        h.hit(14_000_000); h.until(17_250_000); assertTrue(h.active("a")); assertTrue(h.active("b"));
        assertEquals(21_800_000, h.buff("active", "b").orElseThrow().deadline(), "synthetic enhanced calibration, not measured D2 timing");
    }
    @Test void contactExpiryAtActivationBoundaryWinsAndTheNextDamageStartsAFreshWindup() throws Exception {
        var h = new Harness(); h.hit(0); h.hit(3_000_000); h.hit(7_000_000); h.until(12_000_000);
        assertFalse(h.active("a")); assertTrue(h.buff("windup", "a").isEmpty()); assertTrue(h.active("b"));
        h.hit(12_000_000); assertEquals(24_000_000, h.buff("windup", "a").orElseThrow().deadline());
        assertFalse(h.active("a"));
    }
    @Test void activeRefreshUsesSevenSecondsAndExplicitEnhancedCalibrationEvenAfterTheWarmupGapWindow() throws Exception {
        var h = new Harness(); h.activate(); h.incoming(18_000_000);
        assertEquals(25_000_000, h.buff("active", "a").orElseThrow().deadline()); assertEquals(25_800_000, h.buff("active", "b").orElseThrow().deadline());
        assertTrue(h.buff("windup", "a").isEmpty()); h.until(25_000_000); assertFalse(h.active("a")); assertTrue(h.active("b"));
        h.until(25_800_000); assertFalse(h.active("b")); h.hit(26_000_000); assertEquals(38_000_000, h.buff("windup", "a").orElseThrow().deadline());
    }
    @Test void positiveOwnerCombatIsRequiredAndSelfEnvironmentalAndUnrelatedDamageDoNotCount() throws Exception {
        var h = new Harness();
        h.damage(0, "ally", "enemy", 1, DamageReceipt.Outcome.APPLIED, Set.of());
        h.damage(0, "", "player", 1, DamageReceipt.Outcome.APPLIED, Set.of());
        h.damage(0, "player", "player", 1, DamageReceipt.Outcome.APPLIED, Set.of());
        h.damage(0, "environment", "player", 1, DamageReceipt.Outcome.APPLIED, Set.of("chorus:environmental_damage"));
        for (var outcome : DamageReceipt.Outcome.values()) h.damage(0, "player", "enemy", 0, outcome, Set.of());
        assertTrue(h.state().buffs().instances().isEmpty()); h.incoming(0);
        assertTrue(h.buff("windup", "a").isPresent()); assertTrue(h.buff("windup", "b").isPresent(), "incoming absorption-only damage counts for both equipped perks");
    }
    @Test void stowKeepsProgressButDetachAndReattachCannotReceiveTheOldWindupExpiration() throws Exception {
        var h = new Harness(); h.hit(0); h.incoming(4_000_000); h.equip(gear("normal", "secondary"));
        h.until(5_000_000); h.equip(gear("none", "secondary")); assertTrue(h.buff("windup", "a").isEmpty());
        h.until(6_000_000); h.equip(gear("normal", "secondary")); h.hit(6_000_000); h.hit(10_000_000); h.until(12_000_000);
        assertFalse(h.active("a")); assertTrue(h.active("b")); h.hit(14_000_000); h.until(18_000_000); assertTrue(h.active("a"));
        h.equip(Loadout.EMPTY); assertTrue(h.state().buffs().instances().isEmpty()); h.until(30_000_000); assertTrue(h.state().buffs().instances().isEmpty());
    }
    @Test void reloadUsesSharedStatCapAndAcceptedTimeSurvivesSubsequentBuffExpiry() throws Exception {
        var h = new Harness(); h.activate(); h.until(18_500_000); h.session.start(h.now(), new WeaponReload.Request("player", "reload").signal());
        var plan = h.state().reloads().get("player"); assertEquals(1, plan.duration().value());
        assertEquals(110, plan.calculation().orElseThrow().steps().getFirst().trace().stages().get("perks").value());
        h.until(19_000_000); assertFalse(h.active("a")); assertEquals(plan, h.state().reloads().get("player"));
        h.until(19_500_000); assertEquals(5, h.state().ammunition().get("a").magazine()); assertEquals(29, h.state().ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(10, h.query("reload", "a"));
    }
    @Test void refreshCalibrationIsRequiredTypedAndNoUnmeasuredDefaultIsInventedByTheTemplate() throws Exception {
        assertThrows(RuntimeException.class, () -> link("weapon_stats", "frenzy", "frenzy_weapon"));
        var bad = json("frenzy_test_calibration"); bad.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("output_unit", "damage");
        assertThrows(RuntimeException.class, () -> CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, bad).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("weapon_stats")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("frenzy")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("frenzy_weapon")).getOrThrow())));
        var p = program(); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
}
