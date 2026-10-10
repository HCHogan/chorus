package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Explicit physical armor inputs, not a generator or validator for legal Destiny drops. */
class ArmorStatInputsTest {
    static final List<String> STATS = List.of("health", "grenade", "melee", "class", "super", "weapons");
    static final String GRENADE = SolarFragmentStatsTest.GRENADE, MELEE = SolarFragmentStatsTest.MELEE;
    static CompiledEffects program() throws Exception {
        return link("threaded_spike", "combat_damage", "strand_defense", "continuity", "threaded_spike_energy", "grenade_energy", "arcbolt_energy", "character_stats",
                "solar", "solar_test_calibration", "ember_of_char", "ember_of_eruption", "solar_attribute_inputs", "armor_stats", "armor_stat_inputs");
    }
    static Loadout.Gear gear(String instance, String slot, double value) {
        var points = new TreeMap<String, Measure>(); STATS.forEach(stat -> points.put(stat, new Measure(value, Unit.STAT_POINT)));
        return new Loadout.Gear(instance, "test:armor_" + slot, Map.of(), points);
    }
    static Loadout one(String instance, double value) { return new Loadout(Map.of("chorus_d2:arms", gear(instance, "arms", value)), Optional.empty()); }
    static final class Harness {
        final CompiledEffects p; final EffectSession session;
        Harness() throws Exception {
            p = program(); var state = EffectState.empty();
            for (String owner : List.of("owner", "recipient")) for (String id : List.of(GRENADE, MELEE)) state = state.withResource(new ResourceState(new ResourceState.Key(owner, id), 0, 1, 0));
            session = new EffectSession(engine(p), state, _ -> { throw new AssertionError("Stat inputs must be pure"); });
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void choose(String owner, boolean active) {
            var after = active ? new AbilityLoadout(Map.of("chorus_d2:grenade", "test:attribute_grenade", "chorus_d2:melee", "chorus_d2:threaded_spike")) : AbilityLoadout.EMPTY;
            session.start(now(), new AbilityChange(owner, state().abilities().getOrDefault(owner, AbilityLoadout.EMPTY), after).signal());
        }
        void equip(String owner, Loadout after) { session.start(now(), new EquipmentChange(owner, state().equipment().getOrDefault(owner, Loadout.EMPTY), after).signal()); }
        void fragment(String owner, String name) { session.start(now(), SourceChange.bind(SolarFragmentStatsTest.source(owner + name, "chorus_d2:ember_of_" + name, owner))); }
        double points(String owner, String stat) { return p.attribute(state(), owner, "chorus_d2:" + stat + "_stat", new Measure(0, Unit.STAT_POINT), NumericQuery.Path.empty()).output().value(); }
        double gain(String owner, String resource, EnergyGains.Basis basis) {
            var source = SolarFragmentStatsTest.source("grant", "test:solar_attribute_inputs", "owner");
            var event = new EffectEvent("owner", owner, source.origin(), Set.of(), Map.of());
            var resources = p.program().resources().stream().collect(java.util.stream.Collectors.toMap(ResourceDefinition::id, r -> r));
            var e = new Evaluation(state(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), now(), new RuleEngine.Signal("test:grant", event)), "grant", source, Map.of()), Map.of(), Map.of(), resources, Map.of(), Optional.of(p));
            return ((EnergyActions.Result)((RuleEngine.Local<EffectState>)new EnergyActions.Grant(resource, Evaluation.Target.VICTIM, new Value.Constant(.04, Unit.CHARGE), basis, Map.of(), Set.of(), Map.of()).execute(e)).result()).grant().scaled();
        }
        double energy(String owner, String id) { return state().resources().get(new ResourceState.Key(owner, id)).value(); }
    }
    @Test void nakedSelectionsUseFragmentPointsWithoutAnyAttributeBuffInitialization() throws Exception {
        var h = new Harness(); h.choose("owner", true); h.choose("recipient", true); h.fragment("owner", "char"); h.fragment("owner", "eruption");
        assertTrue(h.state().buffs().instances().isEmpty()); assertEquals(10, h.points("owner", "grenade")); assertEquals(10, h.points("owner", "melee"));
        assertEquals(.04 * .75 * SolarFragmentStatsTest.chunk(10), h.gain("owner", GRENADE, EnergyGains.Basis.BASE), 1e-12);
        assertEquals(.04 * .8 * SolarFragmentStatsTest.chunk(10), h.gain("owner", MELEE, EnergyGains.Basis.BASE), 1e-12);
        assertEquals(.04 * .8, h.gain("recipient", MELEE, EnergyGains.Basis.BASE), 1e-12);
        assertEquals(.04, h.gain("owner", GRENADE, EnergyGains.Basis.FIXED), 1e-12);
    }
    @Test void fivePhysicalSlotInputsAggregateAllSixStatsWithoutNeedingAnAbilitySelection() throws Exception {
        var h = new Harness(); var slots = new TreeMap<String, Loadout.Gear>();
        for (String slot : List.of("helmet", "arms", "chest", "legs", "class_item")) slots.put("chorus_d2:" + slot, gear(slot, slot, 15));
        var roll = new Loadout(slots, Optional.empty()); h.equip("owner", roll);
        for (String stat : STATS) { assertEquals(75, h.points("owner", stat)); assertEquals(0, h.points("recipient", stat)); }
        assertTrue(h.state().abilities().isEmpty()); assertTrue(h.state().buffs().instances().isEmpty());
        h.choose("owner", true); assertEquals(.04 * .8 * SolarFragmentStatsTest.chunk(75), h.gain("owner", MELEE, EnergyGains.Basis.BASE), 1e-12);
        h.equip("owner", Loadout.EMPTY); for (String stat : STATS) assertEquals(0, h.points("owner", stat));
        assertEquals(15, roll.slots().get("chorus_d2:class_item").parameters().get("melee").value());
    }
    @Test void currentRecipientArmorAndFragmentContributionsApplyBeforeCapsAndCes() throws Exception {
        var h = new Harness(); for (String owner : List.of("owner", "recipient")) h.choose(owner, true);
        h.equip("recipient", one("recipient-arms", 30)); h.fragment("owner", "char"); h.fragment("owner", "eruption");
        for (double base : List.of(0., 50., 90., 100., 195., 200.)) {
            var gear = one("same-arms", base); h.equip("owner", gear); double effective = Math.min(200, base + 10);
            assertEquals(effective, h.points("owner", "grenade")); assertEquals(effective, h.points("owner", "melee"));
            assertEquals(.04 * .75 * SolarFragmentStatsTest.chunk(effective), h.gain("owner", GRENADE, EnergyGains.Basis.BASE), 1e-12);
            assertEquals(.04 * .8 * SolarFragmentStatsTest.chunk(effective), h.gain("owner", MELEE, EnergyGains.Basis.BASE), 1e-12);
            assertEquals(.04 * .8 * SolarFragmentStatsTest.chunk(30), h.gain("recipient", MELEE, EnergyGains.Basis.BASE), 1e-12);
            assertEquals(gear, h.state().equipment().get("owner")); assertTrue(h.state().buffs().instances().isEmpty());
        }
    }
    @Test void swappingArmorSplitsActualPassiveIntegrationAndClearingSelectionRemovesOnlyItsCurve() throws Exception {
        var h = new Harness(); h.choose("owner", true); h.equip("owner", one("a", 50)); h.fragment("owner", "eruption");
        h.session.observe(500_000, List.of()); h.equip("owner", one("b", 60)); h.session.observe(1_500_000, List.of()); h.equip("owner", Loadout.EMPTY);
        h.session.observe(2_000_000, List.of()); double expected = (.5 * SolarFragmentStatsTest.passive(60) + SolarFragmentStatsTest.passive(70) + .5 * SolarFragmentStatsTest.passive(10)) / 145.2;
        assertEquals(expected, h.energy("owner", MELEE), 1e-12);
        h.choose("owner", false); h.session.observe(3_000_000, List.of()); assertEquals(expected + 1 / 145.2, h.energy("owner", MELEE), 1e-12);
        assertEquals(10, h.points("owner", "melee")); assertEquals(.04 * .8, h.gain("owner", MELEE, EnergyGains.Basis.BASE), 1e-12);
        var balance = h.energy("owner", MELEE); h.choose("owner", true); assertEquals(balance, h.energy("owner", MELEE));
        assertEquals(.04 * .8 * SolarFragmentStatsTest.chunk(10), h.gain("owner", MELEE, EnergyGains.Basis.BASE), 1e-12);
    }
    @Test void optionalIntrinsicPointsAddOnceAndDoNotKeepOldAbilityCurvesAliveAfterDeselection() throws Exception {
        var h = new Harness(); h.choose("owner", true); h.equip("owner", one("arms", 20)); h.fragment("owner", "char");
        var source = SolarFragmentStatsTest.source("input", "test:solar_attribute_inputs", "owner"); h.session.start(0, SourceChange.bind(source));
        h.session.start(0, new RuleEngine.Signal("test:stat", new EffectEvent("owner", "owner", source.origin(), Set.of(), Map.of("grenade", new Measure(30, Unit.STAT_POINT), "melee", new Measure(40, Unit.STAT_POINT)))));
        assertEquals(60, h.points("owner", "grenade")); assertEquals(60, h.points("owner", "melee"));
        assertEquals(.04 * .75 * SolarFragmentStatsTest.chunk(60), h.gain("owner", GRENADE, EnergyGains.Basis.BASE), 1e-12);
        h.choose("owner", false); assertEquals(60, h.points("owner", "grenade")); assertEquals(.04 * .75, h.gain("owner", GRENADE, EnergyGains.Basis.BASE), 1e-12);
        h.equip("owner", Loadout.EMPTY); assertEquals(40, h.points("owner", "grenade")); assertEquals(40, h.points("owner", "melee"));
    }
}
