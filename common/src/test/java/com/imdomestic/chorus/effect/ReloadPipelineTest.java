package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReloadPipelineTest {
    @Test void samePerkProfileFeedsTwoArchetypeCurvesWithoutDuplicatingModifiers() throws Exception {
        var h = new SurplusTest.Harness(false, 1, 1, 1); h.spend("a"); var rifle = h.reload();
        h.equip(SurplusTest.equipment("normal", "normal", "secondary")); h.spend("b"); var shotgun = h.reload();
        assertEquals(1.3, rifle.duration().value(), 1e-12); assertEquals(1.6, shotgun.duration().value(), 1e-12);
        for (var plan : List.of(rifle, shotgun)) {
            var stages = plan.calculation().orElseThrow().steps(); assertEquals(3, stages.size());
            assertEquals("chorus_d2:weapon_reload", stages.getFirst().trace().profile()); assertEquals(70, stages.getFirst().output().value());
            assertEquals(1, stages.getFirst().trace().contributions().stream().filter(c -> c.selected()).count());
            assertEquals("chorus_d2:reload_animation", stages.getLast().trace().profile());
        }
        assertNotEquals(rifle.calculation().orElseThrow().steps().get(1).trace().profile(), shotgun.calculation().orElseThrow().steps().get(1).trace().profile());
    }
    @Test void animationIsAfterCurveAndAcceptedTraceReplaysWithoutReadingNewState() throws Exception {
        var h = new SurplusTest.Harness(false, 1, 1, 1);
        h.session.start(0, SourceChange.bind(new EffectSource("fast", "test:fast_animation", "player", new BuffInstance.Origin("player", "fast", "a", ""), Set.of())));
        h.spend("a"); var plan = h.reload(); var calculation = plan.calculation().orElseThrow();
        assertEquals(975_000, plan.dueAt()); assertEquals(1.3, calculation.steps().get(1).output().value(), 1e-12); assertEquals(.975, calculation.output().value(), 1e-12);
        h.session.start(0, SourceChange.remove("fast")); h.use("grenade");
        assertEquals(1.3, calculation.withoutFactors(Set.of("chorus_d2:reload_animation")).output().value(), 1e-12);
        assertEquals(.75, calculation.withBase(new Measure(80, Unit.STAT_POINT)).output().value(), 1e-12, "saved +60 clamps to 100 before old .75 animation factor");
        assertEquals(plan, h.state().reloads().get("player")); h.session.observe(975_000, List.of()); assertEquals(5, h.state().ammunition().get("a").magazine());
    }
    @Test void legacyProfileAndExplicitPipelineAreEquivalentAndAmbiguousOrBrokenListsFail() throws Exception {
        var legacy = WeaponReloadTest.program(); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, legacy).getOrThrow().getAsJsonObject();
        var reload = encoded.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload");
        assertTrue(reload.has("profiles") && !reload.has("profile")); assertEquals(legacy.program(), compile(encoded).program());
        for (String variant : List.of("both", "empty", "unknown", "wrong_order", "not_seconds")) {
            var data = json("surplus_weapon"); var r = data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload");
            switch (variant) {
                case "both" -> r.addProperty("profile", "chorus_d2:weapon_reload");
                case "empty" -> r.add("profiles", new JsonArray());
                case "unknown" -> r.add("profiles", JsonParser.parseString("[\"test:missing\"]"));
                case "wrong_order" -> r.add("profiles", JsonParser.parseString("[\"test:rifle_reload_time\",\"chorus_d2:weapon_reload\"]"));
                case "not_seconds" -> r.add("profiles", JsonParser.parseString("[\"chorus_d2:weapon_reload\"]"));
            }
            // Decode/link the full content so failure is about the pipeline, not unresolved perk bundles.
            assertThrows(RuntimeException.class, () -> CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("weapon_stats")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("surplus")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("wellspring")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("wellspring_targets")).getOrThrow())), variant);
        }
    }
    @Test void invalidLastSegmentCannotCreateTimerOrTransferAmmo() throws Exception {
        var data = json("surplus_weapon");
        var fast = data.getAsJsonArray("bundles").asList().stream().map(JsonElement::getAsJsonObject).filter(b -> b.get("id").getAsString().equals("test:fast_animation")).findFirst().orElseThrow();
        fast.getAsJsonArray("modifiers").get(0).getAsJsonObject().getAsJsonObject("value").addProperty("value", -1);
        var p = CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("weapon_stats")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("surplus")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("wellspring")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("wellspring_targets")).getOrThrow()));
        var setup = new SurplusTest.Harness(false, 1, 1, 1); setup.spend("a");
        var before = setup.state().withSource(new EffectSource("zero", "test:fast_animation", "player", new BuffInstance.Origin("player", "zero", "a", ""), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> p.reload(before, new WeaponReload.Request("player", "zero-duration")));
        assertTrue(before.reloads().isEmpty()); assertTrue(before.timers().isEmpty()); assertEquals(4, before.ammunition().get("a").magazine());
    }
}
