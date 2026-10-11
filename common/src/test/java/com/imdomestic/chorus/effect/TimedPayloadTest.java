package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TimedPayloadTest {
    static final String TAG="chorus_d2:timed_payload_damage";
    static CompiledEffects program(JsonObject data)throws Exception{
        var parts=new ArrayList<EffectProgram>();parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());
        for(String n:ExplosivePayloadTest.PARTS)parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json(n)).getOrThrow());parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("timed_payload")).getOrThrow());return CompiledEffects.link(parts);
    }
    static class Harness extends ExplosivePayloadTest.Harness {
        Harness(boolean pvp)throws Exception{super(program(json("timed_payload_weapon")),TAG,pvp);}
        @Override void equip(String a,String b,String drawn){equip(new Loadout.Gear("a","test:hand_cannon",Map.of("perk",a),Map.of("payload_radius",new Measure(3,Unit.METER))),new Loadout.Gear("b","test:rifle",Map.of("perk",b),Map.of("payload_radius",new Measure(3,Unit.METER))),drawn);}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){if(r.command() instanceof PositionQuery q&&missing.contains(q.target()))return new PositionQuery.Result(q,Optional.empty());return super.world(r);}
    }
    @Test void fiftyFivePercentSplitWaitsExactlySixTenthsAndHasNoLegendaryHandCannonPenalty()throws Exception{
        for(boolean pvp:List.of(false,true))for(boolean head:List.of(false,true)){
            var h=new Harness(pvp);h.fire();h.hit(5,head);assertEquals(1,h.hits.size());assertEquals(head?14.5:4.5,h.amounts.getFirst(),1e-9);h.until(599_999);assertEquals(1,h.hits.size());h.until(600_000);assertEquals(pvp?5.5:7.15,h.amounts.getLast(),1e-9);assertTrue(h.state().damageGroups().isEmpty());assertEquals(h.hits.getFirst().group(),h.bursts().getFirst().group());
        }
    }
    @Test void pendingBlastTranslatesItsOriginalContactAndOnlyQueriesNeighborsAtDetonation()throws Exception{
        var h=new Harness(false);h.entity("old",1000,-3.5);h.entity("new",1000,16.5);h.fire();h.hit(5,false);assertTrue(h.queries.isEmpty());h.positions.put("target",new WorldPosition("test:world",15,0,0));h.until(600_000);
        assertEquals(1000,h.health.get("old"));assertEquals(996.425,h.health.get("new"),1e-9);assertEquals(988.35,h.health.get("target"),1e-9);assertEquals(15,((TargetQuery.PositionCenter)h.queries.getFirst().center()).position().orElseThrow().x());
    }
    @Test void deadButPresentTargetStillMovesTheBlastAndMissingTargetRetiresItExplicitly()throws Exception{
        var h=new Harness(false);h.entity("target",4,-5);h.entity("new",1000,16.5);h.fire();h.hit(5,false);h.positions.put("target",new WorldPosition("test:world",15,0,0));h.until(600_000);assertEquals(996.425,h.health.get("new"),1e-9);assertEquals(2,h.hits.size());
        var absent=new Harness(false);absent.fire();absent.hit(5,false);absent.missing.add("target");absent.until(600_000);assertTrue(absent.bursts().isEmpty());assertEquals(List.of("test:timed_payload_target_unavailable"),absent.cues);assertTrue(absent.state().damageGroups().isEmpty());
    }
    @Test void checkpointAndSourceRemovalRetainRemainingDelayDamageAndMovingTargetBinding()throws Exception{
        var h=new Harness(false);h.bind("bonus","test:payload_bonus","a");h.fire();h.hit(5,false);h.until(200_000);h.session.start(h.now(),new EquipmentChange("player",h.state().equipment().get("player"),Loadout.EMPTY).signal());h.session.start(h.now(),SourceChange.remove("bonus"));h.restore();h.positions.put("target",new WorldPosition("test:world",15,0,0));h.until(599_999);assertEquals(1,h.hits.size());h.until(600_000);assertEquals(10.725,h.amounts.getLast(),1e-9);assertEquals("a",h.bursts().getFirst().source().weapon());h.restore();h.until(1_000_000);assertEquals(1,h.bursts().size());
    }
    @Test void blockAttachmentUsesRecordedSurfaceAndEnhancedAddsStabilityOnly()throws Exception{
        var h=new Harness(false);assertEquals(55,h.query("chorus_d2:weapon_stability","b",50));assertEquals(50,h.query("chorus_d2:weapon_reload","b",50));h.entity("near",1000,-5);h.fire();h.block(ProjectileFlight.End.BLOCK,5);assertTrue(h.hits.isEmpty());h.entity("player",1000,100);h.until(600_000);assertEquals(992.85,h.health.get("near"),1e-9);
    }
    @Test void consumedOneUseGenerationSurvivesTheDelayAndCheckpointButNotAnotherAttack()throws Exception{
        var h=new Harness(false);h.session.start(h.now(),new RuleEngine.Signal("test:arm",new EffectEvent("player","player",new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("player","arm","a",""),Set.of(),Map.of())));h.healthy();h.fire();h.hit(5,false);assertEquals(9,h.amounts.getFirst(),1e-9);assertTrue(h.state().buffs().instances().isEmpty());h.until(300_000);h.restore();h.until(600_000);assertEquals(14.3,h.amounts.getLast(),1e-9);h.fire();h.hit(5,false);assertEquals(4.5,h.amounts.getLast(),1e-9);h.until(1_200_000);assertEquals(7.15,h.amounts.getLast(),1e-9);
    }
    @Test void unknownDelayedWorldOutcomeDoesNotReplayTheAppliedBlast()throws Exception{
        var h=new Harness(false);h.fire();h.hit(5,false);h.failBurst=true;assertThrows(IllegalStateException.class,()->h.until(600_000));assertEquals(988.35,h.health.get("target"),1e-9);assertThrows(IllegalStateException.class,()->h.until(1_200_000));assertEquals(2,h.hits.size());
    }
    @Test void unknownRadiusRequiresAnExplicitItemParameterMapping()throws Exception{
        var data=json("timed_payload_weapon");data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("parameters").remove("payload_radius");assertThrows(IllegalArgumentException.class,()->program(data));
    }

}
