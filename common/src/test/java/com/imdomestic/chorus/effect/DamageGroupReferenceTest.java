package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Event identities must distinguish independent ledgers without rewriting already observed facts. */
class DamageGroupReferenceTest {
    private static final String A="test:a", B="test:b", HOLDER="player", COUNTER="test:counter";
    private static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin(HOLDER,"source","","");

    private static DamageGroups.Handle legacy(){return new DamageGroups.Handle("same-id",HOLDER,0,1_000_000);}
    private static DamageGroups.Handle issued(String domain){return DamageGroups.Handle.issued("same-id",HOLDER,0,1_000_000,domain);}
    private static RuleEngine.Signal hit(DamageGroups.Handle group,String id){
        var command=new DamageCommand("target",ORIGIN,1,"minecraft:generic",Set.of(),Set.of(),false).withGroup(group);
        var receipt=new DamageReceipt(id,DamageReceipt.Outcome.APPLIED,0,0,1,Optional.empty(),false);
        return DamageFacts.from(command,receipt,Map.of("attack_group","untrusted")).getFirst();
    }
    private static String reference(RuleEngine.Signal signal){return ((EffectEvent)signal.payload()).references().get("attack_group");}
    private static CompiledEffects program(){
        return compile(JsonParser.parseString("""
            {"version":"group-reference",
             "buffs":[{"definition":{"id":"test:counter","version":"group-reference","duration":10,
               "components":{"numbers":{"count":{"initial":0,"unit":"count"}},"sets":["groups"]}},
               "bundle":"test:counter"}],
             "bundles":[{"id":"test:counter","scope":"buff","rules":[{
               "id":"one-per-group","on":"chorus:hit",
               "if":{"type":"chorus:target_is","left":"event_actor","right":"self"},
               "do":[{"type":"chorus:update_component","buff":"test:counter","component":"count","op":"add",
                 "value":{"type":"chorus:constant","value":1,"unit":"count"},
                 "once_set":"groups","event_reference":"attack_group"}]}]}]}
            """).getAsJsonObject());
    }
    private static final class Counter {
        final CompiledEffects program=program();
        final EffectSession session;
        Counter(){this(Optional.empty());}
        Counter(Optional<DamageGroups.Handle> group){
            var state=EffectState.empty();
            state=state.withBuffs(Buffs.grant(state.buffs(),program.buff(COUNTER),HOLDER,HOLDER,ORIGIN,1,1,10_000_000).store());
            if(group.isPresent())state=DamageGroups.begin(state,group.orElseThrow()).state();
            session=new EffectSession(program,clock(),1,state,Counter::world);
        }
        Counter(EffectSession.Checkpoint checkpoint){session=EffectSession.restore(program,clock(),1,checkpoint,Counter::world);}
        private static EffectClock clock(){return new EffectClock((_,_)->new EffectClock.Rate(0,List.of()));}
        private static RuleEngine.ActionResult world(RuleEngine.WorldRequest request){throw new AssertionError("Counting facts must not call the world");}
        void accept(RuleEngine.Signal fact){session.start(0,fact);assertTrue(session.state().idle());}
        BuffInstance value(){return session.state().engine().domain().buffs().instances().values().iterator().next();}
        double count(){return value().components().numbers().get("count");}
        Set<String> groups(){return value().components().sets().get("groups");}
    }
    private static EffectContinuations.Pending pending(DamageGroups.Handle group,RuleEngine.Signal historical){
        var scope=new EffectSource("source",COUNTER,HOLDER,ORIGIN,Set.of());
        var cause=new RuleEngine.Event(1,1,Optional.empty(),0,historical);
        return new EffectContinuations.Pending("pending","test:body","group-reference",scope,cause,
                Map.of("group",group),Optional.of(EffectTimers.Owner.of(scope)));
    }
    private static EffectContinuations.Pending move(EffectContinuations.Pending pending,String source,long clock){
        return pending.relocated("moved/"+clock,pending.scope(),pending.owner().orElseThrow(),clock,Optional.of(source),true);
    }
    private static DamageGroups.Handle group(EffectContinuations.Pending pending){return (DamageGroups.Handle)pending.bindings().get("group");}
    /** Reproduce the exact previously written record shape, including handles nested in a ledger and its timer. */
    private static int oldHandles(JsonElement data,int version){
        int changed=0;
        if(data.isJsonArray()){
            for(var child:data.getAsJsonArray())changed+=oldHandles(child,version);
        }else if(data.isJsonObject()){
            var object=data.getAsJsonObject();
            if(object.has("$")&&object.get("$").getAsString().equals("chorus:effect/combat/damage_groups/handle_v3")){
                object.addProperty("$",version==1?"chorus:effect/combat/damage_groups/handle":"chorus:effect/combat/damage_groups/handle_v2");
                var fields=object.getAsJsonObject("v");assertNotNull(fields.remove("referenceFormat"));
                if(version==1)assertNotNull(fields.remove("issuer"));
                changed++;
            }
            for(var child:object.entrySet())changed+=oldHandles(child.getValue(),version);
        }
        return changed;
    }

