package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class RicochetHitscanTest {
    static JsonObject data(int pierces,int bounces)throws Exception{
        var d=json("ricochet_hitscan");HitscanTest.steps(d).get(4).getAsJsonObject().getAsJsonObject("pierces").addProperty("value",pierces);HitscanTest.steps(d).get(4).getAsJsonObject().add("bounces",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":"+bounces+",\"unit\":\"count\"}"));return d;
    }
    static ProjectileFlight.Impact hit(Hitscan.Query q,Hitscan.Outcome outcome,double x,String target){
        var p=new WorldPosition("world",x,40,0);var end=switch(outcome){case ENTITY->ProjectileFlight.End.ENTITY;case BLOCK->ProjectileFlight.End.BLOCK;case UNLOADED->ProjectileFlight.End.UNLOADED;default->ProjectileFlight.End.EXPIRED;};
        var hit=new ProjectileFlight.Impact(end,p,Optional.ofNullable(target),outcome==Hitscan.Outcome.BLOCK?-1:0,0,0,0).withMember(q.member());
        if(target!=null)hit=hit.withGeometry(new ProjectileFlight.Geometry(new WorldPosition("world",x-1,39,-.5),1,2,1,q.inflation()));return hit;
    }
    static RicochetHitscan.Result path(RicochetHitscan.Query q,int targets,String firstTarget){
        var ray=q.ray();var wall=hit(ray,Hitscan.Outcome.BLOCK,5,null);boolean reflect=q.bounces()>0;var contacts=new ArrayList<RicochetHitscan.Contact>();
        contacts.add(new RicochetHitscan.Contact(ray,Hitscan.Outcome.BLOCK,Optional.of(wall),1,reflect?1:0,0,!reflect,0,false));if(!reflect)return new RicochetHitscan.Result(q,contacts);
        var second=RicochetHitscan.reflected(ray,wall);int count=(int)Math.min(targets,1L+q.pierces());
        for(int i=0;i<count;i++)contacts.add(new RicochetHitscan.Contact(second,Hitscan.Outcome.ENTITY,Optional.of(hit(second,Hitscan.Outcome.ENTITY,4-i,i==0?firstTarget:"enemy"+i)),2+i,1,i+1,i==q.pierces(),5,false));
        if(count<=q.pierces())contacts.add(new RicochetHitscan.Contact(second,Hitscan.Outcome.RANGE,Optional.of(hit(second,Hitscan.Outcome.RANGE,5-second.range(),null)),2+count,1,count,true,5,false));
        return new RicochetHitscan.Result(q,contacts);
    }
    static class Harness extends HitscanTest.Harness {
        int targets=3,queries;RicochetHitscan.Result observed;final List<Long> sequences=new ArrayList<>();
        Harness(int pierces,int bounces)throws Exception{super(data(pierces,bounces));}
        Harness(JsonObject data){super(data);}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){
            if(r.command() instanceof RicochetHitscan.Query q){queries++;
                observed=q.ray().member().filter(m->!ShotGroups.active(state(),m)).isPresent()?new RicochetHitscan.Result(q,List.of(new RicochetHitscan.Contact(q.ray(),Hitscan.Outcome.EXPIRED_SHOT,Optional.empty(),1,0,0,true,0,false))):path(q,targets,"enemy0");return observed;
            }
            if(r.command() instanceof DamageCommand)sequences.add(state().shotGroups().values().iterator().next().pellets().get(0).sequence());return super.world(r);
        }
    }
    @Test void wallThenTwoEntitiesKeepOneMemberAndOrderedConfirmedDamage()throws Exception{
        var h=new Harness(1,1);h.fire();assertEquals(1,h.queries);assertEquals(List.of(2L,3L),h.sequences);assertEquals(List.of("enemy0","enemy1"),h.damage.stream().map(DamageCommand::target).toList());assertTrue(h.state().shotGroups().isEmpty());assertEquals(List.of("test:all_hit"),h.cues);
        assertEquals(2,h.observed.contacts().getLast().contact().orElseThrow().entityContacts());assertEquals(1,h.observed.contacts().getLast().contact().orElseThrow().targetContacts());
    }
    @Test void fieldsExposeTravelDistanceAndSegmentDistanceWithoutResettingRange()throws Exception{
        var h=new Harness(1,1);h.fire();var shape=ResultShape.hitscan(true);var contacts=h.observed.contacts();
        assertEquals(6,shape.read("distance",contacts.get(1)).value());assertEquals(1,shape.read("segment_distance",contacts.get(1)).value());assertEquals(1,shape.read("bounces",contacts.get(1)).value());
        assertTrue(shape.flag("bounced",contacts.getFirst()));assertFalse(shape.flag("terminal",contacts.getFirst()));assertEquals(15,contacts.getLast().query().range());assertEquals(7,contacts.getLast().distance());
    }
    @Test void configuredTargetRevisitsDealTwoHitsButStillCountOnePelletAndOneTarget()throws Exception{
        var d=data(1,1);var ray=HitscanTest.steps(d).get(4).getAsJsonObject();ray.add("hits_per_target",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"count\"}"));
        d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
            {"id":"unique_target","on":"chorus:shot_resolved","if":{"type":"chorus:compare","op":"eq","left":{"type":"chorus:event_number","name":"targets_hit","unit":"count"},"right":{"type":"chorus:constant","value":1,"unit":"count"}},"do":[{"type":"chorus:play_cue","cue":"test:one_target"}]}
            """));
        var h=new Harness(d){@Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){
            if(r.command() instanceof RicochetHitscan.Query q){queries++;var first=q.ray();var wall=hit(first,Hitscan.Outcome.BLOCK,5,null);var reflected=RicochetHitscan.reflected(first,wall);
                observed=new RicochetHitscan.Result(q,List.of(
                    new RicochetHitscan.Contact(first,Hitscan.Outcome.ENTITY,Optional.of(hit(first,Hitscan.Outcome.ENTITY,2,"enemy0")),1,0,1,false,0,false,1),
                    new RicochetHitscan.Contact(first,Hitscan.Outcome.BLOCK,Optional.of(wall),2,1,1,false,0,false,0),
                    new RicochetHitscan.Contact(reflected,Hitscan.Outcome.ENTITY,Optional.of(hit(reflected,Hitscan.Outcome.ENTITY,2,"enemy0")),3,1,2,true,5,false,2)));return observed;
            }return super.world(r);
        }};h.fire();assertEquals(2,h.damage.size());assertEquals(List.of(1L,3L),h.sequences);assertEquals(2,h.observed.contacts().getLast().contact().orElseThrow().targetContacts());assertTrue(h.cues.contains("test:all_hit"));assertEquals(1,h.cues.stream().filter("test:one_target"::equals).count());assertTrue(h.state().shotGroups().isEmpty());
        assertThrows(RuntimeException.class,()->new RicochetHitscan.Result(new RicochetHitscan.Query(h.observed.query().ray(),1,1,1,true),h.observed.contacts()));
        var first=h.observed.contacts().getFirst();var duplicate=new RicochetHitscan.Contact(first.query(),first.outcome(),first.observed(),2,0,2,true,0,false,2);
        assertThrows(RuntimeException.class,()->new RicochetHitscan.Result(h.observed.query(),List.of(first,duplicate)),"same segment cannot manufacture a second contact");
    }
    @Test void zeroBounceStopsAndLargeBudgetsStillEndAtFiniteRange()throws Exception{
        var zero=new Harness(0,0);zero.fire();assertTrue(zero.damage.isEmpty());assertTrue(zero.state().shotGroups().isEmpty());assertEquals(List.of("test:not_all_hit"),zero.cues);
        var many=new Harness(Integer.MAX_VALUE,Integer.MAX_VALUE);many.targets=0;many.fire();assertEquals(2,many.observed.contacts().size());assertEquals(20,many.observed.contacts().getLast().distance());assertEquals(Hitscan.Outcome.RANGE,many.observed.contacts().getLast().outcome());assertTrue(many.state().shotGroups().isEmpty());
    }
    @Test void originHitsRequireExplicitPermissionAndAnEarlierBounce() {
        var ray=new Hitscan.Query(Optional.of(HitscanTest.START),Optional.of(new WorldDirection("world",1,0,0)),20,0,"player","emitter",Optional.empty());
        for(String id:List.of("player","emitter")){
            assertThrows(RuntimeException.class,()->path(new RicochetHitscan.Query(ray,0,1,false),1,id));var allowed=path(new RicochetHitscan.Query(ray,0,1,true),1,id);assertEquals(id,allowed.contacts().getLast().targets().getFirst().entity());
            var initial=new RicochetHitscan.Contact(ray,Hitscan.Outcome.ENTITY,Optional.of(hit(ray,Hitscan.Outcome.ENTITY,2,id)),1,0,1,true,0,false);
            assertThrows(RuntimeException.class,()->new RicochetHitscan.Result(new RicochetHitscan.Query(ray,0,1,true),List.of(initial)));
            assertThrows(RuntimeException.class,()->new Hitscan.Result(ray,Hitscan.Outcome.ENTITY,initial.observed()));
        }
    }
    @Test void storagePreservesPathAndRejectsWrongDirectionBudgetCountersAndRepeatedTargets()throws Exception{
        var h=new Harness(1,1);h.fire();var result=h.observed;assertEquals(result,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(result),RicochetHitscan.Result.class));
        var wall=result.contacts().getFirst();var first=result.contacts().get(1);var second=result.contacts().get(2);
        for(String fault:List.of("distance","count","repeat","disconnect","truncate")){
            var list=new ArrayList<>(result.contacts());
            switch(fault){
                case "distance"->list.set(1,new RicochetHitscan.Contact(first.query(),first.outcome(),first.observed(),2,1,1,false,0,false));
                case "count"->list.set(2,new RicochetHitscan.Contact(second.query(),second.outcome(),second.observed(),3,1,1,true,5,false));
                case "repeat"->list.set(2,new RicochetHitscan.Contact(second.query(),second.outcome(),Optional.of(hit(second.query(),Hitscan.Outcome.ENTITY,3,"enemy0")),3,1,2,true,5,false));
                case "disconnect"->{var wrong=new Hitscan.Query(Optional.of(new WorldPosition("world",6,40,0)),first.query().direction(),15,0,"player","player",first.query().member());list.set(1,new RicochetHitscan.Contact(wrong,first.outcome(),first.observed(),2,1,1,false,5,false));}
                default->list.removeLast();
            }
            assertThrows(RuntimeException.class,()->new RicochetHitscan.Result(result.query(),list),fault);
        }
        assertEquals(0,wall.priorDistance());
    }
    @Test void embeddedWallsAndExactRangeEndpointsCannotReflect() {
        var ray=new Hitscan.Query(Optional.of(HitscanTest.START),Optional.of(new WorldDirection("world",1,0,0)),5,0,"player","player",Optional.empty());var wall=hit(ray,Hitscan.Outcome.BLOCK,5,null);
        assertFalse(RicochetHitscan.canReflect(ray,wall,false));assertFalse(RicochetHitscan.canReflect(ray,hit(ray,Hitscan.Outcome.BLOCK,0,null),true));
        var q=new RicochetHitscan.Query(ray,0,100,true);var end=new RicochetHitscan.Contact(ray,Hitscan.Outcome.BLOCK,Optional.of(wall),1,0,0,true,0,false);assertEquals(List.of(end),new RicochetHitscan.Result(q,List.of(end)).contacts());
    }
    @Test void codecRejectsInvalidBounceUnitsCountsAndUnscopedPolicyFields()throws Exception{
        var p=compile(data(0,0)).program();assertEquals(p,EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p).getOrThrow()).getOrThrow());
        assertEquals(p,EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p),EffectProgram.class));
        for(String fault:List.of("unit","fraction","negative","overflow","target_zero","target_unit","field")){
            var d=data(0,1);var ray=HitscanTest.steps(d).get(4).getAsJsonObject();var n=ray.getAsJsonObject("bounces");
            switch(fault){case "unit"->n.addProperty("unit","second");case "fraction"->n.addProperty("value",.5);case "negative"->n.addProperty("value",-1);case "overflow"->n.addProperty("value",2147483648L);case "target_zero"->ray.add("hits_per_target",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"count\"}"));case "target_unit"->ray.add("hits_per_target",JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"}"));default->ray.addProperty("hit_ownr_after_bounce",true);}
            assertThrows(RuntimeException.class,()->compile(d),fault);
        }
    }
    @Test void unknownDamageDoesNotApplyLaterReflectedContactsOrReplayTheQuery()throws Exception{
        var h=new Harness(1,1);h.unknown=true;assertThrows(IllegalStateException.class,h::fire);assertEquals(1,h.queries);assertEquals(1,h.damage.size());assertThrows(IllegalStateException.class,h::fire);assertEquals(1,h.damage.size());
    }
    @Test void detachedCaptureRestoresDistanceAndBounceCountWithoutRetainingShotCredit()throws Exception{
        var data=data(0,1);var ray=HitscanTest.steps(data).get(4).getAsJsonObject();var delayed=JsonParser.parseString("""
            {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[
              {"if":{"type":"chorus:all","of":[{"type":"chorus:result_flag","binding":"impact","field":"entity"},
                {"type":"chorus:compare","op":"eq","left":{"type":"chorus:result","binding":"impact","field":"distance"},"right":{"type":"chorus:constant","value":6,"unit":"meter"}}]},
               "then":[{"type":"chorus:play_cue","cue":"test:retained_reflection"}]}]}
            """).getAsJsonObject();ray.getAsJsonArray("do").add(delayed);var h=new Harness(data);h.fire();
        var checkpoint=EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint()));h.session=EffectSession.restore(new CompiledEffects(checkpoint.program()),EffectCheckpointTest.clock(),1,checkpoint,h::world);h.session.observe(100_000,List.of());assertEquals(List.of("test:all_hit","test:retained_reflection"),h.cues);assertEquals(1,h.queries);
        delayed.getAsJsonArray("do").add(ray.getAsJsonArray("do").get(0).deepCopy());assertThrows(RuntimeException.class,()->compile(data));
    }
    @Test void expiredGroupRejectsThePathWithoutReopeningMembership()throws Exception{
        var data=data(0,1);var actions=HitscanTest.steps(data);var ray=actions.remove(4);var later=JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"second\"},\"lifetime\":\"detached\",\"do\":[]}").getAsJsonObject();later.getAsJsonArray("do").add(ray);actions.add(later);var h=new Harness(data);h.fire();h.session.observe(2_000_000,List.of());assertTrue(h.damage.isEmpty());assertTrue(h.state().shotGroups().isEmpty());assertEquals(List.of("test:not_all_hit"),h.cues);
    }
}
