package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Snapshot clocks belong to persistent timelines, independently of movable character, Buff and resource leases. */
class DamageSnapshotTimelineTest {
    private static final String A="test:a",B="test:b",C="test:c",H="player",V="victim",CARRY="test:carry";
    private static final long HIGH=100_000_000,LOW=1_000_000;
    private static final EffectSource SOURCE=source("test:attack");
    private static JsonObject object(String json){return JsonParser.parseString(json).getAsJsonObject();}
    private static JsonObject data()throws Exception{
        var data=json("damage_snapshot");
        data.getAsJsonArray("buffs").add(object("""
            {"definition":{"id":"test:carry","version":"test-snapshot-v1","duration":60,
              "components":{"damage_snapshots":["attack"]}}}
            """));
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().add("rules",JsonParser.parseString("""
            [{"id":"capture","on":"test:capture","do":[
               {"type":"chorus:grant_buff","buff":"test:carry"},
               {"action":{"type":"chorus:capture_damage","amount":{"type":"chorus:constant","value":10,"unit":"damage"},
                 "damage_type":"minecraft:generic","scaling_profile":"test:attack_damage"},"as":"saved"},
               {"type":"chorus:write_damage_snapshot","buff":"test:carry","component":"attack","snapshot":"saved"}]},
             {"id":"fire","on":"test:fire","do":[
               {"action":{"type":"chorus:read_damage_snapshot","buff":"test:carry","component":"attack"},"as":"saved"},
               {"type":"chorus:damage_snapshot","snapshot":"saved","target":"victim"}]}]
            """));
        return data;
    }
    private static EffectState empty(long now){return EffectState.empty().withBuffs(new BuffStore(now,1,Map.of()));}
    private static DamageCommand attack(){return new DamageCommand(V,SOURCE.origin(),10,"minecraft:generic",Set.of(),Set.of(),false,Optional.of("test:attack_damage"));}
    private static EffectClock clock(){return new EffectClock((_,_)->new EffectClock.Rate(0,List.of()));}
    private record Hit(String domain,DamageCommand command,double amount){}
    private static final class Fixture {
        final CompiledEffects program;
        final Map<String,EffectSession> sessions=new TreeMap<>();
        final Map<String,EffectProgram> programs=new TreeMap<>();
        final List<Hit> hits=new ArrayList<>();
        ResourceSessionAuthority host;
        ParticipantPlacement.Directory placement;
        Fixture()throws Exception{this(data());}
        Fixture(JsonObject data){
            program=compile(data).withResourceRouting();
            for(String domain:List.of(A,B,C)){
                long now=domain.equals(A)?HIGH:domain.equals(B)?LOW:50_000_000;
                var state=empty(now);
                if(domain.equals(A))state=grant(state.withSource(SOURCE).withResource(new ResourceState(
                        new ResourceState.Key(H,"test:power"),.3,1,now)),"test:empower",H,1);
                sessions.put(domain,new EffectSession(program,clock(),1,state,r->execute(domain,r)));
                programs.put(domain,program.program());
            }
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();
            sessions.forEach((domain,session)->bindings.put(domain,new ResourceSessionAuthority.Binding(program,session)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);
            host.claim(H,A);host.enrollParticipants(Map.of(H,A,V,B),Map.of(H,A,V,B));placement=host.participants();
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request){
            if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);
            if(request.command() instanceof DamageCommand damage){
                var view=host.readView(domain,request).combatParticipants(placement,programs);
                double amount=program.outgoing(view,damage,damage.amount()).orElseThrow().output().value();
                hits.add(new Hit(domain,damage,amount));
                return new DamageReceipt(domain+"/"+request.id(),DamageReceipt.Outcome.APPLIED,0,0,amount,Optional.empty(),false);
            }
            throw new AssertionError(request.command());
        }
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        ResourceReadView view(String domain){return host.readView(domain).combatParticipants(placement,programs);}
        EffectState grant(EffectState state,String buff,String holder,int count){
            return state.withBuffs(Buffs.grant(state.buffs(),program.buff(buff),holder,holder,SOURCE.origin(),count,1,30_000_000).store());
        }
        void change(String domain,UnaryOperator<EffectState> change){
            var session=sessions.get(domain);var before=session.coordinationState();
            EffectSession.commitDomains(List.of(session.prepareInboxDomain(before,change.apply(state(domain)),List.of())));
        }
        void grant(String domain,String buff,String holder,int count){change(domain,s->grant(s,buff,holder,count));}
        void remove(String domain,String buff,String holder){change(domain,s->{
            var instances=new TreeMap<>(s.buffs().instances());
            instances.entrySet().removeIf(e->e.getKey().holder().equals(holder)&&e.getKey().definition().equals(buff));
            return s.withBuffs(new BuffStore(s.buffs().timeMicros(),s.buffs().nextGeneration(),instances));
        });}
        void send(String domain,String type){
            sessions.get(domain).start(state(domain).buffs().timeMicros(),new RuleEngine.Signal(type,
                    new EffectEvent(H,V,SOURCE.origin(),Set.of(),Map.of())));
            assertTrue(sessions.get(domain).state().idle());assertFalse(host.drain(100).pending());
        }
        DamageSnapshot capture(){send(A,"test:capture");return saved(A);}
        DamageSnapshot saved(String domain){return state(domain).buffs().instances().values().stream()
                .filter(b->b.key().holder().equals(H)&&b.key().definition().equals(CARRY)).findFirst().orElseThrow()
                .components().damageSnapshots().get("attack").orElseThrow();}
        void move(String from,String to){host.commit(host.prepareCharacterTransfer(H,from,to,Set.of()));placement=host.participants();}
        void restore(){
            var before=host.checkpoint();var encoded=ResourceCheckpointTest.archive(before).encode();
            var saved=CheckpointArchive.decode(encoded).resourceCheckpoint().orElseThrow();
            var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            for(String domain:List.of(A,B,C))policies.put(domain,new ResourceSessionAuthority.RestoreBinding(program,clock(),1,r->execute(domain,r)));
            var restored=ResourceSessionAuthority.restore(saved,policies);host=restored.authority();sessions.clear();
            restored.bindings().forEach((domain,binding)->sessions.put(domain,binding.session()));placement=host.participants();
            assertEquals(before,host.checkpoint());
        }
        double hit(ResourceReadView view,DamageSnapshot snapshot){return program.outgoing(view,snapshot.command(V),10).orElseThrow().output().value();}
        void rejects(ResourceReadView view,DamageSnapshot snapshot){
            assertThrows(IllegalArgumentException.class,()->program.prepareDamage(view.state(),snapshot.command(V),Optional.of(view)));
            assertThrows(IllegalArgumentException.class,()->hit(view,snapshot));
        }
    }

