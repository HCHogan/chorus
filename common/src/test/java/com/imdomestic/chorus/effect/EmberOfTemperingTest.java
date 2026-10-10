package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import java.util.*;
import org.junit.jupiter.api.Test;

class EmberOfTemperingTest {
    static final String FRAGMENT="chorus_d2:ember_of_tempering", BUFF="chorus_d2:tempering";
    @Test void firstKillActivatesAndOnlySubsequentSolarWeaponKillsRequestFiresprites() throws Exception {
        var h=new FirespriteTest.Harness();h.bind("player",FRAGMENT);
        h.kill("player",false,true);h.kill("player",true,false);h.kill("ally",true,true);
        assertTrue(h.buff("player",BUFF).isEmpty());assertTrue(h.spawns.isEmpty());
        h.kill("player",true,true);assertEquals(1,h.buff("player",BUFF).orElseThrow().count());assertTrue(h.spawns.isEmpty());
        h.kill("player",true,true);assertEquals(2,h.buff("player",BUFF).orElseThrow().count());assertEquals(1,h.spawns.size());
        h.kill("player",true,true);assertEquals(3,h.buff("player",BUFF).orElseThrow().count());assertEquals(1,h.spawns.size(),"shared Firesprite cooldown");
        h.until(5_000_000);h.kill("player",true,true);assertEquals(2,h.spawns.size());
    }
    @Test void alliedBuffSharesThreeStacksAndEightSecondRefreshWhileClassPenaltyBelongsToFragment() throws Exception {
        var h=new FirespriteTest.Harness();h.bind("player",FRAGMENT);h.allies=List.of(new TargetQuery.Target("ally",15));
        assertEquals(40,h.statQuery("player","class_stat",50));assertEquals(50,h.statQuery("ally","class_stat",50));
        for(int stack=1;stack<=3;stack++){
            h.kill("player",true,true);
            for(String who:List.of("player","ally")){
                assertEquals(stack,h.buff(who,BUFF).orElseThrow().count());assertEquals(20*stack,h.statQuery(who,"health_stat",0));assertEquals(30,h.statQuery(who,"weapon_airborne_effectiveness",10));
            }
        }
        var q=h.selections.getFirst();assertEquals(15,q.radius());assertEquals(TargetQuery.Relation.ALLIED,q.relation());assertEquals("player",q.relativeTo());assertTrue(q.exclude().contains("player"));
        h.until(7_000_000);h.kill("player",true,true);assertEquals(15_000_000,h.buff("ally",BUFF).orElseThrow().deadline());
        h.remove("player",FRAGMENT);assertEquals(50,h.statQuery("player","class_stat",50));h.until(14_999_999);assertEquals(60,h.statQuery("player","health_stat",0));
        h.until(15_000_000);assertEquals(0,h.statQuery("player","health_stat",0));assertEquals(10,h.statQuery("ally","weapon_airborne_effectiveness",10));
    }
    @Test void repeatedProducersRefreshOneRecipientBuffAndStatBonusesClamp() throws Exception {
        var h=new FirespriteTest.Harness();h.bind("player",FRAGMENT);h.bind("ally",FRAGMENT);h.allies=List.of(new TargetQuery.Target("ally",2));h.kill("player",true,true);
        h.allies=List.of(new TargetQuery.Target("player",2));h.kill("ally",true,true);
        for(String who:List.of("player","ally")){
            assertEquals(2,h.buff(who,BUFF).orElseThrow().count());assertEquals(200,h.statQuery(who,"health_stat",190));assertEquals(100,h.statQuery(who,"weapon_airborne_effectiveness",95));assertEquals(0,h.statQuery(who,"class_stat",5));
            assertEquals(1,h.state().buffs().instances().values().stream().filter(b->b.key().holder().equals(who)&&b.definition().id().equals(BUFF)).count());
        }
    }
    @Test void allyCanRequestItsOwnPickupUnderExplicitSharedBuffPolicyWithoutBorrowingProducerCooldown() throws Exception {
        var h=new FirespriteTest.Harness();h.bind("player",FRAGMENT);h.allies=List.of(new TargetQuery.Target("ally",2));h.kill("player",true,true);h.kill("player",true,true);
        h.kill("ally",true,true);assertEquals(List.of("player","ally"),h.spawns.stream().map(s->s.recipient()).toList());
        h.finish(1,1,true);assertEquals(.0375,h.energy("ally"),1e-12);assertEquals(0,h.energy("player"));
    }
    @Test void expirationMakesNextKillAnActivationAgainWithoutRemovingFragmentPenalty() throws Exception {
        var h=new FirespriteTest.Harness();h.bind("player",FRAGMENT);h.kill("player",true,true);h.until(8_000_000);h.kill("player",true,true);
        assertEquals(1,h.buff("player",BUFF).orElseThrow().count());assertTrue(h.spawns.isEmpty());assertEquals(40,h.statQuery("player","class_stat",50));
    }
}
