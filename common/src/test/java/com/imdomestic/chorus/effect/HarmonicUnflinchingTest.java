package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class HarmonicUnflinchingTest {
    static final List<String> ELEMENTS = List.of("arc", "solar", "void", "stasis", "strand");
    static JsonObject data(String element) throws Exception {
        var data = UnflinchingTest.data(element); data.add("abilities", json("harmonic_selection").get("abilities")); return data;
    }
    static class Harness extends UnflinchingTest.Harness {
        Harness() throws Exception { this("solar", EffectState.Mode.PVE); }
        Harness(String element, EffectState.Mode mode) throws Exception { super(data(element), mode); }
        void choose(String holder, String slot, String ability) {
            var before = state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY); var selections = new HashMap<>(before.slots());
            if (ability.isEmpty()) selections.remove(slot); else selections.put(slot, ability);
            session.start(now(), new AbilityChange(holder, before, new AbilityLoadout(selections)).signal());
        }
        void superElement(String element) { choose("player", "chorus_d2:super", element.isEmpty() ? "" : "test:super_" + element); }
    }
    @Test void fiveSuperElementsAndBothModesUseTheEquippedSelectionAndNormalCopyTiers() throws Exception {
        for (String element : ELEMENTS) for (var mode : EffectState.Mode.values()) for (int copies = 0; copies <= 3; copies++) {
            var h = new Harness(element, mode); h.mods(Collections.nCopies(copies, "harmonic").toArray(String[]::new)); h.superElement(element);
            assertEquals(-10 * .90625, h.hit(), 1e-9); h.aim(true); h.until(200_000);
            assertEquals(-10 * .90625 * .9 * UnflinchingTest.FACTORS[copies], h.hit(), 1e-9);
            h.aim(false); assertEquals(-10 * .90625, h.hit(), 1e-9);
        }
    }
    @Test void otherElementsAndKineticDoNotMatchEvenWhenOtherAbilitiesOrSourceTagsMatch() throws Exception {
        for (String weapon : UnflinchingTest.ELEMENTS) for (String selected : ELEMENTS) if (!weapon.equals(selected)) {
            var h = new Harness(weapon, EffectState.Mode.PVE); h.mods("harmonic", "harmonic", "harmonic"); h.superElement(selected); h.aim(true); h.until(200_000);
            assertEquals(-10 * .90625 * .9, h.hit(), 1e-9, weapon + " vs Super " + selected);
        }
        var h = new Harness(); h.mods("harmonic", "harmonic", "harmonic");
        h.choose("other", "chorus_d2:super", "test:super_solar"); h.choose("player", "chorus_d2:grenade", "test:grenade_solar");
        h.session.start(0, SourceChange.bind(new EffectSource("subclass-tags", "test:empty", "player", new BuffInstance.Origin("player", "subclass", "", ""), Set.of("chorus_d2:solar", "chorus_d2:prismatic"))));
        h.aim(true); h.until(200_000); assertEquals(-10 * .90625 * .9, h.hit(), 1e-9);
        h.superElement("solar"); assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
    }
    @Test void mixedHarmonicAndElementalCopiesShareTheSelectedTierAndSuperChangesDoNotResetTheGun() throws Exception {
        var h = new Harness(); h.mods("harmonic", "harmonic", "solar"); h.superElement("solar"); h.aim(true); h.until(1_000_000);
        assertEquals(-10 * .90625 * .9 * .65 * .65, h.hit(), 1e-9);
        h.superElement("arc"); assertTrue(h.active("a")); assertEquals(-10 * .90625 * .9 * .75 * .65, h.hit(), 1e-9);
        h.draw("secondary"); h.aim(true); h.until(1_200_000); assertEquals(-10 * .90625 * .9 * .7, h.hit(), 1e-9);
        h.superElement(""); assertEquals(-10 * .90625 * .9, h.hit(), 1e-9); h.superElement("arc");
        h.until(1_900_000); assertEquals(-10 * .90625 * .9 * .7 * .65, h.hit(), 1e-9);
    }
    @Test void domainRestoreRetainsSelectionWithoutCreatingPassiveMarkersOrAllowingSyntheticSupersToCast() throws Exception {
        var h = new Harness(); h.mods("harmonic", "solar", "solar"); h.superElement("solar"); h.aim(true); h.until(1_000_000); h.restoreDomain();
        assertTrue(h.state().sources().keySet().stream().noneMatch(k -> k.startsWith("ability/")), "tag query should not require a dummy passive source");
        assertEquals(-10 * .90625 * .9 * .65 * .65, h.hit(), 1e-9); h.superElement(""); h.restoreDomain();
        assertEquals(-10 * .90625 * .9 * .7 * .65, h.hit(), 1e-9); h.superElement("solar");
        var request = new AbilityUse.Request("player", "chorus_d2:super", "cast", new EffectEvent("player", "player", NoDistractionsTest.ATTACKER, Set.of(), Map.of()));
        assertEquals(AbilityUse.Outcome.CONDITION, ((AbilityUse.Receipt) h.program.useAbility(h.state(), request).result()).outcome());
    }
    @Test void foreignAndFourthHarmonicSourcesCannotExceedTheCorrectThreeCopyTier() throws Exception {
        var h = new Harness(); h.mods("harmonic"); h.superElement("solar");
        h.choose("other", "chorus_d2:super", "test:super_solar"); h.source("foreign", "chorus_d2:unflinching_harmonic", "other", "", Map.of());
        h.aim(true); h.until(200_000); assertEquals(-10 * .90625 * .9 * .75, h.hit(), 1e-9);
        h.mods("harmonic", "harmonic", "solar"); h.source("fourth", "chorus_d2:unflinching_harmonic", "player", "", Map.of());
        assertEquals(-10 * .90625 * .9 * .65, h.hit(), 1e-9);
        h.superElement("arc"); assertEquals(-10 * .90625 * .9 * .75, h.hit(), 1e-9);
    }
}
