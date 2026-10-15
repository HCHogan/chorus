package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Full DSL routes the original physical entity observation back to the unchanged issuing ledger. */
class AttachmentRoutingTest {
    static final String A="test:a",B="test:b",C="test:c",H="player";
    static final List<String> DOMAINS=List.of(A,B,C);
    static final EffectSource SOURCE=EffectTestSupport.source("test:attachment");
    static final EntityAnchor.Reference REFERENCE=new EntityAnchor.Reference("target","original-life",B);
    static WorldPosition point(double x){return new WorldPosition(B,x,2,3);}
    static EntityAnchor.Observation anchor(double x){return new EntityAnchor.Observation(REFERENCE,point(x),TargetQuery.Anchor.FEET,true);}
    static JsonObject object(String text){return JsonParser.parseString(text).getAsJsonObject();}
    static JsonObject data(boolean buffOwned)throws Exception{
        var data=EffectTestSupport.json("entity_attachment");var rules=data.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules");
        var body=rules.get(0).getAsJsonObject().getAsJsonArray("do");var first=body.get(3).getAsJsonObject();first.remove("lifetime");
        var second=first.deepCopy();second.getAsJsonObject("after").addProperty("value",0.2);body.add(second);
        first.getAsJsonArray("do").remove(first.getAsJsonArray("do").size()-1);
        if(buffOwned){
            data.add("buffs",JsonParser.parseString("[{\"definition\":{\"id\":\"test:carry\",\"version\":\"test-entity-attachment\",\"duration\":10},\"bundle\":\"test:carry\"}]"));
            var carry=object("{\"id\":\"test:carry\",\"scope\":\"buff\",\"rules\":[{\"id\":\"attach\",\"on\":\"test:carry_attach\",\"do\":[]}]}");
            carry.getAsJsonArray("rules").get(0).getAsJsonObject().add("do",body.deepCopy());data.getAsJsonArray("bundles").add(carry);
            var initial=new JsonArray();initial.add(object("{\"type\":\"chorus:grant_buff\",\"buff\":\"test:carry\"}"));rules.get(0).getAsJsonObject().add("do",initial);
        }
        return data;
    }
    record Query(String caller,EntityAttachments.Query query){}
    record Selection(String caller,TargetQuery query){}
    record Completion(String caller,EntityAttachments.Resolution result,Map<String,EntityAttachments.Entry> issuerEntries){}
    static final class Fixture {
        final CompiledEffects program;final Map<String,EffectSession> sessions=new TreeMap<>();
        final List<Query> queries=new ArrayList<>();final List<Selection> selections=new ArrayList<>();
        final List<Completion> completions=new ArrayList<>();final List<AttachmentRequests.Receipt> receipts=new ArrayList<>();final List<String> cues=new ArrayList<>();
        ResourceSessionAuthority host;EntityAttachments.Handle handle;boolean unknownQuery,unknownClose,staleQuery;int rawCaptures;
        Fixture(boolean buffOwned)throws Exception{
            program=EffectTestSupport.compile(data(buffOwned)).withResourceRouting();var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();
            for(String domain:DOMAINS){
                long now=domain.equals(A)?0:domain.equals(B)?50_000_000:100_000_000;
                var state=EffectState.empty().withBuffs(new BuffStore(now,1,Map.of()));if(domain.equals(A))state=state.withSource(SOURCE);
                var session=new EffectSession(program,EffectCheckpointTest.clock(),1,state,request->execute(domain,request));sessions.put(domain,session);
                bindings.put(domain,new ResourceSessionAuthority.Binding(program,session));
            }
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);host.enroll(Map.of(H,A),Map.of(H,A),Map.of(H,A));
            sessions.get(A).start(0,new RuleEngine.Signal("test:attach",EffectTestSupport.event(SOURCE)));
            if(buffOwned)sessions.get(A).start(0,new RuleEngine.Signal("test:carry_attach",EffectTestSupport.event(SOURCE)));
            assertFalse(host.drain(100).pending());
            handle=entry().handle();assertEquals(Optional.of(A),handle.issuer());assertEquals(handle,captured(A));assertEquals(2,rawCaptures);
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request){
            if(request.command() instanceof ResourceRequests.Request){
                var actual=host.execute(domain,request);var states=new TreeMap<String,Object>();sessions.forEach((d,s)->states.put(d,s.state()));
                assertSame(actual,host.execute(domain,request));sessions.forEach((d,s)->assertSame(states.get(d),s.state()));
                var primitive=actual instanceof ResourceRoutes.Receipt routed?routed.primitive().orElse(null):actual;
                if(primitive instanceof AttachmentRequests.Receipt receipt){
                    receipts.add(receipt);if(unknownClose&&receipt.request() instanceof AttachmentRequests.Close)throw new IllegalStateException("unknown after committed attachment close");
                }
                if(actual instanceof ActionRoutes.Completed completed&&completed.value() instanceof EntityAttachments.Resolution resolution)
                    completions.add(new Completion(domain,resolution,Map.copyOf(state(A).attachments())));
                return actual;
            }
            return switch(request.command()){
                case EntityAnchor.Query query->{rawCaptures++;yield new EntityAnchor.Result(query,EntityAnchor.Outcome.AVAILABLE,Optional.of(anchor(1)));}
                case PositionQuery query->{rawCaptures++;yield new PositionQuery.Result(query,Optional.of(point(2)));}
                case EntityAttachments.Query query->{
                    queries.add(new Query(domain,query));assertEquals(Optional.of(A),query.expected().handle().issuer());
                    assertEquals(B,query.expected().handle().initial().reference().dimension());assertTrue(state(domain).attachments().isEmpty());
                    if(unknownQuery)throw new IllegalStateException("unknown attachment observation");
                    if(staleQuery){
                        staleQuery=false;var before=sessions.get(A).state();
                        var newer=new EntityAttachments.Observed(query,new EntityAnchor.Result(query.expected().handle().query(),EntityAnchor.Outcome.AVAILABLE,Optional.of(anchor(50))));
                        var observed=EntityAttachments.resolvedIssued(state(A),A,query,newer);
                        EffectSession.commitDomains(List.of(sessions.get(A).prepareDomain(before,observed.state())));
                    }
                    yield new EntityAttachments.Observed(query,new EntityAnchor.Result(query.expected().handle().query(),EntityAnchor.Outcome.AVAILABLE,Optional.of(anchor(10*queries.size()))));
                }
                case TargetQuery query->{selections.add(new Selection(domain,query));yield new TargetQuery.Result(query,TargetQuery.Outcome.AVAILABLE,List.of());}
                case Action.CueCommand cue->{cues.add(domain+"/"+cue.cue());yield RuleEngine.Empty.INSTANCE;}
                default->throw new AssertionError(request.command());
            };
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        EntityAttachments.Entry entry(){return state(A).attachments().values().iterator().next();}
        EffectState.Timer timer(String domain){return state(domain).timers().values().stream().filter(t->t.signal().payload() instanceof EffectContinuations.Pending)
                .min(Comparator.comparingLong(EffectState.Timer::dueAt)).orElseThrow();}
        EntityAttachments.Handle captured(String domain){return ((EffectContinuations.Pending)timer(domain).signal().payload()).bindings().values().stream()
                .filter(EntityAttachments.Handle.class::isInstance).map(EntityAttachments.Handle.class::cast).findFirst().orElseThrow();}
        void move(String from,String to,boolean character){
            var before=host.checkpoint();var original=entry();
            if(character){var prepared=host.prepareCharacterTransfer(H,from,to,Set.of());assertEquals(before,host.checkpoint());host.commit(prepared);}
            else{var prepared=host.prepareTransferWithBuffs(H,from,to);assertEquals(before,host.checkpoint());host.commit(prepared);}
            assertEquals(original,entry());assertEquals(handle.issuedBy(A),captured(to));
            assertTrue(state(B).attachments().isEmpty());assertTrue(state(C).attachments().isEmpty());
        }
        void due(String domain){sessions.get(domain).observe(timer(domain).dueAt(),List.of());assertFalse(host.drain(100).pending());}
        void restore(boolean legacy){
            var saved=host.checkpoint();var encoded=ResourceCheckpointTest.archive(saved).encode();
            if(legacy)downgrade(encoded);
            var decoded=CheckpointArchive.decode(JsonParser.parseString(encoded.toString()));var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            DOMAINS.forEach(d->policies.put(d,new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,r->execute(d,r))));
            var restored=ResourceSessionAuthority.restore(decoded.resourceCheckpoint().orElseThrow(),policies);host=restored.authority();sessions.clear();
            restored.bindings().forEach((d,b)->sessions.put(d,b.session()));if(!legacy)assertEquals(saved,host.checkpoint());
            if(!state(A).attachments().isEmpty())handle=entry().handle();
        }
        static void downgrade(JsonElement value){
            if(value.isJsonArray()){value.getAsJsonArray().forEach(Fixture::downgrade);return;}if(!value.isJsonObject())return;var object=value.getAsJsonObject();
            if(object.has("$")&&object.get("$").getAsString().equals("chorus:effect/target/entity_attachments/handle_v2")){
                object.addProperty("$","chorus:effect/target/entity_attachments/handle");assertNotNull(object.getAsJsonObject("v").remove("issuer"));
            }
            object.entrySet().forEach(entry->downgrade(entry.getValue()));
        }
    }

    @Test void sourceAndBuffContinuationsResolveTheOriginalPhysicalTargetThroughThreeDomains()throws Exception{
        for(boolean buffOwned:List.of(false,true)){
            var f=new Fixture(buffOwned);f.move(A,B,!buffOwned);f.move(B,C,!buffOwned);
            assertTrue(f.state(C).buffs().timeMicros()>f.handle.expiresAt());f.due(C);
            assertEquals(1,f.queries.size());assertEquals(C,f.queries.getFirst().caller());assertEquals(1,f.entry().revision());assertEquals(anchor(10),f.entry().last());
            assertEquals(EntityAttachments.Outcome.RESOLVED,f.completions.getFirst().result().outcome());
            assertEquals(Optional.of(point(11)),f.completions.getFirst().result().position());assertEquals(f.handle.issuedBy(A),f.completions.getFirst().result().handle());
            f.due(C);assertEquals(2,f.queries.size());assertEquals(1,f.queries.getLast().query().expected().revision());
            assertEquals(List.of(Optional.of(point(11)),Optional.of(point(21))),f.selections.stream().map(s->((TargetQuery.PositionCenter)s.query().center()).position()).toList());
            assertTrue(f.selections.stream().allMatch(s->s.caller().equals(C)));assertTrue(f.state(A).attachments().isEmpty());
            assertEquals(1,f.receipts.stream().filter(r->r.request() instanceof AttachmentRequests.Begin).count());assertEquals(1,f.receipts.stream().filter(r->r.request() instanceof AttachmentRequests.Close).count());
            assertEquals(2,f.rawCaptures);assertDoesNotThrow(f.host::checkpoint);
        }
    }

    @Test void expiryUsesOnlyIssuerClockAndDelayedReadsReturnExpiredWithoutConsultingAnotherWorld()throws Exception{
        var f=new Fixture(false);f.move(A,B,true);f.move(B,C,true);f.sessions.get(A).observe(f.handle.expiresAt(),List.of());
        assertTrue(f.state(A).attachments().isEmpty());f.due(C);f.due(C);
        assertTrue(f.queries.isEmpty());assertTrue(f.selections.isEmpty());assertEquals(2,f.completions.size());
        assertTrue(f.completions.stream().allMatch(c->c.result().outcome()==EntityAttachments.Outcome.EXPIRED&&c.result().handle().issuer().equals(Optional.of(A))));
        assertEquals(List.of(C+"/test:attachment_missing",C+"/test:attachment_missing"),f.cues);assertDoesNotThrow(f.host::checkpoint);
    }

    @Test void aNewerIssuerRevisionDuringTheWorldQueryMakesTheOldObservationUnavailableWithoutOverwritingIt()throws Exception{
        var f=new Fixture(false);f.move(A,B,true);f.move(B,C,true);f.staleQuery=true;f.due(C);
        assertEquals(1,f.entry().revision());assertEquals(anchor(50),f.entry().last());assertEquals(EntityAttachments.Outcome.MISSING,f.completions.getFirst().result().outcome());
        assertTrue(f.selections.isEmpty());assertEquals(List.of(C+"/test:attachment_missing"),f.cues);
        f.due(C);assertEquals(1,f.queries.getLast().query().expected().revision());assertEquals(EntityAttachments.Outcome.RESOLVED,f.completions.getLast().result().outcome());
        assertEquals(Optional.of(point(21)),f.completions.getLast().result().position());assertTrue(f.state(A).attachments().isEmpty());
    }

    @Test void jointArchiveAndExactLegacyHandleMigrationContinueTheRemainingQueryWithoutReplayingCreation()throws Exception{
        for(boolean buffOwned:List.of(false,true))for(boolean legacy:List.of(false,true)){
            var f=new Fixture(buffOwned);int captures=f.rawCaptures;var requests=List.copyOf(f.receipts);f.restore(legacy);
            assertEquals(captures,f.rawCaptures);assertEquals(requests,f.receipts);assertEquals(legacy,f.entry().handle().issuer().isEmpty());
            f.move(A,B,!buffOwned);f.move(B,C,!buffOwned);f.due(C);assertEquals(1,f.entry().revision());assertEquals(Optional.of(A),f.entry().handle().issuer());
            var ledger=f.entry();var queries=List.copyOf(f.queries);var resolutions=List.copyOf(f.completions);f.restore(false);
            assertEquals(ledger,f.entry());assertEquals(queries,f.queries);assertEquals(resolutions,f.completions);
            f.due(C);assertEquals(2,f.queries.size());assertEquals(2,f.completions.size());assertTrue(f.state(A).attachments().isEmpty());
            assertEquals(captures,f.rawCaptures);assertEquals(1,f.receipts.stream().filter(r->r.request() instanceof AttachmentRequests.Begin).count());
        }
    }

    @Test void unknownPhysicalQueryKeepsItsOldIssuerEntryAndCannotResumeCloseOrCheckpoint()throws Exception{
        var f=new Fixture(false);f.move(A,B,true);var before=f.entry();f.unknownQuery=true;
        assertThrows(IllegalStateException.class,()->f.due(B));assertEquals(before,f.entry());assertEquals(1,f.queries.size());assertTrue(f.completions.isEmpty());assertTrue(f.selections.isEmpty());
        assertTrue(f.receipts.stream().noneMatch(r->r.request() instanceof AttachmentRequests.Close));
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,f.sessions.get(B)::drain);assertEquals(1,f.queries.size());
    }

    @Test void unknownAfterCommittedCloseRetainsBothConfirmedQueriesAndIssuerDeletionWithoutReplay()throws Exception{
        var f=new Fixture(true);f.move(A,B,false);f.move(B,C,false);f.due(C);f.unknownClose=true;
        assertThrows(IllegalStateException.class,()->f.due(C));assertTrue(f.state(A).attachments().isEmpty());assertEquals(2,f.queries.size());assertEquals(2,f.completions.size());
        assertEquals(1,f.receipts.stream().filter(r->r.request() instanceof AttachmentRequests.Close).count());
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,f.sessions.get(C)::drain);assertEquals(2,f.queries.size());
    }
}
