package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EquipmentTest {
    @Test void committedItemTransferReconcilesMetadataBeforeItsFactsWithoutRepeatingInitialization() throws Exception {
        var h = new Harness(); var change = new EquipmentChange("player", Loadout.EMPTY, pair("weapon_a"));
        var preflight = change.apply(h.state(), h.program.equipment());
        h.session.observe(0, preflight.emitted(), new EquipmentChange.Commit(change));
        assertEquals(preflight.state().sources(), h.state().sources()); assertEquals(pair("weapon_a"), h.state().equipment().get("player"));
        assertEquals(List.of(1.0, 2.0), h.heals.stream().map(HealingCommand::amount).toList());
        assertTrue(h.observed.stream().allMatch(state -> state.equipment().get("player").equals(pair("weapon_a"))));
    }
    static Loadout.Gear gun(String id, String option) { return new Loadout.Gear(id, "test:rifle", Map.of("perk", option)); }
    static Loadout pair(String selected) { return new Loadout(Map.of("test:weapon_a", gun("a", "normal"), "test:weapon_b", gun("b", "enhanced")), Optional.of("test:" + selected)); }
    static DamageCommand attack(String weapon) { return new DamageCommand("enemy", new BuffInstance.Origin("player", weapon, weapon, ""), 10, "minecraft:generic", Set.of(), Set.of(), false, Optional.of("test:equipment_damage")); }
    static final class Harness {
        final CompiledEffects program;
        final EffectSession session;
        final List<HealingCommand> heals = new ArrayList<>();
        final List<EffectState> observed = new ArrayList<>();
        Harness() throws Exception {
            program = load("equipment"); session = new EffectSession(engine(program), EffectState.empty(), request -> {
                var c = (HealingCommand) request.command(); heals.add(c); observed.add(state());
                return new HealingReceipt("equip/" + heals.size(), c, HealingReceipt.Outcome.APPLIED, c.amount(), c.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void equip(long time, Loadout next) { session.start(time, new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); }
        void activate(long time, String weapon) { session.start(time, new RuleEngine.Signal("test:gear_activate", new EffectEvent("player", "player", attack(weapon).source(), Set.of(), Map.of()))); }
        Optional<BuffInstance> ready(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.origin().weapon().equals(weapon)).findFirst(); }
        double damage(String weapon) { return program.outgoing(state(), attack(weapon), 10).orElseThrow().output().value(); }
    }
    @Test void completeLoadoutAndAllSourcesAreVisibleBeforeAnyAttachReactionRuns() throws Exception {
        var h = new Harness(); var loadout = pair("weapon_a"); h.equip(0, loadout);
        assertEquals(5, h.state().sources().size()); assertEquals(List.of(1.0, 2.0), h.heals.stream().map(HealingCommand::amount).toList());
        for (var observed : h.observed) { assertEquals(loadout, observed.equipment().get("player")); assertEquals(h.state().sources(), observed.sources()); }
        assertEquals(15.125, h.damage("a"), 1e-12); assertEquals(16.5, h.damage("b"), 1e-12);
        h.equip(0, loadout); assertEquals(2, h.heals.size()); assertEquals(2, h.state().timers().size());
    }
    @Test void switchingWeaponsPreservesEquippedSourcesAndPausesThenResumesBoundBuffs() throws Exception {
        var h = new Harness(); h.equip(0, pair("weapon_a")); h.activate(0, "a"); h.activate(0, "b");
        assertTrue(h.ready("a").isPresent()); assertTrue(h.ready("b").isEmpty()); long generation = h.ready("a").orElseThrow().generation();
        h.equip(50_000, pair("weapon_b")); assertEquals(2, h.heals.size()); assertTrue(h.ready("a").orElseThrow().pausedAt().isPresent());
        assertEquals(13.75, h.damage("a"), 1e-12); assertEquals(18.15, h.damage("b"), 1e-12);
        h.session.observe(500_000, List.of()); assertEquals(pair("weapon_b"), h.state().equipment().get("player")); assertTrue(h.ready("a").isPresent());
        h.equip(500_000, pair("weapon_a")); assertEquals(generation, h.ready("a").orElseThrow().generation()); assertTrue(h.ready("a").orElseThrow().pausedAt().isEmpty());
        h.session.observe(849_999, List.of()); assertTrue(h.ready("a").isPresent()); h.session.observe(850_000, List.of()); assertTrue(h.ready("a").isEmpty());
    }
    @Test void movingTheSameInstanceBetweenCompatibleSlotsKeepsSourceAndTimerIdentities() throws Exception {
        var h = new Harness(); h.equip(0, pair("weapon_a")); h.activate(0, "a"); var old = h.state();
        var moved = new Loadout(Map.of("test:weapon_a", gun("b", "enhanced"), "test:weapon_b", gun("a", "normal")), Optional.of("test:weapon_b"));
        h.equip(0, moved); assertEquals(old.sources(), h.state().sources()); assertEquals(old.timers(), h.state().timers()); assertEquals(old.buffs(), h.state().buffs()); assertEquals(2, h.heals.size());
    }
    @Test void changingARollRunsOldScopeCleanupOnceThenAttachesTheReplacement() throws Exception {
        var h = new Harness(); var before = new Loadout(Map.of("test:weapon_a", gun("a", "normal")), Optional.of("test:weapon_a")); h.equip(0, before); h.activate(0, "a");
        var after = new Loadout(Map.of("test:weapon_a", gun("a", "enhanced")), before.drawn()); h.equip(50_000, after);
        assertEquals(List.of(1.0, 10.0, 2.0), h.heals.stream().map(HealingCommand::amount).toList()); assertTrue(h.ready("a").isEmpty());
        assertEquals(after, h.observed.get(1).equipment().get("player")); assertEquals(h.state().sources(), h.observed.get(1).sources());
        h.session.observe(100_000, List.of()); assertEquals(3, h.heals.size()); h.session.observe(150_000, List.of()); assertEquals(4, h.heals.size());
    }
    @Test void removalClearsOnlyOwnedBindingsAndUsesOldEnhancedTagsForCleanup() throws Exception {
        var h = new Harness(); var admin = new EffectSource("external", "test:gear_passive", "player", new BuffInstance.Origin("player", "external", "other", ""), Set.of());
        h.session.start(0, SourceChange.bind(admin)); h.equip(0, pair("weapon_a")); h.activate(0, "a"); h.equip(70_001, Loadout.EMPTY);
        assertEquals(Map.of("external", admin), h.state().sources()); assertTrue(h.state().equipment().isEmpty()); assertTrue(h.state().timers().isEmpty()); assertTrue(h.state().buffs().instances().isEmpty());
        assertEquals(List.of(1.0, 2.0, 10.0, 20.0), h.heals.stream().map(HealingCommand::amount).toList()); h.session.observe(200_000, List.of()); assertEquals(4, h.heals.size());
    }
    @Test void slotAcceptanceUniqueInstancesRequiredChoicesDrawnSlotAndScopedLimitsAreValidated() throws Exception {
        var p = load("equipment"); var schema = p.equipment();
        for (var invalid : List.of(new Loadout(Map.of("test:arms", gun("a", "normal")), Optional.empty()),
                new Loadout(Map.of("test:weapon_a", new Loadout.Gear("a", "test:rifle", Map.of())), Optional.empty()),
                new Loadout(Map.of("test:weapon_a", gun("a", "typo")), Optional.empty()),
                new Loadout(Map.of("test:arms", new Loadout.Gear("arms", "test:arms", Map.of())), Optional.of("test:arms")),
                new Loadout(Map.of("test:arms", new Loadout.Gear("arms", "test:arms", Map.of()), "test:class_item", new Loadout.Gear("class", "test:class_item", Map.of())), Optional.empty()))) {
            assertThrows(IllegalArgumentException.class, () -> schema.validate(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new Loadout(Map.of("test:weapon_a", gun("a", "normal"), "test:weapon_b", gun("a", "enhanced")), Optional.empty()));
        schema.validate(new Loadout(Map.of("test:arms", new Loadout.Gear("arms", "test:arms", Map.of())), Optional.empty()));
    }
    @Test void staleMetadataAndForeignSourceChangesCannotOverwriteCurrentEquipment() throws Exception {
        var h = new Harness(); h.equip(0, pair("weapon_a"));
        assertThrows(IllegalStateException.class, () -> new EquipmentChange("player", Loadout.EMPTY, pair("weapon_b")).validateCurrent(h.state(), h.program.equipment()));
        var source = h.state().sources().values().iterator().next();
        assertThrows(IllegalArgumentException.class, () -> h.program.validateSources(SourceBatch.between(Map.of(source.instance(), source), Map.of())));
        var broken = h.state().withoutSource(source.instance());
        assertThrows(IllegalStateException.class, () -> new EquipmentChange("player", pair("weapon_a"), pair("weapon_b")).validateCurrent(broken, h.program.equipment()));
    }
    @Test void catalogueAndLoadoutCodecsRoundTripAndLinkingValidatesCrossFragmentBundles() throws Exception {
        var p = load("equipment"); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow(); assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var loadout = pair("weapon_b"); assertEquals(loadout, EquipmentCodecs.LOADOUT.parse(JsonOps.INSTANCE, EquipmentCodecs.LOADOUT.encodeStart(JsonOps.INSTANCE, loadout).getOrThrow()).getOrThrow());
        var data = json("equipment"); var bundles = data.remove("bundles"); var separate = new com.google.gson.JsonObject(); separate.addProperty("version", "test-1"); separate.add("bundles", bundles);
        assertThrows(RuntimeException.class, () -> compile(data));
        assertEquals(p.program().equipment(), CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, separate).getOrThrow())).program().equipment());
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(p.program(), p.program())));
    }
    @Test void linkedPresentationMayBeSharedButConflictingThemesAreRejected() throws Exception {
        var first = load("equipment").program(); assertEquals(Optional.of("chorus_d2:equipment"), first.equipment().presentation());
        var same = new com.google.gson.JsonObject(); same.addProperty("version", "test-1");
        var schema = new com.google.gson.JsonObject(); schema.addProperty("presentation", "chorus_d2:equipment"); same.add("equipment", schema);
        var compatible = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, same).getOrThrow();
        assertEquals(first.equipment(), CompiledEffects.link(List.of(first, compatible)).program().equipment());
        schema.addProperty("presentation", "other:equipment");
        var conflicting = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, same).getOrThrow();
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(first, conflicting)));
        schema.addProperty("presentation", "invalid theme");
        assertTrue(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, same).error().isPresent());
    }
    @Test void attackSnapshotKeepsDrawnConditionAndSourcesAfterSwitchAndUnequip() throws Exception {
        var h = new Harness(); h.equip(0, pair("weapon_a")); var snapshot = h.program.captureDamage(h.state(), attack("a"));
        h.equip(50_000, pair("weapon_b")); assertEquals(15.125, h.program.outgoing(h.state(), snapshot.command("enemy"), 10).orElseThrow().output().value(), 1e-12);
        h.equip(75_000, Loadout.EMPTY); assertEquals(15.125, h.program.outgoing(h.state(), snapshot.command("enemy"), 10).orElseThrow().output().value(), 1e-12); assertEquals(10, h.damage("a"));
    }
}
