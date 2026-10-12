package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class UnflinchingTest {
    @Test void ordinaryModCostsUseOnePerItemBudgetAndHarmonicDiscountChangesLegalCombinations() throws Exception {
        var data = data("solar"); var items = data.getAsJsonObject("equipment").getAsJsonArray("items");
        var item = items.asList().stream().map(JsonElement::getAsJsonObject).filter(v -> v.get("id").getAsString().equals("test:unflinching_chest")).findFirst().orElseThrow();
        var budget = item.getAsJsonObject("budgets").getAsJsonObject("energy"); assertEquals(10, budget.get("capacity").getAsInt());
        for (var entry : budget.getAsJsonObject("costs").entrySet()) {
            var costs = entry.getValue().getAsJsonObject(); assertEquals(0, costs.get("none").getAsInt()); assertEquals(2, costs.get("harmonic").getAsInt());
            for (String element : ELEMENTS) assertEquals(3, costs.get(element).getAsInt());
        }
        // Eight is a synthetic capacity boundary, not an assertion about D2 armor tiers.
        budget.addProperty("capacity", 8); var p = NoDistractionsTest.program(data).equipment();
        p.validate(new Loadout(Map.of("test:chest", chest("solar", "solar", "harmonic")), Optional.empty()));
        p.validate(new Loadout(Map.of("test:chest", chest("harmonic", "harmonic", "harmonic")), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> p.validate(new Loadout(Map.of("test:chest", chest("solar", "solar", "solar")), Optional.empty())));
    }
    static final String D = "chorus_d2:";
    static final List<String> ELEMENTS = List.of("kinetic", "arc", "solar", "void", "stasis", "strand");
    static final double[] FACTORS = {1, .75, .70, .65};
    static JsonObject data(String element) throws Exception {
        var data = json("no_distractions_weapon");
        var mods = json("unflinching");
        for (String field : List.of("profiles", "bundles")) {
            if (!data.has(field)) data.add(field, new JsonArray());
            mods.getAsJsonArray(field).forEach(value -> data.getAsJsonArray(field).add(value));
        }
        var armor = json("unflinching_equipment").getAsJsonObject("equipment");
        for (String field : List.of("slots", "items")) armor.getAsJsonArray(field).forEach(value -> data.getAsJsonObject("equipment").getAsJsonArray(field).add(value));
        var items = data.getAsJsonObject("equipment").getAsJsonArray("items");
        var rifle = items.get(0).getAsJsonObject(); var other = rifle.deepCopy();
        rifle.getAsJsonArray("tags").add(D + element);
        other.addProperty("id", "test:arc_rifle"); other.getAsJsonArray("tags").add(D + "arc"); items.add(other);
        var weapon = data.getAsJsonArray("weapons").get(0).getAsJsonObject().deepCopy();
        weapon.addProperty("item", "test:arc_rifle"); data.getAsJsonArray("weapons").add(weapon);
        return data;
    }
    static Loadout.Gear chest(String... elements) {
        var choices = new HashMap<String, String>();
        for (int i = 0; i < 3; i++) choices.put("mod_" + i, i < elements.length ? elements[i] : "none");
        return new Loadout.Gear("chest", "test:unflinching_chest", choices);
    }
    static class Harness extends NoDistractionsTest.Harness {
        Harness() throws Exception { this("solar", EffectState.Mode.PVE); }
        Harness(String element, EffectState.Mode mode) throws Exception {
            this(data(element), mode);
        }
        Harness(JsonObject data, EffectState.Mode mode) throws Exception {
            super(data, mode, 100, 50);
            var slots = new HashMap<>(physical.slots()); var previous = slots.get("test:secondary");
            slots.put("test:secondary", new Loadout.Gear(previous.instance(), "test:arc_rifle", previous.choices(), previous.parameters()));
            equip(new Loadout(slots, physical.drawn()));
        }
        void mods(String... elements) {
            var slots = new HashMap<>(physical.slots()); slots.put("test:chest", chest(elements)); equip(new Loadout(slots, physical.drawn()));
        }
        void removeChest() { var slots = new HashMap<>(physical.slots()); slots.remove("test:chest"); equip(new Loadout(slots, physical.drawn())); }
        void draw(String slot) { equip(new Loadout(physical.slots(), Optional.of("test:" + slot))); }
        void restoreDomain() { session = EffectSession.restore(program, EffectCheckpointTest.clock(), 256, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    @Test void allSixElementsAndBothModesUseOneTwoThreeCopyTiersOnlyAfterAdsCompletes() throws Exception {
        for (var mode : EffectState.Mode.values()) for (String element : ELEMENTS) for (int copies = 0; copies <= 3; copies++) {
            var h = new Harness(element, mode); h.mods(Collections.nCopies(copies, element).toArray(String[]::new));
            assertEquals(-10 * .90625, h.hit(), 1e-9); h.aim(true); h.until(199_999);
            assertEquals(-10 * .90625, h.hit(), 1e-9, "entering ADS"); h.until(200_000);
            assertEquals(-10 * .90625 * .9 * FACTORS[copies], h.hit(), 1e-9, element + " copies=" + copies);
            assertEquals(NoDistractionsTest.ATTACKER, h.kicks.getLast().command().origin());
            h.aim(false); assertEquals(-10 * .90625, h.hit(), 1e-9, "ADS release");
        }
    }
    @Test void everyMismatchedElementIsNeutralAndAttackElementDoesNotSelectTheArmor() throws Exception {
        for (String weapon : ELEMENTS) for (String mod : ELEMENTS) if (!weapon.equals(mod)) {
            var h = new Harness(weapon, EffectState.Mode.PVE); h.mods(mod, mod, mod); h.aim(true); h.until(200_000);
            assertEquals(-10 * .90625 * .9, h.hit(), 1e-9, weapon + " vs " + mod);
        }
        var h = new Harness(); h.mods("solar", "solar", "arc"); h.aim(true); h.until(200_000);
        var origin = new BuffInstance.Origin("enemy", "attack", "enemy_gun", "", Set.of(D + "arc"));
        var command = new DamageCommand("player", origin, 1, "minecraft:generic", Set.of(D + "flinch_input", "chorus:arc_damage"), Set.of(), false,
                Optional.empty(), Optional.empty(), new ImpactData(Map.of("flinch_yaw", new com.imdomestic.chorus.stat.Measure(2, com.imdomestic.chorus.stat.Unit.DEGREE), "flinch_pitch", new com.imdomestic.chorus.stat.Measure(-10, com.imdomestic.chorus.stat.Unit.DEGREE))), Optional.empty());
        h.session.observe(h.now(), DamageFacts.from(command, new DamageReceipt("arc-hit", DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false)));
        assertEquals(-10 * .90625 * .9 * .7, h.kicks.getLast().command().pitch(), 1e-9); assertEquals(origin, h.kicks.getLast().command().origin());
    }
    @Test void mixedCopiesFollowTheCurrentHeldWeaponAndArmorChangesDoNotRestartNoDistractions() throws Exception {
        var h = new Harness(); h.mods("solar", "solar", "arc"); h.aim(true); h.until(1_000_000);
        assertEquals(-10 * .90625 * .9 * .7 * .65, h.hit(), 1e-9);
        h.mods("solar"); assertTrue(h.active("a")); assertEquals(-10 * .90625 * .9 * .75 * .65, h.hit(), 1e-9);
        h.removeChest(); assertTrue(h.active("a")); assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
        h.mods("solar", "solar", "arc"); h.draw("secondary"); assertFalse(h.active("a")); h.aim(true); h.until(1_200_000);
        assertEquals(-10 * .90625 * .9 * .75, h.hit(), 1e-9); h.until(1_900_000);
        assertEquals(-10 * .90625 * .9 * .75 * .65, h.hit(), 1e-9);
    }
    @Test void matchingCopiesAreCappedAtThreeAndForeignArmorCannotIncreaseTheTier() throws Exception {
        var h = new Harness(); h.mods("solar"); h.source("foreign", D + "unflinching_solar", "enemy", "", Map.of());
        h.aim(true); h.until(200_000); assertEquals(-10 * .90625 * .9 * .75, h.hit(), 1e-9);
        h.mods("solar", "solar", "solar"); h.source("fourth", D + "unflinching_solar", "player", "", Map.of());
        assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9, "no synthetic fourth tier or per-copy multiplication");
        h.session.start(h.now(), SourceChange.remove("fourth")); h.mods("solar", "solar");
        assertEquals(-10 * .90625 * .9 * .7, h.hit(), 1e-9);
    }
    @Test void passiveArmorNeedsNoChargeOrQuietTimerAndActivePerkSurvivesAcceptedFire() throws Exception {
        var h = new Harness(); h.mods("solar", "solar", "solar"); h.aim(true); h.until(200_000);
        assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9); assertFalse(h.active("a"));
        assertEquals(WeaponFire.Outcome.ACCEPTED, h.fire().outcome()); assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
        h.until(1_200_000); assertTrue(h.active("a")); h.fire();
        assertEquals(-10 * .90625 * .9 * .65 * .65, h.hit(), 1e-9);
    }
    @Test void domainRestoreKeepsPhysicalSocketCopiesAndResamplesThemAfterRemoval() throws Exception {
        var h = new Harness(); h.mods("solar", "solar", "solar"); h.aim(true); h.until(1_000_000); h.restoreDomain();
        assertEquals(-10 * .90625 * .9 * .65 * .65, h.hit(), 1e-9); h.removeChest();
        assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
        assertEquals(h.program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.program.program()).getOrThrow()).getOrThrow());
    }
    @Test void threeRequiredChestSocketsRejectMissingUnknownOrFourthChoicesAndWrongSlots() throws Exception {
        var h = new Harness();
        for (String fault : List.of("missing", "unknown", "fourth", "slot")) {
            var choices = new HashMap<>(chest("solar").choices());
            if (fault.equals("missing")) choices.remove("mod_2");
            if (fault.equals("unknown")) choices.put("mod_1", "made_up");
            if (fault.equals("fourth")) choices.put("mod_3", "solar");
            var slots = new HashMap<>(h.physical.slots()); slots.put(fault.equals("slot") ? "test:primary" : "test:chest", new Loadout.Gear("bad-chest", "test:unflinching_chest", choices));
            assertThrows(IllegalArgumentException.class, () -> h.program.equipment().validate(new Loadout(slots, h.physical.drawn())));
        }
    }
    @Test void threeCopiesStillCauseOnlyOneWorldImpulseAndUnknownResultCannotReplay() throws Exception {
        var h = new Harness(); h.mods("solar", "solar", "solar"); h.aim(true); h.until(1_000_000); h.unknown = true;
        assertThrows(IllegalStateException.class, h::hit); assertEquals(1, h.kicks.size());
        assertEquals(-10 * .90625 * .9 * .65 * .65, h.kicks.getFirst().command().pitch(), 1e-9);
        assertFalse(h.cues.contains("test:flinch_applied")); assertThrows(IllegalStateException.class, h::hit); assertEquals(1, h.kicks.size());
    }
}
