package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.weapon.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import org.junit.jupiter.api.Test;

class WeaponChargeStorageTest {
    private static void releaseOnlyWire(JsonElement value) {
        if(value.isJsonArray()){value.getAsJsonArray().forEach(WeaponChargeStorageTest::releaseOnlyWire);return;}
        if(!value.isJsonObject())return;var o=value.getAsJsonObject();
        if(o.has("$")&&o.get("$").isJsonPrimitive()){
            String id=o.get("$").getAsString();
            if(id.equals("chorus:effect/weapon/weapon_definition/charging_v2")||id.equals("chorus:effect/weapon/weapon_charge/plan_v2")){
                o.addProperty("$",id.substring(0,id.length()-3));o.getAsJsonObject("v").remove("trigger");
            }
        }
        o.entrySet().forEach(e->releaseOnlyWire(e.getValue()));
    }
    @Test void releaseOnlyDefinitionAndPlanMigrateWithoutInventingAutomaticFire()throws Exception{
        var h=new WeaponChargeTest.Harness();h.input("legacy",WeaponCharge.Edge.PRESS);h.until(100_000);var original=h.charge();
        var plan=EffectCheckpointCodec.DATA.encode(original);releaseOnlyWire(plan);var restored=EffectCheckpointCodec.DATA.decode(plan,WeaponCharge.Plan.class);assertEquals(original,restored);assertEquals(WeaponCharge.Trigger.RELEASE,restored.trigger());
        var definition=h.program.program().weapons().getFirst().charging().orElseThrow();var wire=EffectCheckpointCodec.DATA.encode(definition);releaseOnlyWire(wire);assertEquals(definition,EffectCheckpointCodec.DATA.decode(wire,WeaponDefinition.Charging.class));
        plan.getAsJsonObject().getAsJsonObject("v").add("trigger",EffectCheckpointCodec.DATA.encode(WeaponCharge.Trigger.FULL));assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.DATA.decode(plan,WeaponCharge.Plan.class));
        wire.getAsJsonObject().getAsJsonObject("v").add("trigger",EffectCheckpointCodec.DATA.encode(WeaponCharge.Trigger.FULL));assertThrows(IllegalArgumentException.class,()->EffectCheckpointCodec.DATA.decode(wire,WeaponDefinition.Charging.class));
    }
    @Test void oldNestedChargingTimerRestoresReadyAndStillRequiresItsMatchedRelease()throws Exception{
        var h=new WeaponChargeTest.Harness();h.input("legacy",WeaponCharge.Edge.PRESS);h.until(100_000);var wire=EffectCheckpointCodec.encode(h.session.checkpoint());releaseOnlyWire(wire);
        var saved=EffectCheckpointCodec.decode(wire);h.session=EffectSession.restore(new CompiledEffects(saved.program()),EffectCheckpointTest.clock(),256,saved,h::world);
        h.until(400_000);assertEquals(WeaponCharge.Phase.READY,h.charge().phase());assertTrue(h.shots.isEmpty());assertEquals(10,h.ammo());h.input("legacy",WeaponCharge.Edge.RELEASE);assertEquals(9,h.ammo());assertEquals(1,h.shots.size());
    }
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
