package com.imdomestic.chorus.effect;

import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeaponAimStorageTest {
    static JsonObject oldCheckpoint() throws Exception {
        try(var reader=new java.io.InputStreamReader(Objects.requireNonNull(WeaponAimStorageTest.class.getResourceAsStream("/persistence/pre-aim-handling-v2.json")),java.nio.charset.StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    @Test void actualOldSwitchResumesWithoutInventingAimOrReplayingPhysicalWork()throws Exception {
        var input=oldCheckpoint();var saved=EffectCheckpointCodec.decode(input);var domain=saved.timeline().domain();
        assertEquals(50_000,saved.timeline().timeMicros());assertEquals(100_000,domain.handling().get("player").dueAt());assertTrue(domain.aiming().isEmpty());
        assertTrue(saved.program().weapons().stream().allMatch(w->w.aiming().isEmpty()));
        assertEquals(ActionGate.Kind.WEAPON_FIRE,saved.program().bundles().stream().filter(b->b.id().equals("test:aim_migration_gate")).findFirst().orElseThrow().actionGates().getFirst().action());
        var h=new WeaponHandlingTest.Harness(new CompiledEffects(saved.program()));h.physical=domain.equipment().get("player");
        h.session=EffectSession.restore(h.program,EffectCheckpointTest.clock(),256,saved,h::world);
        assertTrue(h.applications.isEmpty());assertTrue(h.cues.isEmpty());assertTrue(saved.identity().isEmpty());
        var upgraded=h.session.checkpoint();var identity=h.session.timelineIdentity();
        assertEquals(new EffectSession.Checkpoint(saved.program(),saved.timeline(),saved.resourceRouting(),Optional.of(identity)),upgraded);
        var restored=EffectSession.restore(h.program,EffectCheckpointTest.clock(),256,EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(upgraded)),_->{throw new AssertionError("Restoration replayed world work");});
        assertEquals(upgraded,restored.checkpoint());assertEquals(identity,restored.timelineIdentity());
        h.until(99_999);assertTrue(h.applications.isEmpty());h.until(100_000);assertEquals(WeaponHandling.Phase.READY,h.plan().phase());assertEquals(250_000,h.plan().dueAt());
        h.until(250_000);assertNull(h.plan());assertEquals(Optional.of("test:secondary"),h.physical.drawn());assertEquals(2,h.applications.size());assertTrue(h.state().aiming().isEmpty());
        assertTrue(h.session.checkpoint().timeline().nextEvent()>saved.timeline().nextEvent());assertTrue(h.session.checkpoint().timeline().nextFrame()>saved.timeline().nextFrame());
        assertEquals(identity,h.session.timelineIdentity());assertEquals(Optional.of(identity),h.session.checkpoint().identity());
        var encoded=EffectCheckpointCodec.encode(saved);String wire=encoded.toString();
        for(String type:List.of("effect_state_v6","weapon_definition_v4","action_gate/kind_v2"))assertTrue(wire.contains(type));
        assertEquals(saved,EffectCheckpointCodec.decode(encoded));assertEquals(input,oldCheckpoint());
    }
    @Test void retiredStateWeaponAndGateTypesKeepTheirOriginalStrictShapes()throws Exception {
        var state=oldCheckpoint();state.getAsJsonObject("state").getAsJsonObject("v").add("aiming",EffectCheckpointCodec.DATA.encode(Map.of()));
        assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.decode(state));
        var weapon=oldCheckpoint();weapon.getAsJsonObject("program").getAsJsonObject("v").getAsJsonObject("weapons").getAsJsonArray("v").get(0).getAsJsonObject().getAsJsonObject("v").add("aiming",EffectCheckpointCodec.DATA.encode(Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.decode(weapon));
        assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.DATA.decode(JsonParser.parseString("{\"$\":\"chorus:effect/input/action_gate/kind\",\"v\":\"WEAPON_AIM\"}"),ActionGate.Kind.class));
    }
    @Test void aimConfigurationRoundTripsAndInvalidTimingCannotBeInstalled()throws Exception {
        var program=EffectTestSupport.compile(WeaponAimTest.data()).program();
        assertEquals(program,EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,program).getOrThrow()).getOrThrow());
        assertEquals(program,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(program),EffectProgram.class));
        for(String variant:List.of("missing_exit","unknown","wrong_unit","unknown_profile","wrong_profile","both","empty")) {
            var data=WeaponAimTest.data();var aim=data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("aiming");var enter=aim.getAsJsonObject("enter");
            switch(variant) {
                case "missing_exit" -> aim.remove("exit");
                case "unknown" -> aim.addProperty("typo",true);
                case "wrong_unit" -> enter.getAsJsonObject("value").addProperty("unit","damage");
                case "unknown_profile" -> enter.addProperty("profile","test:missing");
                case "wrong_profile" -> enter.addProperty("profile","test:magazine");
                case "both" -> enter.add("profiles",JsonParser.parseString("[\"test:aim_time\"]"));
                case "empty" -> {enter.remove("profile");enter.add("profiles",new JsonArray());}
            }
            assertThrows(RuntimeException.class,()->EffectTestSupport.compile(data),variant);
        }
    }
}
