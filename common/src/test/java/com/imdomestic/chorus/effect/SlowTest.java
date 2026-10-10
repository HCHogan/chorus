package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SlowTest {
    static final String S="chorus_d2:slow",F="chorus_d2:freeze";
    static EffectSource source(String id,String owner,double duration){var params=new HashMap<>(FreezeTest.source(id,owner,false).parameters());params.put("slow_duration",new Measure(duration,Unit.SECOND));params.put("slow_durance_extension",new Measure(0,Unit.SECOND));params.put("slow_jump_delta",new Measure(-.3,Unit.DELTA));return new EffectSource(id,"chorus_d2:slow_application",owner,new BuffInstance.Origin(owner,id,"",""),Set.of(),params);}
    static final class Harness {
        final CompiledEffects p;final EffectSession session;final EffectSource a=source("a","caster",2),b=source("b","other",.5);final Map<String,EntityQuery.View> entities=new HashMap<>();final List<StatusResult.Check> checks=new ArrayList<>();StatusResult.Decision freezeDecision=StatusResult.Decision.ALLOWED,slowDecision=StatusResult.Decision.ALLOWED;boolean failFreeze;
        Harness(boolean guardian,boolean playerCaster)throws Exception{
            this(guardian,playerCaster,link("slow","stasis_duration","freeze","freeze_test_falloff","combat_damage","movement_attributes","weapon_stats","slow_inputs"));
        }
        Harness(boolean guardian,boolean playerCaster,CompiledEffects program){
            p=program;entities.put("target",FreezeTest.view(guardian,"chorus_d2:elite"));entities.put("caster",FreezeTest.view(playerCaster));entities.put("other",FreezeTest.view(false));
            session=new EffectSession(engine(p),EffectState.empty().withSource(a).withSource(b),r->switch(r.command()){
                case EntityQuery q->new EntityQuery.Result(q,Optional.ofNullable(entities.get(q.target())));
                case StatusResult.Check q->{checks.add(q);if(q.definition().id().equals(F)&&failFreeze)throw new IllegalStateException("unknown freeze authorization");yield new StatusResult.Checked(q,q.definition().id().equals(F)?freezeDecision:slowDecision);}
                default->throw new AssertionError(r.command());
            });
        }
        EffectState state(){return session.state().engine().domain();}
        void event(EffectSource source,String name,double stacks){session.start(state().buffs().timeMicros(),new RuleEngine.Signal("chorus_d2:"+name,new EffectEvent(source.holder(),"target",source.origin(),Set.of(),Map.of("stacks",new Measure(stacks,Unit.COUNT)),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));}
        void apply(EffectSource source,double stacks){event(source,"apply_slow",stacks);}
        void until(long time){session.observe(time,List.of());}
        Optional<BuffInstance> buff(String name){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(name)).findFirst();}
        double stat(String name,double base){return p.calculate(state(),"target",new EffectEvent("target","target",new BuffInstance.Origin("target","stat","gun",""),Set.of(),Map.of()),"chorus_d2:weapon_"+name,new Measure(base,Unit.STAT_POINT),List.of()).output().value();}
        double nativeDelta(String attribute){return p.nativeAttributes(state(),"target").stream().filter(v->v.binding().attribute().equals("minecraft:"+attribute)).findFirst().orElseThrow().amount();}
    }
    @Test void exactlyOneHundredStacksReuseFreezeApplicationAndClearOnlyAfterSuccess()throws Exception{
        var h=new Harness(false,true);h.apply(h.a,40);assertEquals(40,h.buff(S).orElseThrow().count());h.apply(h.a,59);assertTrue(h.buff(F).isEmpty());h.apply(h.a,1);assertTrue(h.buff(S).isEmpty());assertEquals(1,h.buff(F).orElseThrow().tier());assertEquals(100,h.buff(F).orElseThrow().components().numbers().get("threshold"));assertEquals(1,h.checks.stream().filter(q->q.definition().id().equals(F)).count());assertEquals(h.a.origin(),h.buff(F).orElseThrow().origin());
        h.apply(h.a,100);assertEquals(4,h.checks.size());
    }
    @Test void finalContributingSourceOwnsFreezeWhileExistingSlowKeepsItsFirstCreditAndLongestDeadline()throws Exception{
        var h=new Harness(false,true);h.apply(h.a,40);h.until(500_000);h.apply(h.b,30);var slow=h.buff(S).orElseThrow();assertEquals(h.a.origin(),slow.origin());assertEquals(2_000_000,slow.deadline());h.apply(h.b,30);assertTrue(h.buff(S).isEmpty());assertEquals(h.b.origin(),h.buff(F).orElseThrow().origin());
    }
    @Test void guardianClassificationAndCasterSpecificFreezeDurationSurviveComposition()throws Exception{
        for(boolean playerCaster:List.of(false,true)){var h=new Harness(true,playerCaster);h.apply(h.a,100);assertEquals(playerCaster?1_350_000L:4_750_000L,h.checks.getLast().duration());assertTrue(h.buff(S).isEmpty());}
        var boss=new Harness(false,true);boss.entities.put("target",FreezeTest.view(false,"chorus_d2:boss"));boss.apply(boss.a,100);assertEquals(3,boss.buff(F).orElseThrow().tier());
    }
    @Test void deniedFreezeRetainsHundredSlowStacksAndASecondApplicationMayRetry()throws Exception{
        var h=new Harness(true,true);h.freezeDecision=StatusResult.Decision.DENIED;h.apply(h.a,120);assertEquals(100,h.buff(S).orElseThrow().count());assertTrue(h.buff(F).isEmpty());assertEquals(-.5,h.nativeDelta("movement_speed"));h.freezeDecision=StatusResult.Decision.ALLOWED;h.apply(h.a,1);assertTrue(h.buff(S).isEmpty());assertTrue(h.buff(F).isPresent());assertEquals(0,h.nativeDelta("movement_speed"));
    }
    @Test void independentFreezeAlsoClearsSlowAndThawNeverRestoresConsumedStacks()throws Exception{
        var h=new Harness(false,true);h.apply(h.a,40);h.event(h.a,"apply_freeze",0);assertTrue(h.buff(S).isEmpty());h.event(h.a,"clear_freeze",0);assertTrue(h.buff(S).isEmpty());h.apply(h.a,1);assertEquals(1,h.buff(S).orElseThrow().count());
    }
    @Test void guardianWeaponPenaltyIsAfterPerksBeforeCapAndCombatantsKeepWeaponStats()throws Exception{
        for(boolean guardian:List.of(false,true)){var h=new Harness(guardian,true);h.apply(h.a,1);for(String stat:List.of("stability","handling","reload","recoil_direction")){assertEquals(guardian?40:100,h.stat(stat,160));assertEquals(guardian?20:80,h.stat(stat,80));}assertEquals(-.5,h.nativeDelta("movement_speed"));assertEquals(guardian?-.3:0,h.nativeDelta("jump_strength"),1e-12);}
    }
    @Test void onlyGuardianMovementAbilitiesAreDeniedWhileWeaponsAndOrdinaryAbilitiesRemainAvailable()throws Exception{
        for(boolean guardian:List.of(false,true)){var h=new Harness(guardian,true);h.apply(h.a,1);var input=new EffectEvent("target","",h.a.origin(),Set.of("chorus:movement_ability"),Map.of());for(var action:ActionGate.Kind.values())assertEquals(!(guardian&&action==ActionGate.Kind.ABILITY_USE),h.p.checkAction(h.state(),action,ActionGate.Phase.START,input).allowed());
            var loadout=new AbilityLoadout(Map.of("test:slow_slot","test:slow_move"));h.session.start(0,new AbilityChange("target",AbilityLoadout.EMPTY,loadout).signal());var use=h.p.useAbility(h.state(),new AbilityUse.Request("target","test:slow_slot","cast",new EffectEvent("target","target",h.a.origin(),Set.of(),Map.of())));assertEquals(guardian?AbilityUse.Outcome.RESTRICTED:AbilityUse.Outcome.ACCEPTED,((AbilityUse.Receipt)use.result()).outcome());}
    }
    @Test void expiryAndCleanseReleasePenaltiesWithoutFreezingAndNewStacksStartFresh()throws Exception{
        for(boolean clear:List.of(false,true)){var h=new Harness(true,true);h.apply(h.a,99);if(clear)h.event(h.a,"clear_slow",0);else h.until(2_000_000);assertTrue(h.buff(S).isEmpty()&&h.buff(F).isEmpty());assertEquals(80,h.stat("handling",80));assertEquals(0,h.nativeDelta("movement_speed"));h.apply(h.a,1);assertEquals(1,h.buff(S).orElseThrow().count());}
    }
    @Test void invalidDeniedDeadMissingAndUnclassifiedFreezeRecipientsDoNotInventAConversion()throws Exception{
        var denied=new Harness(false,true);denied.slowDecision=StatusResult.Decision.DENIED;denied.apply(denied.a,100);assertTrue(denied.state().buffs().instances().isEmpty());
        var missing=new Harness(false,true);missing.entities.remove("target");missing.apply(missing.a,100);assertTrue(missing.checks.isEmpty());var dead=new Harness(false,true);dead.entities.put("target",new EntityQuery.View(false,false,0,1000,0));dead.apply(dead.a,100);assertTrue(dead.checks.isEmpty());var zero=new Harness(false,true);zero.apply(zero.a,0);zero.apply(zero.a,-1);assertTrue(zero.checks.isEmpty());
        var unknown=new Harness(false,true);unknown.entities.put("target",FreezeTest.view(false));unknown.apply(unknown.a,100);assertEquals(100,unknown.buff(S).orElseThrow().count());assertTrue(unknown.buff(F).isEmpty());
        var fractional=new Harness(false,true);assertThrows(IllegalStateException.class,()->fractional.apply(fractional.a,1.5));assertTrue(fractional.checks.isEmpty());
    }
    @Test void unknownFreezeAuthorizationKeepsCommittedSlowAndStopsWithoutReplaying()throws Exception{
        var h=new Harness(false,true);h.failFreeze=true;assertThrows(IllegalStateException.class,()->h.apply(h.a,100));assertEquals(100,h.buff(S).orElseThrow().count());assertTrue(h.buff(F).isEmpty());int requests=h.checks.size();assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(requests,h.checks.size());
    }
    @Test void composedProgramRoundtripsAndRequiresExplicitDurationJumpAndFreezeCalibration()throws Exception{
        var h=new Harness(false,true);assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());assertThrows(IllegalArgumentException.class,()->h.p.validateSource(new EffectSource("bad","chorus_d2:slow_application","caster",h.a.origin(),Set.of())));
    }
}
