package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class KickstartTest {
    static final String D="chorus_d2:";
    static final double[] PVE={.162,.217,.284,.344,.384,.413,.434,.45},PVP={.125,.142,.162,.187,.217,.25,.284,.316};
    static String modName(String slot){return (slot.equals("class")?"utility":slot)+"_kickstart";}
    static class Harness extends ArmorChargeTest.Harness {
        int cast;List<RuleEngine.Signal> lastFacts=List.of();
        Harness()throws Exception{this(false);}
        Harness(boolean pvp)throws Exception{super("kickstart","kickstart_inputs");bind("kick_inputs","test:kick_inputs","player");for(String slot:List.of("grenade","melee","class"))selectMore(slot,"kick_"+slot);if(pvp)session=new EffectSession(p,EffectCheckpointTest.clock(),1,state().withMode(EffectState.Mode.PVP),this::world);}
        void setup(String slot,int copies,int charges){for(int i=0;i<copies;i++)mod(modName(slot),i);for(int i=0;i<3;i++)mod("charged_up",i);gain(charges);fill("kick_"+slot);}
        AbilityUse.Receipt use(String slot){return use(slot,Map.of(),Map.of());}
        AbilityUse.Receipt use(String slot,Map<String,Measure> numbers,Map<String,Boolean> flags){var request=new AbilityUse.Request("player",D+slot,"kick/"+(++cast),new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),numbers,flags,Map.of()));var planned=p.useAbility(state(),request);lastFacts=planned.emitted();session.start(now(),request.signal());healthy();return (AbilityUse.Receipt)planned.result();}
        RuleEngine.Signal started(){return lastFacts.stream().filter(f->f.type().equals(AbilityUse.STARTED)).findFirst().orElseThrow();}
        double energyFor(String slot){return account("kick_"+slot);}
        void manualSpend(String slot){session.start(now(),new RuleEngine.Signal("test:spend_kick_"+slot,new EffectEvent("player","player",new BuffInstance.Origin("player","input","",""),Set.of(),Map.of())));healthy();}
    }
    @Test void allThreeModsUseEveryArmorAndCopyCombinationAndBothModeTablesWithCESOnce()throws Exception{
        for(boolean pvp:List.of(false,true))for(String slot:List.of("grenade","melee","class"))for(int copies=1;copies<=3;copies++)for(int charges=1;charges<=6;charges++){
            var h=new Harness(pvp);h.setup(slot,copies,charges);assertEquals(AbilityUse.Outcome.ACCEPTED,h.use(slot).outcome());assertEquals(0,h.charges());assertEquals(1+(pvp?PVP:PVE)[copies+charges-2]*.5,h.energyFor(slot),1e-12,slot+" copies="+copies+" charges="+charges+" pvp="+pvp);assertEquals(101,h.health.get("player"));assertEquals(1,h.heals.size());
        }
    }
    @Test void currentMeleeRuleConsumesAFullChargeEvenWithAnotherWholeChargeRemaining()throws Exception{
        var h=new Harness();h.setup("melee",1,3);h.use("melee");assertEquals(1+.284*.5,h.energyFor("melee"),1e-12);assertEquals(0,h.charges());h.gain(1);h.use("melee");assertEquals(.284*.5+.162*.5,h.energyFor("melee"),1e-12);assertEquals(0,h.charges());
    }
    @Test void zeroArmorDoesNotRefundAndOldAcceptedFactCannotSpendChargesAcquiredLater()throws Exception{
        var h=new Harness();h.setup("grenade",1,0);h.use("grenade");var old=h.started();assertEquals(1,h.energyFor("grenade"));h.gain(3);h.replay(List.of(old));assertEquals(3,h.charges());assertEquals(1,h.energyFor("grenade"));h.use("grenade");assertEquals(0,h.charges());assertEquals(.284*.5,h.energyFor("grenade"),1e-12);
    }
    @Test void freePartialFailedAndExplicitlyDeferredCastsNeverTriggerTheImmediateKickstartPath()throws Exception{
        for(String suffix:List.of("_free","_partial","_blocked","_deferred")){var h=new Harness();h.setup("grenade",1,3);h.selectMore("grenade","kick_grenade"+suffix);var used=h.use("grenade");assertEquals(suffix.equals("_blocked")?AbilityUse.Outcome.CONDITION:AbilityUse.Outcome.ACCEPTED,used.outcome());assertEquals(3,h.charges());assertEquals(suffix.equals("_partial")?1.5:suffix.equals("_deferred")?1:2,h.energyFor("grenade"));}
        var h=new Harness();h.mod("melee_kickstart",0);h.gain(3);assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY,h.use("melee").outcome());assertEquals(3,h.charges());assertEquals(0,h.energyFor("melee"));
    }
    @Test void inputCannotSpoofPaidOrFreeFlagsAndGenericResourceSpendIsNotAnAbilityUse()throws Exception{
        var h=new Harness();h.setup("grenade",1,3);h.selectMore("grenade","kick_grenade_free");h.use("grenade",Map.of("paid",new Measure(1,Unit.CHARGE)),Map.of("free",false));assertEquals(3,h.charges());assertEquals(2,h.energyFor("grenade"));h.manualSpend("grenade");assertEquals(3,h.charges());assertEquals(1,h.energyFor("grenade"));h.selectMore("grenade","kick_grenade");h.use("grenade",Map.of("paid",new Measure(0,Unit.CHARGE)),Map.of("free",true));assertEquals(0,h.charges());assertEquals(.284*.5,h.energyFor("grenade"),1e-12);
    }
    @Test void oneAcceptedUseSpendingTwoChargesStillTriggersOnceAndDoesNotMultiplyTheTableByCost()throws Exception{
        var h=new Harness();h.setup("grenade",1,6);h.selectMore("grenade","kick_grenade_two");h.use("grenade");assertEquals(.413*.5,h.energyFor("grenade"),1e-12);assertEquals(0,h.charges());
    }
    @Test void onlyTheUsedSlotConsumesFromTheSharedPoolAndOtherModTypesDoNotIncreaseItsCopyCount()throws Exception{
        var h=new Harness();h.setup("grenade",1,3);h.mod("melee_kickstart",0);h.mod("utility_kickstart",0);h.use("grenade");assertEquals(1+.284*.5,h.energyFor("grenade"),1e-12);assertEquals(0,h.energyFor("melee"));assertEquals(0,h.energyFor("class"));assertEquals(0,h.charges());
    }
    @Test void changingCopyCountChangesTheNextRefundButDoesNotDuplicateEarlierCast()throws Exception{
        var h=new Harness();h.setup("grenade",1,3);h.use("grenade");var old=h.started();h.gain(3);h.mod("grenade_kickstart",1);h.mod("grenade_kickstart",2);h.replay(List.of(old));assertEquals(3,h.charges());h.fill("kick_grenade");h.use("grenade");assertEquals(1+.384*.5,h.energyFor("grenade"),1e-12);h.unmod("grenade_kickstart",2);h.unmod("grenade_kickstart",1);h.gain(3);h.fill("kick_grenade");h.use("grenade");assertEquals(1+.284*.5,h.energyFor("grenade"),1e-12);
    }
    @Test void additionalGainModifiersApplyOnceAndArmorChargesRemainDiscreteUnscaledCosts()throws Exception{
        var h=new Harness();h.setup("class",3,6);h.bind("boost","test:kick_gain_boost","player");h.use("class");assertEquals(1+.45,h.energyFor("class"),1e-12);assertEquals(0,h.charges());
    }
    @Test void receiptsSurviveSourceAndControllerRebindingAndSessionCheckpoint()throws Exception{
        var h=new Harness();h.setup("melee",1,3);h.use("melee");var old=h.started();h.unmod("melee_kickstart",0);h.remove("armor_charge/player");h.equip(ArmorChargeTest.system("player"));h.mod("melee_kickstart",0);h.gain(3);h.restore();h.replay(List.of(old));assertEquals(3,h.charges());assertEquals(1+.284*.5,h.energyFor("melee"),1e-12);
    }
    @Test void fullOrMissingCurrentRecipientConsumesTheIdentityWithoutConsumingArmorCharges()throws Exception{
        for(boolean missing:List.of(false,true)){var h=new Harness();h.fill("kick_class");h.use("class");var old=h.started();h.setup("class",1,3);if(missing)h.selectMore("class",null);h.replay(List.of(old));assertEquals(3,h.charges());h.selectMore("class","kick_class");h.manualSpend("class");h.replay(List.of(old));assertEquals(3,h.charges());assertEquals(1,h.energyFor("class"));}
    }
    @Test void unknownAbilityBodyRetainsAcceptedCostKickstartPaymentAndRefundWithoutReplay()throws Exception{
        var h=new Harness();h.setup("melee",1,3);h.unknownHeal=true;assertThrows(IllegalStateException.class,()->h.use("melee"));assertEquals(0,h.charges());assertEquals(1+.284*.5,h.energyFor("melee"),1e-12);assertEquals(101,h.health.get("player"));assertThrows(IllegalStateException.class,()->h.until(100_000));assertEquals(1,h.heals.size());
    }
}
