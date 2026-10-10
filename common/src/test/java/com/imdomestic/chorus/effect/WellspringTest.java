package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class WellspringTest {
    static final String[] SLOTS = {"grenade", "melee", "class"};
    static final double[] SCALARS = {.75, .8, .5}; // synthetic receiver profiles, not a class-ability calibration
    static EffectSource weapon(String id, boolean enhanced) {
        return new EffectSource(id, "chorus_d2:wellspring", "player", new BuffInstance.Origin("player", id, id, ""),
                enhanced ? Set.of("chorus:enhanced", "chorus_d2:shotgun") : Set.of());
    }
    static CompiledEffects program(boolean failWorld) throws Exception {
        var content = json("wellspring");
        if (failWorld) content.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do")
                .add(JsonParser.parseString("{\"type\":\"chorus:heal\",\"amount\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"damage\"}}"));
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, content).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("wellspring_targets")).getOrThrow()));
    }
    static class Harness {
        final CompiledEffects program; final EffectSession session; final EffectSource a, b; int worldCalls;
        Harness(boolean enhanced, boolean multi, double... energy) throws Exception { this(enhanced, multi, false, energy); }
        Harness(boolean enhanced, boolean multi, boolean failWorld, double... energy) throws Exception {
            program = program(failWorld); a = weapon("a", enhanced); b = weapon("b", !enhanced);
            var initial = EffectState.empty().withSource(a).withSource(b);
            for (int i = 0; i < SLOTS.length; i++) {
                String id = "test:" + SLOTS[i] + (i == 1 && multi ? "_double" : "") + "_energy";
                initial = initial.withResource(new ResourceState(new ResourceState.Key("player", id), energy[i], i == 1 && multi ? 2 : 1, 0));
            }
            session = new EffectSession(engine(program), initial, _ -> { worldCalls++; throw new IllegalStateException("Unknown post-gain world outcome"); });
            select(Map.of("chorus_d2:grenade", "test:grenade", "chorus_d2:melee", multi ? "test:melee_double" : "test:melee", "chorus_d2:class", "test:class", "chorus_d2:super", "test:super"));
        }
        EffectState state() { return session.state().engine().domain(); }
        void select(Map<String, String> selections) { session.start(0, new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), new AbilityLoadout(selections)).signal()); }
        double energy(String name) { return state().resources().get(new ResourceState.Key("player", "test:" + name + "_energy")).value(); }
        void kill() { kill(a.origin(), Set.of("chorus:weapon_kill")); }
        void kill(BuffInstance.Origin origin, Set<String> tags) { session.start(0, new RuleEngine.Signal("chorus:kill", new EffectEvent(origin.owner(), "target", origin, tags, Map.of()))); }
    }
    @Test void allSingleChargeCombinationsSplitBeforeIndependentReceiverScaling() throws Exception {
        for (boolean enhanced : List.of(false, true)) for (int mask = 0; mask < 8; mask++) {
            var energies = new double[3]; for (int i = 0; i < 3; i++) energies[i] = (mask & 1 << i) == 0 ? 0 : 1;
            var h = new Harness(enhanced, false, energies); h.kill(); int missing = 3 - Integer.bitCount(mask); double base = enhanced ? .036 : .032;
            for (int i = 0; i < 3; i++) assertEquals(energies[i] == 1 ? 1 : base / missing * SCALARS[i], h.energy(SLOTS[i]), 1e-12, enhanced + "/" + mask + "/" + SLOTS[i]);
            assertEquals(0, h.energy("super"), "Wellspring excludes super");
        }
    }
    @Test void fillingFirstRecipientDoesNotChangeLaterSharesOrRedistributeOverflow() throws Exception {
        var h = new Harness(false, false, .999, 0, 0); h.kill();
        assertEquals(1, h.energy("grenade")); assertEquals(.032 / 3 * .8, h.energy("melee"), 1e-12); assertEquals(.032 / 3 * .5, h.energy("class"), 1e-12);
        h.kill(); assertEquals(.032 / 3 * .8 + .032 / 2 * .8, h.energy("melee"), 1e-12, "the next kill observes the new balance");
    }
    @Test void multipleChargesUseExplicitOneChargeBoundaryAndFixedThirdPolicy() throws Exception {
        for (double melee : new double[]{.999, 1, 1.5, 2}) for (int mask = 0; mask < 4; mask++) {
            double grenade = (mask & 1) == 0 ? 0 : 1, clazz = (mask & 2) == 0 ? 0 : 1;
            var h = new Harness(false, true, grenade, melee, clazz); h.kill();
            int n = (grenade < 1 ? 1 : 0) + (melee < 1 ? 1 : 0) + (clazz < 1 ? 1 : 0);
            double meleeShare = melee < 1 ? .032 / n : .032 / 3;
            assertEquals(Math.min(2, melee + meleeShare * .8), h.energy("melee_double"), 1e-12);
            assertEquals(grenade == 1 ? 1 : .032 / n * .75, h.energy("grenade"), 1e-12);
            assertEquals(clazz == 1 ? 1 : .032 / n * .5, h.energy("class"), 1e-12);
        }
        var clipped = new Harness(false, true, 1, 1.999, 1); clipped.kill(); assertEquals(2, clipped.energy("melee_double"));
    }
    @Test void missingSlotsAndNoCostAreExcludedWithoutReadingAbsentNumbers() throws Exception {
        for (boolean noCost : List.of(false, true)) {
            var h = new Harness(false, false, 0, 0, 0);
            h.select(noCost ? Map.of("chorus_d2:melee", "test:melee", "chorus_d2:class", "test:no_energy") : Map.of("chorus_d2:melee", "test:melee"));
            h.kill(); assertEquals(.032 * .8, h.energy("melee"), 1e-12); assertEquals(0, h.energy("grenade")); assertEquals(0, h.energy("class"));
            h.select(Map.of()); h.kill(); assertTrue(h.session.state().engine().failure().isEmpty());
        }
    }
    @Test void weaponCreditAndTwoEquippedCopiesCannotDuplicateEnergy() throws Exception {
        var h = new Harness(false, false, 0, 1, 1);
        h.kill(h.a.origin(), Set.of()); h.kill(new BuffInstance.Origin("other", "a", "a", ""), Set.of("chorus:weapon_kill"));
        h.kill(new BuffInstance.Origin("player", "unknown", "unknown", ""), Set.of("chorus:weapon_kill")); assertEquals(0, h.energy("grenade"));
        h.kill(); assertEquals(.032 * .75, h.energy("grenade"), 1e-12);
        h.kill(h.b.origin(), Set.of("chorus:weapon_kill")); assertEquals((.032 + .036) * .75, h.energy("grenade"), 1e-12);
        h.session.start(0, SourceChange.remove("a")); h.kill(); assertEquals((.032 + .036) * .75, h.energy("grenade"), 1e-12);
    }
    @Test void selectionChangesUseTheNewPoolWithoutMovingOldEnergy() throws Exception {
        var h = new Harness(false, false, 1, .5, 1); h.kill();
        h.select(Map.of("chorus_d2:grenade", "test:grenade", "chorus_d2:melee", "test:melee_double", "chorus_d2:class", "test:class")); h.kill();
        assertEquals(.5 + .032 * .8, h.energy("melee"), 1e-12); assertEquals(.032 * .8, h.energy("melee_double"), 1e-12);
    }
    @Test void laterUnknownWorldOutcomeKeepsAllThreeConfirmedGrants() throws Exception {
        var h = new Harness(false, false, true, 0, 0, 0); assertThrows(IllegalStateException.class, h::kill);
        for (int i = 0; i < 3; i++) assertEquals(.032 / 3 * SCALARS[i], h.energy(SLOTS[i]), 1e-12);
        assertEquals(1, h.worldCalls); assertFalse(h.session.state().engine().pending().isEmpty());
    }
    @Test void contentRoundTripsAndObservationFieldUnitsAreChecked() throws Exception {
        var p = program(false); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var wrong = JsonParser.parseString(json("wellspring").toString().replace("\"count\"", "\"second\""));
        // A numeric observation remains charge_fraction, so comparisons against another unit are rejected.
        wrong = JsonParser.parseString(wrong.toString().replace("\"charge_fraction\"", "\"damage\""));
        var invalid = wrong; assertThrows(RuntimeException.class, () -> EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, invalid).getOrThrow());
    }
}
