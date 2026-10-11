package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;

/** Actual equipment, damage/death receipts and server ticks; synthetic knife/dodge inputs remain explicit. */
public class OphidiaSpatheGameTest {
    static final String D="chorus_d2:", SLOT=D+"melee", SC=D+"scissor_fingers";
    static CompiledEffects program(){var parts=new ArrayList<EffectProgram>();for(String f:List.of("character_stats","combat_damage","solar_melee_energy","ophidia_spathe","ophidia_spathe_inputs"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json(f)).getOrThrow());return CompiledEffects.link(parts);}
    static Map<String,Measure> calibration(){var json=ThreadedSpikeGameTest.json("ophidia_spathe_test_calibration").getAsJsonObject("parameters");var result=new HashMap<String,Measure>();json.entrySet().forEach(e->result.put(e.getKey(),new Measure(e.getValue().getAsJsonObject().get("value").getAsDouble(),e.getKey().equals("recent_use_seconds")?Unit.SECOND:Unit.DELTA)));return result;}
    static BleakWatcherGameTest.Harness open(GameTestHelper h){var t=new BleakWatcherGameTest.Harness(h,program());t.owner.setHealth(10);t.owner.getFoodData().setFoodLevel(0);String owner=BleakWatcherGameTest.id(t.owner);t.runtime.bind(new EffectSource("input","test:ophidia_inputs",owner,new BuffInstance.Origin(owner,"input","",""),Set.of()));select(t,"solar_knife","gamblers_dodge");return t;}
    static void select(BleakWatcherGameTest.Harness t,String melee,String dodge){String owner=BleakWatcherGameTest.id(t.owner);var map=new HashMap<String,String>();if(!melee.isEmpty())map.put(SLOT,"test:"+melee);if(!dodge.isEmpty())map.put(D+"class","test:"+dodge);t.runtime.abilities(new AbilityChange(owner,t.state().abilities().getOrDefault(owner,AbilityLoadout.EMPTY),new AbilityLoadout(map)));}
    static void equip(BleakWatcherGameTest.Harness t,String instance){var e=PlayerEquipment.get(t.owner);var stack=instance.isEmpty()?ItemStack.EMPTY:new ItemStack(Items.STRING);int slot=instance.isEmpty()?1:0;if(!stack.isEmpty())stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(instance,"test:ophidia",Map.of(),calibration()));t.owner.getInventory().setItem(slot,stack);e.swap(t.owner,"test:chest",slot,e.revision());}
    static ResourceState account(BleakWatcherGameTest.Harness t,String id){return t.state().resources().get(new ResourceState.Key(BleakWatcherGameTest.id(t.owner),D+"solar_melee_"+id));}
    static int stacks(BleakWatcherGameTest.Harness t){return t.buff(SC,t.owner).map(BuffInstance::count).orElse(0);}
    static void grant(BleakWatcherGameTest.Harness t,double n){String owner=BleakWatcherGameTest.id(t.owner);t.runtime.start(new RuleEngine.Signal("test:fixed",new EffectEvent(owner,owner,new BuffInstance.Origin(owner,"energy","",""),Set.of(),Map.of("amount",new Measure(n,Unit.CHARGE)))));}
    static void use(GameTestHelper h,BleakWatcherGameTest.Harness t,String slot){h.assertValueEqual(t.runtime.useAbility(t.owner,slot).outcome(),AbilityUse.Outcome.ACCEPTED,"synthetic producer rejected");}
    static void hit(BleakWatcherGameTest.Harness t,LivingEntity target,String kind){String owner=BleakWatcherGameTest.id(t.owner);t.runtime.start(new RuleEngine.Signal("test:"+kind,new EffectEvent(owner,BleakWatcherGameTest.id(target),new BuffInstance.Origin(owner,"knife","","test:solar_knife"),Set.of(),Map.of())));}
    static void kill(GameTestHelper h,BleakWatcherGameTest.Harness t){var target=t.mob(4);target.setHealth(5);hit(t,target,"knife");h.assertTrue(target.isDeadOrDying(),"actual knife-credit damage did not kill");}
    @GameCase public void equipmentReplacementAndSolarSelectionRecomputeCapacityWithoutFreeCharges(GameTestHelper h){
        try(var t=open(h)){
            near(h,account(t,"uses").capacity(),1,"ordinary Solar capacity");equip(t,"first");near(h,account(t,"uses").capacity(),2,"Ophidia capacity");near(h,account(t,"uses").value(),1,"equip granted a charge");grant(t,1);near(h,account(t,"uses").value(),2,"linked restoration");equip(t,"second");near(h,account(t,"uses").value(),2,"replacement clipped balance");
            h.assertValueEqual(t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get()).instance(),"first","old physical item not returned");select(t,"solar_two_knives","gamblers_dodge");near(h,account(t,"uses").capacity(),2,"innate two charges stacked with exotic");
            select(t,"non_solar_melee","gamblers_dodge");near(h,t.state().resources().get(new ResourceState.Key(BleakWatcherGameTest.id(t.owner),"test:non_solar_uses")).capacity(),1,"non-Solar capacity changed");select(t,"solar_knife","gamblers_dodge");equip(t,"");near(h,account(t,"uses").value(),1,"unequip failed to clip");t.healthy();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ophidia_recharge",maxTicks=40)
    public void recentUseExceptionAndNaturalLinkedRecoveryRunOnRealServerTicks(GameTestHelper h){
        var t=open(h);try{
            equip(t,"exotic");grant(t,1);use(h,t,SLOT);use(h,t,SLOT);use(h,t,D+"class");near(h,account(t,"uses").value(),1,"recent dodge incorrectly restored both");use(h,t,SLOT);long start=t.runtime.nowMicros();
            t.at(6,()->{near(h,account(t,"uses").value(),0,"partial progress became usable");near(h,account(t,"progress").value(),(t.runtime.nowMicros()-start)/1_000_000.0,"capacity multiplied recharge rate");});
            t.finish(25,()->{near(h,account(t,"uses").value(),2,"natural cycle failed to refill both");near(h,account(t,"progress").value(),0,"full state retained meter energy");near(h,t.owner.getHealth(),13,"actual paid ability bodies missing");});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase public void confirmedDeathsStackKnifeDamageAndProtectedDeathsDoNot(GameTestHelper h){
        try(var t=open(h)){
            equip(t,"exotic");var protectedTarget=t.mob(5);protectedTarget.setHealth(5);protectedTarget.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));hit(t,protectedTarget,"knife");h.assertTrue(protectedTarget.isAlive(),"death protection failed");h.assertValueEqual(stacks(t),0,"protected death granted stack");
            for(int i=1;i<=3;i++){kill(h,t);h.assertValueEqual(stacks(t),i,"confirmed kill count");var probe=t.mob(6);hit(t,probe,"knife");near(h,probe.getHealth(),1000-new double[]{0,16.7,23.3,30}[i],"real stack damage");}
            for(String kind:List.of("melee","scorch","ignition")){var probe=t.mob(7);hit(t,probe,kind);near(h,probe.getHealth(),990,"non-knife inherited exotic bonus");}
            equip(t,"replacement");h.assertValueEqual(stacks(t),3,"equivalent replacement removed stacks");equip(t,"");h.assertValueEqual(stacks(t),0,"final removal retained stacks");var probe=t.mob(8);hit(t,probe,"knife");near(h,probe.getHealth(),990,"unequipped exotic still modifies damage");t.healthy();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ophidia_refresh",maxTicks=160)
    public void gamblerDodgeRefreshesTheExistingBuffWithoutAddingStacks(GameTestHelper h){
        var t=open(h);try{
            equip(t,"exotic");use(h,t,D+"class");h.assertValueEqual(stacks(t),0,"dodge created a new damage buff");kill(h,t);
            t.at(20,()->{use(h,t,D+"class");h.assertValueEqual(stacks(t),1,"dodge added stack");h.assertValueEqual(t.buff(SC,t.owner).orElseThrow().deadline(),t.runtime.nowMicros()+5_000_000,"dodge did not reset duration");});
            t.at(105,()->{h.assertValueEqual(stacks(t),1,"buff expired at original deadline");select(t,"solar_knife","other_dodge");use(h,t,D+"class");});
            t.finish(130,()->{h.assertValueEqual(stacks(t),0,"other dodge refreshed buff");select(t,"solar_knife","gamblers_dodge");use(h,t,D+"class");h.assertValueEqual(stacks(t),0,"dodge revived expired buff");});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase public void knifeBonusAddsToExistingMeleeFamilyOnActualDamage(GameTestHelper h){
        try(var t=open(h)){
            equip(t,"exotic");for(int i=0;i<3;i++)kill(h,t);String owner=BleakWatcherGameTest.id(t.owner);t.runtime.bind(new EffectSource("bonus","test:melee_bonus",owner,new BuffInstance.Origin(owner,"bonus","",""),Set.of()));t.runtime.bind(new EffectSource("duplicate",D+"ophidia_spathe",owner,new BuffInstance.Origin(owner,"duplicate","",""),Set.of(D+"ophidia_spathe"),calibration()));
            var probe=t.mob(6);hit(t,probe,"knife");near(h,probe.getHealth(),955,"expected 10*(1+2+1.5), duplicate exotic must not contribute twice");t.healthy();
        }h.succeed();
    }
    @GameCase public void unknownWorldBodyRetainsPaidChargeAndPhysicalHealingWithoutReplay(GameTestHelper h){
        try(var t=open(h)){
            equip(t,"exotic");t.failGainHealing=true;try{t.runtime.useAbility(t.owner,SLOT);}catch(IllegalStateException expected){/* world result remains unknown */}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown world result did not stop runtime");near(h,account(t,"uses").value(),0,"paid use refunded");near(h,t.owner.getHealth(),11,"actual healing lost");t.runtime.prepare();near(h,t.owner.getHealth(),11,"unknown body replayed");
        }h.succeed();
    }
}
