package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BreakoutTest {
    static final String F="chorus_d2:freeze",B="chorus_d2:breakout";
    static EffectSource calibration(double cost,double floor,double delay){return new EffectSource("calibration","chorus_d2:breakout_calibration","target",new BuffInstance.Origin("target","calibration","",""),Set.of("chorus_d2:breakout_calibrated"),Map.of("health_cost",new Measure(cost,Unit.DAMAGE),"health_floor",new Measure(floor,Unit.DAMAGE),"breakout_time",new Measure(delay,Unit.SECOND)));}
    static final class Harness {
        final CompiledEffects p;final EffectSession session;final EffectSource caster=FreezeTest.source("caster","caster",false);final List<HealthPayment.Command> payments=new ArrayList<>();final List<Action.CueCommand> cues=new ArrayList<>();boolean missing,fault;double health=20;
        Harness(boolean targetPlayer,boolean casterPlayer,boolean calibrated)throws Exception{
            p=link("freeze","freeze_test_falloff","freeze_inputs","combat_damage","breakout_inputs");
            session=new EffectSession(engine(p),EffectState.empty().withSource(caster),r->switch(r.command()){
                case EntityQuery q->new EntityQuery.Result(q,Optional.of(FreezeTest.view(q.target().equals("target")?targetPlayer:casterPlayer,"chorus_d2:elite")));
                case StatusResult.Check q->new StatusResult.Checked(q,StatusResult.Decision.ALLOWED);
                case HealthPayment.Command q->{payments.add(q);var receipt=missing?HealthPayment.Receipt.unavailable(r.id().toString(),q,HealthPayment.Outcome.MISSING):HealthPayment.plan(r.id().toString(),q,health);if(receipt.paid())health=receipt.balance().orElseThrow().after();if(fault)throw new IllegalStateException("unknown health payment");yield receipt;}
                case Action.CueCommand q->{cues.add(q);yield RuleEngine.Empty.INSTANCE;}
                default->throw new AssertionError("Unexpected damage or world effect: "+r.command());
            });
            if(calibrated)send(SourceChange.bind(calibration(5,1,.5)));send(new AbilityChange("target",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("chorus_d2:class","test:class_cost","chorus_d2:super","test:super"))).signal());
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}void send(RuleEngine.Signal signal){session.start(now(),signal);}void until(long time){session.observe(time,List.of());}
        void freeze(){event("apply_freeze");}void clear(){event("clear_freeze");}
        void event(String type){send(new RuleEngine.Signal("chorus_d2:"+type,new EffectEvent("caster","target",caster.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",caster.instance(),"bundle",caster.bundle()))));}
        Optional<BuffInstance> frozen(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(F)).findFirst();}
        AbilityUse.Receipt use(String slot,boolean grounded){var before=state();var request=new AbilityUse.Request("target","chorus_d2:"+slot,UUID.randomUUID().toString(),new EffectEvent("target","target",new BuffInstance.Origin("target","input","",""),Set.of(),Map.of(),Map.of("on_ground",grounded),Map.of()));var result=p.useAbility(before,request);var receipt=(AbilityUse.Receipt)result.result();if(receipt.outcome()==AbilityUse.Outcome.ACCEPTED)session.observe(now(),result.emitted(),new AbilityUse.Commit(before,result.state()));return receipt;}
    }
    @Test void prolongedGuardianFreezeReplacesClassInAirAndPreservesBaseEnergyUntilAfterThaw()throws Exception{
        var h=new Harness(true,false,true);h.freeze();var cast=h.use("class",false);assertEquals(AbilityUse.Outcome.ACCEPTED,cast.outcome());assertEquals("test:class_cost",cast.base());assertEquals(B,cast.resolved());assertTrue(cast.cost().isEmpty());assertEquals(1,h.frozen().orElseThrow().components().numbers().get("breakout_started"));assertTrue(h.payments.isEmpty());h.until(500_000);assertTrue(h.frozen().isEmpty());assertEquals(15,h.health);assertEquals("target",h.payments.getFirst().source().owner());assertEquals(B,h.payments.getFirst().source().ability());
        var original=h.use("class",true);assertEquals("test:class_cost",original.resolved());assertEquals(AbilityUse.Outcome.ACCEPTED,original.outcome());assertEquals(1,original.cost().orElseThrow().receipt().paid());assertEquals(List.of("test:paid_class"),h.cues.stream().map(Action.CueCommand::cue).toList());
    }
    @Test void shortCombatantAndUncalibratedFreezeDoNotOfferBreakoutAndDirectSelectionCannotBypassEligibility()throws Exception{
        for(var flags:List.of(List.of(true,true,true),List.of(false,false,true),List.of(true,false,false))){var h=new Harness(flags.get(0),flags.get(1),flags.get(2));h.freeze();assertEquals(AbilityUse.Outcome.RESTRICTED,h.use("class",false).outcome());assertTrue(h.payments.isEmpty());}
        var h=new Harness(true,false,true);h.send(new AbilityChange("target",h.state().abilities().get("target"),new AbilityLoadout(Map.of("chorus_d2:class",B))).signal());assertEquals(AbilityUse.Outcome.CONDITION,h.use("class",true).outcome());
    }
    @Test void repeatedInputsCannotRestartTheTimerOrPayTwice()throws Exception{
        var h=new Harness(true,false,true);h.freeze();assertEquals(AbilityUse.Outcome.ACCEPTED,h.use("class",true).outcome());h.until(250_000);assertEquals(AbilityUse.Outcome.CONDITION,h.use("class",false).outcome());h.until(499_999);assertTrue(h.payments.isEmpty()&&h.frozen().isPresent());h.until(500_000);h.until(1_000_000);assertEquals(1,h.payments.size());assertEquals(15,h.health);
    }
    @Test void cleanseRefreezeOrGroundedSuperCancelsTheOldGenerationBeforeAnyPayment()throws Exception{
        for(boolean superAbility:List.of(false,true)){var h=new Harness(true,false,true);h.freeze();h.use("class",false);h.until(200_000);if(superAbility)assertEquals(AbilityUse.Outcome.ACCEPTED,h.use("super",true).outcome());else h.clear();h.freeze();long generation=h.frozen().orElseThrow().generation();h.until(500_000);assertTrue(h.payments.isEmpty());assertEquals(generation,h.frozen().orElseThrow().generation());assertEquals(0,h.frozen().orElseThrow().components().numbers().get("breakout_started"));h.use("class",false);h.until(1_000_000);assertEquals(1,h.payments.size());assertTrue(h.frozen().isEmpty());}
    }
    @Test void acceptedCalibrationIsPinnedAndLowHealthUsesTheExplicitNonlethalFloor()throws Exception{
        for(double health:List.of(3.0,.5)){var h=new Harness(true,false,true);h.health=health;h.freeze();h.use("class",false);h.send(SourceChange.remove("calibration"));h.until(500_000);assertEquals(Math.min(health,1),h.health);assertTrue(h.frozen().isEmpty());assertEquals(5,h.payments.getFirst().amount());}
    }
    @Test void missingHealthPaymentAllowsRetryButUnknownPaymentStopsWithoutThawOrReplay()throws Exception{
        var retry=new Harness(true,false,true);retry.missing=true;retry.freeze();retry.use("class",false);retry.until(500_000);assertTrue(retry.frozen().isPresent());assertEquals(0,retry.frozen().orElseThrow().components().numbers().get("breakout_started"));retry.missing=false;retry.use("class",false);retry.until(1_000_000);assertTrue(retry.frozen().isEmpty());
        var h=new Harness(true,false,true);h.fault=true;h.freeze();h.use("class",false);assertThrows(IllegalStateException.class,()->h.until(500_000));assertEquals(15,h.health);assertTrue(h.frozen().isPresent());assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(1,h.payments.size());
    }
    @Test void invalidCalibrationIsDeniedBeforeStartingAndTheSourceRequiresAllParameters()throws Exception{
        for(var cal:List.of(calibration(-1,1,.5),calibration(5,0,.5),calibration(5,1,-1))){var h=new Harness(true,false,true);h.send(SourceChange.remove("calibration"));h.send(SourceChange.bind(cal));h.freeze();assertEquals(AbilityUse.Outcome.RESTRICTED,h.use("class",false).outcome());assertEquals(0,h.frozen().orElseThrow().components().numbers().get("breakout_started"));assertTrue(h.payments.isEmpty());}
        var h=new Harness(true,false,true);assertThrows(IllegalArgumentException.class,()->h.p.validateSource(new EffectSource("bad","chorus_d2:breakout_calibration","target",h.caster.origin(),Set.of())));
    }
    @Test void naturalThawCancelsLongerBreakoutAndRoamingSuperFreezeNeverOffersIt()throws Exception{
        var h=new Harness(true,false,true);h.send(SourceChange.remove("calibration"));h.send(SourceChange.bind(calibration(5,1,6)));h.freeze();h.use("class",false);h.until(4_750_000);assertTrue(h.frozen().isEmpty());h.until(6_000_000);assertTrue(h.payments.isEmpty());assertEquals(20,h.health);
        var roaming=new Harness(true,false,true);roaming.send(SourceChange.bind(new EffectSource("driver","test:freeze_inputs","caster",roaming.caster.origin(),Set.of())));roaming.send(new RuleEngine.Signal("test:roaming",new EffectEvent("caster","target",roaming.caster.origin(),Set.of(),Map.of())));roaming.freeze();assertEquals(6,roaming.frozen().orElseThrow().tier());assertEquals(AbilityUse.Outcome.RESTRICTED,roaming.use("class",false).outcome());
    }
}