    @Test void actualDslCaptureTravelsToLowerClockAndKeepsOnUseFrozenWhileOnHitStaysLive()throws Exception{
        var f=new Fixture();var snapshot=f.capture();var origin=snapshot.captureOrigin().orElseThrow();
        assertEquals(A,origin.domain());assertEquals(f.host.snapshot().timelines().get(A),origin.timeline());
        assertEquals(HIGH,snapshot.capturedAt());f.move(A,B);assertEquals(snapshot,f.saved(B));
        f.grant(B,"test:empower",H,2);f.send(B,"test:fire");assertEquals(12,f.hits.getLast().amount(),1e-9);
        f.grant(B,"test:live",H,1);f.send(B,"test:fire");assertEquals(14,f.hits.getLast().amount(),1e-9);
        f.remove(B,"test:live",H);f.grant(B,"test:marked",V,1);f.send(B,"test:fire");
        assertEquals(13.5,f.hits.getLast().amount(),1e-9);
        assertTrue(f.hits.stream().allMatch(hit->hit.domain().equals(B)&&hit.command().snapshot().equals(Optional.of(snapshot))));
        assertEquals(LOW,f.state(B).buffs().timeMicros());assertEquals(HIGH,f.saved(B).capturedAt());
    }

    @Test void characterRoundTripAndJointEncodedRestorePreserveTheOriginalCaptureTimeline()throws Exception{
        var f=new Fixture();var snapshot=f.capture();var timelines=f.host.snapshot().timelines();
        f.move(A,B);f.send(B,"test:fire");f.restore();
        assertEquals(timelines,f.host.snapshot().timelines());assertEquals(snapshot,f.saved(B));assertEquals(1,f.hits.size());
        f.send(B,"test:fire");f.move(B,A);f.restore();f.send(A,"test:fire");
        assertEquals(timelines,f.host.snapshot().timelines());assertEquals(snapshot,f.saved(A));
        assertEquals(List.of(12.,12.,12.),f.hits.stream().map(Hit::amount).toList());
        assertEquals(snapshot.captureOrigin(),f.saved(A).captureOrigin());assertEquals(HIGH,f.saved(A).capturedAt());
    }

