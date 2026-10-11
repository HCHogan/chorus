package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TargetGateTest {
    static EffectSource source(String id,String bundle,String holder){return new EffectSource(id,"test:"+bundle,holder,new BuffInstance.Origin("grantor",id,"",""),Set.of());}
    static EffectEvent input(String actor,String victim,String weapon){return new EffectEvent(actor,victim,new BuffInstance.Origin(actor,"shot",weapon,"test:ability",Set.of("test:trackable_origin")),Set.of("chorus:projectile_tracking"),Map.of());}
    static ActionGate.Decision check(CompiledEffects p,EffectState state,String actor,String target,ActionGate.Phase phase){return p.checkAction(state,ActionGate.Kind.PROJECTILE_TRACKING,phase,input(actor,target,"weapon"));}
    @Test void targetAndActorRestrictionsUseTheirDeclaredHolderAndPreserveActualOrigins()throws Exception {
        var p=load("target_gates");var state=EffectState.empty().withSource(source("a","actor_gate","shooter")).withSource(source("b","target_gate","target")).withSource(source("other","target_gate","elsewhere"));
        var r=check(p,state,"shooter","target",ActionGate.Phase.START);assertEquals(2,r.denials().size());assertEquals(List.of("shooter","target"),r.denials().stream().map(ActionGate.Denial::holder).toList());assertEquals(List.of(ActionGate.Subject.ACTOR,ActionGate.Subject.VICTIM),r.denials().stream().map(ActionGate.Denial::subject).toList());assertTrue(r.denials().stream().allMatch(d->d.origin().owner().equals("grantor")));
        assertEquals(1,check(p,state,"shooter","other",ActionGate.Phase.START).denials().size());assertEquals(1,check(p,state,"other","target",ActionGate.Phase.START).denials().size());assertTrue(check(p,state,"other","else",ActionGate.Phase.START).allowed());
        assertEquals(r,p.checkAction(state,r.action(),r.phase(),r.query()));assertThrows(UnsupportedOperationException.class,()->r.denials().clear());
    }
    @Test void identicalActorAndVictimDoNotDuplicateADeclarationAndMissingVictimDoesNotMatch()throws Exception {
        var p=load("target_gates");var state=EffectState.empty().withSource(source("one","target_gate","same"));
        assertEquals(1,check(p,state,"same","same",ActionGate.Phase.START).denials().size());assertTrue(check(p,state,"same","",ActionGate.Phase.START).allowed());
        assertFalse(check(p,state,"","same",ActionGate.Phase.START).allowed());assertThrows(IllegalArgumentException.class,()->check(p,state,"","",ActionGate.Phase.START));
    }
    @Test void acquisitionRetentionAndOrdinaryMeleeCanUseIndependentTargetPolicies()throws Exception {
        var p=load("target_gates");var state=EffectState.empty().withSource(source("retain","retain_gate","target")).withSource(source("melee","melee_target","target"));
        assertTrue(check(p,state,"shooter","target",ActionGate.Phase.START).allowed());assertFalse(check(p,state,"shooter","target",ActionGate.Phase.CONTINUE).allowed());
        assertFalse(p.checkAction(state,ActionGate.Kind.MELEE_ATTACK,ActionGate.Phase.START,input("shooter","target","")).allowed());assertTrue(p.checkAction(state,ActionGate.Kind.WEAPON_FIRE,ActionGate.Phase.START,input("shooter","target","")).allowed());
    }
    @Test void targetBuffExpiryPauseAndWeaponAffinityKeepTheirExistingMeaning()throws Exception {
        var p=load("target_gates");var origin=new BuffInstance.Origin("grantor","cloak","weapon","");
        var state=EffectState.empty().withBuffs(Buffs.grant(EffectState.empty().buffs(),p.buff("test:tracking_weapon_hidden"),"target","target",origin,1,1,200_000).store());
        assertFalse(check(p,state,"shooter","target",ActionGate.Phase.START).allowed());assertTrue(p.checkAction(state,ActionGate.Kind.PROJECTILE_TRACKING,ActionGate.Phase.START,input("shooter","target","other_weapon")).allowed());
        var paused=state.withBuffs(Buffs.weaponState(state.buffs(),"grantor","weapon",true).store());assertTrue(check(p,paused,"shooter","target",ActionGate.Phase.START).allowed());
        var session=new EffectSession(engine(p),state,_->{throw new AssertionError("query and expiry must not issue world actions");});session.observe(200_000,List.of());assertTrue(check(p,session.state().engine().domain(),"shooter","target",ActionGate.Phase.CONTINUE).allowed());
    }
    @Test void queryOnlyEvaluationRetainsSourceTagsAndDoesNotInventMissingMeasurements()throws Exception {
        var p=load("target_gates");var before=EffectState.empty().withSource(source("target","target_gate","target"));var query=input("shooter","target","");assertFalse(p.checkAction(before,ActionGate.Kind.PROJECTILE_TRACKING,ActionGate.Phase.START,query).allowed());
        var other=new EffectEvent(query.actor(),query.victim(),new BuffInstance.Origin("shooter","shot","",""),query.tags(),Map.of());assertTrue(p.checkAction(before,ActionGate.Kind.PROJECTILE_TRACKING,ActionGate.Phase.START,other).allowed());assertEquals(1,before.sources().size());assertTrue(before.buffs().instances().isEmpty());
        var bad=before.withSource(source("bad","bad_tracking","target"));assertThrows(IllegalArgumentException.class,()->p.checkAction(bad,ActionGate.Kind.PROJECTILE_TRACKING,ActionGate.Phase.START,query));
    }
    @Test void declarationsRoundtripDefaultToActorAndRejectUnknownSubjects()throws Exception {
        var p=load("target_gates");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
        assertTrue(load("action_gates").program().bundles().stream().flatMap(b->b.actionGates().stream()).allMatch(g->g.subject()==ActionGate.Subject.ACTOR));
        var data=json("target_gates");data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("action_gates").get(0).getAsJsonObject().addProperty("subject","both");assertThrows(RuntimeException.class,()->compile(data));
        var denial=new ActionGate.Denial("gate","test:target_gate","source",new BuffInstance.Origin("grantor","source","",""),ActionGate.Subject.VICTIM,"wrong");assertThrows(IllegalArgumentException.class,()->new ActionGate.Decision(ActionGate.Kind.PROJECTILE_TRACKING,ActionGate.Phase.START,input("shooter","target",""),List.of(denial)));
    }
    @Test void launchOriginComesFromTheFrozenSourceScopeIncludingItsTags()throws Exception {
        var source=new EffectSource("source","test:projectile","holder",new BuffInstance.Origin("shooter","source","weapon","ability",Set.of("test:origin")),Set.of("test:trackable_origin"));
        var cause=new RuleEngine.Event(1,1,Optional.empty(),0,new RuleEngine.Signal("test:launch",input("shooter","target","weapon")));
        var pending=new EffectContinuations.Pending("flight","test:contact","test-1",source,cause,Map.of(),Optional.empty());
        var launch=new ProjectileFlight.Launch(Optional.of(new WorldPosition("world",0,0,0)),Optional.of(new WorldDirection("world",0,0,1)),new ProjectileFlight.Parameters(1,0,1,1_000_000),"shooter",pending,"impact");
        assertEquals(Set.of("test:origin","test:trackable_origin"),launch.origin().tags());assertEquals("weapon",launch.origin().weapon());assertEquals("ability",launch.origin().ability());
        var p=load("target_gates");var state=EffectState.empty().withSource(source("gate","target_gate","target"));var query=new EffectEvent(launch.owner(),"target",launch.origin(),Set.of(),Map.of());assertFalse(p.checkAction(state,ActionGate.Kind.PROJECTILE_TRACKING,ActionGate.Phase.START,query).allowed());assertFalse(state.sources().containsKey("source"));
    }
}
