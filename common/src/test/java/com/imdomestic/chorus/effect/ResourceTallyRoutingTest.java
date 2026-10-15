package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real DSL continuations address the issuing tally after their owning character changes domains. */
class ResourceTallyRoutingTest {
    static final String A="test:a",B="test:b",C="test:c",H="player";
    static String holder(String domain){return domain.equals(A)?H:"other/"+domain;}
    static EffectSource source(String holder){return new EffectSource("root/"+holder,"test:driver",holder,new BuffInstance.Origin(holder,"root/"+holder,"",""),Set.of());}
    static JsonObject data(){return JsonParser.parseString("""
        {"version":"routed-tally","resources":[{"id":"test:energy","capacity":1,"initial":1}],
         "bundles":[{"id":"test:driver","rules":[{"id":"start","on":"test:start","do":[
           {"action":{"type":"chorus:begin_damage_tally","duration":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"tally"},
           {"action":{"type":"chorus:damage","amount":{"type":"chorus:constant","value":3,"unit":"damage"},"damage_type":"minecraft:generic"},"as":"first"},
           {"type":"chorus:record_damage","tally":"tally","damage":"first"},
           {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"do":[
             {"action":{"type":"chorus:damage","amount":{"type":"chorus:constant","value":7,"unit":"damage"},"damage_type":"minecraft:generic"},"as":"second"},
             {"type":"chorus:record_damage","tally":"tally","damage":"second"},
             {"type":"chorus:record_damage","tally":"tally","damage":"second"},
             {"action":{"type":"chorus:read_damage_tally","tally":"tally"},"as":"current"},
             {"type":"chorus:heal","amount":{"type":"chorus:result","binding":"current","field":"health_loss"}},
             {"action":{"type":"chorus:close_damage_tally","tally":"tally"},"as":"closed"},
             {"type":"chorus:heal","amount":{"type":"chorus:scale","factor":1,"from":"count","to":"damage","of":{"type":"chorus:result","binding":"closed","field":"attempts"}}},
             {"action":{"type":"chorus:read_damage_tally","tally":"tally"},"as":"gone"},
             {"type":"chorus:heal","amount":{"type":"chorus:result","binding":"gone","field":"health_loss"}}
           ]}
         ]}]}]}
        """).getAsJsonObject();}
    static final class Fixture {
        final CompiledEffects program=EffectTestSupport.compile(data()).withResourceRouting();
        final Map<String,EffectSession> sessions=new TreeMap<>();
        final Map<String,List<Double>> healed=new TreeMap<>();
        final Map<String,Integer> damageCalls=new TreeMap<>();
        ResourceSessionAuthority host;int repeatedOperations;boolean failAfterRemoteRecord;
        Fixture(){
            for(var domain:List.of(A,B,C)){
                String holder=holder(domain);healed.put(domain,new ArrayList<>());damageCalls.put(domain,0);
                var state=EffectState.empty().withSource(source(holder)).withResource(new ResourceState(new ResourceState.Key(holder,"test:energy"),1,1,0));
                sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,state,r->execute(domain,r)));
            }
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((d,s)->bindings.put(d,new ResourceSessionAuthority.Binding(program,s)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);
            for(var domain:List.of(A,B,C))host.claim(holder(domain),domain);
            for(var domain:List.of(A,B,C)){
                var origin=source(holder(domain)).origin();sessions.get(domain).start(0,new RuleEngine.Signal("test:start",new EffectEvent(holder(domain),"victim",origin,Set.of(),Map.of())));
            }
            assertEquals(handle(A).id(),handle(B).id());assertEquals(handle(A).id(),handle(C).id());
            assertEquals(handle(A).startedAt(),handle(B).startedAt());assertEquals(handle(A).dueAt(),handle(B).dueAt());
            sessions.get(A).observe(50_000,List.of());
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request){
            if(request.command() instanceof ResourceRequests.Request){
                var actual=host.execute(domain,request);var after=new TreeMap<String,EffectState>();sessions.keySet().forEach(d->after.put(d,state(d)));
                assertSame(actual,host.execute(domain,request),"active operation must return its committed receipt");
                after.forEach((d,state)->assertSame(state,state(d),"repeated operation republished a domain"));repeatedOperations++;
                if(failAfterRemoteRecord&&!domain.equals(A)&&state(A).damageTallies().values().stream().anyMatch(e->e.receipts().size()==2))
                    throw new IllegalStateException("unknown after remote tally record");
                return actual;
            }
            return world(domain,request);
        }
        RuleEngine.ActionResult world(String domain,RuleEngine.WorldRequest request){return switch(request.command()){
            case DamageCommand damage->{
                damageCalls.merge(domain,1,Integer::sum);double loss=damage.amount()==3?(domain.equals(A)?2:domain.equals(B)?9:14):7;
                yield new DamageReceipt(domain+"/"+request.id(),DamageReceipt.Outcome.APPLIED,0,0,loss,Optional.empty(),false);
            }
            case HealingCommand heal->{healed.get(domain).add(heal.amount());yield new HealingReceipt(domain+"/"+request.id(),heal,HealingReceipt.Outcome.APPLIED,heal.amount(),heal.amount(),0);}
            default->throw new AssertionError(request.command());
        };}
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        DamageTallies.Handle handle(String domain){return state(domain).damageTallies().values().iterator().next().handle();}
        EffectContinuations.Pending pending(String domain,String holder){return state(domain).timers().values().stream().map(EffectState.Timer::signal).map(RuleEngine.Signal::payload)
                .filter(EffectContinuations.Pending.class::isInstance).map(EffectContinuations.Pending.class::cast)
                .filter(p->p.scope() instanceof EffectSource source&&source.holder().equals(holder)).findFirst().orElseThrow();}
        DamageTallies.Handle captured(String domain){return pending(domain,H).bindings().values().stream().filter(DamageTallies.Handle.class::isInstance).map(DamageTallies.Handle.class::cast).findFirst().orElseThrow();}
        void move(String from,String to){host.commit(host.prepareCharacterTransfer(H,from,to,Set.of()));}
        void due(String domain){sessions.get(domain).observe(50_000,List.of());assertFalse(host.drain(100).pending());}
        void assertCompleted(String domain){
            assertEquals(List.of(9.,2.,0.),healed.get(domain));assertTrue(state(A).damageTallies().isEmpty());
            assertEquals(9,DamageTallies.read(state(B),handle(B)).numbers().healthLoss());
            assertEquals(14,DamageTallies.read(state(C),handle(C)).numbers().healthLoss());
        }
        void restore(){
            var saved=host.checkpoint();var archive=ResourceCheckpointTest.archive(saved);
            var decoded=CheckpointArchive.decode(JsonParser.parseString(archive.encode().toString()));assertEquals(archive,decoded);
            var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            for(var domain:List.of(A,B,C))policies.put(domain,new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,r->execute(domain,r)));
            var restored=ResourceSessionAuthority.restore(decoded.resourceCheckpoint().orElseThrow(),policies);
            host=restored.authority();sessions.clear();restored.bindings().forEach((d,b)->sessions.put(d,b.session()));assertEquals(saved,host.checkpoint());
        }
    }
    @Test void migratedDslRecordsReadsAndClosesTheIssuerWithoutTouchingAnIdenticalDestinationId(){
        var f=new Fixture();var original=f.handle(A);var before=f.host.checkpoint();
        var prepared=f.host.prepareCharacterTransfer(H,A,B,Set.of());assertEquals(before,f.host.checkpoint());f.host.commit(prepared);
        assertEquals(Optional.of(A),f.captured(B).issuer());assertEquals(original,f.handle(A));
        f.due(B);f.assertCompleted(B);assertEquals(1,f.damageCalls.get(A));assertEquals(2,f.damageCalls.get(B));
    }
    @Test void threeDomainHandoffsKeepTheFirstIssuerAndEveryOtherLedger(){
        var f=new Fixture();var original=f.handle(A);f.move(A,B);f.move(B,C);
        assertEquals(original,f.captured(C));assertEquals(Optional.of(A),f.captured(C).issuer());
        assertEquals(C,f.host.snapshot().directory().owner(H).lease().domain());f.due(C);f.assertCompleted(C);
    }
    @Test void issuerClockExpiryRejectsLateRecordsDespiteTheDestinationClockStillBeingEarlier(){
        var f=new Fixture();var original=f.handle(A);f.move(A,B);f.sessions.get(A).observe(original.dueAt(),List.of());
        assertTrue(f.state(A).damageTallies().isEmpty());assertEquals(0,f.state(B).buffs().timeMicros());
        f.due(B);assertEquals(List.of(0.,0.,0.),f.healed.get(B));assertTrue(f.state(A).damageTallies().isEmpty());
        assertEquals(9,DamageTallies.read(f.state(B),f.handle(B)).numbers().healthLoss());
    }
    @Test void repeatedActiveOperationReceiptsAndRepeatedDamageEvidenceDoNotCountTheRecordTwice(){
        var f=new Fixture();int before=f.repeatedOperations;f.move(A,B);f.due(B);
        assertTrue(f.repeatedOperations>before);f.assertCompleted(B);
        assertEquals(2,f.damageCalls.get(B),"one initial world damage and one migrated world damage");
    }
    @Test void jointArchiveRestoresTheForeignHandleAndOriginalLedgerWithoutReplayingWorldEffects(){
        var f=new Fixture();f.move(A,B);var original=f.captured(B);var calls=Map.copyOf(f.damageCalls);f.restore();
        assertEquals(calls,f.damageCalls);assertEquals(original,f.captured(B));assertEquals(original,f.handle(A));
        f.move(B,C);f.due(C);f.assertCompleted(C);f.sessions.get(C).observe(60_000,List.of());f.assertCompleted(C);
    }
    @Test void unknownCommittedRemoteRecordRetainsIssuerChangesAndCannotReplayOrClose(){
        var f=new Fixture();f.move(A,B);f.failAfterRemoteRecord=true;
        assertThrows(IllegalStateException.class,()->f.due(B));
        var summary=DamageTallies.read(f.state(A),f.handle(A)).numbers();assertEquals(2,summary.attempts());assertEquals(9,summary.healthLoss());
        assertEquals(9,DamageTallies.read(f.state(B),f.handle(B)).numbers().healthLoss());assertTrue(f.healed.get(B).isEmpty());
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,()->f.sessions.get(B).observe(60_000,List.of()));
        assertEquals(2,f.damageCalls.get(B));assertEquals(summary,DamageTallies.read(f.state(A),f.handle(A)).numbers());
    }
    @Test void aLocalDslSessionCannotApplyAForeignQualifiedHandleToItsSameIdLedger(){
        var f=new Fixture();var foreign=f.handle(A);var state=f.state(B);
        for(var timer:List.copyOf(state.timers().values()))if(timer.signal().payload() instanceof EffectContinuations.Pending pending){
            var bindings=new TreeMap<>(pending.bindings());bindings.replaceAll((_,value)->value instanceof DamageTallies.Handle?foreign:value);
            var changed=new EffectContinuations.Pending(pending.id(),pending.definition(),pending.version(),pending.scope(),pending.cause(),bindings,pending.owner(),pending.relocatedAt());
            state=state.cancel(timer.id()).schedule(new EffectState.Timer(timer.id(),timer.dueAt(),timer.intervalMicros(),timer.remaining(),new RuleEngine.Signal(timer.signal().type(),changed),timer.lifetime(),timer.pausedRemaining()));
        }
        var before=state.damageTallies();var local=new EffectSession(new CompiledEffects(f.program.program()),EffectCheckpointTest.clock(),1,state,r->f.world(B,r));
        assertThrows(IllegalStateException.class,()->local.observe(100_000,List.of()));
        assertEquals(before,local.state().engine().domain().damageTallies());assertTrue(f.healed.get(B).isEmpty());
        assertEquals(2,DamageTallies.read(f.state(A),foreign).numbers().healthLoss());
    }
}
