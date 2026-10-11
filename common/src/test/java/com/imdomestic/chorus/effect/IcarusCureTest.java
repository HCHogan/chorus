package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class IcarusCureTest {
    static final String COUNTER="chorus_d2:icarus_air_kill_progress", SEEN="chorus_d2:icarus_air_kill_receipts", CURE="chorus_d2:cure_cooldown", BUNDLE="chorus_d2:icarus_dash_cure";
    record Heal(long time,HealingCommand command) {}
    static class Harness {
        final CompiledEffects p;final EffectSession session;final List<Heal> heals=new ArrayList<>();final List<String> cues=new ArrayList<>();
        final Map<String,Optional<EntityQuery.View>> entities=new HashMap<>();
        DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;boolean prevented,failHeal;Runnable afterReceipt=()->{};List<RuleEngine.Signal> lastFacts=List.of();
        Harness() throws Exception {this(EffectState.Mode.PVE);}
        Harness(EffectState.Mode mode) throws Exception {
            p=CompiledEffects.link(List.of(IcarusDashTest.program().program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,json("icarus_cure_inputs")).getOrThrow()));
            session=new EffectSession(engine(p),EffectState.empty().withMode(mode),request->switch(request.command()) {
                case DamageCommand d -> {
                    var receipt=new DamageReceipt(request.id().toString(),outcome,0,0,outcome==DamageReceipt.Outcome.APPLIED?10:0,
                            outcome==DamageReceipt.Outcome.APPLIED&&!prevented?Optional.of("death/"+request.id()):Optional.empty(),prevented)
                            .withObservedEntities(new EntityObservation(now(),entities));
                    lastFacts=DamageFacts.from(d,receipt);afterReceipt.run();yield receipt;
                }
                case HealingCommand command -> {heals.add(new Heal(now(),command));if(failHeal)throw new IllegalStateException("Unknown committed Cure");yield new HealingReceipt(request.id().toString(),command,HealingReceipt.Outcome.APPLIED,command.amount(),command.amount(),0);}
                case Action.CueCommand cue -> {cues.add(cue.cue());yield RuleEngine.Empty.INSTANCE;}
                default -> throw new AssertionError("Unexpected live observation: "+request.command());
            });
            for(String holder:List.of("player","other")){airborne(holder,true);bind("input/"+holder,"test:icarus_cure_inputs",holder,Set.of());}
            select("player",true);rank(false,Set.of("chorus_d2:rank_and_file"),Set.of());
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal signal){session.start(now(),signal);}void until(long time){session.observe(time,List.of());}
        void bind(String id,String bundle,String holder,Set<String> tags){send(SourceChange.bind(new EffectSource(id,bundle,holder,new BuffInstance.Origin(holder,id,"",""),tags)));}
        void select(String holder,boolean yes){send(new AbilityChange(holder,state().abilities().getOrDefault(holder,AbilityLoadout.EMPTY),yes?new AbilityLoadout(Map.of(IcarusDashTest.SLOT,IcarusDashTest.ABILITY)):AbilityLoadout.EMPTY).signal());}
        void airborne(String holder,boolean air){entities.put(holder,Optional.of(new EntityQuery.View(true,true,10,100,0,Set.of(),Set.of(),Optional.of(new EntityQuery.Movement(!air,false,false,false,false,false,false)))));}
        void rank(boolean guardian,Set<String> entity,Set<String> type){entities.put("target",Optional.of(new EntityQuery.View(false,guardian,0,100,0,entity,type)));}
        void kill(String kind){kill("player",kind);}void kill(String holder,String kind){send(new RuleEngine.Signal("test:icarus_"+kind,new EffectEvent(holder,"target",new BuffInstance.Origin(holder,"attack","weapon",""),Set.of(),Map.of())));}
        Optional<BuffInstance> buff(String id,String holder){return state().buffs().instances().values().stream().filter(b->b.key().holder().equals(holder)&&b.definition().id().equals(id)).findFirst();}
        int progress(String holder){return buff(COUNTER,holder).map(BuffInstance::count).orElse(0);}int progress(){return progress("player");}
        void cure(int tier){send(new RuleEngine.Signal("chorus:cure_requested",new EffectEvent("player","player",new BuffInstance.Origin("player","external","",""),Set.of(),Map.of("tier",new Measure(tier,Unit.COUNT)))));}
        void repeatKill(){send(lastFacts.stream().filter(f->f.type().equals("chorus:kill")).findFirst().orElseThrow());}
    }
    @Test void confirmedWeaponAndSuperKillsUseExplicitRankWeightsInBothTagNamespaces() throws Exception {
        for(String kind:List.of("weapon","super"))for(boolean type:List.of(false,true))for(String rank:List.of("rank_and_file","elite","boss","guardian")) {
            var h=new Harness();var tags=Set.of("chorus_d2:"+rank);h.rank(rank.equals("guardian"),type?Set.of():tags,type?tags:Set.of());h.kill(kind);
            int expected=rank.equals("rank_and_file")?34:67;
            if(rank.equals("boss")){assertEquals(0,h.progress());assertTrue(h.buff(CURE,"player").isPresent());}
            else {assertEquals(expected,h.progress());h.kill(kind);if(expected==34){assertEquals(68,h.progress());h.kill(kind);}}
            assertEquals(0,h.progress());assertTrue(h.heals.isEmpty());h.until(100_000);
            assertEquals(List.of(50_000L,100_000L),h.heals.stream().map(Heal::time).toList());assertEquals(6,h.heals.stream().mapToDouble(x->x.command().amount()).sum());
            assertTrue(h.heals.stream().allMatch(x->x.command().target().equals("player")&&x.command().source().ability().equals(IcarusDashTest.ABILITY)));
            assertTrue(h.cues.isEmpty());
        }
    }
    @Test void mixedWeightsTriggerOneCureDiscardOverflowAndRespectPvpAmounts() throws Exception {
        var h=new Harness(EffectState.Mode.PVP);h.kill("weapon");h.rank(true,Set.of(),Set.of());h.kill("super");
        assertEquals(0,h.progress());assertEquals(1,h.buff(CURE,"player").orElseThrow().tier());h.until(100_000);assertEquals(3,h.heals.stream().mapToDouble(x->x.command().amount()).sum());
        h.kill("super");assertEquals(67,h.progress(),"the previous one-percent overflow was consumed");
    }
    @Test void qualifyingKillsRefreshTheFiveSecondGapAndExactExpiryStartsANewCounter() throws Exception {
        var h=new Harness();h.kill("weapon");h.until(4_000_000);h.kill("super");assertEquals(68,h.progress());h.until(8_000_000);h.kill("weapon");assertEquals(0,h.progress());h.until(8_100_000);assertEquals(2,h.heals.size());
        var exact=new Harness();exact.kill("weapon");exact.until(5_000_000);exact.kill("weapon");assertEquals(34,exact.progress());assertTrue(exact.heals.isEmpty());
        assertEquals(1,h.buff(SEEN,"player").orElseThrow().components().sets().get("deaths").size(),"fixed-lifetime receipt buckets do not grow across an endless streak");
    }
    @Test void GroundedUncreditedAndForeignKillsNeitherCountNorRefreshTheWindow() throws Exception {
        var h=new Harness();h.kill("weapon");h.until(4_000_000);h.airborne("player",false);h.kill("weapon");h.airborne("player",true);h.kill("grenade");h.kill("other","weapon");
        assertEquals(34,h.progress());assertEquals(5_000_000,h.buff(COUNTER,"player").orElseThrow().deadline());h.until(5_000_000);assertEquals(0,h.progress());assertTrue(h.cues.isEmpty());
        for(var outcome:DamageReceipt.Outcome.values())if(outcome!=DamageReceipt.Outcome.APPLIED){var rejected=new Harness();rejected.outcome=outcome;rejected.kill("weapon");assertEquals(0,rejected.progress());}
        var protectedTarget=new Harness();protectedTarget.prevented=true;protectedTarget.kill("weapon");assertEquals(0,protectedTarget.progress());
    }
    @Test void unavailableMovementOrVictimEvidenceReportsUnknownInsteadOfAssumingAirborne() throws Exception {
        for(String missing:List.of("actor","movement","absent_actor","victim","absent_victim")) {
            var h=new Harness();switch(missing){
                case "actor" -> h.entities.remove("player");case "movement" -> h.entities.put("player",Optional.of(new EntityQuery.View(true,true,10,100,0)));
                case "absent_actor" -> h.entities.put("player",Optional.empty());case "victim" -> h.entities.remove("target");case "absent_victim" -> h.entities.put("target",Optional.empty());default -> throw new AssertionError();
            }
            h.kill("weapon");assertEquals(0,h.progress());assertEquals(1,h.cues.size());assertTrue(h.buff(SEEN,"player").isEmpty());
        }
    }
    @Test void unclassifiedMinibossChampionAndConflictingRanksDoNotGuessAWeight() throws Exception {
        for(var tags:List.of(Set.<String>of(),Set.of("chorus_d2:miniboss"),Set.of("chorus_d2:champion"),Set.of("chorus_d2:elite","chorus_d2:boss"),Set.of("chorus_d2:boss","chorus_d2:champion"))) {
            var h=new Harness();h.rank(false,tags,Set.of());h.kill("weapon");assertEquals(0,h.progress());assertEquals(List.of("chorus_d2:icarus_unclassified_kill"),h.cues);
        }
        var h=new Harness();var tags=Set.of("chorus_d2:elite");h.rank(false,tags,tags);h.kill("weapon");assertEquals(67,h.progress());
    }
    @Test void receiptSnapshotSurvivesLandingAndRemovalBeforeKillReactions() throws Exception {
        var h=new Harness();h.rank(false,Set.of("chorus_d2:boss"),Set.of());h.afterReceipt=()->{h.airborne("player",false);h.entities.remove("target");};h.kill("weapon");
        assertTrue(h.buff(CURE,"player").isPresent());h.until(100_000);assertEquals(2,h.heals.size());assertTrue(h.cues.isEmpty());
    }
    @Test void duplicateSourcesAndRepeatedDeathReceiptsDoNotInflateCountsOrRestartAfterThreshold() throws Exception {
        var h=new Harness();h.bind("duplicate",BUNDLE,"player",Set.of(BUNDLE));h.kill("weapon");assertEquals(34,h.progress());h.until(1_000_000);h.repeatKill();
        assertEquals(34,h.progress());assertEquals(5_000_000,h.buff(COUNTER,"player").orElseThrow().deadline());h.kill("weapon");assertEquals(68,h.progress());h.kill("weapon");h.repeatKill();
        assertEquals(0,h.progress());h.until(1_100_000);assertEquals(2,h.heals.size());assertEquals(3,h.buff(SEEN,"player").orElseThrow().components().sets().get("deaths").size());
    }
    @Test void selectionRemovalLastSourceAndHolderDeathCleanOnlyTheirOwnProgress() throws Exception {
        var h=new Harness();h.select("other",true);h.kill("player","weapon");h.kill("other","weapon");h.bind("duplicate",BUNDLE,"player",Set.of(BUNDLE));h.select("player",false);
        assertEquals(34,h.progress());h.send(SourceChange.remove("duplicate"));assertEquals(0,h.progress());assertEquals(34,h.progress("other"));h.select("player",true);h.kill("weapon");assertEquals(34,h.progress());
        h.send(new RuleEngine.Signal("chorus:death",new EffectEvent("enemy","player",new BuffInstance.Origin("enemy","attack","",""),Set.of(),Map.of())));
        assertEquals(0,h.progress());assertTrue(h.buff(SEEN,"player").isEmpty());assertEquals(34,h.progress("other"));
    }
    @Test void cureSharesItsCooldownWithOtherProducersAndConsumesThresholdEvenWhenBlocked() throws Exception {
        var h=new Harness();h.bind("external","chorus_d2:cure","player",Set.of());h.cure(2);h.until(100_000);assertEquals(12,h.heals.stream().mapToDouble(x->x.command().amount()).sum());
        h.rank(false,Set.of("chorus_d2:boss"),Set.of());h.kill("weapon");assertEquals(0,h.progress());h.until(200_000);assertEquals(2,h.heals.size());assertEquals(1_000_000,h.buff(CURE,"player").orElseThrow().deadline());
        h.until(1_000_000);h.kill("weapon");h.until(1_100_000);assertEquals(4,h.heals.size());assertEquals(3,h.heals.getLast().command().amount());
    }
    @Test void unknownHealingRetainsConsumedCounterAndCooldownAndDoesNotReplay() throws Exception {
        var h=new Harness();h.rank(false,Set.of("chorus_d2:boss"),Set.of());h.kill("weapon");h.failHeal=true;assertThrows(IllegalStateException.class,()->h.until(50_000));
        assertEquals(0,h.progress());assertTrue(h.buff(CURE,"player").isPresent());assertEquals(1,h.heals.size());assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(1,h.heals.size());
    }
}
