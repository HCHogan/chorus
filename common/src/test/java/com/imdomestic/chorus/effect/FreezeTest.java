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

class FreezeTest {
    static final String FREEZE="chorus_d2:freeze";
    static EffectSource source(String id,String owner,boolean superAbility){return new EffectSource(id,"chorus_d2:freeze_application",owner,new BuffInstance.Origin(owner,id,"weapon","ability",superAbility?Set.of("chorus:super_ability"):Set.of()),Set.of(),Map.of("combatant_threshold",new Measure(100,Unit.DAMAGE),"shatter_radius",new Measure(4,Unit.METER),"guardian_shatter_damage",new Measure(80,Unit.DAMAGE)));}
    static EntityQuery.View view(boolean player,String...tags){return new EntityQuery.View(true,player,1000,1000,0,Set.of(tags),Set.of());}
    static class Harness {
        final CompiledEffects p;final EffectSession session;final EffectSource source;final EffectSource driver=new EffectSource("driver","test:freeze_inputs","caster",new BuffInstance.Origin("caster","driver","",""),Set.of());
        final Map<String,EntityQuery.View> entities=new HashMap<>();final List<StatusResult.Check> checks=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();final List<TargetQuery> queries=new ArrayList<>();final List<Action.CueCommand> cues=new ArrayList<>();final List<PositionQuery> positions=new ArrayList<>();
        WorldPosition position=new WorldPosition("world",0,0,0);StatusResult.Decision decision=StatusResult.Decision.ALLOWED;boolean failDamage;List<TargetQuery.Target> targets=List.of(new TargetQuery.Target("neighbor",2));
        Harness(EntityQuery.View victim,boolean guardianCaster,boolean superAbility)throws Exception{
            source=source("freeze","caster",superAbility);p=link("freeze","freeze_test_falloff","freeze_inputs","combat_damage");entities.put("caster",view(guardianCaster));if(victim!=null)entities.put("target",victim);entities.put("neighbor",view(false));
            session=new EffectSession(engine(p),EffectState.empty().withSource(source).withSource(driver),r->switch(r.command()){
                case EntityQuery q->new EntityQuery.Result(q,Optional.ofNullable(entities.get(q.target())));
                case PositionQuery q->{positions.add(q);yield new PositionQuery.Result(q,Optional.ofNullable(position));}
                case StatusResult.Check q->{checks.add(q);yield new StatusResult.Checked(q,decision);}
                case TargetQuery q->{queries.add(q);yield new TargetQuery.Result(q,TargetQuery.Outcome.AVAILABLE,targets);}
                case DamageCommand d->{damage.add(d);if(failDamage)throw new IllegalStateException("unknown shatter result");yield new DamageReceipt(r.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,d.amount(),Optional.empty(),false);}
                case Action.CueCommand c->{cues.add(c);yield RuleEngine.Empty.INSTANCE;}
                default->throw new AssertionError(r.command());
            });
        }
        Harness(EntityQuery.View victim)throws Exception{this(victim,true,false);}
        EffectState state(){return session.state().engine().domain();}
        void send(RuleEngine.Signal signal){session.start(state().buffs().timeMicros(),signal);}
        void event(String name,String target){send(new RuleEngine.Signal("chorus_d2:"+name,new EffectEvent(source.holder(),target,source.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",source.instance(),"bundle",source.bundle()))));}
        void apply(){event("apply_freeze","target");}
        void clear(){event("clear_freeze","target");}
        void until(long time){session.observe(time,List.of());}
        void extra(String name){send(new RuleEngine.Signal("test:"+name,new EffectEvent(driver.holder(),"target",driver.origin(),Set.of(),Map.of())));}
        Optional<BuffInstance> frozen(String holder){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(FREEZE)&&b.key().holder().equals(holder)).findFirst();}
        List<RuleEngine.Signal> facts(String id,String target,double health,double shield,double absorption,DamageReceipt.Outcome outcome,boolean lethal){
            var command=new DamageCommand(target,new BuffInstance.Origin("attacker","gun","weapon",""),health+shield+absorption,"test:hit",Set.of(),Set.of(),false);
            var receipt=new DamageReceipt(id,outcome,shield,absorption,health,lethal?Optional.of(id+"/death"):Optional.empty(),false).withObservedBuffs(BuffObservation.capture(state().buffs(),target));
            return DamageFacts.from(command,receipt);
        }
        void observe(List<RuleEngine.Signal> facts){session.observe(state().buffs().timeMicros(),facts);}
        void hit(String id,double amount){observe(facts(id,"target",amount,0,0,DamageReceipt.Outcome.APPLIED,false));}
        boolean gate(ActionGate.Kind kind,boolean superAbility,boolean grounded){return p.checkAction(state(),kind,ActionGate.Phase.START,new EffectEvent("target","",new BuffInstance.Origin("target","input","",""),superAbility?Set.of("chorus:super_ability"):Set.of(),Map.of(),Map.of("on_ground",grounded),Map.of())).allowed();}
    }
    @Test void classificationUsesRecipientCasterAndSuperStateInsteadOfWorldMode()throws Exception{
        for(boolean player:List.of(false,true))for(boolean superAbility:List.of(false,true)){
            var h=new Harness(view(true),player,superAbility);h.apply();assertEquals(player&&!superAbility?1_350_000L:4_750_000L,h.checks.getFirst().duration());
        }
        for(String kind:List.of("rank_and_file","elite","miniboss","champion","boss")){var h=new Harness(view(false,"chorus_d2:"+kind));h.apply();assertEquals(kind.equals("boss")?3_000_000L:6_000_000L,h.checks.getFirst().duration());}
        var roaming=new Harness(view(true),false,false);roaming.extra("roaming");roaming.apply();assertEquals(1_000_000L,roaming.checks.getFirst().duration());roaming.until(1_000_000);assertTrue(roaming.frozen("target").isEmpty());assertTrue(roaming.state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("test:roaming")));assertTrue(roaming.damage.isEmpty());
    }
    @Test void fullControlExemptsBossAndAllowsOnlyGroundedGuardianSuperInput()throws Exception{
        for(String kind:List.of("rank_and_file","boss","guardian")){var h=new Harness(view(kind.equals("guardian"),"chorus_d2:"+kind));h.apply();for(var action:ActionGate.Kind.values())assertEquals(kind.equals("boss"),h.gate(action,false,false),kind+" "+action);assertEquals(kind.equals("boss")||kind.equals("guardian"),h.gate(ActionGate.Kind.ABILITY_USE,true,true));assertEquals(kind.equals("boss"),h.gate(ActionGate.Kind.ABILITY_USE,true,false));}
    }
    @Test void realLossThresholdDeduplicatesDamageAndOldGenerationCannotShatterNewFreeze()throws Exception{
        var h=new Harness(view(false,"chorus_d2:elite"));h.apply();var old=h.frozen("target").orElseThrow();var hit=h.facts("one","target",60,0,0,DamageReceipt.Outcome.APPLIED,false);h.observe(hit);h.observe(hit);assertEquals(60,h.frozen("target").orElseThrow().components().numbers().get("damage"));h.hit("two",39.99);assertTrue(h.damage.isEmpty());h.hit("three",.01);assertTrue(h.frozen("target").isEmpty());assertEquals(180.5,h.damage.getFirst().amount());
        h.apply();assertNotEquals(old.generation(),h.frozen("target").orElseThrow().generation());h.observe(hit);assertEquals(0,h.frozen("target").orElseThrow().components().numbers().get("damage"));h.hit("new",100);assertEquals(2,h.damage.size());
    }
    @Test void shieldLossCountsButAbsorptionAloneAndRejectedDamageDoNotInventProgress()throws Exception{
        var h=new Harness(view(false,"chorus_d2:elite"));h.apply();h.observe(h.facts("absorption","target",0,0,200,DamageReceipt.Outcome.APPLIED,false));assertEquals(0,h.frozen("target").orElseThrow().components().numbers().get("damage"));
        for(var outcome:List.of(DamageReceipt.Outcome.CANCELLED,DamageReceipt.Outcome.IMMUNE,DamageReceipt.Outcome.BLOCKED))h.observe(h.facts(outcome.name(),"target",0,0,0,outcome,false));assertTrue(h.damage.isEmpty());h.observe(h.facts("shield","target",0,100,0,DamageReceipt.Outcome.APPLIED,false));assertEquals(1,h.damage.size());
    }
    @Test void bossAutomaticallyShattersOnceWhileOtherExpiryAndCleanseOnlyRelease()throws Exception{
        var h=new Harness(view(false,"chorus_d2:boss"));h.apply();h.send(SourceChange.remove(h.source.instance()));h.until(2_999_999);assertTrue(h.damage.isEmpty());h.until(3_000_000);assertEquals(1,h.damage.size());assertTrue(h.damage.getFirst().tags().contains("chorus:boss_auto_shatter"));assertEquals(h.source.origin(),h.damage.getFirst().source());h.until(7_000_000);assertEquals(1,h.damage.size());
        for(String kind:List.of("elite","boss")){var clean=new Harness(view(false,"chorus_d2:"+kind));clean.apply();clean.clear();clean.until(7_000_000);assertTrue(clean.damage.isEmpty());}
        var natural=new Harness(view(false,"chorus_d2:elite"));natural.apply();natural.until(6_000_000);assertTrue(natural.damage.isEmpty());assertTrue(natural.gate(ActionGate.Kind.HORIZONTAL_MOTION,false,false));
    }
    @Test void explicitShatterUsesOriginalCreditAndActualRecipientTypeForItsCalibratedDamage()throws Exception{
        var h=new Harness(view(false,"chorus_d2:boss"));h.entities.put("neighbor",view(true));h.apply();h.send(SourceChange.remove(h.source.instance()));h.send(new RuleEngine.Signal("chorus_d2:shatter",new EffectEvent("other","target",new BuffInstance.Origin("other","aspect","",""),Set.of(),Map.of())));assertEquals(40,h.damage.getFirst().amount());assertEquals(h.source.origin(),h.damage.getFirst().source());assertFalse(h.damage.getFirst().tags().contains("chorus:boss_auto_shatter"));h.until(4_000_000);assertEquals(1,h.damage.size());
    }
    @Test void receiptPositionSurvivesDisappearingVictimAndNoWorldPositionIsReread()throws Exception{
        var h=new Harness(view(false,"chorus_d2:elite"));h.apply();var saved=new WorldPosition("world",3,4,5);var observation=new EntityObservation(0,Map.of(),Map.of(new PositionQuery("target"),Optional.of(saved)));
        var facts=h.facts("lethal","target",1,0,0,DamageReceipt.Outcome.APPLIED,true).stream().map(s->{var e=(EffectEvent)s.payload();return new RuleEngine.Signal(s.type(),e.withObservedEntities(Optional.of(observation)));}).toList();h.entities.remove("target");h.position=null;h.observe(facts);assertTrue(h.positions.isEmpty());assertEquals(new TargetQuery.PositionCenter(saved),h.queries.getFirst().center());assertEquals(1,h.damage.size());
    }
    @Test void existingFreezeKeepsItsDeadlineComponentsAndOriginalInstance()throws Exception{
        var h=new Harness(view(false,"chorus_d2:elite"));h.apply();var old=h.frozen("target").orElseThrow();h.until(500_000);h.hit("before",25);h.apply();var same=h.frozen("target").orElseThrow();assertEquals(old.generation(),same.generation());assertEquals(old.deadline(),same.deadline());assertEquals(25,same.components().numbers().get("damage"));assertEquals(1,h.checks.size());
    }
    @Test void deniedUnclassifiedMissingAndDeadRecipientsNeverReceiveControl()throws Exception{
        for(var view:Arrays.asList(view(false),null,new EntityQuery.View(false,false,0,1000,0))){var h=new Harness(view);h.apply();assertTrue(h.checks.isEmpty());assertTrue(h.frozen("target").isEmpty());}
        var unavailable=new Harness(view(true));unavailable.entities.remove("caster");unavailable.apply();assertTrue(unavailable.checks.isEmpty());
        for(var decision:StatusResult.Decision.values())if(decision!=StatusResult.Decision.ALLOWED){var h=new Harness(view(false,"chorus_d2:elite"));h.decision=decision;h.apply();assertTrue(h.frozen("target").isEmpty());h.until(7_000_000);assertTrue(h.damage.isEmpty());}
    }
    @Test void guardianFreezeInterruptsOneOffStatesAndAcceptedGroundedSuperRemovesFreeze()throws Exception{
        var h=new Harness(view(true));h.extra("oneoff");h.apply();assertFalse(h.state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("test:oneoff")));h.extra("oneoff");assertFalse(h.state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("test:oneoff")));
        h.send(new AbilityChange("target",AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("chorus_d2:super","test:super"))).signal());
        var input=new EffectEvent("target","target",new BuffInstance.Origin("target","input","",""),Set.of(),Map.of(),Map.of("on_ground",true),Map.of());h.send(new AbilityUse.Request("target","chorus_d2:super","cast",input).signal());assertTrue(h.frozen("target").isEmpty());assertEquals(List.of("test:super"),h.cues.stream().map(Action.CueCommand::cue).toList());assertTrue(h.damage.isEmpty());
    }
    @Test void unknownExplosionOutcomeStopsAfterRemovalAndNeverRepeatsWorldDamage()throws Exception{
        var h=new Harness(view(false,"chorus_d2:elite"));h.apply();h.failDamage=true;assertThrows(IllegalStateException.class,()->h.hit("threshold",100));assertTrue(h.frozen("target").isEmpty());assertEquals(1,h.damage.size());assertThrows(IllegalStateException.class,()->h.until(1_000_000));assertEquals(1,h.damage.size());
    }
    @Test void contentRequiresCalibrationProfileAndRoundtripsWithLinkedInputs()throws Exception{
        assertThrows(RuntimeException.class,()->load("freeze"));var p=link("freeze","freeze_test_falloff","freeze_inputs","combat_damage");assertEquals(p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,p.program()).getOrThrow()).getOrThrow());
    }
}
