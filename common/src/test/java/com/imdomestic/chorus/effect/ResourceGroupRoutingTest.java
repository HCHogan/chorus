package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Issuer-addressed DSL group ledgers; local combat does not infer character ownership from resources. */
class ResourceGroupRoutingTest {
    static final String A="test:a",B="test:b",C="test:c",H="player",VICTIM="target";
    static final EffectSource SOURCE=EffectTestSupport.source("test:control");
    static final ResourceState.Key ENERGY=new ResourceState.Key(H,"test:energy");
    static JsonObject data(boolean combat)throws Exception{
        var data=EffectTestSupport.json("damage_group");
        data.add("resources",JsonParser.parseString("[{\"id\":\"test:energy\",\"capacity\":1,\"initial\":1}]"));
        var rules=JsonParser.parseString("""
          [{"id":"start","on":"test:start","do":[
            {"action":{"type":"chorus:begin_damage_group","lifetime":{"type":"chorus:constant","value":5,"unit":"second"}},"as":"group"},
            {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"do":[
              {"type":"chorus:damage","target":"victim","amount":{"type":"chorus:constant","value":10,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"test:melee","tags":["test:melee"],"group":"group"},
              {"type":"chorus:damage","target":"victim","amount":{"type":"chorus:constant","value":10,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"test:melee","tags":["test:melee"],"group":"group"},
              {"type":"chorus:damage","target":"victim","amount":{"type":"chorus:constant","value":10,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"test:melee","tags":["test:melee"]},
              {"type":"chorus:end_damage_group","group":"group"}
            ]}
          ]},{"id":"history","on":"chorus:hit","if":{"type":"chorus:event_has_buff","buff":"test:next_hit","target":"source_owner"},"do":[
            {"type":"chorus:play_cue","cue":"test:historical"},
            {"if":{"type":"chorus:not","of":{"type":"chorus:has_buff","buff":"test:next_hit","target":"source_owner","match":"any"}},"then":[{"type":"chorus:play_cue","cue":"test:consumed-before-fact"}]}
          ]}]
          """).getAsJsonArray();
        if(!combat){var body=rules.get(0).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("do");for(int i=0;i<3;i++)body.remove(0);}
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().add("rules",rules);return data;
    }
    record Completion(DamageCommand command,DamageReceipt raw,DamageReceipt result,
            Map<String,EffectState> states,Map<String,List<RuleEngine.Event>> facts){}
    static final class Fixture {
        final CompiledEffects program;final Map<String,EffectSession> sessions=new TreeMap<>();
        final List<Double> amounts=new ArrayList<>();final List<DamageCommand> damages=new ArrayList<>();
        final List<Completion> completions=new ArrayList<>();final List<String> cues=new ArrayList<>();
        final List<EffectHandleRequests.Receipt> handles=new ArrayList<>();
        ResourceSessionAuthority host;int repeatedOperations,repeatedFinishes;boolean unknownClose;
        Fixture()throws Exception{this(false);}
        Fixture(boolean combat)throws Exception{
            program=EffectTestSupport.compile(data(combat)).withResourceRouting();
            for(String domain:List.of(A,B,C)){
                var state=EffectState.empty().withSource(SOURCE);
                if(domain.equals(C)){
                    state=state.withResource(new ResourceState(ENERGY,1,1,0));
                    if(combat)state=state.withBuffs(Buffs.grant(state.buffs(),program.buff("test:next_hit"),H,H,SOURCE.origin(),1,1,3_000_000).store());
                }
                sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,state,r->execute(domain,r)));
            }
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((d,s)->bindings.put(d,new ResourceSessionAuthority.Binding(program,s)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);host.claim(H,C);host.claim(VICTIM,C);
            for(String domain:List.of(A,B,C))sessions.get(domain).start(0,new RuleEngine.Signal("test:start",new EffectEvent(H,VICTIM,SOURCE.origin(),Set.of(),Map.of())));
            assertEquals(handle(A).id(),handle(B).id());assertEquals(handle(A).id(),handle(C).id());
            for(String domain:List.of(A,B,C))assertEquals(Optional.of(domain),handle(domain).issuer());
            if(!combat)capture(handle(A));
        }
        void publish(String domain,EffectState changed){var session=sessions.get(domain);var before=session.coordinationState();EffectSession.commitDomains(List.of(session.prepareInboxDomain(before,changed,List.of())));}
        void capture(DamageGroups.Handle handle){
            // Explicit fixture input; no character transfer or relaxed continuation migration is involved.
            var changed=state(C);
            for(var timer:List.copyOf(changed.timers().values()))if(timer.signal().payload() instanceof EffectContinuations.Pending pending){
                var values=new TreeMap<>(pending.bindings());values.replaceAll((_,value)->value instanceof DamageGroups.Handle?handle:value);
                var captured=new EffectContinuations.Pending(pending.id(),pending.definition(),pending.version(),pending.scope(),pending.cause(),values,pending.owner(),pending.relocatedAt());
                changed=changed.cancel(timer.id()).schedule(new EffectState.Timer(timer.id(),timer.dueAt(),timer.intervalMicros(),timer.remaining(),new RuleEngine.Signal(timer.signal().type(),captured),timer.lifetime(),timer.pausedRemaining()));
            }
            publish(C,changed);
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request){
            if(request.command() instanceof ResourceRequests.Request){
                var before=new TreeMap<String,List<RuleEngine.Event>>();sessions.forEach((d,s)->before.put(d,List.copyOf(s.state().engine().facts())));
                var actual=host.execute(domain,request);
                var after=new TreeMap<String,com.imdomestic.chorus.rule.TimelineEngine.State<EffectState>>();sessions.forEach((d,s)->after.put(d,s.state()));
                assertSame(actual,host.execute(domain,request));after.forEach((d,state)->assertSame(state,sessions.get(d).state(),"replayed active request republished state or facts"));repeatedOperations++;
                if(actual instanceof ResourceRoutes.Receipt route&&route.primitive().orElse(null) instanceof EffectHandleRequests.Receipt handle){
                    handles.add(handle);if(unknownClose&&handle instanceof EffectHandleRequests.GroupClosed)throw new IllegalStateException("unknown after committed group close");
                }
                if(request.command() instanceof ActionRoutes.Finish finish&&finish.command() instanceof DamageCommand damage){
                    var raw=(DamageReceipt)finish.actual();assertTrue(raw.observedBuffs().isEmpty());assertFalse(raw.consumptionSettled());
                    var result=(DamageReceipt)((ActionRoutes.Completed)actual).value();var states=new TreeMap<String,EffectState>();var facts=new TreeMap<String,List<RuleEngine.Event>>();
                    sessions.forEach((d,s)->{states.put(d,state(d));facts.put(d,s.state().engine().facts().stream().filter(e->!before.get(d).contains(e)).toList());});
                    completions.add(new Completion(damage,raw,result,Map.copyOf(states),Map.copyOf(facts)));repeatedFinishes++;
                }
                return actual;
            }
            if(request.command() instanceof DamageCommand damage){
                assertEquals(C,domain);damages.add(damage);double amount=program.outgoing(host.readView(domain,request),damage,damage.amount()).orElseThrow().output().value();amounts.add(amount);
                return new DamageReceipt(domain+"/"+request.id(),DamageReceipt.Outcome.APPLIED,0,0,amount,Optional.empty(),false);
            }
            if(request.command() instanceof Action.CueCommand cue){cues.add(domain+"/"+cue.cue());return RuleEngine.Empty.INSTANCE;}
            throw new AssertionError(request.command());
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        DamageGroups.Handle handle(String domain){return state(domain).damageGroups().values().iterator().next().handle();}
        void run(){sessions.get(C).observe(100_000,List.of());assertFalse(host.drain(100).pending());}
        void restore(){
            var saved=host.checkpoint();var archive=ResourceCheckpointTest.archive(saved);var decoded=CheckpointArchive.decode(JsonParser.parseString(archive.encode().toString()));assertEquals(archive,decoded);
            var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            for(String domain:List.of(A,B,C))policies.put(domain,new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,r->execute(domain,r)));
            var restored=ResourceSessionAuthority.restore(decoded.resourceCheckpoint().orElseThrow(),policies);host=restored.authority();sessions.clear();restored.bindings().forEach((d,b)->sessions.put(d,b.session()));assertEquals(saved,host.checkpoint());
        }
    }
    static int stacks(EffectState state){return state.buffs().instances().values().stream().mapToInt(BuffInstance::count).sum();}
    static List<RuleEngine.Event> buffFacts(Completion value,String domain){return value.facts().get(domain).stream().filter(e->e.signal().payload() instanceof Buffs.Change).toList();}
    static List<EffectEvent> damageFacts(Completion value,String domain){return value.facts().get(domain).stream()
            .filter(e->Set.of("chorus:hit","chorus:damage_taken").contains(e.signal().type())).map(e->(EffectEvent)e.signal().payload()).toList();}
    static void assertObservation(Completion completion,int before,int after){
        assertEquals(after,stacks(completion.states().get(C)));var observed=completion.result().observedBuffs().orElseThrow();
        assertTrue(observed.available(H));assertTrue(observed.available(VICTIM));assertEquals(before,observed.require(H).stream().mapToDouble(BuffObservation.Entry::stacks).sum());
        var facts=damageFacts(completion,C);assertEquals(2,facts.size());for(var fact:facts)assertEquals(Optional.of(observed),fact.observedBuffs());
    }

    @Test void dslBeginsQualifiedGroupsAndClosesOnlyTheForeignIssuerDespiteIdenticalIds()throws Exception{
        var f=new Fixture();var b=f.state(B);var cGroup=f.state(C).damageGroups();int initial=f.repeatedOperations;f.run();
        assertTrue(f.state(A).damageGroups().isEmpty());assertFalse(f.state(A).timers().containsKey(f.handle(B).timerId()));assertSame(b,f.state(B));assertEquals(cGroup,f.state(C).damageGroups());
        assertTrue(f.damages.isEmpty());assertTrue(f.repeatedOperations>initial);
        assertEquals(3,f.handles.stream().filter(EffectHandleRequests.GroupStarted.class::isInstance).count());
        var closed=f.handles.stream().filter(EffectHandleRequests.GroupClosed.class::isInstance).map(EffectHandleRequests.GroupClosed.class::cast).toList();
        assertEquals(1,closed.size());assertEquals(A,closed.getFirst().request().issuer());
    }

    @Test void issuerExpiryAndRepeatedCloseCannotRemoveCallerOrNeighborGroups()throws Exception{
        var f=new Fixture();long due=f.handle(A).dueAt();var a=f.state(A);
        for(var timer:List.copyOf(a.timers().values()))if(timer.signal().payload() instanceof EffectContinuations.Pending)a=a.cancel(timer.id());
        f.publish(A,a);f.sessions.get(A).observe(due,List.of());assertTrue(f.state(A).damageGroups().isEmpty());assertEquals(0,f.state(C).buffs().timeMicros());
        var b=f.state(B).damageGroups();var c=f.state(C).damageGroups();f.run();assertTrue(f.state(A).damageGroups().isEmpty());
        assertEquals(b,f.state(B).damageGroups());assertEquals(c,f.state(C).damageGroups());assertTrue(f.damages.isEmpty());
    }

    @Test void jointArchiveKeepsForeignCapturedGroupAndItsOriginalLedgerWithoutReissuingBegin()throws Exception{
        var f=new Fixture();var a=f.handle(A);var b=f.state(B).damageGroups();var c=f.state(C).damageGroups();int receipts=f.handles.size();f.restore();
        assertEquals(receipts,f.handles.size());assertEquals(a,f.handle(A));f.run();assertTrue(f.state(A).damageGroups().isEmpty());
        assertEquals(b,f.state(B).damageGroups());assertEquals(c,f.state(C).damageGroups());assertEquals(receipts+1,f.handles.size());
    }

    @Test void unknownCloseAcknowledgementKeepsTheCommittedIssuerDeletionAndCannotReplay()throws Exception{
        var f=new Fixture();var b=f.state(B).damageGroups();var c=f.state(C).damageGroups();f.unknownClose=true;assertThrows(IllegalStateException.class,f::run);
        assertTrue(f.state(A).damageGroups().isEmpty());assertEquals(b,f.state(B).damageGroups());assertEquals(c,f.state(C).damageGroups());
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,()->f.sessions.get(C).observe(200_000,List.of()));
        assertEquals(1,f.handles.stream().filter(EffectHandleRequests.GroupClosed.class::isInstance).count());assertTrue(f.damages.isEmpty());
    }

    @Test void localConsumptionAndEventBuffHistorySurviveAResourceOnlyTransfer()throws Exception{
        for(boolean move:List.of(false,true)){
            var f=new Fixture(true);var buff=f.state(C).buffs();
            if(move){f.host.commit(f.host.prepareTransfer(H,B));assertEquals(B,f.host.snapshot().directory().owner(H).lease().domain());assertEquals(buff,f.state(C).buffs());assertTrue(f.state(C).resources().isEmpty());assertEquals(1,f.state(B).resources().get(ENERGY).value());}
            f.run();assertEquals(List.of(25.,25.,10.),f.amounts);assertEquals(0,stacks(f.state(C)));assertTrue(f.state(B).buffs().instances().isEmpty());
            assertObservation(f.completions.get(0),1,0);assertObservation(f.completions.get(1),0,0);assertObservation(f.completions.get(2),0,0);
            assertEquals(1,f.damages.getFirst().consumptions().size());assertTrue(f.damages.get(1).consumptions().isEmpty());assertTrue(f.damages.get(2).consumptions().isEmpty());
            assertEquals(List.of(C+"/test:historical",C+"/test:consumed-before-fact"),f.cues);assertEquals(3,f.repeatedFinishes);
            assertFalse(buffFacts(f.completions.getFirst(),C).isEmpty());assertTrue(buffFacts(f.completions.get(1),C).isEmpty());
            for(var completion:f.completions){assertTrue(completion.facts().get(A).isEmpty());assertTrue(completion.facts().get(B).isEmpty());}
        }
    }

    @Test void defaultDslConsumesOnlyTheCurrentBuffOwnerAndRetainsTheForeignIssuerGroup()throws Exception{
        var f=new Fixture(true);var issuer=f.handle(A);var local=f.handle(C);var neighbor=f.handle(B);f.capture(issuer);f.run();
        assertEquals(List.of(25.,25.,10.),f.amounts);assertEquals(0,stacks(f.state(C)));
        assertEquals(3,f.completions.size());assertEquals(1,f.completions.getFirst().states().get(A).damageGroups().get(issuer.id()).grants().size());
        assertTrue(f.completions.getFirst().states().get(C).damageGroups().get(local.id()).grants().isEmpty());
        assertTrue(f.completions.getFirst().states().get(B).damageGroups().get(neighbor.id()).grants().isEmpty());
        assertTrue(f.state(A).damageGroups().isEmpty());assertEquals(local,f.handle(C));assertEquals(neighbor,f.handle(B));
        assertObservation(f.completions.getFirst(),1,0);assertObservation(f.completions.get(1),0,0);
        assertEquals(List.of(C+"/test:historical",C+"/test:consumed-before-fact"),f.cues);
        assertEquals(3,f.repeatedFinishes);assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void groupRequestsAndReceiptsRoundTripWithTheirExactQualifiedIssuer(){
        var handle=new DamageGroups.Handle("same-id",H,0,5_000_000).issuedBy(A);var begin=new EffectHandleRequests.GroupBegin(A,handle);var close=new EffectHandleRequests.GroupClose(A,handle);
        for(var request:List.<ResourceRequests.Request>of(begin,close))assertEquals(request,EffectCheckpointCodec.DATA.decode(JsonParser.parseString(EffectCheckpointCodec.DATA.encode(request).toString()),ResourceRequests.Request.class));
        for(var receipt:List.<ResourceRequests.Receipt>of(new EffectHandleRequests.GroupStarted(begin,handle),new EffectHandleRequests.GroupClosed(close)))assertEquals(receipt,EffectCheckpointCodec.DATA.decode(JsonParser.parseString(EffectCheckpointCodec.DATA.encode(receipt).toString()),ResourceRequests.Receipt.class));
        assertThrows(IllegalArgumentException.class,()->new EffectHandleRequests.GroupBegin(B,handle));assertThrows(IllegalArgumentException.class,()->new EffectHandleRequests.GroupClose(B,handle));
    }
}