    @Test void equalBuffGenerationsAndModifierIdsRemainDistinctAcrossCaptureAndImpactAfterRestore()throws Exception{
        var data=data();
        data.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0)
                .getAsJsonObject().getAsJsonObject("group").addProperty("reduction","sum");
        for(var element:data.getAsJsonArray("bundles")){
            var bundle=element.getAsJsonObject();
            if(Set.of("test:empower_active","test:live_active").contains(bundle.get("id").getAsString()))
                bundle.getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("id","bonus");
        }
        var f=new Fixture(data);f.remove(A,"test:empower",H);f.grant(A,"test:empower",H,1);
        assertEquals(2,f.state(A).buffs().instances().values().stream()
                .filter(b->b.key().definition().equals("test:empower")).findFirst().orElseThrow().generation());
        var snapshot=f.capture();var frozen=snapshot.contributions().stream()
                .filter(c->c.modifier().id().equals("bonus")).findFirst().orElseThrow();
        f.remove(A,"test:empower",H);f.move(A,B);f.grant(B,"test:live",H,1);
        assertEquals(2,f.state(B).buffs().instances().values().stream()
                .filter(b->b.key().definition().equals("test:live")).findFirst().orElseThrow().generation());
        var result=f.program.outgoing(f.view(B),snapshot.command(V),10).orElseThrow();
        var contributions=result.inputs().contributions();
        assertEquals(16,result.output().value(),1e-9);assertEquals(2,contributions.size());
        assertTrue(contributions.stream().allMatch(c->c.id().endsWith("/buff/2/modifier/bonus")));
        assertEquals(2,contributions.stream().map(c->c.id()).distinct().count());
        assertEquals(Set.of("test:empower","test:live"),contributions.stream().map(c->c.stackingKey()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(contributions.stream().anyMatch(c->c.id().equals(frozen.instance()+"/modifier/bonus")));
        f.restore();
        assertEquals(snapshot,f.saved(B));
        var restored=f.program.outgoing(f.view(B),f.saved(B).command(V),10).orElseThrow();
        assertEquals(result.inputs(),restored.inputs());assertEquals(result.trace(),restored.trace());
        f.send(B,"test:fire");assertEquals(16,f.hits.getLast().amount(),1e-9);
    }

    @Test void providerContributionIdentityIsIndependentOfTheRootQueryDomain()throws Exception{
        var f=new Fixture();f.grant(A,"test:live",H,1);f.grant(B,"test:marked",V,1);
        var expected=f.program.outgoing(f.view(A),attack(),10).orElseThrow().inputs().contributions();
        assertTrue(expected.stream().anyMatch(c->c.id().contains("/source/")));
        assertTrue(expected.stream().anyMatch(c->c.id().contains("/buff/")));
        assertTrue(expected.stream().allMatch(c->c.id().startsWith("timeline/")));
        for(String root:List.of(B,C))
            assertEquals(expected,f.program.outgoing(f.view(root),attack(),10).orElseThrow().inputs().contributions());
        var nested=f.view(C).characterView(H);
        assertEquals(expected,f.program.outgoing(nested,attack(),10).orElseThrow().inputs().contributions());
        f.restore();
        for(String root:List.of(A,B,C))
            assertEquals(expected,f.program.outgoing(f.view(root),attack(),10).orElseThrow().inputs().contributions());
    }

    @Test void captureDomainIsIndependentOfAllThreeParticipantFacets()throws Exception{
        var f=new Fixture();f.host.commit(f.host.prepareTransferWithBuffs(H,A,C));
        f.host.commit(f.host.prepareTransfer(H,B));f.placement=f.host.participants();
        assertEquals(A,f.placement.characters().get(H).domain());assertEquals(C,f.placement.buffs().get(H).domain());
        assertEquals(B,f.host.snapshot().directory().owner(H).lease().domain());
        var view=f.view(B);var snapshot=f.program.captureDamage(view.state(),attack(),Optional.of(view));
        assertEquals(B,snapshot.captureOrigin().orElseThrow().domain());assertEquals(LOW,snapshot.capturedAt());
        assertEquals(12,f.hit(f.view(C),snapshot),1e-9);
    }

    @Test void nestedAndLocalProjectionViewsKeepEveryCapturedTimeline()throws Exception{
        var f=new Fixture();var snapshot=f.capture();
        f.host.commit(f.host.prepareTransferWithBuffs(H,A,C));f.placement=f.host.participants();
        var root=f.view(B);var nested=root.buffView(H);
        assertEquals(C,nested.domain());assertEquals(C,nested.captureOrigin().orElseThrow().domain());
        assertEquals(12,f.hit(nested,snapshot),1e-9);
        var projected=nested.withLocalState(f.grant(nested.state(),"test:live",H,1));
        assertNotSame(nested.state(),projected.state());
        assertEquals(nested.captureOrigin(),projected.captureOrigin());assertEquals(14,f.hit(projected,snapshot),1e-9);
        var resourceScope=projected.owner(H);assertEquals(C,resourceScope.domain());
        assertEquals(14,f.hit(resourceScope.characterView(H),snapshot),1e-9);
    }

    @Test void originalTimelineCannotRewindEvenWhenImpactClockIsLater()throws Exception{
        var f=new Fixture();var snapshot=f.capture();f.move(A,B);
        var original=f.host.snapshot();var states=new TreeMap<>(original.domains());states.put(A,empty(HIGH-1));
        states.put(C,empty(HIGH+1));
        var rewound=new ResourceAuthority.Worlds(original.directory(),states,original.timelines());
        for(String impact:List.of(A,B,C)){
            var view=new ResourceReadView(rewound,impact,f.program.program()).combatParticipants(f.placement,f.programs);
            f.rejects(view,snapshot);
        }
        assertEquals(12,f.hit(f.view(B),snapshot),1e-9);
    }

    @Test void sameNamedNewAuthorityCannotClaimAnOldSnapshotsTimeline()throws Exception{
        var original=new Fixture();var snapshot=original.capture();var rebuilt=new Fixture();
        assertNotEquals(original.host.snapshot().timelines().get(A),rebuilt.host.snapshot().timelines().get(A));
        assertEquals(HIGH,rebuilt.state(A).buffs().timeMicros());
        rebuilt.rejects(rebuilt.view(A),snapshot);rebuilt.rejects(rebuilt.view(B),snapshot);
    }

    @Test void qualifiedSnapshotRejectsMissingOriginMissingMetadataAndChangedImpactMode()throws Exception{
        var f=new Fixture();var snapshot=f.capture();f.move(A,B);var worlds=f.host.snapshot();
        var noMetadata=new ResourceReadView(new ResourceAuthority.Worlds(worlds.directory(),worlds.domains()),B,f.program.program());
        f.rejects(noMetadata,snapshot);
        var states=new TreeMap<>(worlds.domains());states.remove(A);var identities=new TreeMap<>(worlds.timelines());identities.remove(A);
        var noOrigin=new ResourceReadView(new ResourceAuthority.Worlds(worlds.directory(),states,identities),B,f.program.program());
        f.rejects(noOrigin,snapshot);
        var wrongMode=f.view(B).withLocalState(f.state(B).withMode(EffectState.Mode.PVP));
        f.rejects(wrongMode,snapshot);
        var local=compile(data());assertThrows(IllegalArgumentException.class,()->local.outgoing(empty(HIGH),snapshot.command(V),10));
        assertThrows(IllegalArgumentException.class,()->local.prepareDamage(empty(HIGH),snapshot.command(V)));
    }

    @Test void futureCaptureCannotBorrowALaterImpactClockFromAnotherDomain()throws Exception{
        var f=new Fixture();var snapshot=f.capture();
        var future=new DamageSnapshot(HIGH+1,snapshot.mode(),snapshot.attack(),snapshot.profile(),snapshot.buffs(),
                snapshot.resources(),snapshot.contributions(),snapshot.shieldScaling(),snapshot.captureOrigin());
        var worlds=f.host.snapshot();var states=new TreeMap<>(worlds.domains());states.put(C,empty(HIGH+100));
        var view=new ResourceReadView(new ResourceAuthority.Worlds(worlds.directory(),states,worlds.timelines()),C,f.program.program())
                .combatParticipants(f.placement,f.programs);
        f.rejects(view,future);assertEquals(12,f.hit(view,snapshot),1e-9);
    }

    @Test void coordinatedCaptureCannotSilentlyProduceLegacyWithoutTimelineMetadata()throws Exception{
        var f=new Fixture();var worlds=f.host.snapshot();
        var view=new ResourceReadView(new ResourceAuthority.Worlds(worlds.directory(),worlds.domains()),A,f.program.program());
        assertThrows(IllegalArgumentException.class,()->f.program.captureDamage(view.state(),attack(),Optional.of(view)));
        assertThrows(IllegalStateException.class,()->f.program.captureDamage(f.state(A),attack()));
    }

    @Test void legacySnapshotsKeepTheirOriginalLocalTimeCheckWithoutInferredOrigins()throws Exception{
        var f=new Fixture();var current=f.capture();
        var legacy=new DamageSnapshot(current.capturedAt(),current.mode(),current.attack(),current.profile(),current.buffs(),
                current.resources(),current.contributions(),current.shieldScaling());
        assertTrue(legacy.captureOrigin().isEmpty());f.move(A,B);
        f.rejects(f.view(B),legacy);
        assertEquals(12,f.hit(f.view(A),legacy),1e-9);
        var local=compile(data());assertThrows(IllegalArgumentException.class,()->local.outgoing(empty(LOW),legacy.command(V),10));
        assertEquals(12,local.outgoing(empty(HIGH),legacy.command(V),10).orElseThrow().output().value(),1e-9);
        assertTrue(EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(legacy),DamageSnapshot.class).captureOrigin().isEmpty());
    }
}
