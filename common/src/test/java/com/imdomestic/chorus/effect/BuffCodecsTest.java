package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.BuffsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.core.codec.DataCodecs;
import com.imdomestic.chorus.effect.buff.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BuffCodecsTest {
    @Test void jsonDefinitionDrivesRealPausePolicyAndRoundTrips() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/buffs/frame_of_reference.json")), StandardCharsets.UTF_8)) {
            var definition = BuffCodecs.DEFINITION.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var applied = grant(BuffStore.empty(), definition, A, 1).store();
            var stowed = Buffs.weaponState(Buffs.advanceStep(applied, 2_250_000).store(), "player", "weapon-a", true).store();
            var drawn = Buffs.weaponState(Buffs.advanceStep(stowed, 100 * SECOND).store(), "player", "weapon-a", false).store();
            assertEquals(106 * SECOND, drawn.nextDeadline());
            var encoded = BuffCodecs.DEFINITION.encodeStart(JsonOps.INSTANCE, definition).getOrThrow();
            assertEquals(definition, BuffCodecs.DEFINITION.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        }
    }

    @Test void invalidTimersReturnLoadErrorsAndNeverDefaultToZero() {
        for (String fields : List.of(
                "\"duration\": 0", "\"duration\": -1", "\"duration\": 0.0000001", "\"duration\": \"unknown\"",
                "\"duration\": 1, \"max_stacks\": 0", "\"duration\": 1, \"timer_mode\": \"per_stack\", \"refresh\": \"reset\"",
                "\"duration\": 1, \"decay\": \"one_by_one\"", "\"duration\": 1, \"on_stow\": \"typo\"",
                "\"duration\": 1, \"bundle\": {}", "\"duration\": 1, \"max_stack\": 5")) {
            var json = JsonParser.parseString("{\"id\":\"test:invalid\",\"version\":\"1\"," + fields + "}");
            assertTrue(BuffCodecs.DEFINITION.parse(JsonOps.INSTANCE, json).error().isPresent(), fields);
        }
        assertEquals(70_001L, DataCodecs.MICROS.parse(JsonOps.INSTANCE, JsonParser.parseString("0.070001")).getOrThrow());
        assertEquals(Long.MAX_VALUE, DataCodecs.DURATION.parse(JsonOps.INSTANCE, JsonParser.parseString("\"permanent\"")).getOrThrow());
        assertTrue(DataCodecs.MICROS.encodeStart(JsonOps.INSTANCE, Long.MAX_VALUE - 1).error().isPresent());
    }
}
