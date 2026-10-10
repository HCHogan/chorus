package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurplusTest {
    static final List<String> STATS = List.of("stability", "handling", "reload");
    static final double[][] BONUSES = {{0, 5, 15, 25}, {0, 5, 25, 60}, {0, 10, 25, 60}};
    static CompiledEffects program() throws Exception { return link("surplus", "surplus_weapon", "wellspring_targets", "wellspring"); }
    static Loadout equipment(String primaryPerk, String secondaryPerk, String drawn) {
        return new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", primaryPerk)),
                "test:secondary", new Loadout.Gear("b", "test:shotgun", Map.of("perk", secondaryPerk))), Optional.of("test:" + drawn));
    }
    static class Harness {
        final CompiledEffects program = program(); final EffectSession session; int sequence;
        Harness(boolean enhanced, double grenade, double melee, double clazz) throws Exception {
            var initial = EffectState.empty(); String[] pools = {"grenade", "melee_double", "class", "super"}; double[] values = {grenade, melee, clazz, 1};
            for (int i = 0; i < pools.length; i++) initial = initial.withResource(new ResourceState(new ResourceState.Key("player", "test:" + pools[i] + "_energy"), values[i], i == 1 ? 2 : 1, 0));
            initial = initial.withSource(new EffectSource("input", "test:surplus_input", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of()));
            session = new EffectSession(engine(program), initial, request -> new WeaponReload.Verified((WeaponReload.Verify) request.command(), true));
            select(Map.of("chorus_d2:grenade", "test:grenade", "chorus_d2:melee", "test:melee_double", "chorus_d2:class", "test:class", "chorus_d2:super", "test:super"));
            equip(equipment(enhanced ? "enhanced" : "normal", "normal", "primary"));
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void select(Map<String, String> slots) { session.start(now(), new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), new AbilityLoadout(slots)).signal()); }
        void equip(Loadout loadout) { session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout).signal()); }
        CalculationProfile.Result query(String stat, String weapon, double base, Map<String, Measure> calibration) {
            return program.calculate(state(), "player", new EffectEvent("player", weapon, new BuffInstance.Origin("player", weapon, weapon, ""), Set.of(), calibration),
                    "chorus_d2:weapon_" + stat, new Measure(base, Unit.STAT_POINT), List.of());
        }
        double points(String stat, String weapon) { return query(stat, weapon, 10, Map.of()).trace().stages().get("stat_cap").value(); }
        void spend(String weapon) { session.start(now(), new RuleEngine.Signal("test:spend_ammo", new EffectEvent("player", weapon, new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of()))); }
        void use(String slot) { session.start(now(), new AbilityUse.Request("player", "chorus_d2:" + slot, "cast-" + ++sequence,
                new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of())).signal()); }
        WeaponReload.Plan reload() { session.start(now(), new WeaponReload.Request("player", "reload-" + ++sequence).signal()); return state().reloads().get("player"); }
    }
    @Test void countsWholeChargesAcrossSlotsAndCapsAtThreeWithoutSuper() throws Exception {
        for (double grenade : new double[]{0, .999, 1}) for (double melee : new double[]{0, .999, 1, 1.999, 2}) for (double clazz : new double[]{0, 1}) {
            var h = new Harness(false, grenade, melee, clazz); int tier = Math.min(3, (int) grenade + (int) melee + (int) clazz);
            for (int i = 0; i < STATS.size(); i++) assertEquals(10 + BONUSES[i][tier], h.points(STATS.get(i), "a"), 1e-12, grenade + "/" + melee + "/" + clazz);
            assertEquals(1, h.state().resources().get(new ResourceState.Key("player", "test:super_energy")).value());
        }
    }
    @Test void eachWeaponGetsOnlyItsOwnPerkAndStowedQueriesRemainCurrent() throws Exception {
        var h = new Harness(false, 1, 1, 1); assertEquals(70, h.points("handling", "a")); assertEquals(70, h.points("handling", "b"));
        h.equip(equipment("normal", "none", "secondary")); assertEquals(70, h.points("handling", "a")); assertEquals(10, h.points("handling", "b"));
        assertEquals(10, h.points("handling", "unrelated"));
        h.use("grenade"); assertEquals(35, h.points("handling", "a"), "stowed source reads current energy");
        h.equip(Loadout.EMPTY); assertEquals(10, h.points("handling", "a"));
    }
    @Test void missingAndNoCostSlotsAreExplicitlyExcludedAndSelectionsUpdateImmediately() throws Exception {
        var h = new Harness(false, 1, 2, 1);
        h.select(Map.of("chorus_d2:melee", "test:melee_double", "chorus_d2:class", "test:no_energy")); assertEquals(35, h.points("handling", "a"));
        h.select(Map.of("chorus_d2:melee", "test:melee")); assertEquals(10, h.points("handling", "a"));
        h.select(Map.of()); assertEquals(10, h.points("handling", "a")); assertTrue(h.session.state().engine().failure().isEmpty());
    }
    @Test void enhancedTotalsAreExplicitTierInputsAndMissingCalibrationIsNotNormalFallback() throws Exception {
        for (int tier = 1; tier <= 3; tier++) {
            var h = new Harness(true, tier == 3 ? 1 : 0, Math.min(tier, 2), 0);
            for (int i = 0; i < STATS.size(); i++) {
                String stat = STATS.get(i), key = "surplus_enhanced_" + stat + "_" + tier;
                var before = h.state(); assertThrows(IllegalArgumentException.class, () -> h.query(stat, "a", 10, Map.of()));
                assertSame(before, h.state());
                // +2 is a synthetic test input, not a measured enhanced effect.
                var result = h.query(stat, "a", 10, Map.of(key, new Measure(BONUSES[i][tier] + 2, Unit.STAT_POINT)));
                assertEquals(12 + BONUSES[i][tier], result.trace().stages().get("stat_cap").value());
                assertThrows(IllegalArgumentException.class, () -> h.query(stat, "a", 10, Map.of(key, new Measure(1, Unit.SECOND))));
            }
        }
        var empty = new Harness(true, 0, 0, 0); assertEquals(10, empty.points("handling", "a"));
    }
    @Test void bonusesEnterBeforeStatClampAndReloadArchetypeCurve() throws Exception {
        var h = new Harness(false, 1, 2, 1); var result = h.query("reload", "a", 80, Map.of());
        assertEquals(140, result.trace().stages().get("perks").value()); assertEquals(100, result.trace().stages().get("stat_cap").value());
        assertEquals(new Measure(1, Unit.SECOND), result.output()); assertEquals(1, result.trace().contributions().stream().filter(c -> c.selected()).count());
        assertEquals(100, h.query("stability", "a", 90, Map.of()).output().value());
    }
    @Test void acceptedReloadKeepsItsDurationAndNextReloadUsesSpentAbilityState() throws Exception {
        var h = new Harness(false, 1, 1, 1); h.spend("a"); var first = h.reload();
        assertEquals(1_300_000, first.dueAt()); assertEquals(new Measure(10, Unit.STAT_POINT), first.input());
        h.use("melee"); assertEquals(35, h.points("reload", "a")); assertEquals(first, h.state().reloads().get("player"));
        h.session.observe(1_299_999, List.of()); assertEquals(4, h.state().ammunition().get("a").magazine());
        h.session.observe(1_300_000, List.of()); assertEquals(5, h.state().ammunition().get("a").magazine());
        h.spend("a"); var second = h.reload(); assertEquals(2_950_000, second.dueAt()); assertEquals(1.65, second.duration().value(), 1e-12);
    }
    @Test void missingEnhancedReloadCalibrationFailsBeforeSchedulingOrMovingAmmo() throws Exception {
        var h = new Harness(true, 1, 1, 1); h.spend("a"); var before = h.state();
        var failure = assertThrows(IllegalStateException.class, h::reload); assertTrue(failure.getMessage().contains("surplus_enhanced_reload_3")); assertEquals(before.ammunition(), h.state().ammunition());
        assertTrue(h.state().reloads().isEmpty()); assertTrue(h.state().timers().isEmpty());
    }
    @Test void combinedDefinitionsRoundTripWithTypedLiveEnergyReads() throws Exception {
        var p = program(); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
}
