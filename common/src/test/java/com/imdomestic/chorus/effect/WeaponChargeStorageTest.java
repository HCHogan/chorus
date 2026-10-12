package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.*;
import org.junit.jupiter.api.Test;

class WeaponChargeStorageTest {
    @Test void oldV4StateKeepsItsExistingAttachmentAndNeverInventsACharge()throws Exception{
        var h=new TimedPayloadTest.Harness(false);h.fire();h.hit(5,false);h.until(100_000);var state=h.state();assertFalse(state.attachments().isEmpty());
        var wire=EffectCheckpointCodec.DATA.encode(state).getAsJsonObject();wire.addProperty("$","chorus:effect/effect_state_v4");wire.getAsJsonObject("v").remove("charging");
        var migrated=EffectCheckpointCodec.DATA.decode(wire,EffectState.class);assertEquals(state,migrated);assertTrue(migrated.charging().isEmpty());assertEquals("chorus:effect/effect_state_v5",EffectCheckpointCodec.DATA.encode(migrated).getAsJsonObject().get("$").getAsString());
        wire.getAsJsonObject("v").add("charging",EffectCheckpointCodec.DATA.encode(java.util.Map.of()));assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.DATA.decode(wire,EffectState.class));
    }
    @Test void oldWeaponDefinitionAndFireEnumKeepTheirExactAcceptedNames()throws Exception{
        var definition=ExplosiveHeadTest.program(json("explosive_head_test_calibration")).program().weapons().getFirst();
        var wire=EffectCheckpointCodec.DATA.encode(definition).getAsJsonObject();wire.addProperty("$","chorus:effect/weapon/weapon_definition_v3");wire.getAsJsonObject("v").remove("charging");
        var restored=EffectCheckpointCodec.DATA.decode(wire,WeaponDefinition.class);assertEquals(definition,restored);assertTrue(restored.charging().isEmpty());
        for(var outcome:WeaponFire.Outcome.values()){
            var encoded=EffectCheckpointCodec.DATA.encode(outcome).getAsJsonObject();encoded.addProperty("$","chorus:effect/weapon/weapon_fire/outcome");
            if(outcome==WeaponFire.Outcome.CHARGE_REQUIRED)assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.DATA.decode(encoded,WeaponFire.Outcome.class));
            else assertEquals(outcome,EffectCheckpointCodec.DATA.decode(encoded,WeaponFire.Outcome.class));
        }
    }
}
