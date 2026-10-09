package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class EffectTestSupport {
    private EffectTestSupport() {}
    static JsonObject json(String fixture) throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(EffectTestSupport.class.getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    static CompiledEffects load(String fixture) throws Exception { return compile(json(fixture)); }
    static CompiledEffects compile(JsonObject json) { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, json).getOrThrow(); }
    static EffectSource source(String bundle) {
        return new EffectSource("perk", bundle, "player", new BuffInstance.Origin("player", "perk", "weapon", ""), Set.of());
    }
    static EffectEvent event(EffectSource source) { return new EffectEvent("player", "target", source.origin(), Set.of(), Map.of()); }
    static TimelineEngine<EffectState> engine(CompiledEffects compiled) {
        return compiled.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1);
    }
    static TimelineEngine.Transition<EffectState> pump(TimelineEngine<EffectState> engine, TimelineEngine.Transition<EffectState> result) {
        for (int i = 0; i < 3000 && result.needsPump(); i++) result = engine.transition(result.state(), Pump.INSTANCE);
        assertFalse(result.needsPump()); assertTrue(result.state().engine().failure().isEmpty(), result.state().engine().failure().toString()); return result;
    }
    static TimelineEngine.Transition<EffectState> send(TimelineEngine<EffectState> engine, TimelineEngine.State<EffectState> state, long time, String type, EffectEvent event) {
        return pump(engine, engine.transition(state, new Start(time, new Signal(type, event))));
    }
    static TimelineEngine.Transition<EffectState> complete(TimelineEngine<EffectState> engine, TimelineEngine.Transition<EffectState> waiting, ActionResult result) {
        assertEquals(1, waiting.actions().size());
        return pump(engine, engine.transition(waiting.state(), new Completed(waiting.actions().getFirst().id(), result)));
    }
    static BuffInstance buff(TimelineEngine.State<EffectState> state, String id, String holder) {
        return state.engine().domain().buffs().instances().values().stream()
                .filter(value -> value.definition().id().equals(id) && value.key().holder().equals(holder)).findFirst().orElseThrow();
    }
}
