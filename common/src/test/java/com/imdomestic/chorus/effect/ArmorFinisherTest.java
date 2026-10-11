package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArmorFinisherTest {
    static final String D="chorus_d2:";
    static class Harness extends ArmorChargeTest.Harness {
        Finisher.Decision decision=Finisher.Decision.ALLOWED; Finisher.Plan plan;
        boolean prevented;
        Harness(String... additional) throws Exception {super(modules(additional));equip(FinisherTest.source("player"));bind("mod_inputs","test:finisher_mod_inputs","player");selectMore("melee","finisher_melee");}
        static String[] modules(String[] additional){var names=new ArrayList<>(List.of("armor_finisher_mods","armor_finisher_inputs","void_overshield"));names.addAll(List.of(additional));return names.toArray(String[]::new);}
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r){
            if(r.command() instanceof Finisher.Check q)return new Finisher.Checked(q,decision);
            if(prevented&&r.command() instanceof DamageCommand q){lastCommand=q;lastReceipt=new DamageReceipt(r.id().toString(),DamageReceipt.Outcome.APPLIED,0,0,0,Optional.empty(),true);return lastReceipt;}
            return super.world(r);
        }
        void input(String kind){session.start(now(),new RuleEngine.Signal("test:"+kind,new EffectEvent("player","enemy",FinisherTest.source("player").origin(),Set.of(),Map.of())));healthy();}
        void begin(){health.put("enemy",1.0);input("begin");plan=(Finisher.Plan)state().timers().values().stream().map(t->t.signal().payload()).filter(Finisher.Plan.class::isInstance).findFirst().orElseThrow();}
        void complete(){begin();until(now()+300_000);}
        void setup(String mod,int n){mod(mod,0);for(int i=0;i<3;i++)mod("charged_up",i);gain(n);}
        RuleEngine.Signal finishedFact(){var e=(EffectEvent)facts().stream().filter(f->f.type().equals("chorus:kill")).findFirst().orElseThrow().payload();var refs=new HashMap<>(e.references());refs.put("finisher_id",plan.id());return new RuleEngine.Signal("chorus:finisher",new EffectEvent(e.actor(),e.victim(),e.source(),e.tags(),e.numbers(),e.flags(),refs,e.impact(),e.reactions(),e.proc(),e.observedBuffs(),e.observedEntities()));}
    }
    @Test void healthyConsumesOneAfterConfirmedCompletionAndRestoresActualHealthOnly()throws Exception{
        var h=new Harness();h.setup("healthy_finisher",3);h.begin();assertEquals(3,h.charges());assertEquals(100,h.health.get("player"));h.until(300_000);assertEquals(2,h.charges());assertEquals(1000,h.health.get("player"));assertEquals(1,h.heals.size());assertEquals("healthy_finisher/0/player",h.heals.getFirst().source().source());assertTrue(h.buff("void_overshield").isEmpty());
    }
    @Test void insufficientChargesDoNotPreventFinisherAndDoNotPartiallyPay()throws Exception{
        for(String mod:List.of("healthy_finisher","bulwark_finisher","explosive_finisher","one_two_finisher","utility_finisher")){
            var h=new Harness();int n=mod.equals("healthy_finisher")?0:2;h.setup(mod,n);h.complete();assertEquals(0,h.health.get("enemy"));assertEquals(n,h.charges());assertTrue(h.heals.isEmpty());assertTrue(h.buff("void_overshield").isEmpty());assertEquals(0,h.energy("a"));assertEquals(0,h.account("finisher_melee"));assertEquals(0,h.account("class_a"));
        }
    }
    @Test void eachEnergyModUsesAllPaidStacksAndTheRecipientsCESExactlyOnce()throws Exception{
        String[] mods={"explosive_finisher","one_two_finisher","utility_finisher"};String[] accounts={"grenade_a","finisher_melee","class_a"};
        for(int m=0;m<mods.length;m++)for(int n=3;n<=6;n++){var h=new Harness();h.setup(mods[m],n);h.complete();assertEquals(0,h.charges());assertEquals((.7+(n-3)*.1)*.5,h.account(accounts[m]),1e-12);assertEquals(100,h.health.get("player"));}
    }
    @Test void bulwarkUsesAllPaidStacksForFortyFiveHpAndEightToFourteenSecondDuration()throws Exception{
        for(int n=3;n<=6;n++){var h=new Harness();h.setup("bulwark_finisher",n);h.complete();assertEquals(0,h.charges());var b=h.buff("void_overshield").orElseThrow();assertEquals(45,b.components().numbers().get("capacity"));assertEquals(h.now()+(8+2*(n-3))*1_000_000,b.deadline());h.unmod("bulwark_finisher",0);assertTrue(h.buff("void_overshield").isPresent());h.until(b.deadline());assertTrue(h.buff("void_overshield").isEmpty());}
    }
    @Test void fullHealthAndMissingOrFullAbilityAccountKeepChargesAndConsumeTheReceipt()throws Exception{
        var h=new Harness();h.setup("healthy_finisher",3);h.health.put("player",1000.0);h.complete();var fact=h.finishedFact();assertEquals(3,h.charges());h.health.put("player",100.0);h.replay(List.of(fact));assertEquals(3,h.charges());assertTrue(h.heals.isEmpty());
        for(boolean missing:List.of(false,true)){var e=new Harness();e.setup("utility_finisher",3);if(missing)e.selectMore("class",null);else e.fill("class_a");e.complete();var old=e.finishedFact();assertEquals(3,e.charges());e.selectMore("class","class_b");e.replay(List.of(old));assertEquals(0,e.account("class_b"));assertEquals(3,e.charges());}
    }
    @Test void selectedAbilityAtCompletionReceivesEnergyAndOldSelectionRemainsUnchanged()throws Exception{
        var h=new Harness();h.setup("utility_finisher",4);h.begin();h.selectMore("class","class_b");h.until(300_000);assertEquals(.8*.75,h.account("class_b"),1e-12);assertEquals(0,h.account("class_a"));assertEquals(0,h.charges());
    }
    @Test void partiallyChargedSecondUseCanReceiveEnergyUnderTheExplicitChorusCapacityPolicy()throws Exception{
        var h=new Harness();h.setup("one_two_finisher",6);h.complete();h.gain(6);h.complete();assertEquals(1,h.account("finisher_melee"));h.gain(6);h.complete();assertEquals(1.5,h.account("finisher_melee"));h.gain(6);h.complete();assertEquals(2,h.account("finisher_melee"));h.gain(6);h.complete();assertEquals(6,h.charges());
    }
    @Test void empoweredStartsOnlyAtZeroAndStacksOnStacksAddsOneWithoutDuplicateModBenefits()throws Exception{
        var h=new Harness();h.mod("empowered_finish",0);h.mod("empowered_finish",1);h.mod("stacks_on_stacks",0);h.complete();assertEquals(2,h.charges());h.complete();assertEquals(2,h.charges());h.spend(2);h.complete();assertEquals(2,h.charges());
    }
    @Test void differentModesSuppressBenefitsRegardlessOfSourceOrderButStillFinishTheTarget()throws Exception{
        for(boolean reversed:List.of(false,true)){var h=new Harness();h.mod(reversed?"healthy_finisher":"empowered_finish",0);h.mod(reversed?"empowered_finish":"healthy_finisher",0);h.gain(3);h.complete();var old=h.finishedFact();assertEquals(3,h.charges());assertEquals(100,h.health.get("player"));assertEquals(0,h.health.get("enemy"));h.unmod("empowered_finish",0);h.replay(List.of(old));assertEquals(3,h.charges());h.complete();assertEquals(2,h.charges());assertEquals(1000,h.health.get("player"));}
        var h=new Harness();h.setup("utility_finisher",6);h.mod("explosive_finisher",0);h.complete();assertEquals(6,h.charges());assertEquals(0,h.account("class_a"));assertEquals(0,h.energy("a"));
    }
    @Test void duplicateCopiesAndFactsAfterRebindingChangingModeAndCheckpointCannotSpendOrAwardTwice()throws Exception{
        var h=new Harness();h.setup("healthy_finisher",3);h.mod("healthy_finisher",1);h.complete();var old=h.finishedFact();assertEquals(2,h.charges());assertEquals(1,h.heals.size());h.unmod("healthy_finisher",0);h.unmod("healthy_finisher",1);h.mod("empowered_finish",0);h.spend(2);h.restore();h.replay(List.of(old));assertEquals(0,h.charges());h.complete();assertEquals(1,h.charges());
    }
    @Test void cancelProtectedStrikeAndOrdinaryKillCannotSpendChargesOrAwardFinisherBenefits()throws Exception{
        for(int mode=0;mode<3;mode++){var h=new Harness();h.setup("healthy_finisher",3);if(mode==0){h.begin();h.input("cancel");h.until(300_000);}else if(mode==1){h.prevented=true;h.complete();}else h.kill();assertEquals(3,h.charges());assertEquals(100,h.health.get("player"));assertTrue(h.heals.isEmpty());}
    }
    @Test void equipmentIsResolvedAtCompletionAndOldReceiptsSurviveControllerRebinding()throws Exception{
        var h=new Harness();h.setup("healthy_finisher",3);h.begin();h.unmod("healthy_finisher",0);h.mod("utility_finisher",0);h.until(300_000);assertEquals(.35,h.account("class_a"),1e-12);assertTrue(h.heals.isEmpty());var old=h.finishedFact();h.remove("armor_charge/player");h.equip(ArmorChargeTest.system("player"));h.gain(3);h.replay(List.of(old));assertEquals(3,h.charges());
    }
    @Test void unknownHealKeepsActualPaymentAndHealthAndCannotReplayTheReward()throws Exception{
        var h=new Harness();h.setup("healthy_finisher",3);h.unknownHeal=true;h.begin();assertThrows(IllegalStateException.class,()->h.until(300_000));assertEquals(2,h.charges());assertEquals(1000,h.health.get("player"));assertEquals(1,h.heals.size());assertThrows(IllegalStateException.class,()->h.until(400_000));assertEquals(1,h.heals.size());assertEquals(2,h.charges());
    }
    @Test void rejectedHealingStillPaysOnceWhileMissingRecipientCannotPay()throws Exception{
        var h=new Harness();h.setup("healthy_finisher",3);h.denyHeal=true;h.complete();assertEquals(2,h.charges());assertEquals(100,h.health.get("player"));h.replay(List.of(h.finishedFact()));assertEquals(1,h.heals.size());
        var absent=new Harness();absent.setup("healthy_finisher",3);absent.kinds.remove("player");absent.complete();assertEquals(3,absent.charges());assertTrue(absent.heals.isEmpty());
    }
}
