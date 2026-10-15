package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ParticipantAuthorityTest {
    static final String A="test:a",B="test:b",C="test:c",H="player",G="carried-weapon",LEFT="left-weapon";
    static final ResourceState.Key ENERGY=new ResourceState.Key(H,"test:energy");
    static final EffectSource SOURCE=EffectTestSupport.source("test:input");
    static final class Fixture {
        final CompiledEffects program=EffectTestSupport.compile(JsonParser.parseString("""
          {"version":"participant-authority","resources":[{"id":"test:energy","capacity":1,"initial":1}],
           "buffs":[{"definition":{"id":"test:carry","version":"participant-authority","duration":2}}],
           "bundles":[{"id":"test:input","rules":[]}]}
          """).getAsJsonObject()).withResourceRouting();
        final Map<String,EffectSession> sessions=new TreeMap<>();ResourceSessionAuthority host;
        Fixture(){this(true);}
        Fixture(boolean populated){
            for(String domain:List.of(A,B,C)){
                long time=domain.equals(A)?1_000_000:domain.equals(B)?50_000:100_000;
                var state=EffectState.empty().withBuffs(new BuffStore(time,1,Map.of()));
                if(domain.equals(A)&&populated){state=state.withSource(SOURCE).withAmmo(new AmmoState(G,7,10,Optional.empty())).withResource(new ResourceState(ENERGY,.4,1,time));state=grant(state,H);}
                sessions.put(domain,new EffectSession(program,EffectCheckpointTest.clock(),1,state,r->execute(domain,r)));
            }
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();sessions.forEach((d,s)->bindings.put(d,new ResourceSessionAuthority.Binding(program,s)));
            host=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,bindings);host.claim(H,A);host.enrollParticipants(Map.of(H,A),Map.of(H,A));
        }
        RuleEngine.ActionResult execute(String domain,RuleEngine.WorldRequest request){if(request.command() instanceof ResourceRequests.Request)return host.execute(domain,request);throw new AssertionError(request.command());}
        EffectState grant(EffectState state,String holder){return state.withBuffs(Buffs.grant(state.buffs(),program.buff("test:carry"),holder,holder,SOURCE.origin(),1,1,2_000_000).store());}
        void grant(String domain,String holder){var session=sessions.get(domain);EffectSession.commitDomains(List.of(session.prepareInboxDomain(session.coordinationState(),grant(state(domain),holder),List.of())));}
        EffectState state(String domain){return sessions.get(domain).state().engine().domain();}
        ParticipantPlacement.Lease character(){return host.participants().characters().get(H);}
        ParticipantPlacement.Lease buff(String holder){return host.participants().buffs().get(holder);}
        List<BuffInstance> buffs(String domain,String holder){return state(domain).buffs().instances().values().stream().filter(b->b.key().holder().equals(holder)).toList();}
        ResourceSessionAuthority.CharacterHandoff move(String from,String to,Set<String> carried){var plan=host.prepareCharacterTransfer(H,from,to,carried);host.commit(plan);return plan;}
        void restore(){
            var saved=host.checkpoint();var policies=new TreeMap<String,ResourceSessionAuthority.RestoreBinding>();
            for(String domain:List.of(A,B,C))policies.put(domain,new ResourceSessionAuthority.RestoreBinding(program,EffectCheckpointTest.clock(),1,r->execute(domain,r)));
            var restored=ResourceSessionAuthority.restore(saved,policies);host=restored.authority();sessions.clear();restored.bindings().forEach((d,b)->sessions.put(d,b.session()));assertEquals(saved,host.checkpoint());
        }
    }

    @Test void explicitFacetEnrollmentIsIdempotentAndAConflictingBatchPublishesNothing(){
        var f=new Fixture(false);var before=f.host.checkpoint();f.host.enrollParticipants(Map.of(H,A),Map.of(H,A));assertEquals(before,f.host.checkpoint());
        assertThrows(IllegalStateException.class,()->f.host.enrollParticipants(Map.of("new-character",B,H,C),Map.of("new-buff",B)));
        assertEquals(before,f.host.checkpoint());assertFalse(f.host.participants().characters().containsKey("new-character"));assertFalse(f.host.participants().buffs().containsKey("new-buff"));
        f.host.enrollParticipants(Map.of("new-character",B),Map.of("new-buff",C));
        assertEquals(B,f.host.participants().characters().get("new-character").domain());assertEquals(C,f.host.participants().buffs().get("new-buff").domain());
        assertEquals(before.placement().characters().get(H),f.character());assertEquals(before.placement().buffs().get(H),f.buff(H));
    }

    @Test void movingOnlyResourcesPreservesBothFacetsAndEveryCharacterAndBuffProjection(){
        var f=new Fixture();var placement=f.host.participants();var sources=f.state(A).sources();var buffs=f.state(A).buffs();var ammo=f.state(A).ammunition();
        f.host.commit(f.host.prepareTransfer(H,B));assertEquals(B,f.host.snapshot().directory().owner(H).lease().domain());assertEquals(.4,f.state(B).resources().get(ENERGY).value());
        assertEquals(placement,f.host.participants());assertEquals(sources,f.state(A).sources());assertEquals(buffs,f.state(A).buffs());assertEquals(ammo,f.state(A).ammunition());
        assertTrue(f.state(A).resources().isEmpty());assertTrue(f.state(B).sources().isEmpty());assertTrue(f.state(B).buffs().instances().isEmpty());
    }

    @Test void resourceAndBuffHandoffChangesOnlyTheBuffFacetAndLeavesCharacterLocationIntact(){
        var f=new Fixture();var character=f.character();var buff=f.buff(H);var sources=f.state(A).sources();
        var before=f.host.checkpoint();var plan=f.host.prepareTransferWithBuffs(H,A,C);assertEquals(before,f.host.checkpoint());f.host.commit(plan);
        assertEquals(character,f.character());assertEquals(C,f.buff(H).domain());assertTrue(f.buff(H).generation()>buff.generation());
        assertEquals(C,f.host.snapshot().directory().owner(H).lease().domain());assertEquals(sources,f.state(A).sources());assertEquals(1,f.buffs(C,H).size());assertTrue(f.buffs(A,H).isEmpty());
        assertTrue(f.state(C).sources().isEmpty());assertTrue(f.state(A).ammunition().containsKey(G));
    }

    @Test void characterHandoffFetchesPlayerBuffsFromTheirRegisteredThirdDomain(){
        var f=new Fixture();f.host.commit(f.host.prepareTransferWithBuffs(H,A,C));var character=f.character();var buff=f.buff(H);var source=f.state(A).sources();
        long remaining=f.buffs(C,H).getFirst().deadline()-f.state(C).buffs().timeMicros();var before=f.host.checkpoint();
        var plan=f.host.prepareCharacterTransfer(H,A,B,Set.of(G));assertEquals(before,f.host.checkpoint());f.host.commit(plan);
        assertEquals(B,f.character().domain());assertTrue(f.character().generation()>character.generation());assertEquals(B,f.buff(H).domain());assertTrue(f.buff(H).generation()>buff.generation());
        assertEquals(source,f.state(B).sources());assertTrue(f.state(A).sources().isEmpty());assertTrue(f.buffs(C,H).isEmpty());assertEquals(remaining,f.buffs(B,H).getFirst().deadline()-f.state(B).buffs().timeMicros());
        assertEquals(B,f.host.snapshot().directory().owner(H).lease().domain());assertEquals(7,f.state(B).ammunition().get(G).magazine());assertTrue(f.state(A).ammunition().isEmpty());
        assertTrue(plan.buffTransfers().keySet().containsAll(Set.of(H,G)));
    }

    @Test void alreadyResidentBuffPartitionKeepsItsLeaseAndInstanceDuringCharacterArrival(){
        var f=new Fixture();f.host.commit(f.host.prepareTransferWithBuffs(H,A,B));
        var resident=f.buff(H);var buffs=f.state(B).buffs();var resources=f.host.snapshot().directory().owner(H).lease();
        var plan=f.move(A,B,Set.of(G));
        assertEquals(B,f.character().domain());assertEquals(resident,f.buff(H));assertEquals(buffs,f.state(B).buffs());
        assertEquals(resources,f.host.snapshot().directory().owner(H).lease());
        assertTrue(plan.buffs().identities().isEmpty());assertTrue(plan.buffs().timerIds().isEmpty());
        assertEquals(Set.of(H,G),plan.buffTransfers().keySet());f.restore();assertEquals(resident,f.buff(H));
    }

    @Test void emptyFacetsKeepDistinctLeasesAndAdvanceGenerationsAcrossRoundTrips(){
        var f=new Fixture(false);var initial=f.host.participants();var outward=f.move(A,B,Set.of());var atB=f.host.participants();
        assertEquals(B,f.character().domain());assertEquals(B,f.buff(H).domain());assertNotEquals(f.character(),f.buff(H));
        assertTrue(f.character().generation()>initial.characters().get(H).generation());assertTrue(f.buff(H).generation()>initial.buffs().get(H).generation());
        f.move(B,A,Set.of());assertEquals(A,f.character().domain());assertEquals(A,f.buff(H).domain());
        assertTrue(f.character().generation()>atB.characters().get(H).generation());assertTrue(f.buff(H).generation()>atB.buffs().get(H).generation());
        var returned=f.host.checkpoint();assertThrows(IllegalStateException.class,()->f.host.commit(outward));assertEquals(returned,f.host.checkpoint());
        for(String domain:List.of(A,B,C))assertTrue(f.state(domain).sources().isEmpty()&&f.state(domain).buffs().instances().isEmpty()&&f.state(domain).resources().isEmpty());
    }

    @Test void carriedWeaponBuffsUseTheirRegisteredDomainOrExplicitSourceWithoutMovingLeftBehindBuffs(){
        for(boolean registered:List.of(false,true)){
            var f=new Fixture();String from=registered?C:A;f.grant(from,G);f.grant(A,LEFT);
            if(registered)f.host.enrollParticipants(Map.of(),Map.of(G,C));f.host.enrollParticipants(Map.of(),Map.of(LEFT,A));var left=f.buff(LEFT);
            var original=f.buffs(from,G).getFirst();long remaining=original.deadline()-f.state(from).buffs().timeMicros();var plan=f.move(A,B,Set.of(G));
            assertEquals(B,f.buff(G).domain());assertFalse(f.host.participants().characters().containsKey(G));assertEquals(1,f.buffs(B,G).size());assertTrue(f.buffs(from,G).isEmpty());
            assertEquals(remaining,f.buffs(B,G).getFirst().deadline()-f.state(B).buffs().timeMicros());assertEquals(left,f.buff(LEFT));assertEquals(1,f.buffs(A,LEFT).size());assertTrue(f.buffs(B,LEFT).isEmpty());
            assertTrue(plan.buffTransfers().containsKey(G));assertEquals(7,f.state(B).ammunition().get(G).magazine());
        }
    }

    @Test void unregisteredCarriedBuffCopiesInAnotherDomainRejectTheWholeCharacterPlan(){
        var f=new Fixture();f.grant(A,G);f.grant(C,G);var before=f.host.checkpoint();
        assertThrows(IllegalStateException.class,()->f.host.prepareCharacterTransfer(H,A,B,Set.of(G)));
        assertEquals(before,f.host.checkpoint());assertEquals(A,f.character().domain());assertEquals(A,f.buff(H).domain());assertFalse(f.host.participants().buffs().containsKey(G));
    }

    @Test void restorePreservesIndependentFacetsBeforeAThreeDomainCharacterHandoff(){
        var f=new Fixture();f.host.commit(f.host.prepareTransferWithBuffs(H,A,C));f.host.commit(f.host.prepareTransfer(H,B));
        var placement=f.host.participants();var resources=f.host.snapshot().directory();f.restore();assertEquals(placement,f.host.participants());assertEquals(resources,f.host.snapshot().directory());
        assertEquals(A,f.character().domain());assertEquals(C,f.buff(H).domain());assertEquals(B,f.host.snapshot().directory().owner(H).lease().domain());
        f.move(A,B,Set.of(G));assertEquals(B,f.character().domain());assertEquals(B,f.buff(H).domain());f.restore();assertEquals(B,f.character().domain());
    }

    @Test void enrollmentAfterPreparationInvalidatesAPlanWithoutPartialFacetOrResourcePublication(){
        var f=new Fixture();var plan=f.host.prepareCharacterTransfer(H,A,B,Set.of(G));f.host.enrollParticipants(Map.of("neighbor",C),Map.of());var before=f.host.checkpoint();
        assertThrows(IllegalStateException.class,()->f.host.commit(plan));assertEquals(before,f.host.checkpoint());assertEquals(A,f.character().domain());assertEquals(A,f.buff(H).domain());assertEquals(A,f.host.snapshot().directory().owner(H).lease().domain());
    }
}
