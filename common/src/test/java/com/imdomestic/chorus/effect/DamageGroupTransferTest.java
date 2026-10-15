package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real transfer protocols move lexical group references; the ledger and retained eligibility stay put. */
class DamageGroupTransferTest {
    static final String A="test:a",B="test:b",C="test:c",H="player";
    static final List<String> DOMAINS=List.of(A,B,C);
    static String holder(String domain){return domain.equals(A)?H:"other/"+domain;}
    static EffectSource source(String holder){return new EffectSource("root/"+holder,"test:control",holder,new BuffInstance.Origin(holder,"root/"+holder,"",""),Set.of());}
    static JsonObject object(String json){return JsonParser.parseString(json).getAsJsonObject();}
    static JsonObject damage(boolean grouped){
        var action=object("""
            {"type":"chorus:damage","target":"victim","amount":{"type":"chorus:constant","value":10,"unit":"damage"},
             "damage_type":"minecraft:generic","scaling_profile":"test:melee","tags":["test:melee"]}
            """);
        if(grouped)action.addProperty("group","attack");return action;
    }
    static JsonObject data(boolean buffOwned,boolean initialHit)throws Exception{
        var data=EffectTestSupport.json("damage_group");
        data.add("resources",JsonParser.parseString("[{\"id\":\"test:energy\",\"capacity\":1,\"initial\":1}]"));
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("max_stacks",2);
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject()
                .add("value",object("{\"type\":\"chorus:by_stacks\",\"values\":[0.5,1],\"unit\":\"delta\"}"));
        var start=object("""
            {"id":"start","on":"test:start","do":[
             {"type":"chorus:grant_buff","buff":"test:next_hit","stacks":{"type":"chorus:constant","value":2,"unit":"count"}}]}
            """);
        var rules=new JsonArray();rules.add(start);data.getAsJsonArray("bundles").get(0).getAsJsonObject().add("rules",rules);
        var body=start.getAsJsonArray("do");
        if(buffOwned){
            data.getAsJsonArray("buffs").add(object("""
                {"definition":{"id":"test:carry","version":"test-1","duration":3},"bundle":"test:carry"}
                """));
            body.add(object("{\"type\":\"chorus:grant_buff\",\"buff\":\"test:carry\"}"));
            var carry=object("""
                {"id":"test:carry","scope":"buff","rules":[
                 {"id":"gained","on":"chorus:buff_gained","if":{"type":"chorus:own_buff"},"do":[]}]}
                """);
            data.getAsJsonArray("bundles").add(carry);body=carry.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
        }
        body.add(object("""
            {"action":{"type":"chorus:begin_damage_group","lifetime":{"type":"chorus:constant","value":0.5,"unit":"second"}},"as":"attack"}
            """));
        if(initialHit)body.add(damage(true));
        var delayed=object("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"},\"do\":[]}");
        var later=delayed.getAsJsonArray("do");later.add(damage(true));later.add(damage(true));
        later.add(object("{\"type\":\"chorus:end_damage_group\",\"group\":\"attack\"}"));later.add(damage(false));
        later.add(object("{\"type\":\"chorus:play_cue\",\"cue\":\"test:closed\"}"));body.add(delayed);return data;
    }
    record Hit(String domain,DamageCommand command,double amount){}
    record Completion(DamageCommand command,DamageReceipt result,Map<String,EffectState> states){}
    static final class Fixture {
        final CompiledEffects program;
        final Map<String,EffectSession> sessions=new TreeMap<>();
        final Map<String,EffectProgram> programs=new TreeMap<>();
        final Map<String,DamageGroups.Handle> initialGroups=new TreeMap<>();
        final List<Hit> hits=new ArrayList<>();final List<Completion> completions=new ArrayList<>();
        final List<EffectHandleRequests.Receipt> handles=new ArrayList<>();final List<String> cues=new ArrayList<>();
        ResourceSessionAuthority host;ParticipantPlacement.Directory placement;
        boolean unknownDamage,unknownClose;
        Fixture(boolean buffOwned,boolean initialHit,boolean differentClocks)throws Exception{
            program=EffectTestSupport.compile(data(buffOwned,initialHit)).withResourceRouting();
            for(String domain:DOMAINS){
                long now=differentClocks?(domain.equals(B)?50_000_000:domain.equals(C)?100_000_000:0):0;
                var state=EffectState.empty().withBuffs(new BuffStore(now,1,Map.of())).withSource(source(holder(domain)))
                        .withResource(new ResourceState(new ResourceState.Key(holder(domain),"test:energy"),1,1,now));
                sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,state,r->execute(domain,r)));programs.put(domain,program.program());
            }
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((d,s)->bindings.put(d,new ResourceSessionAuthority.Binding(program,s)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);
            var holders=new TreeMap<String,String>();DOMAINS.forEach(d->holders.put(holder(d),d));host.enroll(holders,holders,holders);placement=host.participants();
            for(String domain:DOMAINS){
                var origin=source(holder(domain)).origin();sessions.get(domain).start(state(domain).buffs().timeMicros(),
                        new RuleEngine.Signal("test:start",new EffectEvent(holder(domain),"victim",origin,Set.of(),Map.of())));
                initialGroups.put(domain,state(domain).damageGroups().values().iterator().next().handle());
            }
            assertEquals(initialGroups.get(A).id(),initialGroups.get(B).id());assertEquals(initialGroups.get(A).id(),initialGroups.get(C).id());
            sessions.get(A).observe(50_000,List.of());assertFalse(host.drain(100).pending());
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request){
            if(request.command() instanceof ResourceRequests.Request){
                var actual=host.execute(domain,request);
                assertSame(actual,host.execute(domain,request),"repeated active requests return their committed receipt");
                if(actual instanceof ResourceRoutes.Receipt route&&route.primitive().orElse(null) instanceof EffectHandleRequests.Receipt handle){
                    handles.add(handle);
                    if(unknownClose&&handle instanceof EffectHandleRequests.GroupClosed)throw new IllegalStateException("unknown after committed issuer close");
                }
                if(request.command() instanceof ActionRoutes.Finish finish&&finish.command() instanceof DamageCommand damage){
                    var states=new TreeMap<String,EffectState>();DOMAINS.forEach(d->states.put(d,state(d)));
                    completions.add(new Completion(damage,(DamageReceipt)((ActionRoutes.Completed)actual).value(),Map.copyOf(states)));
                }
                return actual;
            }
            if(request.command() instanceof DamageCommand damage){
                var view=host.readView(domain,request).combatParticipants(placement,programs);
                double amount=program.outgoing(view,damage,damage.amount()).orElseThrow().output().value();hits.add(new Hit(domain,damage,amount));
                if(unknownDamage&&damage.source().owner().equals(H))throw new IllegalStateException("unknown after migrated world damage");
                return new DamageReceipt(domain+"/"+request.id(),DamageReceipt.Outcome.APPLIED,0,0,amount,Optional.empty(),false);
            }
            if(request.command() instanceof Action.CueCommand cue){cues.add(domain+"/"+cue.cue());return RuleEngine.Empty.INSTANCE;}
            throw new AssertionError(request.command());
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        DamageGroups.Group group(String domain){return DamageGroups.require(state(domain),initialGroups.get(domain));}
        EffectState.Timer timer(String domain){return state(domain).timers().values().stream()
                .filter(t->t.signal().payload() instanceof EffectContinuations.Pending p&&ownedByPlayer(p)).findFirst().orElseThrow();}
        static boolean ownedByPlayer(EffectContinuations.Pending pending){return switch(pending.scope()){
            case EffectSource source->source.holder().equals(H);
            case BuffRules.Scope buff->buff.snapshot().key().holder().equals(H);
            default->false;
        };}
        DamageGroups.Handle captured(String domain){return ((EffectContinuations.Pending)timer(domain).signal().payload()).bindings().values().stream()
                .filter(DamageGroups.Handle.class::isInstance).map(DamageGroups.Handle.class::cast).findFirst().orElseThrow();}
        BuffInstance next(String domain){return state(domain).buffs().instances().values().stream()
                .filter(b->b.key().holder().equals(H)&&b.key().definition().equals("test:next_hit")).findFirst().orElseThrow();}
        void move(String from,String to,boolean character){
            var before=host.checkpoint();
            if(character){var prepared=host.prepareCharacterTransfer(H,from,to,Set.of());assertEquals(before,host.checkpoint());host.commit(prepared);}
            else{var prepared=host.prepareTransferWithBuffs(H,from,to);assertEquals(before,host.checkpoint());host.commit(prepared);}
            placement=host.participants();assertEquals(initialGroups.get(A),captured(to));
        }
        void due(String domain){sessions.get(domain).observe(timer(domain).dueAt(),List.of());assertFalse(host.drain(100).pending());}
        List<Hit> playerHits(){return hits.stream().filter(h->h.command().source().owner().equals(H)).toList();}
        List<Completion> playerCompletions(){return completions.stream().filter(c->c.command().source().owner().equals(H)).toList();}
        void assertCompleted(String domain,boolean initialHit){
            assertEquals(initialHit?List.of(20.,20.,20.,15.):List.of(20.,20.,15.),playerHits().stream().map(Hit::amount).toList());
            var completed=playerCompletions();assertEquals(playerHits().size(),completed.size());
            assertEquals(1,completed.getFirst().command().consumptions().size());
            for(int i=1;i<completed.size()-1;i++)assertTrue(completed.get(i).command().consumptions().isEmpty(),"retained group eligibility cannot spend another charge");
            assertEquals(1,completed.getLast().command().consumptions().size());
            assertTrue(completed.stream().allMatch(c->c.result().consumptionSettled()));
            assertTrue(state(A).damageGroups().isEmpty());assertFalse(state(A).timers().containsKey(initialGroups.get(A).timerId()));
            assertTrue(state(domain).buffs().instances().values().stream().noneMatch(b->b.key().holder().equals(H)&&b.key().definition().equals("test:next_hit")));
            assertEquals(List.of(domain+"/test:closed"),cues);
            var closed=handles.stream().filter(EffectHandleRequests.GroupClosed.class::isInstance).map(EffectHandleRequests.GroupClosed.class::cast).toList();
            assertEquals(1,closed.size());assertEquals(A,closed.getFirst().request().issuer());assertDoesNotThrow(host::checkpoint);
        }
        void restore(){
            var saved=host.checkpoint();var archive=ResourceCheckpointTest.archive(saved);
            var decoded=CheckpointArchive.decode(JsonParser.parseString(archive.encode().toString()));assertEquals(archive,decoded);
            var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            DOMAINS.forEach(d->policies.put(d,new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,r->execute(d,r))));
            var restored=ResourceSessionAuthority.restore(decoded.resourceCheckpoint().orElseThrow(),policies);host=restored.authority();
            sessions.clear();restored.bindings().forEach((d,b)->sessions.put(d,b.session()));placement=host.participants();assertEquals(saved,host.checkpoint());
        }
    }

    @Test void consumedSourceOwnedGroupSurvivesThreeDomainsAndRoundTripWithoutSpendingItsGrantAgain()throws Exception{
        var f=new Fixture(false,true,true);var original=f.group(A);var b=f.group(B);var c=f.group(C);
        var retained=original.grants().values().iterator().next();assertEquals(2,retained.count());assertEquals(1,f.next(A).count());
        f.move(A,B,true);f.move(B,C,true);f.move(C,A,true);f.move(A,C,true);
        assertEquals(original,f.group(A));assertEquals(1,f.next(C).count());
        assertTrue(f.state(C).buffs().timeMicros()>original.handle().dueAt());
        assertTrue(f.state(C).buffs().timeMicros()>retained.stacks().getFirst().expiresAt());
        f.due(C);f.assertCompleted(C,true);assertEquals(b,f.group(B));assertEquals(c,f.group(C));
    }

    @Test void buffOwnedGroupKeepsConsumedEligibilityAcrossBuffOnlyAndWholeCharacterTransfers()throws Exception{
        for(boolean character:List.of(false,true)){
            var f=new Fixture(true,true,true);var retained=f.group(A).grants();var b=f.group(B);var c=f.group(C);
            f.move(A,B,character);f.move(B,C,character);assertEquals(retained,f.group(A).grants());assertEquals(1,f.next(C).count());
            assertEquals(character?C:A,f.host.participants().owner(ParticipantPlacement.Facet.CHARACTER,H).domain());
            f.due(C);f.assertCompleted(C,true);assertEquals(b,f.group(B));assertEquals(c,f.group(C));
        }
    }

    @Test void firstConsumptionAfterTransferUsesTheMovedBuffAndRetainsItOnlyAtTheOriginalIssuer()throws Exception{
        for(boolean buffOwned:List.of(false,true)){
            var f=new Fixture(buffOwned,false,true);var b=f.group(B);var c=f.group(C);assertTrue(f.group(A).grants().isEmpty());
            f.move(A,B,!buffOwned);var moved=f.next(B);assertEquals(2,moved.count());f.due(B);f.assertCompleted(B,false);
            var first=f.playerCompletions().getFirst();var retained=first.states().get(A).damageGroups().get(f.initialGroups.get(A).id()).grants();
            assertEquals(Map.of(moved.key(),moved),retained);
            assertEquals(b,first.states().get(B).damageGroups().get(b.handle().id()));assertEquals(b,f.group(B));assertEquals(c,f.group(C));
        }
    }

    @Test void issuerClockExpiryRejectsTheContinuationEvenWhileTheDestinationHasNotReachedItsLocalDueTime()throws Exception{
        var f=new Fixture(false,true,false);var b=f.group(B);var c=f.group(C);f.move(A,B,true);
        f.sessions.get(A).observe(f.initialGroups.get(A).dueAt(),List.of());assertTrue(f.state(A).damageGroups().isEmpty());
        assertEquals(0,f.state(B).buffs().timeMicros());
        assertThrows(IllegalArgumentException.class,()->f.due(B));assertEquals(1,f.playerHits().size());assertEquals(1,f.next(B).count());
        assertEquals(b,f.group(B));assertEquals(c,f.group(C));assertTrue(f.cues.isEmpty());
    }

    @Test void jointArchiveRestoresMovedSourceAndBuffContinuationsWithoutReplayingDamageOrGroupBegin()throws Exception{
        for(boolean buffOwned:List.of(false,true)){
            var f=new Fixture(buffOwned,true,true);f.move(A,B,!buffOwned);
            var hits=List.copyOf(f.hits);var handles=List.copyOf(f.handles);var ledger=f.group(A);var binding=f.captured(B);
            f.restore();assertEquals(hits,f.hits);assertEquals(handles,f.handles);assertEquals(ledger,f.group(A));assertEquals(binding,f.captured(B));
            f.move(B,C,!buffOwned);f.due(C);f.assertCompleted(C,true);
            var once=List.copyOf(f.hits);f.sessions.get(C).observe(f.state(C).buffs().timeMicros()+1,List.of());assertEquals(once,f.hits);
        }
    }

    @Test void unknownMigratedDamageCannotSpendAnotherChargeCloseTheIssuerOrBecomeReplayable()throws Exception{
        var f=new Fixture(false,true,true);f.move(A,B,true);var ledger=f.group(A);var remaining=f.next(B);f.unknownDamage=true;
        assertThrows(IllegalStateException.class,()->f.due(B));assertEquals(List.of(20.,20.),f.playerHits().stream().map(Hit::amount).toList());
        assertEquals(ledger,f.group(A));assertEquals(remaining,f.next(B));assertEquals(1,f.playerCompletions().size());assertTrue(f.cues.isEmpty());
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,f.sessions.get(B)::drain);
        assertEquals(2,f.playerHits().size());
    }

    @Test void unknownCloseRetainsIssuerDeletionAndNeverFallsBackToTheDestinationGroup()throws Exception{
        var f=new Fixture(true,true,true);f.move(A,B,false);var b=f.group(B);var c=f.group(C);f.unknownClose=true;
        assertThrows(IllegalStateException.class,()->f.due(B));assertTrue(f.state(A).damageGroups().isEmpty());assertEquals(b,f.group(B));assertEquals(c,f.group(C));
        assertEquals(List.of(20.,20.,20.),f.playerHits().stream().map(Hit::amount).toList());assertEquals(1,f.next(B).count());
        assertEquals(1,f.handles.stream().filter(EffectHandleRequests.GroupClosed.class::isInstance).count());assertTrue(f.cues.isEmpty());
        assertThrows(IllegalStateException.class,f.host::checkpoint);assertThrows(IllegalStateException.class,f.sessions.get(B)::drain);
    }

    @Test void noncoordinatedDestinationRejectsBothOwnershipModesBeforeChangingAnyCapturedState()throws Exception{
        for(boolean buffOwned:List.of(false,true)){
            var f=new Fixture(buffOwned,true,false);var before=f.host.checkpoint();var local=new CompiledEffects(f.program.program());
            if(buffOwned)assertThrows(IllegalArgumentException.class,()->BuffTransfer.prepare(H,f.program,f.state(A),local,f.state(B),Optional.of(A)));
            else assertThrows(IllegalArgumentException.class,()->CharacterTransfer.prepare(H,Set.of(),f.program,f.state(A),local,f.state(B),Optional.of(A)));
            assertEquals(before,f.host.checkpoint());assertEquals(Optional.of(A),f.captured(A).issuer());
        }
    }
}
