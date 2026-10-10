package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityInputTest {
    @Test void heldInputGatesResolveReplacementsWithoutEvaluatingCastParametersOrPaying() throws Exception {
        var p = load("ability_input"); var state = p.changeAbilities(EffectState.empty(), new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("chorus_d2:grenade", "test:tap")))).state();
        state = state.withSource(source("test:base_gate"));
        var request = new AbilityUse.Request("player", "chorus_d2:grenade", "input/1", event(source("test:base_gate")));
        assertFalse(p.abilityInputAllowed(state, request));
        var converted = state.withSource(new EffectSource("override", "test:unconditional_conversion", "player", request.input().source(), Set.of()));
        assertTrue(p.abilityInputAllowed(converted, request)); // Missing held duration in the action body is not evaluated until an actual use.
        assertEquals(10, converted.resources().values().iterator().next().value());
    }
    @Test void onlyReleaseWithObservedThresholdDurationSelectsConversionAndDirectInputStaysInstant() throws Exception {
        var p = load("ability_input");
        var state = p.changeAbilities(EffectState.empty(), new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("chorus_d2:grenade", "test:tap")))).state().withSource(source("test:conversion"));
        for (boolean released : List.of(false, true)) for (double seconds : List.of(0d, .299999, .3, 60d)) {
            var event = new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), released ? Set.of("chorus:ability_input_release") : Set.of(), Map.of("input_hold_time", new Measure(seconds, Unit.SECOND)));
            var result = p.useAbility(state, new AbilityUse.Request("player", "chorus_d2:grenade", "input", event));
            var receipt = (AbilityUse.Receipt) result.result(); assertEquals(released && seconds >= .3 ? "test:hold" : "test:tap", receipt.resolved());
            assertEquals(1, receipt.cost().orElseThrow().receipt().paid()); assertEquals(9, result.state().resources().values().iterator().next().value());
        }
        assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
    }
    @Test void bleakConversionRequiresAnEquippedSourceAndExplicitPositiveThreshold() throws Exception {
        var base = BleakWatcherTest.program(true);
        var p = CompiledEffects.link(List.of(base.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("bleak_watcher_conversion")).getOrThrow()));
        // A synthetic ordinary selection shares a declared account; native acceptance separately verifies Duskfield's actual pool.
        var data = json("bleak_watcher"); var ordinary = data.getAsJsonArray("abilities").get(0).getAsJsonObject().deepCopy(); ordinary.addProperty("id", "test:ordinary");
        ordinary.remove("parameters"); ordinary.remove("effects"); ordinary.add("on_use", new com.google.gson.JsonArray());
        var fragment = new com.google.gson.JsonObject(); fragment.addProperty("version", p.program().version()); var abilities = new com.google.gson.JsonArray(); abilities.add(ordinary); fragment.add("abilities", abilities);
        p = CompiledEffects.link(List.of(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, fragment).getOrThrow()));
        var state = p.changeAbilities(EffectState.empty(), new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("chorus_d2:grenade", "test:ordinary")))).state();
        var origin = new BuffInstance.Origin("player", "aspect", "", "");
        var event = new EffectEvent("player", "player", origin, Set.of("chorus:ability_input_release"), Map.of("input_hold_time", new Measure(.5, Unit.SECOND)));
        var request = new AbilityUse.Request("player", "chorus_d2:grenade", "held", event);
        assertEquals("test:ordinary", ((AbilityUse.Receipt) p.useAbility(state, request).result()).resolved());
        for (double threshold : List.of(-1d, 0d, .5, .500001)) {
            var aspect = new EffectSource("aspect", "chorus_d2:bleak_watcher_conversion", "player", origin, Set.of(), Map.of("hold_time", new Measure(threshold, Unit.SECOND)));
            var receipt = (AbilityUse.Receipt) p.useAbility(state.withSource(aspect), request).result();
            assertEquals(threshold == .5 ? "chorus_d2:bleak_watcher" : "test:ordinary", receipt.resolved());
        }
        var compiled = p; assertThrows(IllegalArgumentException.class, () -> compiled.validateSource(new EffectSource("aspect", "chorus_d2:bleak_watcher_conversion", "player", origin, Set.of())));
    }
}
