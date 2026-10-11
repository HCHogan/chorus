package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Known Ophidia behavior plus explicit synthetic timing/PvP/skill calibration. */
class OphidiaSpatheTest {
    static final String D="chorus_d2:", SLOT=D+"melee", SC=D+"scissor_fingers", REC=D+"ophidia_recent_melee";
    static CompiledEffects program() throws Exception {return link("character_stats","combat_damage","solar_melee_energy","ophidia_spathe","ophidia_spathe_inputs");}
    static Map<String,Measure> calibration(){return Map.of("recent_use_seconds",new Measure(.2,Unit.SECOND),"pvp_one",new Measure(.11,Unit.DELTA),"pvp_two",new Measure(.22,Unit.DELTA),"pvp_three",new Measure(.33,Unit.DELTA));}
    static EffectSource exotic(String id,String holder){return new EffectSource(id,D+"ophidia_spathe",holder,new BuffInstance.Origin(holder,id,"",""),Set.of(D+"ophidia_spathe"),calibration());}
    static class Harness {
        final CompiledEffects p;final EffectSession session;final List<DamageCommand> damage=new ArrayList<>();final List<HealingCommand> healing=new ArrayList<>();final List<Double> amounts=new ArrayList<>();
        DamageReceipt.Outcome outcome=DamageReceipt.Outcome.APPLIED;boolean lethal,prevented,failHeal;int casts;List<RuleEngine.Signal> facts=List.of();
        Harness() throws Exception {this(EffectState.Mode.PVE);}
        Harness(EffectState.Mode mode)throws Exception {
            p=program();session=new EffectSession(engine(p),EffectState.empty().withMode(mode),r->switch(r.command()){
                case DamageCommand d->{damage.add(d);double amount=p.outgoing(state(),d,d.amount()).orElseThrow().output().value();amounts.add(amount);var receipt=new DamageReceipt(r.id().toString(),outcome,0,0,outcome==DamageReceipt.Outcome.APPLIED?amount:0,lethal&&!prevented&&outcome==DamageReceipt.Outcome.APPLIED?Optional.of("death/"+r.id()):Optional.empty(),prevented);facts=DamageFacts.from(d,receipt);yield receipt;}
                case HealingCommand h->{healing.add(h);if(failHeal)throw new IllegalStateException("unknown knife body");yield new HealingReceipt(r.id().toString(),h,HealingReceipt.Outcome.APPLIED,h.amount(),h.amount(),0);}
                default->throw new AssertionError(r.command());
            });
            for(String holder:List.of("player","other"))send(SourceChange.bind(new EffectSource("input/"+holder,"test:ophidia_inputs",holder,new BuffInstance.Origin(holder,"input/"+holder,"",""),Set.of())));
            select("solar_knife","gamblers_dodge");
        }
        EffectState state(){return session.state().engine().domain();}long now(){return state().buffs().timeMicros();}
        void send(RuleEngine.Signal signal){session.start(now(),signal);}void until(long t){session.observe(t,List.of());}
        void select(String melee,String dodge){var map=new HashMap<String,String>();if(!melee.isEmpty())map.put(SLOT,"test:"+melee);if(!dodge.isEmpty())map.put(D+"class","test:"+dodge);send(new AbilityChange("player",state().abilities().getOrDefault("player",AbilityLoadout.EMPTY),new AbilityLoadout(map)).signal());}
        void equip(String id){send(SourceChange.bind(exotic(id,"player")));}
        ResourceState account(String id){return state().resources().get(new ResourceState.Key("player",D+"solar_melee_"+id));}
        Optional<BuffInstance> buff(String id){return state().buffs().instances().values().stream().filter(b->b.key().holder().equals("player")&&b.definition().id().equals(id)).findFirst();}
        int stacks(){return buff(SC).map(BuffInstance::count).orElse(0);}
        EffectEvent event(String actor,String target){return new EffectEvent(actor,target,new BuffInstance.Origin(actor,"attack","","test:solar_knife"),Set.of(),Map.of());}
        AbilityUse.Outcome use(String slot){var request=new AbilityUse.Request("player",slot,"cast/"+ ++casts,event("player","player"));var result=(AbilityUse.Receipt)p.useAbility(state(),request).result();send(request.signal());return result.outcome();}
        void cast(){assertEquals(AbilityUse.Outcome.ACCEPTED,use(SLOT));}
        void grant(double n,String basis){send(new RuleEngine.Signal("test:"+basis,new EffectEvent("player","player",event("player","player").source(),Set.of(),Map.of("amount",new Measure(n,Unit.CHARGE)))));}
        double hit(String kind,boolean kill){return hit("player",kind,kill);}
        double hit(String holder,String kind,boolean kill){lethal=kill;send(new RuleEngine.Signal("test:"+kind,event(holder,"target")));return amounts.getLast();}
    }
    @Test void capacityUsesCurrentSolarSelectionWithoutGrantingEnergyOrStackingIntrinsicSecondCharge()throws Exception {
        var h=new Harness();assertEquals(1,h.account("uses").capacity());assertEquals(1,h.account("uses").value());h.equip("exotic");assertEquals(2,h.account("uses").capacity());assertEquals(1,h.account("uses").value());
        h.equip("duplicate");assertEquals(2,h.account("uses").capacity());h.grant(1,"fixed");assertEquals(2,h.account("uses").value());
        h.select("solar_two_knives","gamblers_dodge");assertEquals(2,h.account("uses").capacity());assertEquals(2,h.account("uses").value());
        h.select("non_solar_melee","gamblers_dodge");assertEquals(1,h.state().resources().get(new ResourceState.Key("player","test:non_solar_uses")).capacity());h.select("","gamblers_dodge");h.send(SourceChange.remove("exotic"));h.send(SourceChange.remove("duplicate"));
        h.select("solar_knife","gamblers_dodge");assertEquals(1,h.account("uses").capacity());assertEquals(1,h.account("uses").value());
    }
    @Test void recentUseYieldsOneAndExactExpiryRestoresAllMissingThroughSlotBasedGains()throws Exception {
        var h=new Harness();h.equip("exotic");h.grant(1,"fixed");h.cast();h.cast();assertEquals(0,h.account("uses").value());assertEquals(200_000,h.buff(REC).orElseThrow().deadline());
        h.grant(1,"fixed");assertEquals(1,h.account("uses").value(),"recent-use exception uses explicit one-charge policy");h.cast();h.until(199_999);assertTrue(h.buff(REC).isPresent());h.until(200_000);assertTrue(h.buff(REC).isEmpty());h.grant(1,"fixed");assertEquals(2,h.account("uses").value());assertEquals(0,h.account("progress").value());
    }
    @Test void naturalCyclesPauseWithoutSelectionAndRestoreBothWithoutCapacityMultiplyingRate()throws Exception {
        var h=new Harness();h.equip("exotic");h.grant(1,"fixed");h.cast();h.cast();h.until(300_000);assertEquals(.3,h.account("progress").value(),1e-8);h.select("","gamblers_dodge");h.until(800_000);assertEquals(.3,h.account("progress").value(),1e-8);
        h.select("solar_two_knives","gamblers_dodge");h.until(1_499_999);assertEquals(0,h.account("uses").value());h.until(1_500_000);assertEquals(2,h.account("uses").value());assertEquals(0,h.account("progress").value());
    }
    @Test void baseGainsApplySelectedScalarAndCurrentMeleeStatWhileFixedDodgeBypassesThem()throws Exception {
        var h=new Harness();h.equip("exotic");h.grant(1,"fixed");h.cast();h.cast();h.grant(.4,"base");assertEquals(.2,h.account("progress").value(),1e-8);assertEquals(0,h.account("uses").value());h.use(D+"class");assertEquals(1,h.account("uses").value());assertEquals(0,h.account("progress").value());h.cast();h.until(200_000);h.use(D+"class");assertEquals(2,h.account("uses").value());
    }
    @Test void ordinaryTwoChargeMeleeRestoresOnlyOnePerCycleWithoutTheExotic()throws Exception {
        var h=new Harness();h.select("solar_two_knives","gamblers_dodge");h.grant(1,"fixed");h.cast();h.cast();h.until(1_000_000);assertEquals(1,h.account("uses").value());h.until(2_000_000);assertEquals(2,h.account("uses").value());assertEquals(0,h.account("progress").value());
    }
    @Test void statChangesScaleLaterIntervalsAndBaseGainsButNotFixedGrants()throws Exception {
        var h=new Harness();h.equip("exotic");h.grant(1,"fixed");h.cast();h.cast();h.until(100_000);assertEquals(.1,h.account("progress").value(),1e-8);
        h.send(SourceChange.bind(new EffectSource("stats","test:solar_melee_stat","player",new BuffInstance.Origin("player","stats","",""),Set.of(),Map.of("points",new Measure(100,Unit.STAT_POINT)))));
        h.until(200_000);assertEquals(.375,h.account("progress").value(),1e-8);h.grant(.4,"base");assertEquals(.825,h.account("progress").value(),1e-8);h.grant(.175,"fixed");assertEquals(2,h.account("uses").value());assertEquals(0,h.account("progress").value());
    }
    @Test void knifeKillsBuildThreeSharedStacksAndUseThePreKillDamageState()throws Exception {
        var h=new Harness();h.equip("exotic");h.equip("duplicate");assertEquals(10,h.hit("knife",true),1e-8);assertEquals(1,h.stacks());assertEquals(16.7,h.hit("knife",true),1e-8);assertEquals(2,h.stacks());assertEquals(23.3,h.hit("knife",true),1e-8);assertEquals(3,h.stacks());assertEquals(30,h.hit("knife",true),1e-8);assertEquals(3,h.stacks());
        var kill=h.facts.stream().filter(s->s.type().equals("chorus:kill")).findFirst().orElseThrow();long deadline=h.buff(SC).orElseThrow().deadline();h.until(100_000);h.send(kill);assertEquals(3,h.stacks());assertEquals(deadline,h.buff(SC).orElseThrow().deadline(),"duplicate receipt refreshed buff");
    }
    @Test void otherAttacksAndDeathsWithoutOwnedKnifeCreditCannotBuildStacks()throws Exception {
        var h=new Harness();h.equip("exotic");h.hit("other","knife",true);h.hit("melee",true);h.hit("scorch",true);h.hit("ignition",true);assertEquals(0,h.stacks());h.prevented=true;h.hit("knife",true);assertEquals(0,h.stacks());h.prevented=false;h.outcome=DamageReceipt.Outcome.CANCELLED;h.hit("knife",true);assertEquals(0,h.stacks());
        h.outcome=DamageReceipt.Outcome.APPLIED;h.hit("knife",true);assertEquals(1,h.stacks());assertEquals(10,h.hit("melee",false));assertEquals(10,h.hit("scorch",false));assertEquals(10,h.hit("ignition",false));assertEquals(10,h.hit("other","knife",false));
    }
    @Test void damageAddsToOtherMeleeFamiliesAndPvpRequiresItsOwnCalibration()throws Exception {
        for(var mode:EffectState.Mode.values()){
            var h=new Harness(mode);h.equip("exotic");h.send(SourceChange.bind(new EffectSource("otherbonus","test:melee_bonus","player",new BuffInstance.Origin("player","otherbonus","",""),Set.of())));
            for(int i=0;i<3;i++)h.hit("knife",true);assertEquals(mode==EffectState.Mode.PVP?28.3:45,h.hit("knife",false),1e-8);
        }
    }
    @Test void qualifyingDodgeRefreshesOnlyExistingStacksAndExactExpiryDoesNotReviveThem()throws Exception {
        var h=new Harness();h.equip("exotic");h.use(D+"class");assertEquals(0,h.stacks());h.hit("knife",true);h.until(4_000_000);h.use(D+"class");assertEquals(1,h.stacks());assertEquals(9_000_000,h.buff(SC).orElseThrow().deadline());
        h.select("solar_knife","other_dodge");h.until(8_000_000);h.use(D+"class");assertEquals(9_000_000,h.buff(SC).orElseThrow().deadline());h.select("solar_knife","gamblers_dodge");h.until(9_000_000);h.use(D+"class");assertEquals(0,h.stacks());
    }
    @Test void cappedKillsRefreshAndReceiptBucketsExpireWithoutUnboundedSlidingHistory()throws Exception {
        var h=new Harness();h.equip("exotic");for(int i=0;i<3;i++)h.hit("knife",true);h.until(4_000_000);h.hit("knife",true);assertEquals(3,h.stacks());assertEquals(9_000_000,h.buff(SC).orElseThrow().deadline());h.until(5_000_000);assertTrue(h.buff(D+"ophidia_kill_receipts").isEmpty());assertEquals(3,h.stacks());h.hit("knife",true);assertEquals(1,h.buff(D+"ophidia_kill_receipts").orElseThrow().components().sets().get("deaths").size());
    }
    @Test void atomicEquivalentSourceReplacementKeepsStacksAndFinalRemovalCleansState()throws Exception {
        var h=new Harness();h.equip("exotic");h.grant(1,"fixed");h.hit("knife",true);var old=h.state().sources().get("exotic");var next=exotic("replacement","player");
        h.send(new RuleEngine.Signal(SourceBatch.EVENT,new SourceBatch(List.of(new SourceBatch.Edit("exotic",Optional.of(old),Optional.empty()),new SourceBatch.Edit("replacement",Optional.empty(),Optional.of(next))))));assertEquals(1,h.stacks());assertEquals(2,h.account("uses").value());h.send(SourceChange.remove("replacement"));assertEquals(0,h.stacks());assertEquals(1,h.account("uses").value());assertTrue(h.buff(D+"ophidia_kill_receipts").isEmpty());
    }
    @Test void holderDeathClearsAllOphidiaBuffsWithoutAffectingAnotherHolder()throws Exception {
        var h=new Harness();h.equip("exotic");h.hit("knife",true);h.cast();h.send(new RuleEngine.Signal("chorus:death",h.event("other","other")));assertEquals(1,h.stacks());h.send(new RuleEngine.Signal("chorus:death",h.event("other","player")));assertEquals(0,h.stacks());assertTrue(h.buff(REC).isEmpty());assertTrue(h.buff(D+"ophidia_kill_receipts").isEmpty());
    }
    @Test void explicitParametersAreRequiredAndContentRoundtrips()throws Exception {
        var h=new Harness();assertEquals(h.p.program(),EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE,h.p.program()).getOrThrow()).getOrThrow());
        for(String missing:calibration().keySet()){
            var params=new HashMap<>(calibration());params.remove(missing);var s=new EffectSource("invalid",D+"ophidia_spathe","player",new BuffInstance.Origin("player","invalid","",""),Set.of(D+"ophidia_spathe"),params);
            assertThrows(IllegalArgumentException.class,()->h.p.validateSource(s));
        }
    }
    @Test void unknownAbilityBodyKeepsPaidChargeAndNeverReplaysTheWorldAction()throws Exception {
        var h=new Harness();h.equip("exotic");h.failHeal=true;assertThrows(IllegalStateException.class,h::cast);assertEquals(0,h.account("uses").value());assertEquals(1,h.healing.size());assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(1,h.healing.size());
    }
}
