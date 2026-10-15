package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Shot fact identities are issuer-qualified without rewriting historical once-set keys. */
class ShotGroupReferenceTest {
    private static final String A="test:a", B="test:b", HOLDER="player", COUNTER="test:counter";
    private static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin(HOLDER,"source","weapon","ability",Set.of("alpha","omega"));
    private static final CompiledEffects PROGRAM=program();
    private static final WorldPosition START=new WorldPosition("world",0,1,0), POINT=new WorldPosition("world",1,1,0);

    private static ShotGroups.Handle legacy(){return new ShotGroups.Handle("same-id",ORIGIN,2,0,1_000_000);}
    private static ShotGroups.Handle issued(String domain){return ShotGroups.Handle.issued("same-id",ORIGIN,2,0,1_000_000,domain);}
    private static CompiledEffects program(){
        return compile(JsonParser.parseString("""
            {"version":"shot-reference",
             "buffs":[{"definition":{"id":"test:counter","version":"shot-reference","duration":10,
               "components":{"numbers":{"count":{"initial":0,"unit":"count"}},"sets":["shots"]}},
               "bundle":"test:counter"}],
             "bundles":[{"id":"test:counter","scope":"buff","rules":[{
               "id":"one-per-shot","on":"chorus:hit",
               "if":{"type":"chorus:target_is","left":"event_actor","right":"self"},
               "do":[{"type":"chorus:update_component","buff":"test:counter","component":"count","op":"add",
                 "value":{"type":"chorus:constant","value":1,"unit":"count"},
                 "once_set":"shots","event_reference":"shot"}]}]}]}
            """).getAsJsonObject());
    }
    private static EffectState initial(){
        var state=EffectState.empty();
        return state.withBuffs(Buffs.grant(state.buffs(),PROGRAM.buff(COUNTER),HOLDER,HOLDER,ORIGIN,1,1,10_000_000).store());
    }
    private static final class Counter {
        final EffectSession session;
        Counter(){this(initial());}
        Counter(EffectState state){session=new EffectSession(PROGRAM,clock(),1,state,Counter::world);}
        Counter(EffectSession.Checkpoint checkpoint){session=EffectSession.restore(PROGRAM,clock(),1,checkpoint,Counter::world);}
        private static EffectClock clock(){return new EffectClock((_,_)->new EffectClock.Rate(0,List.of()));}
        private static RuleEngine.ActionResult world(RuleEngine.WorldRequest request){throw new AssertionError("Counting receipt facts must not call the world");}
        EffectState state(){return session.state().engine().domain();}
        void accept(RuleEngine.Signal fact){session.start(0,fact);assertTrue(session.state().idle());}
        BuffInstance value(){return state().buffs().instances().values().iterator().next();}
        double count(){return value().components().numbers().get("count");}
        Set<String> shots(){return value().components().sets().get("shots");}
    }
    private record Contact(EffectState state,ProjectileFlight.Impact impact) {}
    private record Completion(EffectState state,List<RuleEngine.Signal> facts) {
        RuleEngine.Signal hit(){return facts.stream().filter(s->s.type().equals("chorus:hit")).findFirst().orElseThrow();}
        ShotGroups.Progress progress(){return facts.stream().map(RuleEngine.Signal::payload).filter(ShotGroups.Progress.class::isInstance)
                .map(ShotGroups.Progress.class::cast).findFirst().orElseThrow();}
    }
    /** Open the pellet through its actual geometry receipt before completing an observed damage write. */
    private static Contact open(EffectState state,ShotGroups.Handle shot,int pellet){
        var member=new ShotGroups.Member(shot,pellet);
        var query=new Hitscan.Query(Optional.of(START),Optional.of(new WorldDirection("world",1,0,0)),2,0,
                shot.origin().owner(),shot.origin().weapon(),Optional.of(member));
        var impact=new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,POINT,Optional.of("target"),0,0,0,
                0,1,0,1,1,true,Optional.of(member),Optional.of(new ProjectileFlight.Geometry(POINT,1,1,1,0)));
        var observed=new Hitscan.Result(query,Hitscan.Outcome.ENTITY,Optional.of(impact));
        return new Contact(ShotGroups.traced(ShotGroups.reserve(state,member),observed).state(),impact);
    }
    private static Completion hit(EffectState state,ShotGroups.Handle shot,int pellet){
        var opened=open(state,shot,pellet);
        var command=new DamageCommand("target",shot.origin(),1,"minecraft:generic",Set.of(),Set.of(),false);
        var receipt=new DamageReceipt(shot.reference()+"/hit/"+pellet,DamageReceipt.Outcome.APPLIED,0,0,1,Optional.empty(),false);
        var cause=new RuleEngine.Event(1,1,Optional.empty(),0,new RuleEngine.Signal("test:contact",RuleEngine.Empty.INSTANCE));
        var scope=new EffectSource("source",COUNTER,HOLDER,shot.origin(),Set.of());
        var context=new RuleEngine.Context(cause,"test:damage",scope,Map.of("contact",opened.impact()),
                new RuleEngine.OperationId(1,0,0),Map.of(),Optional.of(command));
        var action=new Action.DamageCaptured("captured",Evaluation.Target.VICTIM,Map.of(),Optional.of("contact"));
        var completed=action.complete(new Evaluation(opened.state(),context,Map.of(),Map.of()),receipt);
        var closed=ShotGroups.close(completed.state(),opened.impact());
        var facts=new ArrayList<>(completed.emitted());facts.addAll(closed.emitted());
        return new Completion(closed.state(),List.copyOf(facts));
    }
    private static String reference(RuleEngine.Signal signal){return EffectTimers.event(signal.payload()).orElseThrow().references().get("shot");}
    private static int oldHandles(JsonElement data,int version){
        int changed=0;
        if(data.isJsonArray()){
            for(var child:data.getAsJsonArray())changed+=oldHandles(child,version);
        }else if(data.isJsonObject()){
            var object=data.getAsJsonObject();
            if(object.has("$")&&object.get("$").getAsString().equals("chorus:effect/projectile/shot_groups/handle_v3")){
                object.addProperty("$",version==1?"chorus:effect/projectile/shot_groups/handle":"chorus:effect/projectile/shot_groups/handle_v2");
                var fields=object.getAsJsonObject("v");assertNotNull(fields.remove("referenceFormat"));
                if(version==1)assertNotNull(fields.remove("issuer"));
                changed++;
            }
            for(var child:object.entrySet())changed+=oldHandles(child.getValue(),version);
        }
        return changed;
    }

    @Test void actualDslCountsSameLocalShotIdOncePerIssuerRatherThanOncePerPellet(){
        var counter=new Counter();
        for(var shot:List.of(issued(A),issued(B))){
            var first=hit(ShotGroups.begin(counter.state(),shot).state(),shot,0);
            var second=hit(first.state(),shot,1);
            assertEquals(reference(first.hit()),reference(second.hit()));
            counter.accept(first.hit());double count=counter.count();
            counter.accept(second.hit());counter.accept(first.hit());assertEquals(count,counter.count());
        }
        assertEquals(2,counter.count());assertEquals(Set.of(issued(A).reference(),issued(B).reference()),counter.shots());
    }

    @Test void issuedReferencesIncludeEveryOriginFieldAndTheCompleteLedgerIdentity(){
        var handles=new ArrayList<>(List.of(issued(A),issued(B),
                ShotGroups.Handle.issued("other-id",ORIGIN,2,0,1_000_000,A),
                ShotGroups.Handle.issued("same-id",ORIGIN,3,0,1_000_000,A),
                ShotGroups.Handle.issued("same-id",ORIGIN,2,1,1_000_000,A),
                ShotGroups.Handle.issued("same-id",ORIGIN,2,0,1_000_001,A)));
        for(var origin:List.of(new BuffInstance.Origin("other",ORIGIN.source(),ORIGIN.weapon(),ORIGIN.ability(),ORIGIN.tags()),
                new BuffInstance.Origin(HOLDER,"other",ORIGIN.weapon(),ORIGIN.ability(),ORIGIN.tags()),
                new BuffInstance.Origin(HOLDER,ORIGIN.source(),"other",ORIGIN.ability(),ORIGIN.tags()),
                new BuffInstance.Origin(HOLDER,ORIGIN.source(),ORIGIN.weapon(),"other",ORIGIN.tags()),
                new BuffInstance.Origin(HOLDER,ORIGIN.source(),ORIGIN.weapon(),ORIGIN.ability(),Set.of("alpha"))))
            handles.add(ShotGroups.Handle.issued("same-id",origin,2,0,1_000_000,A));
        assertEquals(handles.size(),handles.stream().map(ShotGroups.Handle::reference).distinct().count());
        assertTrue(issued(A).reference().startsWith("shot-issued/"));
        assertNotEquals(legacy().reference(),issued(A).reference());
    }

    @Test void stringBoundariesAndTagCardinalityCannotCollideAndTagOrderIsCanonical(){
        assertNotEquals(ShotGroups.Handle.issued("b/c",ORIGIN,2,0,10,"test:a").reference(),
                ShotGroups.Handle.issued("c",ORIGIN,2,0,10,"test:a/b").reference());
        var left=new BuffInstance.Origin("a/b","c","d/e","f",Set.of("g/h","i"));
        var right=new BuffInstance.Origin("a","b/c","d","e/f",Set.of("g","h/i"));
        assertNotEquals(ShotGroups.Handle.issued("same",left,2,0,10,A).reference(),ShotGroups.Handle.issued("same",right,2,0,10,A).reference());
        var tags=new BuffInstance.Origin(HOLDER,"source","weapon","ability",Set.of("a/b"));
        var split=new BuffInstance.Origin(HOLDER,"source","weapon","ability",Set.of("a","b"));
        assertNotEquals(ShotGroups.Handle.issued("same",tags,2,0,10,A).reference(),ShotGroups.Handle.issued("same",split,2,0,10,A).reference());
        var reverse=new BuffInstance.Origin(HOLDER,"source","weapon","ability",new LinkedHashSet<>(List.of("omega","alpha")));
        assertEquals(issued(A).reference(),ShotGroups.Handle.issued("same-id",reverse,2,0,1_000_000,A).reference());
    }

    @Test void damageProgressAndFinalSummaryPublishTheSameReference(){
        for(var shot:List.of(legacy(),legacy().issuedBy(A),issued(A),issued(B))){
            var first=hit(ShotGroups.begin(initial(),shot).state(),shot,0);
            var second=hit(first.state(),shot,1);
            assertEquals(List.of("chorus:hit","chorus:damage_taken",ShotGroups.PROGRESS),first.facts().stream().map(RuleEngine.Signal::type).toList());
            assertEquals(List.of("chorus:hit","chorus:damage_taken",ShotGroups.PROGRESS,ShotGroups.RESOLVED),second.facts().stream().map(RuleEngine.Signal::type).toList());
            for(var step:List.of(first,second)){
                for(var fact:step.facts())assertEquals(shot.reference(),reference(fact));
                assertEquals(shot.reference(),step.progress().before().event().references().get("shot"));
                assertEquals(shot.reference(),step.progress().after().event().references().get("shot"));
                assertEquals(shot.origin(),step.progress().event().source());
            }
            assertEquals("0",first.progress().event().references().get("pellet"));
            assertEquals("1",second.progress().event().references().get("pellet"));
            var summary=(ShotGroups.Summary)second.facts().getLast().payload();
            assertEquals(shot,summary.shot());assertTrue(summary.complete());assertEquals(Map.of("target",2),summary.hits());
            assertEquals(summary.hits(),summary.effective());assertTrue(second.state().shotGroups().isEmpty());
            assertFalse(second.state().timers().containsKey(shot.timerId()));
        }
    }

    @Test void qualificationAndOldV1OrV2DataNeverChangeTheLegacyBareId(){
        var codec=EffectCheckpointCodec.DATA;
        assertEquals("same-id",legacy().reference());
        for(int version:List.of(1,2)){
            var original=version==1?legacy():legacy().issuedBy(A);
            var encoded=codec.encode(original);assertEquals(1,oldHandles(encoded,version));
            var restored=codec.decode(encoded,ShotGroups.Handle.class);
            assertEquals(original,restored);assertEquals(ShotGroups.ReferenceFormat.LEGACY,restored.referenceFormat());
            var qualified=restored.issuedBy(A).issuedBy(B);
            assertEquals(Optional.of(A),qualified.issuer());assertEquals(original.id(),qualified.reference());
            assertEquals(qualified,codec.decode(codec.encode(qualified),ShotGroups.Handle.class));
        }
        var current=issued(A);assertSame(current,current.issuedBy(B));
        assertEquals(current,codec.decode(codec.encode(current),ShotGroups.Handle.class));
    }

    @Test void oldQualifiedV2CheckpointDoesNotRecountTheNextActualPelletAfterRestore(){
        var old=legacy().issuedBy(A);
        var first=hit(ShotGroups.begin(initial(),old).state(),old,0);
        var before=new Counter(first.state());before.accept(first.hit());
        assertEquals(1,before.count());assertEquals(Set.of(old.id()),before.shots());
        assertEquals(ShotGroups.Phase.CLOSED,before.state().shotGroups().get(old.id()).pellets().get(0).phase());
        var encoded=EffectCheckpointCodec.encode(before.session.checkpoint());
        assertEquals(2,oldHandles(encoded,2),"The still-active ledger and its expiry timer both use the old qualified record shape");
        var after=new Counter(EffectCheckpointCodec.decode(encoded));
        var restored=after.state().shotGroups().get(old.id()).handle();
        assertEquals(old,restored);assertEquals(Optional.of(A),restored.issuer());
        assertEquals(ShotGroups.ReferenceFormat.LEGACY,restored.referenceFormat());
        assertEquals(Set.of(old.id()),after.shots());assertEquals(1,after.count());
        var second=hit(after.state(),restored,1);
        assertEquals(old.id(),reference(second.hit()));assertTrue(second.state().shotGroups().isEmpty());
        after.accept(second.hit());assertEquals(1,after.count());assertEquals(Set.of(old.id()),after.shots());
    }

    @Test void changingReferenceFormatCannotAliasAnExistingShotLedger(){
        var old=legacy().issuedBy(A);var current=issued(A);
        assertFalse(old.matches(current));assertFalse(current.matches(old));
        assertTrue(legacy().matches(old));assertTrue(old.matches(legacy()));
        assertFalse(current.matches(issued(B)));
        assertThrows(IllegalArgumentException.class,()->new ShotGroups.Handle("same-id",ORIGIN,2,0,1_000_000,
                Optional.empty(),ShotGroups.ReferenceFormat.ISSUED_V1));
        for(var canonical:List.of(old,current)){
            var alias=canonical==old?current:old;
            var state=ShotGroups.begin(initial(),canonical).state();
            assertTrue(ShotGroups.active(state,new ShotGroups.Member(canonical,0)));
            assertThrows(IllegalArgumentException.class,()->ShotGroups.active(state,new ShotGroups.Member(alias,0)));
            assertThrows(IllegalArgumentException.class,()->ShotGroups.reserve(state,new ShotGroups.Member(alias,0)));
            var opened=open(state,canonical,0);
            var contact=opened.impact().withMember(Optional.of(new ShotGroups.Member(alias,0)));
            assertThrows(IllegalArgumentException.class,()->ShotGroups.close(opened.state(),contact));
            var command=new DamageCommand("target",ORIGIN,1,"minecraft:generic",Set.of(),Set.of(),false);
            assertThrows(IllegalArgumentException.class,()->ShotGroups.validateDamage(opened.state(),contact,command));
        }
    }
}
