package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EmberOfEmpyreanTest {
    static final String RAD="chorus_d2:radiant", REST="chorus_d2:restoration", FRAGMENT="chorus_d2:ember_of_empyrean";
    static CompiledEffects program()throws Exception{return link("radiant","empowering_damage","radiant_inputs","solar_effect_duration","ember_of_solace","restoration_effect","restoration_test_calibration","mercy_inputs","character_stats","ember_of_empyrean","empyrean_inputs");}
    static class Harness extends RadiantTest.Harness {
        final Map<String,EntityQuery.View> targets=new HashMap<>();final List<String> cues=new ArrayList<>();int queries;
        DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;boolean prevented, omitObservation;Runnable afterObservation=()->{};
        Harness()throws Exception{super(EmberOfEmpyreanTest.program(),EffectState.Mode.PVE);bind("player",FRAGMENT);bind("player","test:mercy_inputs");for(String h:List.of("player","ally"))bind(h,"test:empyrean_inputs");classify(false,Set.of("chorus_d2:combatant_tier_1"),Set.of());}
        @Override RuleEngine.ActionResult execute(RuleEngine.WorldRequest r){return switch(r.command()){
            case EntityQuery q -> {queries++;yield new EntityQuery.Result(q,Optional.ofNullable(targets.get(q.target())));}
            case DamageCommand command -> {
                assertTrue(command.source().tags().contains("chorus_d2:solar"));
                var receipt=new DamageReceipt(r.id().toString(),outcome,0,0,outcome==DamageReceipt.Outcome.APPLIED?10:0,outcome==DamageReceipt.Outcome.APPLIED&&!prevented?Optional.of("death/"+r.id()):Optional.empty(),outcome==DamageReceipt.Outcome.APPLIED&&prevented);
                if(!omitObservation)receipt=receipt.withObservedEntities(new EntityObservation(now(),Map.of(command.target(),Optional.ofNullable(targets.get(command.target())))));
                afterObservation.run();yield receipt;
            }
            case HealingCommand h -> new HealingReceipt(r.id().toString(),h,HealingReceipt.Outcome.APPLIED,h.amount(),h.amount(),0);
            case Action.CueCommand cue -> {cues.add(cue.cue());yield RuleEngine.Empty.INSTANCE;}
            default -> super.execute(r);
        };}
        void classify(boolean player,Set<String> entity,Set<String> type){targets.put("target",new EntityQuery.View(false,player,0,100,0,entity,type));}
        void restore(int tier,double duration){session.start(now(),new RuleEngine.Signal("test:restore_raw",new EffectEvent("player","player",source("player","test:mercy_inputs").origin(),Set.of(),Map.of("tier",new Measure(tier,Unit.COUNT),"duration",new Measure(duration,Unit.SECOND)))));healthy();}
        void kill(String who,boolean solar){var origin=new BuffInstance.Origin(who,"input","weapon","",Set.of("chorus_d2:solar"));session.start(now(),new RuleEngine.Signal(solar?"test:empyrean_strike":"test:empyrean_arc",new EffectEvent(who,"target",origin,Set.of(),Map.of())));healthy();}
        Optional<BuffInstance> buff(String id,String owner){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(id)&&b.key().holder().equals(owner)).findFirst();}
        BuffInstance active(String id){return buff(id,"player").orElseThrow();}
        long remaining(String id){return active(id).deadline()-now();}
    }
    @Test void confirmedSolarKillsUseAllFourDistinctTiersAndGuardianAndExtendBothStates()throws Exception{
        double[] seconds={1.5,2.25,3,6,3};
        for(int i=1;i<=5;i++)for(boolean type:List.of(false,true)){
            var h=new Harness();var tags=Set.of("chorus_d2:combatant_tier_"+i);h.classify(i==5,type?Set.of():tags,type?tags:Set.of());
            h.grant("player","player","radiant",10);h.restore(2,4);h.until(1_000_000);h.kill("player",true);
            assertEquals((long)((9+seconds[i-1])*1_000_000),h.remaining(RAD));assertEquals((long)((3+seconds[i-1])*1_000_000),h.remaining(REST));assertEquals(2,h.active(REST).tier());assertTrue(h.cues.isEmpty());
        }
    }
    @Test void extensionDoesNotGrantMissingEffectsOrReapplyHistoryAndCapsAlreadyLongerTimers()throws Exception{
        var h=new Harness();h.kill("player",true);assertTrue(h.state().buffs().instances().isEmpty());assertEquals(0,h.queries);
        h.restore(2,20);var before=h.active(REST);h.kill("player",true);
        assertEquals(15_000_000,h.remaining(REST));assertEquals(20_000_000,h.active(REST).longestDurationMicros());assertEquals(before.origin(),h.active(REST).origin());assertTrue(h.buff(RAD,"player").isEmpty());
        h.restore(1,2);assertEquals(20_000_000,h.remaining(REST));assertEquals(2,h.active(REST).tier());
        h.grant("player","player","radiant",25);h.kill("player",true);assertEquals(15_000_000,h.remaining(RAD));assertEquals(25_000_000,h.active(RAD).longestDurationMicros());
        h.until(15_000_000);h.kill("player",true);assertTrue(h.state().buffs().instances().isEmpty(),"exact expiry is not revived by a kill");
    }
    @Test void repeatedKillsHaveNoInventedCooldownAndSolaceDoesNotMultiplyTheQuotedExtensionTable()throws Exception{
        var h=new Harness();h.bind("player",RadiantTest.SOLACE);h.grant("player","player","radiant",4);h.restore(2,4);h.kill("player",true);h.kill("player",true);
        assertEquals(9_000_000,h.remaining(RAD));assertEquals(7_000_000,h.remaining(REST));
        h.until(5_000_000);h.kill("player",true);assertEquals(5_500_000,h.remaining(RAD),"extends remaining time rather than historic maximum");
    }
    @Test void otherDamageForeignKillsAndUnconfirmedDeathsCannotExtendEvenWithSolarWeaponOrigin()throws Exception{
        for(String scenario:List.of("arc","foreign","prevented","cancelled","immune","blocked","failed","unequipped")){
            var h=new Harness();h.grant("player","player","radiant",10);h.restore(1,4);
            if(scenario.equals("prevented"))h.prevented=true;
            if(Set.of("cancelled","immune","blocked","failed").contains(scenario))h.outcome=DamageReceipt.Outcome.valueOf(scenario.toUpperCase(Locale.ROOT));
            if(scenario.equals("unequipped"))h.remove("player",FRAGMENT);
            h.kill(scenario.equals("foreign")?"ally":"player",!scenario.equals("arc"));assertEquals(10_000_000,h.remaining(RAD),scenario);assertEquals(4_000_000,h.remaining(REST),scenario);assertEquals(0,h.queries);
        }
    }
    @Test void unknownOrAmbiguousTierReportsGapAndDuplicateSameTierAcrossNamespacesIsOneTier()throws Exception{
        for(String scenario:List.of("missing","rank_only","conflict","absent")){
            var h=new Harness();h.grant("player","player","radiant",10);h.restore(1,4);
            h.classify(false,scenario.equals("conflict")?Set.of("chorus_d2:combatant_tier_1"):Set.of("chorus_d2:boss"),scenario.equals("conflict")?Set.of("chorus_d2:combatant_tier_4"):Set.of());
            if(scenario.equals("missing"))h.classify(false,Set.of(),Set.of());if(scenario.equals("absent"))h.targets.clear();
            h.kill("player",true);assertEquals(10_000_000,h.remaining(RAD));assertEquals(4_000_000,h.remaining(REST));assertEquals(List.of("test:empyrean_unclassified"),h.cues);
        }
        var h=new Harness();var tags=Set.of("chorus_d2:combatant_tier_2");h.classify(false,tags,tags);h.restore(1,4);h.kill("player",true);assertEquals(6_250_000,h.remaining(REST));assertTrue(h.cues.isEmpty());
    }
    @Test void receiptClassificationSurvivesRemovalOrChangedLiveTierWithoutWorldQueries()throws Exception{
        for(boolean removed:List.of(false,true)){
            var h=new Harness();h.grant("player","player","radiant",4);h.restore(2,4);
            h.afterObservation=()->{if(removed)h.targets.clear();else h.classify(false,Set.of("chorus_d2:combatant_tier_4"),Set.of());};
            h.kill("player",true);assertEquals(5_500_000,h.remaining(RAD));assertEquals(5_500_000,h.remaining(REST));assertTrue(h.cues.isEmpty());assertEquals(0,h.queries);
        }
    }
    @Test void adapterWithoutEntityObservationReportsUnknownEvenIfCorpseCanStillBeQueried()throws Exception{
        var h=new Harness();h.grant("player","player","radiant",4);h.restore(1,4);h.omitObservation=true;h.kill("player",true);
        assertEquals(4_000_000,h.remaining(RAD));assertEquals(4_000_000,h.remaining(REST));assertEquals(List.of("test:empyrean_unclassified"),h.cues);assertEquals(0,h.queries);
    }
    @Test void healthPenaltyIsCurrentAndAllDeclarationsRoundTrip()throws Exception{
        var h=new Harness();var query=new EffectEvent("player","player",new BuffInstance.Origin("player","query","",""),Set.of(),Map.of());
        assertEquals(40,h.program.calculate(h.state(),"player",query,"chorus_d2:health_stat",new Measure(50,Unit.STAT_POINT),List.of()).output().value());
        h.remove("player",FRAGMENT);assertEquals(50,h.program.calculate(h.state(),"player",query,"chorus_d2:health_stat",new Measure(50,Unit.STAT_POINT),List.of()).output().value());
        var encoded=EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE,h.program).getOrThrow();assertEquals(h.program.program(),EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,encoded).getOrThrow().program());
    }
}
