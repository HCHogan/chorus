package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;

/** Selected-ability capacity routing with synthetic skills and real equipment, costs and native observations. */
public class AbilityCapacityGameTest {
    static BleakWatcherGameTest.Harness open(GameTestHelper h) {
        var p=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("ability_capacity")).getOrThrow();
        var t=new BleakWatcherGameTest.Harness(h,p);t.owner.setHealth(10);t.owner.getFoodData().setFoodLevel(0);String owner=BleakWatcherGameTest.id(t.owner);
        t.runtime.bind(new EffectSource("input","test:capacity_inputs",owner,new BuffInstance.Origin(owner,"input","",""),Set.of()));select(t,"linked");return t;
    }
    static ResourceState account(BleakWatcherGameTest.Harness t,String name){return t.state().resources().get(new ResourceState.Key(BleakWatcherGameTest.id(t.owner),"test:capacity_"+name));}
    static void select(BleakWatcherGameTest.Harness t,String name){String owner=BleakWatcherGameTest.id(t.owner);t.runtime.abilities(new AbilityChange(owner,t.state().abilities().getOrDefault(owner,AbilityLoadout.EMPTY),name.isEmpty()?AbilityLoadout.EMPTY:new AbilityLoadout(Map.of("chorus_d2:melee","test:capacity_"+name))));}
    static void equip(BleakWatcherGameTest.Harness t,String instance) {
        var e=PlayerEquipment.get(t.owner);var stack=instance.isEmpty()?ItemStack.EMPTY:new ItemStack(Items.STRING);int slot=instance.isEmpty()?1:0;
        if(!stack.isEmpty())stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(instance,"test:capacity_armor",Map.of()));
        t.owner.getInventory().setItem(slot,stack);e.swap(t.owner,"test:armor",slot,e.revision());
    }
    @GameCase public void physicalEquipmentResizesTheCurrentSkillAndPreservesOtherAccountsAndMeters(GameTestHelper h) {
        try(var t=open(h)) {
            equip(t,"first");near(h,account(t,"uses").capacity(),3,"selected usable capacity");near(h,account(t,"uses").value(),1,"equipment granted energy");near(h,account(t,"meter").capacity(),1,"equipment resized progress instead");near(h,account(t,"meter").value(),.4,"equipment reset progress");
            LinkedRechargeGameTest.send(t,"grant",.6);near(h,account(t,"uses").value(),3,"cycle ignored new capacity");equip(t,"replacement");near(h,account(t,"uses").value(),3,"replacement transiently clipped uses");near(h,t.owner.getHealth(),11,"replacement repeated observation");
            h.assertValueEqual(t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get()).instance(),"first","replaced physical item not returned");
            select(t,"legacy");near(h,account(t,"legacy").capacity(),3,"current legacy account did not resize");near(h,account(t,"uses").value(),3,"old account was migrated or reset");
            LinkedRechargeGameTest.use(h,t,AbilityUse.Outcome.ACCEPTED);LinkedRechargeGameTest.use(h,t,AbilityUse.Outcome.INSUFFICIENT_ENERGY);LinkedRechargeGameTest.send(t,"grant",3);
            equip(t,"");near(h,account(t,"legacy").capacity(),2,"removal did not recompute current account");near(h,account(t,"legacy").value(),2,"removal failed to clip overflow");near(h,account(t,"uses").capacity(),3,"API silently changed an unselected account");
            select(t,"linked");near(h,account(t,"uses").capacity(),2,"selection rule did not recompute retained capacity");near(h,account(t,"uses").value(),2,"reselection restored overflow");near(h,t.owner.getHealth(),15,"real casts or resize observations missing");t.healthy();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:ability_capacity_ticks",maxTicks=40)
    public void realRechargeUsesTheExpandedCeilingWithoutChangingItsRateOrProgress(GameTestHelper h) {
        var t=open(h);try {
            LinkedRechargeGameTest.use(h,t,AbilityUse.Outcome.ACCEPTED);long start=t.runtime.nowMicros();
            t.at(8,()->{double progress=account(t,"meter").value();equip(t,"first");near(h,account(t,"meter").value(),progress,"expansion reset elapsed recharge");near(h,account(t,"uses").value(),0,"expansion generated a use");});
            t.at(18,()->{near(h,account(t,"meter").value(),.4+(t.runtime.nowMicros()-start)/2_000_000.0,"expanded capacity multiplied recharge rate");LinkedRechargeGameTest.use(h,t,AbilityUse.Outcome.INSUFFICIENT_ENERGY);});
            t.finish(27,()->{near(h,account(t,"uses").value(),3,"cycle did not restore expanded capacity");near(h,account(t,"meter").value(),0,"completed progress not consumed");for(int i=0;i<3;i++)LinkedRechargeGameTest.use(h,t,AbilityUse.Outcome.ACCEPTED);LinkedRechargeGameTest.use(h,t,AbilityUse.Outcome.INSUFFICIENT_ENERGY);near(h,t.owner.getHealth(),15,"expanded uses did not execute three actual ability bodies");});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:ability_capacity_later",maxTicks=25)
    public void aDelayedResizeTargetsTheSelectionAtExecutionAndSkipsEmptyOrFixedSkills(GameTestHelper h) {
        var t=open(h);try {
            for(String kind:List.of("","free","fixed")){select(t,kind);LinkedRechargeGameTest.send(t,"resize",4);t.healthy();}
            near(h,account(t,"fixed").capacity(),1,"fixed capacity changed");near(h,t.owner.getHealth(),10,"unavailable target emitted a resize observation");
            select(t,"legacy");LinkedRechargeGameTest.send(t,"later",4);t.at(1,()->select(t,"linked"));
            t.finish(4,()->{near(h,account(t,"uses").capacity(),4,"delayed action retained old selection");near(h,account(t,"legacy").capacity(),2,"delayed action resized old legacy account");near(h,account(t,"meter").capacity(),1,"delayed action resized recharge meter");near(h,t.owner.getHealth(),11,"delayed actual observation missing");});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase public void unknownNativeObserverPreservesTheEquipmentCapacityAndAppliedHealth(GameTestHelper h) {
        try(var t=open(h)) {
            t.failGainHealing=true;try{equip(t,"first");}catch(IllegalStateException expected){/* equipment and selected account already committed */}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown capacity observation did not stop runtime");near(h,account(t,"uses").capacity(),3,"committed capacity rolled back");near(h,account(t,"uses").value(),1,"failed observation granted or removed energy");near(h,account(t,"meter").value(),.4,"failed observation changed meter");
            h.assertValueEqual(PlayerEquipment.get(t.owner).snapshot().items().get("test:armor").get(ChorusComponents.EQUIPMENT.get()).instance(),"first","physical equipment rolled back");near(h,t.owner.getHealth(),11,"actual healing lost");t.runtime.prepare();near(h,t.owner.getHealth(),11,"unknown observation replayed");
        }h.succeed();
    }
}
