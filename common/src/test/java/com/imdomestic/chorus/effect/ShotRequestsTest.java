package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Issuer-local member transitions preserve physical observations and independently address participant facts. */
class ShotRequestsTest {
    private static final String A="test:a",B="test:b",C="test:c",H="owner";
    private static final long DUE=10_000_000;
    private static final BuffInstance.Origin ORIGIN=new BuffInstance.Origin(H,"source","weapon","");
    private static ShotGroups.Handle handle(String issuer){return ShotGroups.Handle.issued("same",ORIGIN,2,0,DUE,issuer);}
    private static ShotGroups.Handle legacy(){return new ShotGroups.Handle("same",ORIGIN,2,0,DUE);}
    private static EffectState clock(EffectState state,long now){
        // The timeline removes due timers before its expiry fact is evaluated against the still-present ledger.
        for(var timer:state.timers().values())if(timer.dueAt()<=now)state=state.cancel(timer.id());
        return state.withBuffs(new BuffStore(now,state.buffs().nextGeneration(),state.buffs().instances()));
    }
    private static EffectState state(ShotGroups.Handle handle,long now){return clock(ShotGroups.begin(EffectState.empty(),handle).state(),now);}
    private static CompiledEffects program(){return EffectTestSupport.compile(JsonParser.parseString("{\"version\":\"shot-protocol\",\"bundles\":[]}").getAsJsonObject());}
    private static ResourceAuthority.Worlds worlds(boolean old){
        return new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(
                A,state(old?legacy().issuedBy(A):handle(A),5_000_000),
                B,state(old?legacy().issuedBy(B):handle(B),20_000_000),
                C,state(old?legacy().issuedBy(C):handle(C),1_000_000)),
                Map.of(A,TimelineIdentity.fresh(),B,TimelineIdentity.fresh(),C,TimelineIdentity.fresh()));
    }
    private static ResourceReadView view(ResourceAuthority.Worlds worlds,String domain){return new ResourceReadView(worlds,domain,program().program());}
    private static ShotGroups.Member member(int pellet){return new ShotGroups.Member(handle(A),pellet);}
    private static WorldPosition point(double x){return new WorldPosition(B,x,0,0);}
    private static Hitscan.Query ray(ShotGroups.Member member){return new Hitscan.Query(Optional.of(point(0)),Optional.of(new WorldDirection(B,1,0,0)),10,0,H,H,Optional.of(member));}
    private static ProjectileFlight.Impact entity(ShotGroups.Member member,long sequence,boolean terminal){
        return new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,point(sequence),Optional.of("target"),0,0,0,0,
                sequence,0,sequence,sequence,terminal,Optional.of(member)).withGeometry(new ProjectileFlight.Geometry(point(sequence),1,2,1,0));
    }
    private static ProjectileFlight.Impact block(ShotGroups.Member member,long sequence,long entities){
        return new ProjectileFlight.Impact(ProjectileFlight.End.BLOCK,point(sequence),Optional.empty(),1,0,0,0,
                sequence,0,entities,0,true,Optional.of(member));
    }
    private static ProjectileFlight.Launch launch(ShotGroups.Member member){
        var source=new EffectSource("source","test:bundle",H,ORIGIN,Set.of());
        var cause=new RuleEngine.Event(1,1,Optional.empty(),0,new RuleEngine.Signal("test:launch",RuleEngine.Empty.INSTANCE));
        var pending=new EffectContinuations.Pending("launch","test:body","shot-protocol",source,cause,Map.of("shot",member.shot()),Optional.empty());
        return new ProjectileFlight.Launch(Optional.of(point(0)),Optional.of(new WorldDirection(B,1,0,0)),
                new ProjectileFlight.Parameters(1,0,0,DUE),H,pending,"impact",Optional.of(member));
    }
    private static ShotRequests.Applied apply(ResourceAuthority.Worlds before,ShotRequests.Request request){
        var result=ShotRequests.apply(before,request);
        assertEquals(before.timelines(),result.worlds().timelines());
        for(String domain:before.domains().keySet())if(!domain.equals(request.issuer()))assertSame(before.domain(domain),result.worlds().domain(domain));
        assertSame(request,result.receipt().request());assertTrue(result.receipt().recipient().isEmpty());
        var codec=EffectCheckpointCodec.DATA;
        assertEquals(request,codec.decode(codec.encode(request),ShotRequests.Request.class));
        assertEquals(result.receipt(),codec.decode(codec.encode(result.receipt()),ShotRequests.Receipt.class));
        return result;
    }

    @Test void physicalLaunchAndSequentialContactsOnlyChangeTheIssuerLedger(){
        var worlds=worlds(false);var first=member(0);var original=worlds.domain(A);
        worlds=apply(worlds,new ShotRequests.Reserve(A,first)).worlds();
        assertTrue(original.shotGroups().get("same").pellets().isEmpty());
        assertThrows(IllegalArgumentException.class,()->ShotRequests.apply(worlds(false),new ShotRequests.Open(A,entity(first,1,false))));
        var actual=new ProjectileFlight.Receipt(launch(first),ProjectileFlight.Outcome.LAUNCHED,Optional.of("projectile"));
        var launched=apply(worlds,new ShotRequests.Launched(A,actual));assertSame(actual,launched.receipt().value());worlds=launched.worlds();
        var hit=entity(first,1,false);assertTrue(view(worlds,B).shotAccepts(hit));
        worlds=apply(worlds,new ShotRequests.Open(A,hit)).worlds();
        assertFalse(view(worlds,B).shotAccepts(hit));
        var opened=worlds;assertThrows(IllegalArgumentException.class,()->ShotRequests.apply(opened,new ShotRequests.Open(A,entity(first,2,false))));
        worlds=apply(worlds,new ShotRequests.Close(A,hit)).worlds();
        assertTrue(view(worlds,B).shotAccepts(block(first,2,1)));
        worlds=apply(worlds,new ShotRequests.Open(A,block(first,2,1))).worlds();
        worlds=apply(worlds,new ShotRequests.Close(A,block(first,2,1))).worlds();
        var second=member(1);worlds=apply(worlds,new ShotRequests.Reserve(A,second)).worlds();
        var unavailable=new Hitscan.Result(new Hitscan.Query(Optional.empty(),Optional.empty(),10,0,H,H,Optional.of(second)),Hitscan.Outcome.MISSING_POSITION,Optional.empty());
        var completed=apply(worlds,new ShotRequests.Traced(A,unavailable));
        assertSame(unavailable,completed.receipt().value());assertEquals(1,completed.receipt().facts().size());
        var summary=(ShotGroups.Summary)completed.receipt().facts().getFirst().payload();
        assertTrue(summary.complete());assertTrue(summary.hits().isEmpty(),"Geometry alone is not actual damage");
        assertEquals(handle(A),summary.shot());assertEquals(handle(A).reference(),summary.event().references().get("shot"));
        assertFalse(completed.worlds().domain(A).shotGroups().containsKey("same"));
        assertFalse(completed.worlds().domain(A).timers().containsKey(handle(A).timerId()));
        assertTrue(completed.worlds().domain(B).shotGroups().containsKey("same"));
    }

    @Test void pathReceiptKeepsAllContactsWhileOpeningOnlyTheFirstMemberContact(){
        var first=member(0);var query=ray(first);
        var observed=new Hitscan.PathResult(new Hitscan.PiercingQuery(query,1),List.of(
                new Hitscan.PathContact(new Hitscan.Result(query,Hitscan.Outcome.ENTITY,Optional.of(entity(first,1,true))),1,false),
                new Hitscan.PathContact(new Hitscan.Result(query,Hitscan.Outcome.BLOCK,Optional.of(block(first,1,0))),2,true)));
        var reserved=apply(worlds(false),new ShotRequests.Reserve(A,first)).worlds();
        var traced=apply(reserved,new ShotRequests.TracedPath(A,observed));
        assertSame(observed,traced.receipt().value());
        var pellet=traced.worlds().domain(A).shotGroups().get("same").pellets().get(0);
        assertEquals(1,pellet.sequence());assertTrue(pellet.contactOpen());assertTrue(traced.receipt().facts().isEmpty());
        var closed=apply(traced.worlds(),new ShotRequests.Close(A,observed.contacts().getFirst().contact().orElseThrow())).worlds();
        var last=observed.contacts().getLast().contact().orElseThrow();assertTrue(view(closed,B).shotAccepts(last));
        var opened=apply(closed,new ShotRequests.Open(A,last)).worlds();
        assertEquals(2,opened.domain(A).shotGroups().get("same").pellets().get(0).sequence());
    }

    @Test void issuerClockControlsExpirationEvenWhenCallerClockIsAhead(){
        var worlds=worlds(false);var member=member(0);assertTrue(view(worlds,B).shotActive(member));
        assertFalse(view(worlds,B).shotActive(new ShotGroups.Member(handle(B),0)));
        var due=ResourceAuthority.publish(worlds,A,worlds.domain(A),clock(worlds.domain(A),DUE));
        assertFalse(view(due,C).shotActive(member));
        var reserved=apply(due,new ShotRequests.Reserve(A,member));assertEquals(due,reserved.worlds());
        var expiredLaunch=new ProjectileFlight.Receipt(launch(member),ProjectileFlight.Outcome.EXPIRED_SHOT,Optional.empty());
        assertSame(expiredLaunch,apply(due,new ShotRequests.Launched(A,expiredLaunch)).receipt().value());
        var expiredTrace=new Hitscan.Result(ray(member),Hitscan.Outcome.EXPIRED_SHOT,Optional.empty());
        assertSame(expiredTrace,apply(due,new ShotRequests.Traced(A,expiredTrace)).receipt().value());
        var finished=apply(due,new ShotRequests.Expire(A,handle(A)));
        assertEquals(1,finished.receipt().facts().size());
        assertFalse(((ShotGroups.Summary)finished.receipt().facts().getFirst().payload()).complete());
        assertTrue(apply(finished.worlds(),new ShotRequests.Expire(A,handle(A))).receipt().facts().isEmpty());
        assertThrows(IllegalArgumentException.class,()->ShotRequests.apply(worlds,new ShotRequests.Expire(A,handle(A))));
    }

    @Test void missingIssuerLedgerNeverFindsTheSameIdInAnotherDomain(){
        var worlds=worlds(false);var removed=worlds.domain(A).withoutShotGroup("same").cancel(handle(A).timerId());
        worlds=ResourceAuthority.publish(worlds,A,worlds.domain(A),removed);var view=view(worlds,B);var member=member(0);
        assertEquals(member,view.shotMember(member));assertFalse(view.shotActive(member));assertFalse(view.shotAccepts(entity(member,1,true)));
        assertEquals(worlds,apply(worlds,new ShotRequests.Reserve(A,member)).worlds());
        assertTrue(apply(worlds,new ShotRequests.Expire(A,handle(A))).receipt().facts().isEmpty());
        var missing=worlds;
        assertThrows(IllegalArgumentException.class,()->ShotRequests.apply(missing,new ShotRequests.Launched(A,
                new ProjectileFlight.Receipt(launch(member),ProjectileFlight.Outcome.LAUNCHED,Optional.of("projectile")))));
        assertThrows(IllegalArgumentException.class,()->ShotRequests.apply(missing,new ShotRequests.Open(A,entity(member,1,true))));
        assertEquals(handle(B),missing.domain(B).shotGroups().get("same").handle());
    }

    @Test void legacyAliasesUseThePhysicalRootAndReturnCanonicalQualifiedMembers(){
        var worlds=worlds(true);var alias=new ShotGroups.Member(legacy(),0);var program=program().program();
        var placement=ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY,Map.of(H,A),Map.of(H,C));
        var root=new ResourceReadView(worlds,B,program).combatParticipants(placement,Map.of(A,program,B,program,C,program));
        for(var query:List.of(root,root.characterView(H),root.buffView(H))){
            assertEquals(B,query.shotDomain(alias));assertSame(worlds.domain(B),query.shotState(alias));
            assertEquals(new ShotGroups.Member(legacy().issuedBy(B),0),query.shotMember(alias));assertFalse(query.shotActive(alias));
            var remote=new ShotGroups.Member(legacy().issuedBy(A),0);assertEquals(A,query.shotDomain(remote));assertTrue(query.shotActive(remote));
        }
        var result=apply(worlds,new ShotRequests.Reserve(A,alias));
        assertEquals(legacy().issuedBy(A),result.worlds().domain(A).shotGroups().get("same").handle());
        assertEquals("same",view(result.worlds(),A).shotMember(alias).shot().reference());
        var original=state(legacy(),0);var unqualified=ResourceAuthority.publish(worlds,A,worlds.domain(A),clock(original,5_000_000));
        assertEquals(legacy().issuedBy(A),view(unqualified,A).shotMember(alias).shot());
        assertTrue(unqualified.domain(A).shotGroups().get("same").handle().issuer().isEmpty(),"A read does not rewrite a legacy ledger");
    }

    @Test void issuerAndCanonicalHandleMismatchesAreRejectedBeforeAnyTransition(){
        var worlds=worlds(false);var member=member(0);
        assertThrows(IllegalArgumentException.class,()->new ShotRequests.Reserve(B,member));
        assertThrows(IllegalArgumentException.class,()->new ShotRequests.Expire(B,handle(A)));
        assertThrows(IllegalArgumentException.class,()->view(worlds,B).shotMember(new ShotGroups.Member(legacy().issuedBy(A),0)));
        var malformed=new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY,Map.of(A,state(legacy().issuedBy(B),0),B,EffectState.empty()));
        assertThrows(IllegalArgumentException.class,()->view(malformed,A).shotActive(new ShotGroups.Member(legacy(),0)));
        assertThrows(IllegalArgumentException.class,()->ShotRequests.apply(malformed,new ShotRequests.Reserve(A,new ShotGroups.Member(legacy(),0))));
        var ungrouped=new Hitscan.Query(Optional.empty(),Optional.empty(),10,0,H,H,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->new ShotRequests.Traced(A,new Hitscan.Result(ungrouped,Hitscan.Outcome.MISSING_POSITION,Optional.empty())));
        assertThrows(IllegalArgumentException.class,()->new ShotRequests.Open(A,entity(member,1,true).withMember(Optional.empty())));
        assertThrows(IllegalArgumentException.class,()->new ShotRequests.Receipt(new ShotRequests.Reserve(A,member),
                new Hitscan.Result(ray(member),Hitscan.Outcome.MISSING_POSITION,Optional.empty()),List.of()));
    }

    @Test void rawFactsRouteToIssuerAndBothCurrentFacetsWithoutCloningOrReordering(){
        var worlds=worlds(false);var due=ResourceAuthority.publish(worlds,A,worlds.domain(A),clock(worlds.domain(A),DUE));
        var resolved=apply(due,new ShotRequests.Expire(A,handle(A))).receipt().facts().getFirst();
        var summary=(ShotGroups.Summary)resolved.payload();
        var progress=new RuleEngine.Signal(ShotGroups.PROGRESS,new ShotGroups.Progress(summary.event(),summary,summary));
        var facts=List.of(progress,resolved);
        var placement=ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY,Map.of(H,B),Map.of(H,C));
        var routed=ShotFacts.route(placement,A,facts);assertEquals(Set.of(A,B,C),routed.keySet());
        for(var delivered:routed.values()){
            assertEquals(facts,delivered);assertSame(progress,delivered.getFirst());assertSame(resolved,delivered.getLast());
            assertSame(summary,delivered.getLast().payload());
        }
        var colocated=ParticipantPlacement.enroll(ParticipantPlacement.Directory.EMPTY,Map.of(H,B),Map.of(H,B));
        assertEquals(Map.of(A,facts,B,facts),ShotFacts.route(colocated,A,facts));
        assertEquals(Map.of(A,facts),ShotFacts.route(ParticipantPlacement.Directory.EMPTY,A,facts));
        assertThrows(UnsupportedOperationException.class,()->routed.get(A).add(resolved));
        assertThrows(IllegalArgumentException.class,()->ShotFacts.route(placement,B,facts));
        assertThrows(IllegalArgumentException.class,()->ShotFacts.route(placement,A,List.of(new RuleEngine.Signal("test:other",summary))));
        assertThrows(IllegalArgumentException.class,()->ShotFacts.route(placement,A,List.of(new RuleEngine.Signal(ShotGroups.PROGRESS,summary))));
    }
}
