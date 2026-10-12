package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PiercingHitscanTest {
    static JsonObject data(int pierces)throws Exception {
        var d=json("hitscan");var s=HitscanTest.steps(d);while(s.size()>5)s.remove(5);
        s.get(3).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("pellets").addProperty("value",1);
        s.get(4).getAsJsonObject().add("pierces",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":"+pierces+",\"unit\":\"count\"}"));return d;
    }
    static Hitscan.Result entity(Hitscan.Query q,String id,double x) {
        var c=new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,new WorldPosition("world",x,40,0),Optional.of(id),0,0,0,0).withMember(q.member())
                .withGeometry(new ProjectileFlight.Geometry(new WorldPosition("world",x,39,-.5),1,2,1,q.inflation()));
        return new Hitscan.Result(q,Hitscan.Outcome.ENTITY,Optional.of(c));
    }
    static Hitscan.PathResult path(Hitscan.PiercingQuery q,int entities) {
        var contacts=new ArrayList<Hitscan.PathContact>();int limit=(int)Math.min(entities,(long)q.pierces()+1);
        for(int i=0;i<limit;i++)contacts.add(new Hitscan.PathContact(entity(q.ray(),"enemy"+i,2+i),i+1L,i==q.pierces()));
        if(entities<=q.pierces())contacts.add(new Hitscan.PathContact(HitscanTest.contact(q.ray(),Hitscan.Outcome.RANGE),limit+1L,true));
        return new Hitscan.PathResult(q,contacts);
    }
    static class Harness extends HitscanTest.Harness {
        int available=3;int queries;final List<Long> sequences=new ArrayList<>();Hitscan.PathResult observed;
        Harness(int pierces)throws Exception {super(data(pierces));}
        Harness(JsonObject d){super(d);}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if(r.command() instanceof Hitscan.PiercingQuery q) {queries++;observed=path(q,available);return observed;}
            if(r.command() instanceof DamageCommand)sequences.add(state().shotGroups().values().iterator().next().pellets().get(0).sequence());
            return super.world(r);
        }
    }
    @Test void oneAdditionalPenetrationHitsTwoDistinctTargetsWithOneMember()throws Exception {
        var h=new Harness(1);h.fire();assertEquals(1,h.queries);assertEquals(List.of("enemy0","enemy1"),h.damage.stream().map(DamageCommand::target).toList());
        assertEquals(List.of(1L,2L),h.sequences);assertEquals(List.of("test:all_hit"),h.cues);assertTrue(h.state().shotGroups().isEmpty());
        assertEquals(1,h.observed.contacts().getLast().contact().orElseThrow().targetContacts());assertEquals(2,h.observed.contacts().getLast().contact().orElseThrow().entityContacts());
    }
    @Test void zeroAndLargeCountsUseTheSameFiniteSceneWithoutAPerChainCutoff()throws Exception {
        var zero=new Harness(0);zero.fire();assertEquals(1,zero.damage.size());
        var many=new Harness(Integer.MAX_VALUE);many.fire();assertEquals(3,many.damage.size());assertEquals(Hitscan.Outcome.RANGE,many.observed.contacts().getLast().outcome());assertEquals(4,many.observed.contacts().size());assertTrue(many.state().shotGroups().isEmpty());
    }
    @Test void rangeAfterTheLastEnemyClosesTheMemberWithoutLosingItsHits()throws Exception {
        for(int n=0;n<3;n++) {var h=new Harness(3);h.available=n;h.fire();assertEquals(n,h.damage.size());assertEquals(List.of(n==0?"test:not_all_hit":"test:all_hit"),h.cues);assertTrue(h.state().shotGroups().isEmpty());}
    }
    @Test void geometrySnapshotAndPathOrderSurviveStorageAndRejectMalformedReceipts()throws Exception {
        var h=new Harness(2);h.fire();var p=h.observed;assertEquals(p,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p),Hitscan.PathResult.class));
        var reversed=new ArrayList<>(p.contacts());Collections.reverse(reversed);assertThrows(RuntimeException.class,()->new Hitscan.PathResult(p.query(),reversed));
        var duplicate=List.of(new Hitscan.PathContact(p.contacts().getFirst().observed(),1,false),new Hitscan.PathContact(p.contacts().getFirst().observed(),2,true));
        assertThrows(RuntimeException.class,()->new Hitscan.PathResult(new Hitscan.PiercingQuery(p.query().ray(),1),duplicate));
        assertThrows(RuntimeException.class,()->new Hitscan.PathResult(p.query(),List.of(p.contacts().getFirst())));
    }
    @Test void capturedSceneDoesNotChangeWhenEarlierDamageMutatesTheLiveScene()throws Exception {
        var h=new Harness(1){@Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){if(r.command() instanceof DamageCommand)available=0;return super.world(r);}};
        h.fire();assertEquals(2,h.damage.size());assertEquals(1,h.queries);assertEquals(List.of("enemy0","enemy1"),h.damage.stream().map(DamageCommand::target).toList());
    }
    @Test void unknownFirstDamageDoesNotApplyLaterCapturedContactsOrReplayTheQuery()throws Exception {
        var h=new Harness(2);h.unknown=true;assertThrows(IllegalStateException.class,h::fire);assertEquals(1,h.queries);assertEquals(1,h.damage.size());
        assertThrows(IllegalStateException.class,h::fire);assertEquals(1,h.damage.size());assertTrue(h.state().shotGroups().values().iterator().next().pellets().get(0).contactOpen());
    }
    @Test void codecRetainsExplicitZeroPolicyAndRejectsFractionalOrWrongUnits()throws Exception {
        var p=compile(data(0)).program();assertEquals(p,EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow());
        assertEquals(p,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p),EffectProgram.class));
        for(String fault:List.of("unit","fraction","negative")){var d=data(1);var value=HitscanTest.steps(d).get(4).getAsJsonObject().getAsJsonObject("pierces");
            if(fault.equals("unit"))value.addProperty("unit","meter");else value.addProperty("value",fault.equals("fraction")?.5:-1);assertThrows(RuntimeException.class,()->compile(d));}
    }
    @Test void delayedGeometryKeepsItsContactIndexButCannotSpendClosedMembership()throws Exception {
        var d=data(1);var ray=HitscanTest.steps(d).get(4).getAsJsonObject();var delayed=JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"},\"lifetime\":\"detached\",\"do\":[{\"if\":{\"type\":\"chorus:result_flag\",\"binding\":\"impact\",\"field\":\"pierced\"},\"then\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:first\"}]}]}").getAsJsonObject();ray.getAsJsonArray("do").add(delayed);
        var h=new Harness(d);h.fire();var saved=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint()));h.session=EffectSession.restore(new CompiledEffects(saved.program()),EffectCheckpointTest.clock(),1,saved,h::world);h.session.observe(100_000,List.of());assertEquals(List.of("test:all_hit","test:first"),h.cues);assertEquals(1,h.queries);
        delayed.getAsJsonArray("do").add(ray.getAsJsonArray("do").get(0).deepCopy());assertThrows(RuntimeException.class,()->compile(d));
    }
}
