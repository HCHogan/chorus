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
import net.minecraft.world.item.*;

public class SpiritArmamentariumGameTest {
    static final String SLOT="chorus_d2:grenade",GEAR="chorus_d2:class_item";
    static CompiledEffects program() {
        var parts=new ArrayList<EffectProgram>();parts.add(BleakWatcherGameTest.conversionProgram().program());
        for(String name:List.of("arcbolt_energy","spirit_armamentarium","spirit_armamentarium_inputs"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json(name)).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static String owner(BleakWatcherGameTest.Harness t){return BleakWatcherGameTest.id(t.owner);}
    static ResourceState account(BleakWatcherGameTest.Harness t){return t.state().resources().get(new ResourceState.Key(owner(t),"chorus_d2:grenade_energy"));}
    static EffectSource source(BleakWatcherGameTest.Harness t,String instance,String bundle){return new EffectSource(instance,bundle,owner(t),new BuffInstance.Origin(owner(t),instance,"",""),Set.of());}
    static void observe(BleakWatcherGameTest.Harness t){t.owner.setHealth(100);t.runtime.bind(source(t,"input","test:spirit_inputs"));}
    static void grant(BleakWatcherGameTest.Harness t,double amount){t.runtime.start(new RuleEngine.Signal("test:grant",new EffectEvent(owner(t),owner(t),new BuffInstance.Origin(owner(t),"input","",""),Set.of(),Map.of("amount",new Measure(amount,Unit.CHARGE)))));}
    static void equip(BleakWatcherGameTest.Harness t,String instance) {
        var e=PlayerEquipment.get(t.owner);var stack=instance.isEmpty()?ItemStack.EMPTY:new ItemStack(Items.STRING);
        if(!stack.isEmpty())stack.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(instance,"test:spirit_class_item",Map.of()));
        int inventorySlot=instance.isEmpty()?1:0;t.owner.getInventory().setItem(inventorySlot,stack);e.swap(t.owner,GEAR,inventorySlot,e.revision());
    }
    @GameCase public void physicalClassItemBeforeSelectionAllowsTwoActualGrenadeThrowsAfterCharging(GameTestHelper h) {
        try(var t=new BleakWatcherGameTest.Harness(h,program())){
            observe(t);equip(t,"first");near(h,account(t).capacity(),2,"physical class item capacity");near(h,account(t).value(),1,"equipping generated a charge");near(h,t.owner.getHealth(),102,"capacity fact did not reach native healing");
            BleakWatcherGameTest.choose(t,"chorus_d2:duskfield");grant(t,1);
            for(int i=0;i<2;i++)h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"charged actual throw");
            near(h,account(t).value(),0,"two throws did not consume two units");h.assertValueEqual(t.launches.size(),2,"two physical projectiles missing");
            h.assertValueEqual(t.runtime.useAbility(t.owner,SLOT).outcome(),AbilityUse.Outcome.INSUFFICIENT_ENERGY,"third throw bypassed capacity");h.assertValueEqual(t.launches.size(),2,"rejected third projectile spawned");t.healthy();
        }h.succeed();
    }
    @GameCase public void physicalReplacementKeepsEnergyAndUnequippingRetainsOnlyIndependentCapacity(GameTestHelper h) {
        try(var t=new BleakWatcherGameTest.Harness(h,program())){
            observe(t);BleakWatcherGameTest.choose(t,"chorus_d2:duskfield");equip(t,"first");grant(t,.7);equip(t,"second");
            near(h,account(t).value(),1.7,"atomic physical replacement clipped energy");near(h,t.owner.getHealth(),102,"equivalent replacement published another capacity change");
            var old=t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get());h.assertValueEqual(old.instance(),"first","old item not returned to actual inventory");
            t.runtime.bind(source(t,"aspect","test:additional_charge"));near(h,account(t).capacity(),3,"independent third charge");grant(t,1.3);
            BleakWatcherGameTest.choose(t,"");equip(t,"");near(h,account(t).capacity(),2,"removing Spirit removed another capacity source");near(h,account(t).value(),2,"removed third charge stayed banked");
            t.runtime.unbind("aspect");near(h,account(t).capacity(),1,"last capacity source remained after empty-slot removal");near(h,account(t).value(),1,"second charge stayed banked");
            h.assertTrue(PlayerEquipment.get(t.owner).snapshot().items().isEmpty(),"physical class item still equipped");t.healthy();
        }h.succeed();
    }
    @GameCase public void unknownCapacityObserverKeepsActualEquipmentAndHealingWithoutReplay(GameTestHelper h) {
        try(var t=new BleakWatcherGameTest.Harness(h,program())){
            observe(t);BleakWatcherGameTest.choose(t,"chorus_d2:duskfield");t.failGainHealing=true;
            try{equip(t,"first");}catch(IllegalStateException expected){/* physical transfer, source and capacity remain committed */}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown capacity observer did not fail runtime");near(h,account(t).capacity(),2,"capacity rolled back");near(h,account(t).value(),1,"unknown observer changed balance");near(h,t.owner.getHealth(),102,"actual capacity observation lost");
            h.assertValueEqual(PlayerEquipment.get(t.owner).snapshot().items().get(GEAR).get(ChorusComponents.EQUIPMENT.get()).instance(),"first","committed physical transfer lost");
            h.assertValueEqual(t.state().equipment().get(owner(t)).slots().get(GEAR).instance(),"first","committed source projection lost");t.runtime.prepare();near(h,t.owner.getHealth(),102,"unknown observer replayed");
        }h.succeed();
    }
}
