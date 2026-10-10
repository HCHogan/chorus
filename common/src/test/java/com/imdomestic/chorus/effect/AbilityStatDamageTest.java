package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityStatDamageTest {
    static EffectSource scaling(String instance, String owner) {
        return SolarFragmentStatsTest.source(instance, "chorus_d2:ability_stat_damage", owner);
    }
    static DamageCommand attack(String owner, Set<String> tags) {
        // Identities are deliberately populated even for attacks without ability credit.
        return new DamageCommand("recipient", new BuffInstance.Origin(owner, "cast", "glaive", "grenade"), 100,
                "minecraft:generic", tags, Set.of(), false, Optional.of("chorus_d2:outgoing"));
    }
    static final class Harness {
        final CompiledEffects p; final EffectSession session;
        Harness(EffectState.Mode mode) throws Exception {
            var perk = JsonParser.parseString("""
                {"version":"compendium-2026-10-05","bundles":[{"id":"test:damage_perk","modifiers":[{
                  "id":"perk","profile":"chorus_d2:outgoing","stage":"perk","group":"perk","op":"multiply",
                  "stacking_key":"test:perk","value":{"type":"chorus:constant","value":0.5,"unit":"delta"},
                  "reference":"Synthetic independent damage factor","confidence":"assumed"}]}]}
                """);
            p = CompiledEffects.link(List.of(ArmorStatInputsTest.program().program(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("ability_stat_damage")).getOrThrow(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, perk).getOrThrow()));
            session = new EffectSession(engine(p), EffectState.empty().withMode(mode)
                    .withSource(scaling("owner-scaling", "owner")).withSource(scaling("recipient-scaling", "recipient")),
                    _ -> { throw new AssertionError("Damage stat queries must be pure"); });
        }
        EffectState state() { return session.state().engine().domain(); }
        void equip(String owner, Loadout gear) { session.start(0, new EquipmentChange(owner, state().equipment().getOrDefault(owner, Loadout.EMPTY), gear).signal()); }
        void bind(EffectSource source) { session.start(0, SourceChange.bind(source)); }
        double damage(DamageCommand d) { return p.outgoing(state(), d, d.amount()).orElseThrow().output().value(); }
    }
    @Test void enhancedMeleeAndGrenadeUseTheirOwnModeSlopeOnlyAboveOneHundred() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode); h.equip("recipient", ArmorStatInputsTest.one("other-arms", 200));
            for (double points : List.of(0., 99., 100., 101., 150., 200.)) {
                h.equip("owner", ArmorStatInputsTest.one("arms", points));
                for (String stat : List.of("melee", "grenade")) {
                    double rate = mode == EffectState.Mode.PVP ? .002 : stat.equals("melee") ? .003 : .0065;
                    var d = attack("owner", Set.of("chorus:" + stat + "_damage"));
                    assertEquals(100 * (1 + rate * Math.max(0, points - 100)), h.damage(d), 1e-10);
                    assertEquals(100 * (1 + rate * 100), h.damage(attack("recipient", d.tags())), 1e-10);
                }
            }
        }
    }
    @Test void eachCreditUsesItsOwnStatRatherThanTheLargestEquippedNumber() throws Exception {
        var h = new Harness(EffectState.Mode.PVE);
        var values = new TreeMap<>(ArmorStatInputsTest.gear("arms", "arms", 200).parameters());
        values.put("grenade", new com.imdomestic.chorus.stat.Measure(100, com.imdomestic.chorus.stat.Unit.STAT_POINT));
        h.equip("owner", new Loadout(Map.of("chorus_d2:arms", new Loadout.Gear("arms", "test:armor_arms", Map.of(), values)), Optional.empty()));
        assertEquals(130, h.damage(attack("owner", Set.of("chorus:melee_damage"))), 1e-10);
        assertEquals(100, h.damage(attack("owner", Set.of("chorus:grenade_damage"))), 1e-10);
    }
    @Test void explicitCreditCoversUnpoweredPoweredAndGlaiveMeleeWithoutInferringFromOrigin() throws Exception {
        var h = new Harness(EffectState.Mode.PVE); h.equip("owner", ArmorStatInputsTest.one("arms", 200));
        for (var tags : List.of(Set.of("chorus:melee_damage"), Set.of("chorus:melee_damage", "chorus:ability_damage"),
                Set.of("chorus:melee_damage", "chorus:glaive_melee", "chorus:weapon_damage")))
            assertEquals(130, h.damage(attack("owner", tags)), 1e-10);
        for (var tags : List.of(Set.<String>of(), Set.of("chorus:weapon_damage"), Set.of("chorus:ability_damage"),
                Set.of("chorus:super_damage"), Set.of("chorus:class_damage"), Set.of("chorus:glaive_melee"),
                Set.of("chorus:melee_damage", "chorus:grenade_damage")))
            assertEquals(100, h.damage(attack("owner", tags)), 1e-10);
        h.bind(scaling("duplicate-owner-scaling", "owner"));
        assertEquals(130, h.damage(attack("owner", Set.of("chorus:melee_damage"))), 1e-10);
    }
    @Test void armorAndFragmentSumBeforeTheCapWithoutWritingBackOrRequiringSelectedAbilities() throws Exception {
        var h = new Harness(EffectState.Mode.PVE); var armor = ArmorStatInputsTest.one("arms", 195); h.equip("owner", armor);
        h.bind(SolarFragmentStatsTest.source("char", "chorus_d2:ember_of_char", "owner"));
        h.bind(SolarFragmentStatsTest.source("eruption", "chorus_d2:ember_of_eruption", "owner"));
        var before = h.state();
        assertEquals(165, h.damage(attack("owner", Set.of("chorus:grenade_damage"))), 1e-10);
        assertEquals(130, h.damage(attack("owner", Set.of("chorus:melee_damage"))), 1e-10);
        assertSame(before, h.state()); assertEquals(armor, h.state().equipment().get("owner"));
        assertTrue(h.state().abilities().isEmpty() && h.state().buffs().instances().isEmpty());
        h.equip("owner", Loadout.EMPTY); assertEquals(100, h.damage(attack("owner", Set.of("chorus:grenade_damage"))), 1e-10);
    }
    @Test void statFactorMultipliesIndependentPerksAndSeverInsteadOfJoiningTheirMaximum() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode); h.equip("owner", ArmorStatInputsTest.one("arms", 200));
            h.bind(SolarFragmentStatsTest.source("perk", "test:damage_perk", "owner"));
            var s = StrandDefenseTest.grant(h.p, h.state(), StrandDefenseTest.SEVER, "owner", 10_000_000);
            var d = attack("owner", Set.of("chorus:melee_damage"));
            assertEquals(100 * (mode == EffectState.Mode.PVE ? 1.3 * .6 : 1.2 * .85) * 1.5,
                    h.p.outgoing(s, d, 100).orElseThrow().output().value(), 1e-10);
        }
    }
    @Test void capturePinsSourceStatWhileDirectDamageAndSeverReadCurrentState() throws Exception {
        var h = new Harness(EffectState.Mode.PVE); h.equip("owner", ArmorStatInputsTest.one("arms", 200));
        var d = attack("owner", Set.of("chorus:grenade_damage")); var captured = h.p.captureDamage(h.state(), d);
        h.equip("owner", ArmorStatInputsTest.one("arms", 100));
        assertEquals(100, h.damage(d), 1e-10); assertEquals(165, h.damage(captured.command("recipient")), 1e-10);
        var now = StrandDefenseTest.grant(h.p, h.state(), StrandDefenseTest.SEVER, "owner", 10_000_000);
        assertEquals(99, h.p.outgoing(now, captured.command("recipient"), 100).orElseThrow().output().value(), 1e-10);
        h.session.start(0, SourceChange.remove("owner-scaling"));
        assertEquals(165, h.damage(captured.command("recipient")), 1e-10);
    }
}
