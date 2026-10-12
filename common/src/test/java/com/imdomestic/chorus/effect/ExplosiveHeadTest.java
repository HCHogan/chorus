package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Original split/stat rules with an explicitly synthetic distance-to-fuse calibration. */
class ExplosiveHeadTest {
    static final String TAG="chorus_d2:explosive_head_damage";
    static final List<String> PARTS=List.of("explosive_head","explosive_payload","explosive_payload_test_calibration","weapon_stats");
    static CompiledEffects program(JsonObject calibration)throws Exception{
        var parts=new ArrayList<EffectProgram>();
        parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("explosive_head_weapon")).getOrThrow());
        for(String name:PARTS)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(name)).getOrThrow());
        if(calibration!=null)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,calibration).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static JsonObject constantDelay(double seconds)throws Exception{
        var calibration=json("explosive_head_test_calibration");
        for(var point:calibration.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("curve").getAsJsonArray("points"))point.getAsJsonObject().addProperty("output",seconds);
        return calibration;
    }
    static class Harness extends TimedPayloadTest.Harness {
        Harness(boolean pvp)throws Exception{this(json("explosive_head_test_calibration"),pvp);}
        Harness(JsonObject calibration,boolean pvp)throws Exception{super(program(calibration),TAG,pvp);}
        @Override void equip(String a,String b,String drawn){equip(new Loadout.Gear("a","test:bow",Map.of("perk",a)),new Loadout.Gear("b","test:bow",Map.of("perk",b)),drawn);}
    }
    static void arm(Harness h){h.session.start(h.now(),new RuleEngine.Signal("test:arm",new EffectEvent("player","player",new BuffInstance.Origin("player","arm","a",""),Set.of(),Map.of())));h.healthy();}

    @Test void distanceControlsOnlyTheFuseAcrossBothModesAndPrecisionComponents()throws Exception{
        for(boolean pvp:List.of(false,true))for(boolean head:List.of(false,true))for(double d:new double[]{5,15,30}){
            var h=new Harness(pvp);h.fire();h.hit(d,head);long due=d==5?200_000:d==15?400_000:700_000;
            assertEquals(List.of(head?15d:5d),h.amounts);h.until(due-1);assertEquals(1,h.hits.size());h.until(due);
            assertEquals(pvp?5:6.5,h.amounts.getLast(),1e-9);assertEquals(1000-(head?15:5)-(pvp?5:6.5),h.health.get("target"),1e-9);
            assertFalse(h.bursts().getFirst().tags().contains("chorus:precision"));assertEquals(h.hits.getFirst().group(),h.bursts().getFirst().group());assertTrue(h.state().attachments().isEmpty()&&h.state().damageGroups().isEmpty());
        }
    }
    @Test void savedMuzzleAndCapturedDamageSurviveSourceRemovalOwnerMovementAndCheckpoint()throws Exception{
        var h=new Harness(false);h.bind("bonus","test:payload_bonus","a");h.fire();var shot=h.shot();
        h.session.start(h.now(),new EquipmentChange("player",h.state().equipment().get("player"),Loadout.EMPTY).signal());h.session.start(h.now(),SourceChange.remove("bonus"));h.entity("player",1000,1000);h.restore();h.hit(shot,15,true);
        assertEquals(22.5,h.amounts.getFirst(),1e-9);h.until(100_000);h.restore();h.positions.put("target",new WorldPosition("test:world",25,0,0));h.entity("near",1000,26.5);
        h.until(399_999);assertEquals(1,h.hits.size());h.until(400_000);assertEquals(9.75,h.amounts.get(1),1e-9);assertEquals(995.125,h.health.get("near"),1e-9);assertTrue(h.hits.stream().allMatch(d->d.source().weapon().equals("a")));
    }
    @Test void longCalibratedFuseKeepsTheConsumedAttackBonusAndAttachmentUntilItsDeadline()throws Exception{
        var h=new Harness(constantDelay(2.4),false);arm(h);h.fire();h.hit(5,false);assertEquals(List.of(10d),h.amounts);assertTrue(h.state().buffs().instances().isEmpty());
        h.until(1_200_000);h.restore();assertEquals(1,h.state().attachments().size());assertEquals(1,h.state().damageGroups().size());h.until(2_399_999);assertEquals(1,h.hits.size());h.until(2_400_000);assertEquals(13,h.amounts.getLast(),1e-9);
        h.fire();h.hit(5,false);assertEquals(5,h.amounts.getLast(),1e-9);h.until(4_800_000);assertEquals(6.5,h.amounts.getLast(),1e-9);assertTrue(h.state().attachments().isEmpty()&&h.state().damageGroups().isEmpty());
    }
    @Test void zeroIsImmediateAndPositiveFractionalMicrosecondsRoundUpWithoutEarlyBlast()throws Exception{
        var zero=new Harness(constantDelay(0),false);zero.fire();zero.hit(5,false);assertEquals(List.of(5d,6.5),zero.amounts);assertTrue(zero.state().attachments().isEmpty()&&zero.state().damageGroups().isEmpty());zero.until(1_000_000);assertEquals(2,zero.hits.size());
        var fractional=new Harness(constantDelay(.20000001),false);fractional.fire();fractional.hit(5,false);fractional.until(200_000);assertEquals(1,fractional.hits.size());fractional.until(200_001);assertEquals(List.of(5d,6.5),fractional.amounts);
    }
    @Test void invalidNegativeCalibrationDiagnosesBeforeAnyDamageAndNoPerkDoesNotQueryIt()throws Exception{
        var h=new Harness(constantDelay(-.1),false);h.fire();h.hit(5,false);assertTrue(h.hits.isEmpty()&&h.state().attachments().isEmpty()&&h.state().damageGroups().isEmpty());assertEquals(List.of("test:explosive_head_invalid_delay"),h.cues);
        h.until(200_000);h.equip("none","enhanced","primary");h.fire();h.hit(5,false);assertEquals(List.of(10d),h.amounts);assertEquals(1,h.cues.size());assertTrue(h.state().attachments().isEmpty()&&h.state().damageGroups().isEmpty());
    }
    @Test void distinctArrowsCanDetonateOutOfContactOrderAfterCheckpoint()throws Exception{
        var h=new Harness(false);h.fire();h.hit(15,false);h.until(150_000);h.fire();h.hit(5,false);assertEquals(2,h.state().attachments().size());h.restore();
        h.until(349_999);assertTrue(h.bursts().isEmpty());h.until(350_000);assertEquals(1,h.bursts().size());assertEquals(1,h.state().attachments().size());h.until(400_000);assertEquals(2,h.bursts().size());assertTrue(h.state().attachments().isEmpty()&&h.state().damageGroups().isEmpty());
    }
    @Test void historicalRemovalFixesTheBlastWithoutTransferringItToANewLife()throws Exception{
        var h=new Harness(false);h.fire();h.hit(15,false);h.until(100_000);TimedPayloadTest.removal(h,EntityLifecycle.Kind.REMOVED,15);h.lives.put("target","new-life");h.entity("target",1000,100);h.entity("near",1000,16.5);h.restore();h.until(400_000);
        assertEquals(1000,h.health.get("target"));assertEquals(996.75,h.health.get("near"),1e-9);assertEquals(15,((TargetQuery.PositionCenter)h.queries.getFirst().center()).position().orElseThrow().x());assertTrue(h.state().attachments().isEmpty()&&h.state().damageGroups().isEmpty());
    }
    @Test void blockRetainsSurfaceWhileExpiryUnloadAndMissingLifeDoNotInventABlast()throws Exception{
        for(var end:List.of(ProjectileFlight.End.BLOCK,ProjectileFlight.End.EXPIRED,ProjectileFlight.End.UNLOADED)){
            var h=new Harness(false);h.entity("near",1000,-5);h.fire();h.block(end,5);h.entity("player",1000,100);h.until(200_000);assertEquals(end==ProjectileFlight.End.BLOCK?993.5:1000,h.health.get("near"));
        }
        for(boolean knownUnload:List.of(false,true)){
            var h=new Harness(false);h.fire();h.hit(5,false);if(knownUnload)TimedPayloadTest.removal(h,EntityLifecycle.Kind.UNLOADED,-5);else h.lives.put("target","new-life");h.until(200_000);
            assertTrue(h.bursts().isEmpty()&&h.state().attachments().isEmpty()&&h.state().damageGroups().isEmpty());assertEquals(List.of("test:explosive_head_target_unavailable"),h.cues);
        }
    }
    @Test void enhancedAccuracyHasItsOwnCappedFamilyAndDoesNotChangeStabilityOrReload()throws Exception{
        var h=new Harness(false);assertEquals(50,h.query("chorus_d2:weapon_accuracy","a",50));assertEquals(55,h.query("chorus_d2:weapon_accuracy","b",50));
        h.session.start(h.now(),SourceChange.bind(new EffectSource("duplicate","chorus_d2:explosive_head","player",new BuffInstance.Origin("player","duplicate","b",""),Set.of("chorus:enhanced"))));h.healthy();
        assertEquals(55,h.query("chorus_d2:weapon_accuracy","b",50));assertEquals(100,h.query("chorus_d2:weapon_accuracy","b",98));assertEquals(50,h.query("chorus_d2:weapon_stability","b",50));assertEquals(50,h.query("chorus_d2:weapon_reload","b",50));
    }
    @Test void unknownDelayedWorldOutcomeRetainsAppliedDamageWithoutReplay()throws Exception{
        var h=new Harness(false);h.fire();h.hit(5,false);h.failBurst=true;assertThrows(IllegalStateException.class,()->h.until(200_000));assertEquals(988.5,h.health.get("target"),1e-9);assertThrows(IllegalStateException.class,()->h.until(400_000));assertEquals(2,h.hits.size());
    }
    @Test void distanceToSecondsProviderIsMandatoryAndMustHaveBothCorrectUnits()throws Exception{
        assertThrows(IllegalArgumentException.class,()->program(null));
        for(boolean input:List.of(false,true)){
            var data=json("explosive_head_test_calibration");var profile=data.getAsJsonArray("profiles").get(0).getAsJsonObject();
            if(input)profile.addProperty("input_unit","second");else profile.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("output_unit","meter");
            assertThrows(IllegalArgumentException.class,()->program(data));
        }
    }
}
