package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ArmorChargeGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/** Server-accepted skill costs, physical Orbs and native skill bodies exercise the shared charge consumer. */
public class KickstartGameTest {
    static String modName(String slot){return (slot.equals("class")?"utility":slot)+"_kickstart";}
    static ProjectileGameTest.Harness openKickstart(GameTestHelper h)throws Exception{
        var t=ArmorChargeGameTest.open(h,"kickstart","kickstart_inputs");
        try{IncandescentGameTest.bind(t,"kick_inputs","test:kick_inputs","");for(String slot:List.of("grenade","melee","class"))StarvationGameTest.select(t,slot,"kick_"+slot);return t;}catch(Exception|Error e){t.close();throw e;}
    }
    static void setup(ProjectileGameTest.Harness t,String slot,int copies,int n){for(int i=0;i<copies;i++)mod(t,modName(slot),i);for(int i=0;i<3;i++)mod(t,"charged_up",i);for(int i=0;i<n;i++)orb(t);StarvationGameTest.fill(t,"kick_"+slot);}
    static double energy(ProjectileGameTest.Harness t,String slot){return StarvationGameTest.account(t,"kick_"+slot);}
    static AbilityUse.Receipt use(ProjectileGameTest.Harness t,String slot){var r=t.runtime.useAbility(t.owner,D+slot);ShieldThrowGameTest.settled(t);return r;}
    static void table(GameTestHelper h,String slot,int copies,int n,double base)throws Exception{
        try(var t=openKickstart(h)){setup(t,slot,copies,n);h.assertValueEqual(charges(t),n,"real Orb charge pool");var r=use(t,slot);h.assertValueEqual(r.outcome(),AbilityUse.Outcome.ACCEPTED,"server accepted ability");h.assertValueEqual(charges(t),0,"all charges consumed exactly once");near(h,energy(t,slot),1+base*.5,"table uses paid charges and copy count, CES once");near(h,t.owner.getHealth(),101,"actual skill body executed once");h.assertValueEqual(t.cues.stream().filter(c->c.cue().equals("test:kick_"+slot+"_cast")).count(),1L,"one physical use body");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:kickstart_grenade")
    public void grenadePaidUseConsumesOneActualOrbAndGrantsTheLowestTableTier(GameTestHelper h)throws Exception{table(h,"grenade",1,1,.162);}
    @GameCase(environment="chorus_gametest:kickstart_melee")
    public void meleeTwoCopiesAndFourActualOrbsSelectTheCombinedTierOnce(GameTestHelper h)throws Exception{table(h,"melee",2,4,.384);}
    @GameCase(environment="chorus_gametest:kickstart_class")
    public void utilityThreeCopiesAndSixActualOrbsSelectTheMaximumTierOnce(GameTestHelper h)throws Exception{table(h,"class",3,6,.45);}
    @GameCase(environment="chorus_gametest:kickstart_multicharge")
    public void meleeTriggersWithOneWholeChargeRemainingAndTheNextIndependentUseCanTriggerAgain(GameTestHelper h)throws Exception{
        try(var t=openKickstart(h)){setup(t,"melee",1,3);use(t,"melee");near(h,energy(t,"melee"),1+.284*.5,"second full charge does not suppress current melee behavior");orb(t);use(t,"melee");near(h,energy(t,"melee"),(.284+.162)*.5,"independent accepted use receives its own reward");h.assertValueEqual(charges(t),0,"new Orb paid");near(h,t.owner.getHealth(),102,"two actual skill uses");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:kickstart_excluded")
    public void freePartialRejectedDeferredAndOrdinaryUnpoweredAttacksDoNotSpendCharges(GameTestHelper h)throws Exception{
        for(String slot:List.of("grenade","melee","class"))try(var t=openKickstart(h)){setup(t,slot,1,3);
            for(String suffix:List.of("_free","_partial","_blocked","_deferred")){StarvationGameTest.fill(t,"kick_"+slot);StarvationGameTest.select(t,slot,"kick_"+slot+suffix);var r=use(t,slot);h.assertValueEqual(r.outcome(),suffix.equals("_blocked")?AbilityUse.Outcome.CONDITION:AbilityUse.Outcome.ACCEPTED,"use qualification");h.assertValueEqual(charges(t),3,"excluded use retains Armor Charge");near(h,energy(t,slot),suffix.equals("_partial")?1.5:suffix.equals("_deferred")?1:2,"no immediate Kickstart energy");}
            t.runtime.start(new RuleEngine.Signal("test:spend_kick_"+slot,new EffectEvent(owner(t),owner(t),new BuffInstance.Origin(owner(t),"input","",""),Set.of(),Map.of())));ShieldThrowGameTest.settled(t);near(h,energy(t,slot),0,"generic payment spends last resource unit");h.assertValueEqual(charges(t),3,"generic spend does not imply an ability use");StarvationGameTest.select(t,slot,"kick_"+slot);h.assertValueEqual(use(t,slot).outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"insufficient use rejected");h.assertValueEqual(charges(t),3,"failed cast no mod cost");
            t.owner.setPos(h.absoluteVec(new Vec3(2.5,40,3.5)));var victim=t.cow(3.5,40,3.5);t.owner.attack(victim);ShieldThrowGameTest.settled(t);h.assertTrue(victim.getHealth()<100,"real ordinary attack did damage");h.assertValueEqual(charges(t),3,"unpowered hit not a paid skill");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:kickstart_shield_throw")
    public void actualShieldProjectileConsumesChargesAndUsesItsOwnRecipientScalarWithoutSyntheticSkillBody(GameTestHelper h)throws Exception{
        try(var t=openKickstart(h)){setup(t,"melee",1,3);t.owner.setPos(h.absoluteVec(new Vec3(2.5,40,3.5)));var victim=FeedTheVoidGameTest.target(t,0,1000);FeedTheVoidGameTest.cast(t,false);ShieldThrowGameTest.settled(t);h.assertValueEqual(charges(t),0,"paid projectile cast consumes mod charge");near(h,state(t).resources().get(new ResourceState.Key(owner(t),D+"shield_throw_energy")).value(),.284*.9,"Shield Throw current gain profile applies its 0.9 scalar once");near(h,energy(t,"melee"),2,"previous synthetic account unaffected by current selection");FeedTheVoidGameTest.contacts(t,1);near(h,victim.getHealth(),600,"actual shield impact");near(h,ShieldThrowGameTest.capacity(t,D+"void_overshield"),15,"normal on-hit shield reward still composes");near(h,t.owner.getHealth(),100,"no synthetic heal in physical skill");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:kickstart_fonts")
    public void actualConsumptionRemovesPassiveFontAndAppliesAdditionalEnergyGainOnlyOnce(GameTestHelper h)throws Exception{
        try(var t=openKickstart(h)){setup(t,"class",3,6);mod(t,"health_font",0);near(h,stat(t,"health"),70,"active passive benefit");IncandescentGameTest.bind(t,"boost","test:kick_gain_boost","");use(t,"class");near(h,energy(t,"class"),1+.45,"CES half and gain double apply once each");h.assertValueEqual(charges(t),0,"unscaled six-stack payment");near(h,stat(t,"health"),50,"Font disappears with the consumed pool");h.assertTrue(buff(t,"armor_charge_decay").isEmpty(),"empty pool has no remaining decay timer");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:kickstart_identity")
    public void lateCopiesAndReplayedAcceptedIdentityCannotSpendNewlyCollectedOrbs(GameTestHelper h)throws Exception{
        try(var t=openKickstart(h)){setup(t,"grenade",1,3);var r=use(t,"grenade");for(int i=0;i<3;i++)orb(t);mod(t,"grenade_kickstart",1);mod(t,"grenade_kickstart",2);
            var old=new EffectEvent(owner(t),owner(t),new BuffInstance.Origin(owner(t),"cast/"+r.cast(),"",r.resolved()),Set.of(),Map.of("paid",new Measure(1,Unit.CHARGE)),Map.of("free",false),Map.of("cast",r.cast(),"ability_slot",r.slot(),"ability",r.resolved(),"base_ability",r.base()));
            t.runtime.start(new RuleEngine.Signal(AbilityUse.STARTED,old));ShieldThrowGameTest.settled(t);h.assertValueEqual(charges(t),3,"old fact is not a new cast");near(h,energy(t,"grenade"),1+.284*.5,"no old reward repeated");StarvationGameTest.fill(t,"kick_grenade");use(t,"grenade");near(h,energy(t,"grenade"),1+.384*.5,"new cast uses current three copies");h.assertValueEqual(charges(t),0,"new cast pays exactly once");near(h,t.owner.getHealth(),102,"replayed start does not re-run skill body");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:kickstart_unknown")
    public void unknownSkillHealingRetainsCostArmorPaymentAndRefundWithoutRepeatingWorldWork(GameTestHelper h)throws Exception{
        try(var t=openKickstart(h)){setup(t,"melee",1,3);t.failAfterHealing=true;boolean threw=false;try{t.runtime.useAbility(t.owner,D+"melee");}catch(IllegalStateException expected){h.assertValueEqual(expected.getMessage(),"Injected unknown projectile healing outcome","expected world failure");threw=true;}h.assertTrue(threw&&t.runtime.failure().isPresent(),"runtime stopped on unknown skill body");h.assertValueEqual(charges(t),0,"mod payment retained");near(h,energy(t,"melee"),1+.284*.5,"accepted cost and prior refund retained");near(h,t.owner.getHealth(),101,"already applied physical healing retained");int operations=t.operations.size();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"no world retry");h.assertValueEqual(charges(t),0,"no additional mod payment");}h.succeed();
    }
}
