package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real DSL requests preserve one issuer ledger through moved continuations, frozen paths and joint archives. */
class ShotGroupRoutingTest {
    static final String A="test:a",B="test:b",C="test:c",H="player";
    static final List<String> DOMAINS=List.of(A,B,C);
    static JsonObject object(String value){return JsonParser.parseString(value).getAsJsonObject();}
    static JsonObject data(boolean buffOwned,boolean reflected)throws Exception{
        var data=reflected?RicochetHitscanTest.data(1,1):PiercingHitscanTest.data(1);
        var body=HitscanTest.steps(data);var ray=body.remove(4).getAsJsonObject();
        if(reflected)ray.add("hits_per_target",object("{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"count\"}"));
        var later=object("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"},\"do\":[]}");
        later.getAsJsonArray("do").add(ray);
        later.getAsJsonArray("do").add(object("{\"type\":\"chorus:play_cue\",\"cue\":\"test:after_path\"}"));body.add(later);
        if(buffOwned){
            data.add("buffs",JsonParser.parseString("[{\"definition\":{\"id\":\"test:carry\",\"version\":\"hitscan-test-v1\",\"duration\":10},\"bundle\":\"test:carry\"}]"));
            var carry=object("{\"id\":\"test:carry\",\"scope\":\"buff\",\"rules\":[{\"id\":\"gained\",\"on\":\"chorus:buff_gained\",\"if\":{\"type\":\"chorus:own_buff\"},\"do\":[]},{\"id\":\"resolved\",\"on\":\"chorus:shot_resolved\",\"do\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:carry_resolved\"}]}]}");
            carry.getAsJsonArray("rules").get(0).getAsJsonObject().add("do",body.deepCopy());data.getAsJsonArray("bundles").add(carry);
            var initial=new JsonArray();initial.add(object("{\"type\":\"chorus:grant_buff\",\"buff\":\"test:carry\"}"));
            data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().add("do",initial);
        }
        data.getAsJsonArray("bundles").add(object("""
            {"id":"test:watch","rules":[
              {"id":"resolved","on":"chorus:shot_resolved","do":[{"type":"chorus:play_cue","cue":"test:watch_resolved"}]},
              {"id":"progress","on":"chorus:shot_progress","do":[{"type":"chorus:play_cue","cue":"test:watch_progress"}]}]}
            """));
        return data;
    }
    record Hit(String domain,long sequence,DamageCommand command){}
    record Cue(String domain,String cue){}
    static final class Fixture {
        final CompiledEffects program;final Map<String,EffectSession> sessions=new TreeMap<>();
        final List<Hitscan.PathObservation> paths=new ArrayList<>();final List<Hit> hits=new ArrayList<>();
        final List<ShotRequests.Receipt> receipts=new ArrayList<>();final List<Cue> cues=new ArrayList<>();
        ResourceSessionAuthority host;ShotGroups.Handle handle;boolean unknownClose,unknownDamage,unknownTrace;
        int repeatedRequests;
        Fixture(boolean buffOwned,boolean reflected)throws Exception{
            program=EffectTestSupport.compile(data(buffOwned,reflected)).withResourceRouting();
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();
            for(String domain:DOMAINS){
                long now=domain.equals(A)?0:domain.equals(B)?50_000_000:100_000_000;
                var watch=new EffectSource("watch/"+domain,"test:watch","observer/"+domain,
                        new BuffInstance.Origin("observer/"+domain,"watch/"+domain,"",""),Set.of());
                var state=EffectState.empty().withBuffs(new BuffStore(now,1,Map.of())).withSource(watch);
                if(domain.equals(A))state=state.withSource(HitscanTest.SOURCE);
                var session=new EffectSession(program,EffectCheckpointTest.clock(),1,state,request->execute(domain,request));
                sessions.put(domain,session);bindings.put(domain,new ResourceSessionAuthority.Binding(program,session));
            }
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);
            host.enroll(Map.of(H,A),Map.of(H,A),Map.of(H,A));
            sessions.get(A).start(0,new RuleEngine.Signal("test:launch",EffectTestSupport.event(HitscanTest.SOURCE)));
            assertFalse(host.drain(100).pending());handle=state(A).shotGroups().values().iterator().next().handle();
            assertEquals(Optional.of(A),handle.issuer());assertTrue(paths.isEmpty());assertEquals(handle,captured(A));
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request){
            if(request.command() instanceof ResourceRequests.Request){
                var actual=host.execute(domain,request);var states=new TreeMap<String,Object>();sessions.forEach((d,s)->states.put(d,s.state()));
                assertSame(actual,host.execute(domain,request));sessions.forEach((d,s)->assertSame(states.get(d),s.state()));repeatedRequests++;
                var primitive=actual instanceof ResourceRoutes.Receipt routed?routed.primitive().orElse(null):actual;
                if(primitive instanceof ShotRequests.Receipt shot){
                    receipts.add(shot);
                    if(unknownTrace&&shot.request() instanceof ShotRequests.TracedPath)
                        throw new IllegalStateException("unknown after committed frozen path");
                    if(unknownClose&&shot.request() instanceof ShotRequests.Close close&&close.impact().terminal())
                        throw new IllegalStateException("unknown after committed final contact close");
                }
                return actual;
            }
            return switch(request.command()){
                case PositionQuery query->new PositionQuery.Result(query,Optional.of(HitscanTest.START));
                case DirectionQuery query->new DirectionQuery.Result(query,Optional.of(new WorldDirection("world",1,0,0)));
                case Hitscan.PiercingQuery query->{
                    Hitscan.PathObservation result=host.readView(domain,request).shotActive(query.ray().member().orElseThrow())
                            ?PiercingHitscanTest.path(query,2):new Hitscan.PathResult(query,List.of(new Hitscan.PathContact(
                                    new Hitscan.Result(query.ray(),Hitscan.Outcome.EXPIRED_SHOT,Optional.empty()),1,true)));
                    paths.add(result);yield result;
                }
                case RicochetHitscan.Query query->{
                    Hitscan.PathObservation result=host.readView(domain,request).shotActive(query.ray().member().orElseThrow())
                            ?reflected(query):new RicochetHitscan.Result(query,List.of(new RicochetHitscan.Contact(
                                    query.ray(),Hitscan.Outcome.EXPIRED_SHOT,Optional.empty(),1,0,0,true,0,false)));
                    paths.add(result);yield result;
                }
                case DamageCommand damage->{
                    var pellet=state(A).shotGroups().get(handle.id()).pellets().get(0);assertTrue(pellet.contactOpen());
                    assertTrue(state(domain).shotGroups().isEmpty(),"The current physical caller never receives a copied ledger");
                    hits.add(new Hit(domain,pellet.sequence(),damage));
                    if(unknownDamage)throw new IllegalStateException("unknown actual damage");
                    yield new DamageReceipt(domain+"/"+request.id(),DamageReceipt.Outcome.APPLIED,0,0,damage.amount(),Optional.empty(),false);
                }
                case Action.CueCommand cue->{cues.add(new Cue(domain,cue.cue()));yield RuleEngine.Empty.INSTANCE;}
                default->throw new AssertionError(request.command());
            };
        }
        static RicochetHitscan.Result reflected(RicochetHitscan.Query query){
            var first=query.ray();var wall=RicochetHitscanTest.hit(first,Hitscan.Outcome.BLOCK,5,null);var reflected=RicochetHitscan.reflected(first,wall);
            return new RicochetHitscan.Result(query,List.of(
                    new RicochetHitscan.Contact(first,Hitscan.Outcome.ENTITY,Optional.of(RicochetHitscanTest.hit(first,Hitscan.Outcome.ENTITY,2,"enemy0")),1,0,1,false,0,false,1),
                    new RicochetHitscan.Contact(first,Hitscan.Outcome.BLOCK,Optional.of(wall),2,1,1,false,0,false,0),
                    new RicochetHitscan.Contact(reflected,Hitscan.Outcome.ENTITY,Optional.of(RicochetHitscanTest.hit(reflected,Hitscan.Outcome.ENTITY,2,"enemy0")),3,1,2,true,5,false,2)));
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        EffectState.Timer timer(String domain){return state(domain).timers().values().stream()
                .filter(t->t.signal().payload() instanceof EffectContinuations.Pending).findFirst().orElseThrow();}
        ShotGroups.Handle captured(String domain){return ((EffectContinuations.Pending)timer(domain).signal().payload()).bindings().values().stream()
                .filter(ShotGroups.Handle.class::isInstance).map(ShotGroups.Handle.class::cast).findFirst().orElseThrow();}
        void move(String from,String to,boolean character){
            var saved=host.checkpoint();var group=state(A).shotGroups().get(handle.id());
            if(character){var transfer=host.prepareCharacterTransfer(H,from,to,Set.of());assertEquals(saved,host.checkpoint());host.commit(transfer);}
            else{var transfer=host.prepareTransferWithBuffs(H,from,to);assertEquals(saved,host.checkpoint());host.commit(transfer);}
            assertEquals(handle,captured(to));assertEquals(group,state(A).shotGroups().get(handle.id()));
            assertTrue(state(B).shotGroups().isEmpty());assertTrue(state(C).shotGroups().isEmpty());
        }
        void due(String domain){sessions.get(domain).observe(timer(domain).dueAt(),List.of());assertFalse(host.drain(100).pending());}
        List<ShotGroups.Summary> summaries(){return receipts.stream().flatMap(r->r.facts().stream()).map(RuleEngine.Signal::payload)
                .filter(ShotGroups.Summary.class::isInstance).map(ShotGroups.Summary.class::cast).toList();}
        long count(String domain,String cue){return cues.stream().filter(value->value.equals(new Cue(domain,cue))).count();}
        void restore(){
            var saved=host.checkpoint();var decoded=CheckpointArchive.decode(JsonParser.parseString(ResourceCheckpointTest.archive(saved).encode().toString()));
            var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            DOMAINS.forEach(d->policies.put(d,new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,r->execute(d,r))));
            var restored=ResourceSessionAuthority.restore(decoded.resourceCheckpoint().orElseThrow(),policies);
            host=restored.authority();sessions.clear();restored.bindings().forEach((d,b)->sessions.put(d,b.session()));assertEquals(saved,host.checkpoint());
        }
    }

    @Test void piercingAndReflectedPathsKeepOneIssuerAndConfirmedUniquePelletCountsAcrossThreeDomains()throws Exception{
        for(boolean reflected:List.of(false,true)){
            var f=new Fixture(false,reflected);f.move(A,B,true);f.move(B,C,true);
            assertTrue(f.state(C).buffs().timeMicros()>f.handle.dueAt());f.due(C);
            assertEquals(1,f.paths.size());assertEquals(reflected?List.of(1L,3L):List.of(1L,2L),f.hits.stream().map(Hit::sequence).toList());
            assertTrue(f.hits.stream().allMatch(hit->hit.domain().equals(C)));assertEquals(2,f.hits.size());
            assertEquals(1,f.summaries().size());var summary=f.summaries().getFirst();assertTrue(summary.complete());
            assertEquals(reflected?Map.of("enemy0",1):Map.of("enemy0",1,"enemy1",1),summary.hits());assertEquals(summary.hits(),summary.effective());
            assertEquals(f.handle.reference(),summary.event().references().get("shot"));
            assertEquals(reflected?1:2,f.count(A,"test:watch_progress"));assertEquals(reflected?1:2,f.count(C,"test:watch_progress"));
            assertEquals(0,f.count(B,"test:watch_progress"));assertEquals(1,f.count(A,"test:watch_resolved"));assertEquals(1,f.count(C,"test:watch_resolved"));
            assertEquals(1,f.count(C,"test:all_hit"));assertEquals(1,f.count(C,"test:after_path"));
            assertTrue(f.state(A).shotGroups().isEmpty());assertFalse(f.state(A).timers().containsKey(f.handle.timerId()));
            assertTrue(f.repeatedRequests>10);assertDoesNotThrow(f.host::checkpoint);
        }
    }

    @Test void buffContinuationAndIndependentSourceReceiveOneFactAtTheirOwnCurrentFacets()throws Exception{
        var f=new Fixture(true,true);f.move(A,B,false);f.move(B,C,false);
        assertEquals(A,f.host.participants().owner(ParticipantPlacement.Facet.CHARACTER,H).domain());
        assertEquals(C,f.host.participants().owner(ParticipantPlacement.Facet.BUFFS,H).domain());f.due(C);
        assertEquals(2,f.hits.size());assertEquals(1,f.summaries().size());assertEquals(Map.of("enemy0",1),f.summaries().getFirst().hits());
        assertEquals(1,f.count(A,"test:all_hit"));assertEquals(1,f.count(C,"test:carry_resolved"));
        assertEquals(1,f.count(A,"test:watch_resolved"));assertEquals(1,f.count(C,"test:watch_resolved"));assertEquals(0,f.count(B,"test:watch_resolved"));
    }

    @Test void exactIssuerExpiryRoutesToCurrentOwnerAndExpiredDelayedPathsCannotPublishAnotherSummary()throws Exception{
        for(boolean reflected:List.of(false,true)){
            var f=new Fixture(true,reflected);f.move(A,B,false);f.move(B,C,false);
            f.sessions.get(A).observe(f.handle.dueAt(),List.of());assertFalse(f.host.drain(100).pending());
            assertTrue(f.state(A).shotGroups().isEmpty());assertEquals(1,f.summaries().size());assertFalse(f.summaries().getFirst().complete());
            assertTrue(f.summaries().getFirst().hits().isEmpty());assertEquals(1,f.count(A,"test:watch_resolved"));assertEquals(1,f.count(C,"test:carry_resolved"));
            assertEquals(0,f.count(B,"test:watch_resolved"));assertEquals(1,f.receipts.stream().filter(r->r.request() instanceof ShotRequests.Expire).count());
            f.due(C);assertEquals(1,f.paths.size());assertEquals(Hitscan.Outcome.EXPIRED_SHOT,f.paths.getFirst().observations().getFirst().outcome());
            assertTrue(f.hits.isEmpty());assertEquals(1,f.summaries().size());assertEquals(1,f.count(C,"test:after_path"));assertDoesNotThrow(f.host::checkpoint);
        }
    }

    @Test void jointArchiveKeepsSourceAndBuffCapturedShotIdentityWithoutReplayingTheFirstWorldQueries()throws Exception{
        for(boolean buffOwned:List.of(false,true)){
            var f=new Fixture(buffOwned,true);f.move(A,B,!buffOwned);var ledger=f.state(A).shotGroups().get(f.handle.id());
            int operations=f.repeatedRequests;var captured=f.captured(B);f.restore();
            assertEquals(operations,f.repeatedRequests);assertEquals(ledger,f.state(A).shotGroups().get(f.handle.id()));assertEquals(captured,f.captured(B));
            assertTrue(f.paths.isEmpty());f.move(B,C,!buffOwned);f.due(C);assertEquals(2,f.hits.size());assertEquals(1,f.summaries().size());
            var hits=List.copyOf(f.hits);var facts=List.copyOf(f.receipts);f.restore();assertEquals(hits,f.hits);assertEquals(facts,f.receipts);
            f.sessions.get(C).observe(f.state(C).buffs().timeMicros()+1,List.of());assertFalse(f.host.drain(100).pending());assertEquals(hits,f.hits);
        }
    }

    @Test void unknownAfterCommittedCloseKeepsIssuerDeletionAndCannotReplayThePathOrCheckpoint()throws Exception{
        var f=new Fixture(false,true);f.move(A,B,true);f.unknownClose=true;
        assertThrows(IllegalStateException.class,()->f.due(B));assertEquals(1,f.paths.size());assertEquals(2,f.hits.size());
        assertTrue(f.state(A).shotGroups().isEmpty());assertFalse(f.state(A).timers().containsKey(f.handle.timerId()));
        assertEquals(1,f.summaries().size());assertEquals(Map.of("enemy0",1),f.summaries().getFirst().hits());assertEquals(0,f.count(B,"test:after_path"));
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,f.sessions.get(B)::drain);
        assertEquals(1,f.paths.size());assertEquals(2,f.hits.size());assertEquals(1,f.summaries().size());
    }

    @Test void unknownPhysicalDamageKeepsOnlyTheOpenContactAndDoesNotInventHitCredit()throws Exception{
        var f=new Fixture(false,false);f.move(A,B,true);f.unknownDamage=true;
        assertThrows(IllegalStateException.class,()->f.due(B));assertEquals(1,f.paths.size());assertEquals(1,f.hits.size());
        var pellet=f.state(A).shotGroups().get(f.handle.id()).pellets().get(0);assertEquals(1,pellet.sequence());assertTrue(pellet.contactOpen());assertTrue(pellet.hits().isEmpty());
        assertTrue(f.summaries().isEmpty());assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,f.sessions.get(B)::drain);
        assertEquals(1,f.paths.size());assertEquals(1,f.hits.size());
    }

    @Test void unknownAfterCommittedPathKeepsTheOriginalGeometryAndFirstOpenContactWithoutReplayingEitherStage()throws Exception{
        for(boolean reflected:List.of(false,true)){
            var f=new Fixture(false,reflected);f.move(A,B,true);f.move(B,C,true);f.unknownTrace=true;
            assertThrows(IllegalStateException.class,()->f.due(C));assertEquals(1,f.paths.size());assertTrue(f.hits.isEmpty());
            var path=f.paths.getFirst();var traced=f.receipts.stream().filter(r->r.request() instanceof ShotRequests.TracedPath).toList();
            assertEquals(1,traced.size());assertSame(path,traced.getFirst().value());
            assertSame(path,((ShotRequests.TracedPath)traced.getFirst().request()).observation());
            var timeline=f.sessions.get(C).state().engine();var pending=timeline.pending().orElseThrow();
            assertEquals(traced.getFirst().request(),timeline.frames().getFirst().pendingCommand().orElseThrow());
            // The second Await uses the same instruction with a new invocation; its first actual read remains evidence.
            var original=new RuleEngine.OperationId(pending.frame(),pending.pc(),pending.invocation()-1);
            var actual=timeline.receipts().get(original);
            if(actual instanceof RuleEngine.WorldReceipt envelope)actual=envelope.value();
            assertSame(path,actual);assertFalse(timeline.receipts().containsKey(pending),"A thrown callback cannot confirm its pending result");
            var pellet=f.state(A).shotGroups().get(f.handle.id()).pellets().get(0);
            assertEquals(ShotGroups.Phase.FLYING,pellet.phase());assertEquals(1,pellet.sequence());assertTrue(pellet.contactOpen());
            assertTrue(pellet.hits().isEmpty());assertTrue(pellet.effective().isEmpty());assertTrue(f.summaries().isEmpty());
            assertTrue(f.state(B).shotGroups().isEmpty());assertTrue(f.state(C).shotGroups().isEmpty());
            assertEquals(0,f.count(C,"test:after_path"));assertThrows(IllegalStateException.class,f.host::checkpoint);
            assertThrows(IllegalStateException.class,f.sessions.get(C)::drain);
            assertEquals(1,f.paths.size());assertTrue(f.hits.isEmpty());
            assertEquals(1,f.receipts.stream().filter(r->r.request() instanceof ShotRequests.TracedPath).count());
        }
    }
}
