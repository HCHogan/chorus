package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ArmorChargeGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

/** Actual Orb contacts, equipped weapons, accepted ammunition costs and physical impacts. */
public class WeaponSurgeGameTest {
    static final List<String> ELEMENTS=List.of("kinetic","arc","solar","void","stasis","strand");
    static ProjectileGameTest.Harness openSurge(GameTestHelper h,String... additional)throws Exception{
        var modules=new ArrayList<>(List.of("weapon_surge","empowering_damage","radiant","weapon_surge_inputs","weapon_surge_weapon"));modules.addAll(List.of(additional));var t=ArmorChargeGameTest.open(h,modules.toArray(String[]::new));
        try{IncandescentGameTest.bind(t,"surge_inputs","test:surge_inputs","");return t;}catch(Exception|Error e){t.close();throw e;}
    }
    static void surge(ProjectileGameTest.Harness t,String element,int copies){for(int i=0;i<copies;i++)mod(t,element+"_weapon_surge",i);}
    static void signal(ProjectileGameTest.Harness t,String kind,double duration){t.runtime.start(new RuleEngine.Signal("test:surge_"+kind,new EffectEvent(owner(t),owner(t),new BuffInstance.Origin(owner(t),"input","",""),Set.of(),Map.of("duration",new Measure(duration,Unit.SECOND)))));ShieldThrowGameTest.settled(t);}
    static void aim(ProjectileGameTest.Harness t){t.owner.setPos(t.h.absoluteVec(new Vec3(2.5,40,3.5)));t.owner.setYRot(0);t.owner.setXRot(-90);}
    static String arm(ProjectileGameTest.Harness t,String element,String perk){
        String id="surge/"+UUID.randomUUID();var stack=new ItemStack(Items.DIAMOND_SWORD);stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(id,"test:surge_"+element,Map.of("perk",perk)));var equipment=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,stack);equipment.swap(t.owner,"test:primary",0,equipment.revision());PugilistGameTest.draw(t,"primary");aim(t);return id;
    }
    static void impact(ProjectileGameTest.Harness t,EffectProjectile shot){for(int i=0;i<30&&!shot.isRemoved();i++)shot.tick();t.h.assertTrue(shot.isRemoved(),"physical shot reached its terminal contact");ShieldThrowGameTest.settled(t);}
    static void hitCurrent(ProjectileGameTest.Harness t,double expected)throws Exception{aim(t);var victim=t.cow(2.5,46,3.5);impact(t,PugilistGameTest.fire(t));near(t.h,victim.getHealth(),100-expected,"actual health after composed weapon damage");near(t.h,t.receipts.getLast().outgoing().orElseThrow().output().value(),expected,"same amount in damage trace");victim.discard();}
    static void hit(ProjectileGameTest.Harness t,String element,double expected)throws Exception{arm(t,element,"none");hitCurrent(t,expected);}
    @GameCase(environment="chorus_gametest:weapon_surge_elements")
    public void allSixElementWeaponsUseActualOrbChargeAndRejectNonmatchingSurges(GameTestHelper h)throws Exception{
        for(String element:ELEMENTS)try(var t=openSurge(h)){surge(t,element,1);orb(t);hit(t,element,11);String other=element.equals("solar")?"void":"solar";hit(t,other,10);h.assertValueEqual(charges(t),1,"shots do not individually consume charge");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_surge_switch")
    public void actualWeaponSwitchReadsEachElementsOwnCopyTierFromTheSharedPool(GameTestHelper h)throws Exception{
        try(var t=openSurge(h)){surge(t,"solar",3);surge(t,"arc",2);orb(t);hit(t,"solar",12.2);hit(t,"void",10);hit(t,"arc",11.7);h.assertValueEqual(charges(t),1,"one shared pool despite different elemental mods");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_surge_rampage",maxTicks=50)
    public void actualRampageKillAndRadiantMultiplyWithOnlyTheHighestActiveSurge(GameTestHelper h)throws Exception{
        var t=openSurge(h);try{surge(t,"solar",3);orb(t);signal(t,"radiant",10);String weapon=arm(t,"solar","rampage");var first=t.cow(2.5,46,3.5);first.setHealth(1);impact(t,PugilistGameTest.fire(t));h.assertTrue(first.isDeadOrDying(),"physical weapon kill grants Rampage");first.discard();near(h,t.receipts.getLast().outgoing().orElseThrow().output().value(),10*1.2*1.22,"first kill has no prior Rampage stack");
            RampageGameTest.at(t,4,()->hitCurrent(t,10*1.2*1.22*1.1));
            RampageGameTest.at(t,8,()->{signal(t,"four_solar",1);hitCurrent(t,10*1.2*1.25*1.1);});
            t.finish(30,()->{try{hitCurrent(t,10*1.2*1.22*1.1);h.assertValueEqual(state(t).ammunition().get(weapon).magazine(),1,"all four actual shots paid ammo");h.assertValueEqual(charges(t),1,"no cost per shot or Rampage kill");}catch(Exception e){throw new RuntimeException(e);}});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_surge_masked",maxTicks=270)
    public void higherTierDoesNotPauseLowerArmorChargeDecayAndExpiresIndependently(GameTestHelper h)throws Exception{
        var t=openSurge(h);try{surge(t,"solar",3);orb(t);signal(t,"four_solar",12);hit(t,"solar",12.5);
            RampageGameTest.at(t,202,()->{h.assertValueEqual(charges(t),0,"lower tier lost its last charge while masked");hit(t,"solar",12.5);});
            t.finish(242,()->{try{hit(t,"solar",10);h.assertTrue(buff(t,"armor_charge_decay").isEmpty(),"no suspended lower-tier timer revives");}catch(Exception e){throw new RuntimeException(e);}});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_surge_dilation",maxTicks=440)
    public void timeDilationAndFontsShareOneRealDecayWithoutChangingTheSurgeDamageTier(GameTestHelper h)throws Exception{
        var t=openSurge(h);try{surge(t,"solar",3);mod(t,"health_font",0);for(int i=0;i<3;i++)mod(t,"time_dilation",i);orb(t);orb(t);
            RampageGameTest.at(t,202,()->{h.assertValueEqual(charges(t),2,"twenty-second shared period");hit(t,"solar",12.2);});
            t.finish(402,()->{try{h.assertValueEqual(charges(t),1,"one shared decay despite multiple passive benefits");hit(t,"solar",12.2);near(h,stat(t,"health"),70,"Font and Surge still active on last charge");}catch(Exception e){throw new RuntimeException(e);}});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:weapon_surge_kickstart")
    public void paidKickstartConsumesThePoolButCannotRewriteAnEarlierPhysicalShot(GameTestHelper h)throws Exception{
        try(var t=openSurge(h,"kickstart","kickstart_inputs")){IncandescentGameTest.bind(t,"kick_inputs","test:kick_inputs","");StarvationGameTest.select(t,"melee","kick_melee");StarvationGameTest.fill(t,"kick_melee");mod(t,"melee_kickstart",0);surge(t,"solar",3);orb(t);arm(t,"solar","none");var victim=t.cow(2.5,46,3.5);var shot=PugilistGameTest.fire(t);
            h.assertValueEqual(t.runtime.useAbility(t.owner,D+"melee").outcome(),AbilityUse.Outcome.ACCEPTED,"actual paid ability");ShieldThrowGameTest.settled(t);h.assertValueEqual(charges(t),0,"Kickstart consumed shared charge");near(h,StarvationGameTest.account(t,"kick_melee"),1+.162*.5,"independent Kickstart refund");for(int i=0;i<3;i++)unmod(t,"solar_weapon_surge",i);arm(t,"void","none");impact(t,shot);near(h,victim.getHealth(),87.8,"in-flight shot retains its captured tier after payment and unequip");victim.discard();hit(t,"solar",10);
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_surge_unbind")
    public void removingCopiesChangesOnlyFutureDamageAndKeepsOtherPassiveBenefitsAndTheirDeadline(GameTestHelper h)throws Exception{
        try(var t=openSurge(h)){surge(t,"solar",3);mod(t,"health_font",0);orb(t);orb(t);long deadline=buff(t,"armor_charge_decay").orElseThrow().deadline();unmod(t,"solar_weapon_surge",2);hit(t,"solar",11.7);unmod(t,"solar_weapon_surge",1);hit(t,"solar",11);unmod(t,"solar_weapon_surge",0);hit(t,"solar",10);h.assertValueEqual(charges(t),2,"Font keeps the pool enabled");h.assertValueEqual(buff(t,"armor_charge_decay").orElseThrow().deadline(),deadline,"same passive schedule");near(h,stat(t,"health"),70,"unrelated Font remains");}h.succeed();
    }
    @GameCase(environment="chorus_gametest:weapon_surge_unknown")
    public void unknownPhysicalImpactRetainsBoostedDamageAndSpentAmmoWithoutReplayingTheShot(GameTestHelper h)throws Exception{
        try(var t=openSurge(h)){surge(t,"solar",3);orb(t);String weapon=arm(t,"solar","none");var victim=t.cow(2.5,46,3.5);t.failAfterDamage=true;var shot=PugilistGameTest.fire(t);for(int i=0;i<30&&!shot.isRemoved();i++)shot.tick();h.assertTrue(t.runtime.failure().isPresent(),"unknown world receipt stops runtime");near(h,victim.getHealth(),87.8,"actual boosted damage already applied once");h.assertValueEqual(state(t).ammunition().get(weapon).magazine(),4,"accepted ammo cost kept");h.assertValueEqual(charges(t),1,"Surge has no per-shot charge cost");int operations=t.operations.size();shot.tick();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"no replay of unknown impact");h.assertValueEqual(t.hits.size(),1,"one actual damage operation");}h.succeed();
    }
}
