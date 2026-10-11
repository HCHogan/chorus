package com.imdomestic.chorus.effect;

import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WeaponHandlingStorageTest {
    static JsonObject oldCheckpoint() throws Exception {
        try (var reader = new java.io.InputStreamReader(Objects.requireNonNull(WeaponHandlingStorageTest.class.getResourceAsStream(
                "/persistence/pre-handling-reload-v1.json")), java.nio.charset.StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    @Test void actualPreHandlingCheckpointRestoresReloadAndBuffsWithoutReplayOrCounterReset() throws Exception {
        var input = oldCheckpoint(); var saved = EffectCheckpointCodec.decode(input);
        assertEquals(50_000, saved.timeline().timeMicros());
        assertEquals(WeaponReloadTest.program().program(), saved.program());
        assertTrue(saved.program().weapons().stream().allMatch(w -> w.handling().isEmpty()));
        assertEquals(200_000, saved.timeline().domain().reloads().get("player").dueAt());
        assertTrue(saved.timeline().domain().handling().isEmpty(), "Old state must not invent a pending switch");
        var world = new ArrayList<RuleEngine.WorldRequest>();
        var program = new CompiledEffects(saved.program());
        var session = EffectSession.restore(program, EffectCheckpointTest.clock(), 256, saved, request -> {
            world.add(request);
            return switch (request.command()) {
                case WeaponReload.Verify q -> new WeaponReload.Verified(q, true);
                case HealingCommand heal -> new HealingReceipt("heal", heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                default -> throw new AssertionError(request.command());
            };
        });
        assertTrue(world.isEmpty()); assertEquals(saved, session.checkpoint());
        var reencoded = EffectCheckpointCodec.encode(saved);
        assertTrue(reencoded.toString().contains("chorus:effect/weapon/weapon_definition_v3"));
        assertFalse(reencoded.toString().contains("\"chorus:effect/weapon/weapon_definition\""));
        assertEquals("chorus:effect/effect_state_v3", reencoded.getAsJsonObject("state").get("$").getAsString());
        assertEquals(saved, EffectCheckpointCodec.decode(reencoded));
        session.observe(199_999, List.of()); assertTrue(world.isEmpty());
        session.observe(200_000, List.of());
        var state = session.state().engine().domain();
        assertEquals(5, state.ammunition().get("a").magazine()); assertEquals(8, state.ammunition().get("a").reserve().orElseThrow().rounds());
        assertEquals(1, state.ammunition().get("b").magazine()); assertTrue(state.reloads().isEmpty());
        assertEquals(List.of(4.0), world.stream().filter(w -> w.command() instanceof HealingCommand).map(w -> ((HealingCommand) w.command()).amount()).toList());
        assertTrue(state.buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:kill_clip") && b.origin().weapon().equals("a")));
        assertTrue(world.getFirst().id().frame() >= saved.timeline().nextFrame());
        assertTrue(session.checkpoint().timeline().nextEvent() > saved.timeline().nextEvent());
        assertEquals(input, oldCheckpoint(), "Loading cannot mutate the archived wire data");
    }
    @Test void oldWireTypeRemainsStrictAndCannotSmuggleTheNewBehavior() throws Exception {
        for (String variant : List.of("missing", "new_field", "invalid_identity")) {
            var tree = oldCheckpoint();
            var weapon = tree.getAsJsonObject("program").getAsJsonObject("v").getAsJsonObject("weapons").getAsJsonArray("v").get(0).getAsJsonObject().getAsJsonObject("v");
            switch (variant) {
                case "missing" -> weapon.remove("fire");
                case "new_field" -> weapon.add("handling", EffectCheckpointCodec.DATA.encode(Optional.empty()));
                case "invalid_identity" -> weapon.addProperty("item", "invalid");
            }
            assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.decode(tree));
        }
    }
    static JsonObject configured() throws Exception {
        var data = json("weapons");
        data.getAsJsonArray("weapons").get(0).getAsJsonObject().add("handling", JsonParser.parseString("""
                {"ready":{"value":{"type":"chorus:constant","value":0.3,"unit":"second"}},
                 "stow":{"value":{"type":"chorus:constant","value":0.2,"unit":"second"},"profiles":["test:reload_time"]}}
                """));
        return data;
    }
    static CompiledEffects compile(JsonObject data) throws Exception {
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(), load("kill_clip").program()));
    }
    @Test void phasePipelinesRoundTripInDataPacksAndCheckpointsAndStayOptional() throws Exception {
        var p = compile(configured()).program();
        assertTrue(p.weapons().get(0).handling().isPresent());
        assertTrue(p.weapons().get(1).handling().isEmpty());
        assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        assertEquals(p, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p), EffectProgram.class));
    }
    @Test void malformedHandlingAndIncompatibleTimingPipelinesFailBeforeInstallation() throws Exception {
        for (String variant : List.of("missing_ready", "unknown", "negative", "zero", "wrong_unit", "unknown_profile", "wrong_profile", "both", "empty")) {
            var data = configured(); var handling = data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("handling");
            var ready = handling.getAsJsonObject("ready"); var value = ready.getAsJsonObject("value");
            switch (variant) {
                case "missing_ready" -> handling.remove("ready");
                case "unknown" -> handling.addProperty("typo", true);
                case "negative" -> value.addProperty("value", -1);
                case "zero" -> value.addProperty("value", 0);
                case "wrong_unit" -> value.addProperty("unit", "damage");
                case "unknown_profile" -> ready.addProperty("profile", "test:missing");
                case "wrong_profile" -> ready.addProperty("profile", "test:magazine");
                case "both" -> { ready.addProperty("profile", "test:reload_time"); ready.add("profiles", JsonParser.parseString("[\"test:reload_time\"]")); }
                case "empty" -> ready.add("profiles", new JsonArray());
            }
            assertThrows(RuntimeException.class, () -> compile(data), variant);
        }
    }
}
