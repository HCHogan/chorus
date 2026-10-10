package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.effect.SolarTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EmberOfSearingTest {
    static final String ENERGY="test:searing_melee_energy", FRAGMENT="chorus_d2:ember_of_searing";
    static CompiledEffects program()throws Exception{return link("ember_of_searing","searing_test_calibration","searing_inputs","solar","solar_test_calibration","solar_test_source","character_stats","threaded_spike_energy","firesprite","firesprite_test_calibration");}
    static class Harness extends SolarTest.Harness {
        final List<WorldPickup.Spawn> pickups=new ArrayList<>();final List<Action.CueCommand> cues=new ArrayList<>();
        DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;boolean prevented, omitObservation;int entityReads;Runnable afterObservation=()->{};
        Harness()throws Exception{
            super(EmberOfSearingTest.program(),EffectState.Mode.PVE);
            for(var owner:List.of(FIRST,SECOND)){
                for(String bundle:List.of("test:searing_inputs","chorus_d2:firesprite_system"))session.start(0,SourceChange.bind(new EffectSource(owner.holder()+bundle,bundle,owner.holder(),owner.origin(),Set.of())));
                select(owner,true);
            }
            session.start(0,SourceChange.bind(new EffectSource("searing",FRAGMENT,FIRST.holder(),FIRST.origin(),Set.of())));healthy();
        }
        @Override RuleEngine.ActionResult execute(RuleEngine.WorldRequest request){
            if(request.command() instanceof EntityQuery)entityReads++;
            if(request.command() instanceof PositionQuery q && !views.containsKey(q.target()))return new PositionQuery.Result(q,Optional.empty());
            if(request.command() instanceof WorldPickup.Spawn s){if(s.position().isEmpty())return new WorldPickup.Receipt(s,WorldPickup.Outcome.MISSING_POSITION,Optional.empty());pickups.add(s);return new WorldPickup.Receipt(s,WorldPickup.Outcome.SPAWNED,Optional.of("pickup/"+pickups.size()));}
            if(request.command() instanceof Action.CueCommand c){cues.add(c);return RuleEngine.Empty.INSTANCE;}
            if(request.command() instanceof DamageCommand d && d.tags().contains("test:finisher")){
                boolean applied=outcome==DamageReceipt.Outcome.APPLIED;
                if(applied&&!prevented){var v=views.get(d.target());if(v!=null)views.put(d.target(),new EntityQuery.View(false,v.player(),0,v.maximumHealth(),v.absorption(),v.entityTags(),v.typeTags()));}
                var receipt=new DamageReceipt(request.id().toString(),outcome,0,0,applied?10:0,applied&&!prevented?Optional.of("death/"+request.id()):Optional.empty(),applied&&prevented);
                receipt=receipt.withObservedEntities(new EntityObservation(state().buffs().timeMicros(),omitObservation?Map.of():Map.of(d.target(),Optional.ofNullable(views.get(d.target()))),Map.of(new PositionQuery(d.target()),Optional.of(new WorldPosition("test:world",4,50,6)))));
                afterObservation.run();return receipt;
            }
            return super.execute(request);
        }
        void select(EffectSource owner,boolean active){session.start(state().buffs().timeMicros(),new AbilityChange(owner.holder(),state().abilities().getOrDefault(owner.holder(),AbilityLoadout.EMPTY),active?new AbilityLoadout(Map.of("chorus_d2:melee","test:searing_melee")):AbilityLoadout.EMPTY).signal());healthy();}
        void input(String kind,EffectSource owner,Map<String,Measure> numbers){session.start(state().buffs().timeMicros(),new RuleEngine.Signal(kind,new EffectEvent(owner.holder(),"target",owner.origin(),Set.of(),numbers)));healthy();}
        void kill(EffectSource owner){input("test:searing_kill",owner,Map.of());}
        double energy(EffectSource owner){return state().resources().get(new ResourceState.Key(owner.holder(),ENERGY)).value();}
        void classify(boolean player,Set<String> entity,Set<String> type){views.put("target",new EntityQuery.View(true,player,1000,1000,0,entity,type));}
    }
    @Test void everyExplicitCombatantTierAndGuardianBranchRestoresCurrentMeleeAfterDeathCleanup()throws Exception{
        double[] quoted={.08,.15,.175,.25,.2};
        for(int i=1;i<=5;i++)for(boolean typeTag:List.of(false,true)){
            var h=new Harness();var tags=Set.of("chorus_d2:combatant_tier_"+i);h.classify(i==5,typeTag?Set.of():tags,typeTag?tags:Set.of());
            h.apply(0,SECOND,1);h.input("test:searing_stat",FIRST,Map.of("stat",new Measure(100,Unit.STAT_POINT)));h.kill(FIRST);
            assertTrue(h.buff(SCORCH).isEmpty(),"death already removed Scorch before kill");assertEquals(quoted[i-1]*.8,h.energy(FIRST),1e-12);assertEquals(0,h.energy(SECOND));assertEquals(1,h.pickups.size());assertTrue(h.cues.isEmpty());
        }
    }
    @Test void meleeGainHasNoFirespriteCooldownAndAbsentSelectionDoesNotPreventCreation()throws Exception{
        var h=new Harness();h.classify(false,Set.of("chorus_d2:combatant_tier_2"),Set.of());h.apply(0,SECOND,1);h.kill(FIRST);double first=h.energy(FIRST);
        h.classify(false,Set.of("chorus_d2:combatant_tier_2"),Set.of());h.apply(0,SECOND,1);h.kill(FIRST);
        assertEquals(first*2,h.energy(FIRST),1e-12);assertEquals(1,h.pickups.size());
        var missing=new Harness();missing.classify(false,Set.of("chorus_d2:combatant_tier_1"),Set.of());missing.select(FIRST,false);missing.apply(0,SECOND,1);missing.kill(FIRST);assertEquals(0,missing.energy(FIRST));assertEquals(1,missing.pickups.size());
    }
    @Test void nonScorchedExpiredForeignAndUnconfirmedKillsDoNotQualify()throws Exception{
        for(String scenario:List.of("none","expired","foreign","prevented","cancelled","immune","blocked","failed")){
            var h=new Harness();h.classify(false,Set.of("chorus_d2:combatant_tier_1"),Set.of());if(!scenario.equals("none"))h.apply(0,SECOND,1);
            if(scenario.equals("expired"))h.until(3_000_000);
            if(scenario.equals("prevented"))h.prevented=true;
            if(Set.of("cancelled","immune","blocked","failed").contains(scenario))h.outcome=DamageReceipt.Outcome.valueOf(scenario.toUpperCase(Locale.ROOT));
            h.kill(scenario.equals("foreign")?SECOND:FIRST);assertEquals(0,h.energy(FIRST),scenario);assertTrue(h.pickups.isEmpty(),scenario);
        }
    }
    @Test void missingOrAmbiguousClassificationPublishesGapWithoutGuessingEnergyAndStillRequestsPickup()throws Exception{
        for(Set<String> tags:List.of(Set.<String>of(),Set.of("chorus_d2:boss"),Set.of("chorus_d2:combatant_tier_1","chorus_d2:combatant_tier_4"))){
            var h=new Harness();h.classify(false,tags,Set.of());h.apply(0,SECOND,1);h.kill(FIRST);
            assertEquals(0,h.energy(FIRST));assertEquals(List.of("test:unclassified"),h.cues.stream().map(Action.CueCommand::cue).toList());assertEquals(1,h.pickups.size());
        }
    }
    @Test void observedTierAndPositionSurviveLiveMutationOrRemoval()throws Exception{
        for(boolean removed:List.of(false,true)){
            var h=new Harness();h.classify(false,Set.of("chorus_d2:combatant_tier_1"),Set.of());h.apply(0,SECOND,1);int reads=h.entityReads;
            h.afterObservation=()->{if(removed)h.views.clear();else h.classify(false,Set.of("chorus_d2:combatant_tier_4"),Set.of());};
            h.kill(FIRST);assertEquals(.08/2.25*.8,h.energy(FIRST),1e-12);assertTrue(h.cues.isEmpty());assertEquals(reads,h.entityReads);assertEquals(1,h.pickups.size());assertEquals(Optional.of(new WorldPosition("test:world",4,50,6)),h.pickups.getFirst().position());
        }
    }
    @Test void absentAdapterObservationDoesNotGuessTierFromCurrentCorpseButKnownPickupLegRemains()throws Exception{
        var h=new Harness();h.classify(false,Set.of("chorus_d2:combatant_tier_1"),Set.of());h.apply(0,SECOND,1);int reads=h.entityReads;h.omitObservation=true;h.kill(FIRST);
        assertEquals(0,h.energy(FIRST));assertEquals(List.of("test:unclassified"),h.cues.stream().map(Action.CueCommand::cue).toList());assertEquals(1,h.pickups.size());assertEquals(reads,h.entityReads);
    }
    @Test void fragmentClassBonusUnbindsAndCalibrationIsRequiredWithRoundTrip()throws Exception{
        var h=new Harness();var query=new EffectEvent(FIRST.holder(),"target",FIRST.origin(),Set.of(),Map.of());
        assertEquals(60,h.program.calculate(h.state(),FIRST.holder(),query,"chorus_d2:class_stat",new Measure(50,Unit.STAT_POINT),List.of()).output().value());
        h.session.start(0,SourceChange.remove("searing"));assertEquals(50,h.program.calculate(h.state(),FIRST.holder(),query,"chorus_d2:class_stat",new Measure(50,Unit.STAT_POINT),List.of()).output().value());
        var encoded=EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.program.program()).getOrThrow();assertEquals(h.program.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,encoded).getOrThrow());
        var profiles=encoded.getAsJsonObject().getAsJsonArray("profiles");for(int i=profiles.size()-1;i>=0;i--)if(profiles.get(i).getAsJsonObject().get("id").getAsString().equals("chorus_d2:searing_base_energy"))profiles.remove(i);
        assertThrows(RuntimeException.class,()->compile(encoded.getAsJsonObject()));
    }
}
