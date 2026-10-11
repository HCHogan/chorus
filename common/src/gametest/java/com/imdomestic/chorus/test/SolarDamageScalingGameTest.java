package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.IncandescentGameTest.*;
import static com.imdomestic.chorus.test.ArmorChargeGameTest.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Real Orb charge, weapon kills, shared Solar generations and native damage receipts. */
public class SolarDamageScalingGameTest {
    static ProjectileGameTest.Harness openSolar(GameTestHelper h)throws Exception {return openSolar(h,_ -> {});}
    static ProjectileGameTest.Harness openSolar(GameTestHelper h,java.util.function.Consumer<com.google.gson.JsonObject> edit)throws Exception {
        var t=IncandescentGameTest.harness(h,true,true,data->{
            RadiantGameTest.merge(data,"weapon_surge","radiant","weapon_surge_inputs","solar_test_source","solar_damage_perks",
                    "armor_charge","armor_charge_mods","armor_charge_inputs","orb_of_power");
            // Reuse the explicit Orb calibration without importing unrelated subclass/test sources.
            for(var profile:ThreadedSpikeGameTest.json("starvation_inputs").getAsJsonArray("profiles"))
                if(profile.getAsJsonObject().get("id").getAsString().equals("chorus_d2:orb_of_power_base_energy"))data.getAsJsonArray("profiles").add(profile);
            edit.accept(data);
        });
        try {
            t.runtime.bind(system(t.owner.getUUID().toString()));bind(t,"armor_inputs","test:armor_charge_inputs","");
            bind(t,"surge_inputs","test:surge_inputs","");WeaponSurgeGameTest.surge(t,"solar",3);orb(t);return t;
        }catch(Exception|Error e){t.close();throw e;}
    }
    static void apply(ProjectileGameTest.Harness t,LivingEntity victim,int count,String credit,String weapon) {
        String owner=t.owner.getUUID().toString();var origin=new BuffInstance.Origin(owner,"seed",weapon,"test:seed",Set.of("chorus:"+credit+"_damage"));
        t.runtime.bind(new EffectSource("seed","test:scorch_source",owner,origin,Set.of()));
        t.runtime.start(new RuleEngine.Signal("test:scorch_apply",new EffectEvent(owner,victim.getUUID().toString(),origin,Set.of(),Map.of("stacks",new Measure(count,Unit.COUNT)))));
        ShieldThrowGameTest.settled(t);
    }
    @GameCase(environment="chorus_gametest:solar_damage_ignition")
    public void physicalIncandescentKillsIgniteWithSurgeAndEachVictimsRadiantClassification(GameTestHelper h)throws Exception {
        for(boolean champion:List.of(false,true))try(var t=openSolar(h)) {
            WeaponSurgeGameTest.signal(t,"radiant",10);var victim=cow(t,2,500);if(champion)victim.addTag("chorus_d2:champion");
            kill(t,"primary",false);var origin=scorch(t,victim).orElseThrow().origin();kill(t,"secondary",true);
            var ignition=hits(t,"ignition");h.assertValueEqual(ignition.size(),1,"one native ignition");
            near(h,victim.getHealth(),500-4.8-67.6*1.22*(champion?1.3:1.2),"burst payloads plus shared ignition scaling");
            h.assertValueEqual(ignition.getFirst().source(),origin,"first weapon owns the ignition");
            h.assertValueEqual(victim.getLastDamageSource().getEntity(),t.owner,"native attacker retained");
            h.assertValueEqual(charges(t),1,"Solar hits do not spend Armor Charge");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:solar_damage_snapshot",maxTicks=30)
    public void realScorchRetainsCapturedSurgeAndRadiantAfterChargeGearAndSourcesAreRemoved(GameTestHelper h)throws Exception {
        var t=openSolar(h);try {
            WeaponSurgeGameTest.signal(t,"radiant",.1);var victim=cow(t,2,500);kill(t,"secondary",true);
            near(h,victim.getHealth(),497.6,"independent Incandescent burst");spend(t,1);
            for(int i=0;i<3;i++)unmod(t,"solar_weapon_surge",i);
            var gear=PlayerEquipment.get(t.owner);t.owner.getInventory().setItem(0,ItemStack.EMPTY);gear.swap(t.owner,"test:secondary",0,gear.revision());t.runtime.unbind("solar");
            t.finish(11,()-> {
                near(h,victim.getHealth(),497.6-(2.7+.175*60)*1.025*.1*1.22*1.2,"actual captured weapon Scorch after teardown");
                h.assertTrue(buff(t,"radiant").isEmpty(),"Radiant must really expire");h.assertValueEqual(charges(t),0,"charge was actually spent");
                h.assertValueEqual(hits(t,"scorch").size(),1,"one native tick");
                h.assertValueEqual(hits(t,"scorch").getFirst().source().weapon(),"b","original removed weapon retained");
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:solar_damage_perk",maxTicks=30)
    public void actualRampageKillOnlyScalesSolarSnapshotsCreditedToThatWeapon(GameTestHelper h)throws Exception {
        var t=openSolar(h);try {
            WeaponSurgeGameTest.signal(t,"radiant",10);bind(t,"rampage","chorus_d2:rampage","a");kill(t,"primary",false);
            h.assertTrue(state(t).buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("chorus_d2:rampage")&&b.count()==1),"native kill must activate Rampage");
            var same=cow(t,2,500);var other=cow(t,3,500);apply(t,same,60,"weapon","a");apply(t,other,60,"weapon","b");
            t.finish(11,()-> {
                near(h,same.getHealth(),500-1.6236*1.22*1.2*1.1,"matching weapon perk plus shared buffs");
                near(h,other.getHealth(),500-1.6236*1.22*1.2,"other weapon cannot borrow Rampage");
                h.assertValueEqual(hits(t,"scorch").size(),2,"two separate target ticks");
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:solar_damage_ability")
    public void grenadeMeleeAndSuperIgnitionsDoNotReceiveTheEquippedWeaponBuffs(GameTestHelper h)throws Exception {
        for(String credit:List.of("grenade","melee","super"))try(var t=openSolar(h)) {
            WeaponSurgeGameTest.signal(t,"radiant",10);var victim=cow(t,2,500);apply(t,victim,100,credit,"a");
            near(h,victim.getHealth(),432.4,"ability-origin ignition excludes weapon Surge and Radiant");
            h.assertTrue(hits(t,"ignition").getFirst().killTags().contains("chorus:ability_kill"),"ability attribution retained");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:solar_damage_unknown")
    public void unknownScaledIgnitionKeepsCommittedHealthChargeAndLockoutWithoutReplay(GameTestHelper h)throws Exception {
        try(var t=openSolar(h)) {
            WeaponSurgeGameTest.signal(t,"radiant",10);var victim=cow(t,2,500);t.failAfterDamage=true;boolean failed=false;
            try{apply(t,victim,100,"weapon","a");}catch(IllegalStateException expected){failed=true;}
            h.assertTrue(failed&&t.runtime.failure().isPresent(),"unknown ignition must stop");
            near(h,victim.getHealth(),500-67.6*1.22*1.2,"scaled ignition already committed");h.assertValueEqual(charges(t),1,"charge is not a per-hit cost");
            h.assertTrue(scorch(t,victim).isEmpty()&&buff(t,"scorch_lockout",victim.getUUID().toString()).isPresent(),"threshold and lockout already committed");
            t.failAfterDamage=false;t.runtime.prepare();h.assertValueEqual(hits(t,"ignition").size(),1,"unknown ignition repeated");
        }h.succeed();
    }
}