    @Test void actualDslCountsSameOwnerAndLocalIdOncePerIssuerButNotOncePerHit(){
        var a=issued(A);var b=issued(B);var counter=new Counter();
        var first=hit(a,"a/first");var second=hit(a,"a/second");var independent=hit(b,"b/first");
        assertEquals(reference(first),reference(second));assertNotEquals(reference(first),reference(independent));
        counter.accept(first);assertEquals(1,counter.count());
        counter.accept(second);assertEquals(1,counter.count());
        counter.accept(independent);assertEquals(2,counter.count());
        counter.accept(hit(b,"b/second"));assertEquals(2,counter.count());
        assertEquals(Set.of(a.reference(),b.reference()),counter.groups());
    }

    @Test void qualifiedReferencesIncludeTheFullIdentityAndUnambiguousStringBoundaries(){
        var baseline=issued(A);
        var references=List.of(baseline,issued(B),
                DamageGroups.Handle.issued("other-id",HOLDER,0,1_000_000,A),
                DamageGroups.Handle.issued("same-id","other-owner",0,1_000_000,A),
                DamageGroups.Handle.issued("same-id",HOLDER,1,1_000_000,A),
                DamageGroups.Handle.issued("same-id",HOLDER,0,1_000_001,A));
        assertEquals(references.size(),references.stream().map(DamageGroups.Handle::reference).distinct().count());
        var left=DamageGroups.Handle.issued("c/d","b",0,10,"test:a");
        var right=DamageGroups.Handle.issued("d","c",0,10,"test:a/b");
        assertNotEquals(left.reference(),right.reference());
        assertEquals("group/6:player/same-id",legacy().reference());
        assertTrue(baseline.reference().startsWith("group-issued/"));assertNotEquals(legacy().reference(),baseline.reference());
    }

    @Test void qualifiedIdentitySurvivesDataRoundTripsAndReturnTransfers(){
        var original=issued(A);var saved=pending(original,hit(original,"before"));
        var outward=move(saved,A,50);var codec=EffectCheckpointCodec.DATA;
        var restored=codec.decode(codec.encode(outward),EffectContinuations.Pending.class);
        var returned=move(restored,B,10);var finalCopy=codec.decode(codec.encode(returned),EffectContinuations.Pending.class);
        assertEquals(original,group(finalCopy));assertEquals(original.reference(),group(finalCopy).reference());
        assertEquals(original.reference(),reference(hit(group(finalCopy),"after")));
        assertEquals(saved.cause(),finalCopy.cause());assertEquals(10,finalCopy.causalTime());
        assertEquals(original,codec.decode(codec.encode(original),DamageGroups.Handle.class));
    }

    @Test void firstQualificationDoesNotRewriteLegacyCauseOrExistingDslDeduplicationSets(){
        var legacy=legacy();var historical=hit(legacy,"legacy-hit");var counter=new Counter();counter.accept(historical);
        var saved=pending(legacy,historical);var moved=move(saved,A,50);
        assertSame(saved.cause(),moved.cause());assertEquals(legacy.issuedBy(A),group(moved));
        assertEquals(legacy.reference(),reference(moved.cause().signal()));
        assertEquals(legacy.reference(),reference(hit(group(moved),"qualified-hit")));
        var codec=EffectCheckpointCodec.DATA;
        var restored=codec.decode(codec.encode(moved),EffectContinuations.Pending.class);
        var oldCounter=codec.decode(codec.encode(counter.value()),BuffInstance.class);
        assertEquals(saved.cause(),restored.cause());assertEquals(legacy.reference(),reference(restored.cause().signal()));
        assertEquals(Set.of(legacy.reference()),oldCounter.components().sets().get("groups"));
        assertEquals(Set.of(legacy.reference()),counter.groups());assertEquals(1,counter.count());
        counter.accept(hit(group(restored),"after-move"));assertEquals(1,counter.count());
    }

    @Test void oldQualifiedV2CheckpointKeepsOnceOnlyFactsAfterActualRestore(){
        var old=legacy().issuedBy(A);var before=new Counter(Optional.of(old));before.accept(hit(old,"before-save"));
        assertEquals(1,before.count());assertEquals(Set.of(old.reference()),before.groups());
        var encoded=EffectCheckpointCodec.encode(before.session.checkpoint());
        assertEquals(2,oldHandles(encoded,2),"The saved ledger and expiry timer both contain the old qualified handle");
        var after=new Counter(EffectCheckpointCodec.decode(encoded));
        var restored=after.session.state().engine().domain().damageGroups().get(old.id()).handle();
        assertEquals(old,restored);assertEquals(Optional.of(A),restored.issuer());
        assertEquals(DamageGroups.ReferenceFormat.LEGACY,restored.referenceFormat());
        assertEquals(Set.of(old.reference()),after.groups());
        after.accept(hit(restored,"after-restore"));assertEquals(1,after.count());
        assertEquals(Set.of(old.reference()),after.groups());
    }

    @Test void oldV1AndV2ReferencesRemainStableAcrossQualificationAndReturnTransfers(){
        var codec=EffectCheckpointCodec.DATA;
        for(int version:List.of(1,2)){
            var original=version==1?legacy():legacy().issuedBy(A);
            var encoded=codec.encode(pending(original,hit(original,"old-cause")));
            assertEquals(1,oldHandles(encoded,version));
            var restored=codec.decode(encoded,EffectContinuations.Pending.class);
            assertEquals(original,group(restored));
            var outward=move(restored,A,50);var returned=move(outward,B,10);
            var finalCopy=codec.decode(codec.encode(returned),EffectContinuations.Pending.class);
            assertEquals(Optional.of(A),group(finalCopy).issuer());
            assertEquals(DamageGroups.ReferenceFormat.LEGACY,group(finalCopy).referenceFormat());
            assertEquals(original.reference(),group(finalCopy).reference());
            assertEquals(restored.cause(),finalCopy.cause());
            assertEquals(original.reference(),reference(hit(group(finalCopy),"after-return")));
        }
    }

    @Test void changingReferenceFormatCannotAliasAnExistingLedger(){
        var old=legacy().issuedBy(A);var current=issued(A);
        assertFalse(old.matches(current));assertFalse(current.matches(old));
        assertTrue(legacy().matches(old));assertTrue(old.matches(legacy()));
        assertThrows(IllegalArgumentException.class,()->new DamageGroups.Handle("same-id",HOLDER,0,1_000_000,
                Optional.empty(),DamageGroups.ReferenceFormat.ISSUED_V1));
        for(var canonical:List.of(old,current)){
            var alias=canonical==old?current:old;
            var state=DamageGroups.begin(EffectState.empty(),canonical).state();
            assertThrows(IllegalArgumentException.class,()->DamageGroups.require(state,alias));
            assertThrows(IllegalArgumentException.class,()->DamageGroups.close(state,alias));
            var compiled=program();
            var view=new ResourceReadView(new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(A,state)),A,compiled.program());
            var command=new DamageCommand("target",ORIGIN,1,"minecraft:generic",Set.of(),Set.of(),false).withGroup(alias);
            assertThrows(IllegalArgumentException.class,()->compiled.prepareDamage(state,command,Optional.of(view)));
            assertEquals(canonical,DamageGroups.require(state,canonical).handle());
        }
    }
}
