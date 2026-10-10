package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.effect.data.NumericQuery;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class SolarFragmentStatsGameTest {
    static double chunk(double stat){return 1.625-.625*StrictMath.cos(StrictMath.PI*stat/100);}
    static double passive(double stat){return stat>=70?2.10898698+.00639461*stat:1+.004273626*stat+.000300195*stat*stat-6.37618e-7*stat*stat*stat;}
    static void solar(JsonObject data){
        for(String name:List.of("solar","solar_test_calibration","ember_of_char","ember_of_eruption"))
            for(var field:ThreadedSpikeGameTest.json(name).entrySet())if(field.getValue().isJsonArray()){
                if(!data.has(field.getKey()))data.add(field.getKey(),new JsonArray());field.getValue().getAsJsonArray().forEach(v->data.getAsJsonArray(field.getKey()).add(v));
            }
    }
    static void armor(JsonObject data) {
        ThreadedSpikeGameTest.json("armor_stats").getAsJsonArray("bundles").forEach(v -> data.getAsJsonArray("bundles").add(v));
        var schema = ThreadedSpikeGameTest.json("armor_stat_inputs").getAsJsonObject("equipment");
        if (!data.has("equipment")) data.add("equipment", new JsonObject());
        schema.entrySet().forEach(entry -> {
            var equipment = data.getAsJsonObject("equipment"); if (!equipment.has(entry.getKey())) equipment.add(entry.getKey(), new JsonArray());
            entry.getValue().getAsJsonArray().forEach(v -> equipment.getAsJsonArray(entry.getKey()).add(v));
        });
    }
    static ItemStack armorItem(String instance, String slot, double points) {
        var values = new TreeMap<String, Measure>();
        for (String stat : List.of("health", "grenade", "melee", "class", "super", "weapons")) values.put(stat, new Measure(points, Unit.STAT_POINT));
        var stack = new ItemStack(Items.DIAMOND_CHESTPLATE); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(instance, "test:armor_" + slot, Map.of(), values)); return stack;
    }
    static void equipArmor(ProjectileGameTest.Harness t, String instance, String slot, double points) {
        t.owner.getInventory().setItem(0, armorItem(instance, slot, points)); var equipment = PlayerEquipment.get(t.owner); equipment.swap(t.owner, "chorus_d2:" + slot, 0, equipment.revision());
    }
    static double points(ProjectileGameTest.Harness t, String stat) {
        return t.runtime.program().attribute(t.runtime.state().engine().domain(), FirespriteGameTest.id(t.owner), "chorus_d2:" + stat + "_stat", new Measure(0, Unit.STAT_POINT), NumericQuery.Path.empty()).output().value();
    }
    @GameCase public void physicalArmorSwapChangesThreadedSpikesNextContactWithoutRecasting(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "threaded_spike", data -> {
            ThreadedSpikeGameTest.prepare(data); armor(data);
            ThreadedSpikeGameTest.json("ability_stat_damage").getAsJsonArray("bundles").forEach(v -> data.getAsJsonArray("bundles").add(v));
        }, true)) {
            var first = ThreadedSpikeGameTest.target(t, 2.5, 44, 3.5, 1000);
            var second = ThreadedSpikeGameTest.target(t, 2.5, 54, 3.5, 1000);
            equipArmor(t, "high", "arms", 200); IncandescentGameTest.bind(t, "scaling", "chorus_d2:ability_stat_damage", "");
            ThreadedSpikeGameTest.cast(t); var projectile = t.projectiles.getFirst();
            for (int i = 0; i < 20 && t.hits.isEmpty(); i++) projectile.tick();
            h.assertValueEqual(t.hits.size(), 1, "first real projectile contact");
            near(h, 1000 - first.getHealth(), 427 * 1.3, "200 melee stat enhances first hit");
            equipArmor(t, "low", "arms", 100);
            for (int i = 0; i < 20 && t.hits.size() < 2; i++) projectile.tick();
            h.assertValueEqual(t.hits.size(), 2, "same projectile reaches second target");
            near(h, 1000 - second.getHealth(), 427 * .82, "current armor controls second hit while base decay stays cast-bound");
            h.assertTrue(t.hits.stream().allMatch(d -> d.snapshot().isEmpty()), "Threaded Spike uses its explicit direct-contact policy");
            h.assertTrue(t.runtime.failure().isEmpty(), "physical armor damage failed");
        }
        h.succeed();
    }
    @GameCase public void actualArmorComponentsAndCharScalePhysicalPickupWithoutCreatingStatBuffs(GameTestHelper h) throws Exception {
        try (var t = FirespriteGameTest.harness(h, data -> { solar(data); armor(data); })) {
            equipArmor(t, "helmet", "helmet", 50); equipArmor(t, "arms", "arms", 20); IncandescentGameTest.bind(t, "char", "chorus_d2:ember_of_char", "");
            near(h, points(t, "grenade"), 80, "two physical armor rolls plus Char");
            h.assertTrue(FirespriteGameTest.buff(t, t.owner, "chorus_d2:grenade_stat").isEmpty(), "armor created a hidden stat input");
            FirespriteGameTest.pair(t); var equipment = PlayerEquipment.get(t.owner);
            equipment.swap(t.owner, "chorus_d2:helmet", 0, equipment.revision()); near(h, points(t, "grenade"), 30, "unequipped helmet no longer contributes");
            var removed = t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get());
            h.assertValueEqual(removed.parameters().get("grenade"), new Measure(50, Unit.STAT_POINT), "raw item roll survived removal");
            double before = FirespriteGameTest.energy(t); var pickup = t.pickups.getFirst(); t.owner.setPos(pickup.position()); pickup.tick();
            near(h, FirespriteGameTest.energy(t) - before, .05 * .75 * chunk(30), "collection reads current physical armor plus fragment");
            h.assertTrue(pickup.isRemoved() && t.runtime.failure().isEmpty(), "physical armor pickup failed");
            h.assertTrue(FirespriteGameTest.buff(t, t.owner, "chorus_d2:grenade_stat").isEmpty(), "pickup required stat Buff initialization");
        }
        h.succeed();
    }
    @GameCase(environment="chorus_gametest:armor_energy_change", maxTicks=45)
    public void actualArmorSwapSplitsSelectedMeleeRegenerationAndChangesTheNextGrant(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "threaded_spike", data -> { EnergyGainGameTest.prepare(data); solar(data); armor(data); }, true);
        try {
            EnergyGainGameTest.start(t); equipArmor(t, "old-arms", "arms", 50); IncandescentGameTest.bind(t, "eruption", "chorus_d2:ember_of_eruption", "");
            long start = t.runtime.nowMicros(); long[] changed = {-1};
            h.runAfterDelay(10, () -> { try { equipArmor(t, "new-arms", "arms", 60); changed[0] = t.runtime.nowMicros(); } catch (Exception | Error e) { t.close(); throw e; } });
            t.finish(30, () -> {
                long end = t.runtime.nowMicros(); h.assertTrue(changed[0] > start && changed[0] < end, "physical swap occurred between real ticks");
                near(h, ThreadedSpikeGameTest.energy(t), ((changed[0]-start)*passive(60)+(end-changed[0])*passive(70))/1_000_000/145.2, "physical armor splits selected passive rate");
                near(h, points(t, "melee"), 70, "new armor and Eruption sum");
                h.assertTrue(FirespriteGameTest.buff(t, t.owner, "chorus_d2:melee_stat").isEmpty(), "physical armor depended on base stat Buff");
                double before = ThreadedSpikeGameTest.energy(t); EnergyGainGameTest.send(t, "base", "amount", .04, Unit.CHARGE);
                near(h, ThreadedSpikeGameTest.energy(t)-before, .04*.8*chunk(70), "next actual grant uses new armor");
                var old = t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get());
                h.assertValueEqual(old.instance(), "old-arms", "swap returned original item"); h.assertValueEqual(old.parameters().get("melee"), new Measure(50, Unit.STAT_POINT), "swap preserved old raw value");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void physicalFirespriteUsesCollectorsCurrentCharBonusAndKeepsBasePoints(GameTestHelper h)throws Exception {
        for(boolean equippedAtCollection:List.of(false,true))try(var t=FirespriteGameTest.harness(h,SolarFragmentStatsGameTest::solar)){
            DemolitionistGameTest.input(t,"stat",FirespriteGameTest.id(t.owner),"stat",50,Unit.STAT_POINT);
            if(!equippedAtCollection)IncandescentGameTest.bind(t,"char","chorus_d2:ember_of_char","");FirespriteGameTest.pair(t);
            if(equippedAtCollection)IncandescentGameTest.bind(t,"char","chorus_d2:ember_of_char","");else t.runtime.unbind("char");
            double before=FirespriteGameTest.energy(t);var pickup=t.pickups.getFirst();t.owner.setPos(pickup.position());pickup.tick();
            near(h,FirespriteGameTest.energy(t)-before,.05*.75*chunk(equippedAtCollection?60:50),"current Char stat before grenade gain curve and CES");
            h.assertTrue(pickup.isRemoved(),"physical reward consumed");
            near(h,FirespriteGameTest.buff(t,t.owner,"chorus_d2:grenade_stat").orElseThrow().components().numbers().get("points"),50,"base points never rewritten");
            h.assertTrue(t.runtime.failure().isEmpty(),"Char stat pickup failed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:solar_stat_change",maxTicks=45)
    public void realTickEruptionChangesPassiveAndChunkReturnsAtCurrentMeleeStat(GameTestHelper h)throws Exception {
        var t=new ProjectileGameTest.Harness(h,"threaded_spike",data->{EnergyGainGameTest.prepare(data);solar(data);},true);
        try{
            EnergyGainGameTest.start(t);EnergyGainGameTest.stat(t,60);IncandescentGameTest.bind(t,"eruption","chorus_d2:ember_of_eruption","");
            long start=t.runtime.state().engine().domain().buffs().timeMicros();long[] changed={-1};
            h.runAfterDelay(10,()->{try{t.runtime.unbind("eruption");changed[0]=t.runtime.state().engine().domain().buffs().timeMicros();}catch(Exception|Error e){t.close();throw e;}});
            t.finish(30,()->{
                long end=t.runtime.state().engine().domain().buffs().timeMicros();
                h.assertTrue(changed[0]>start&&changed[0]<end,"fragment removed between real ticks");
                near(h,ThreadedSpikeGameTest.energy(t),((changed[0]-start)*passive(70)+(end-changed[0])*passive(60))/1_000_000/145.2,"fragment change splits old/new passive rates");
                double before=ThreadedSpikeGameTest.energy(t);EnergyGainGameTest.send(t,"base","amount",.04,Unit.CHARGE);near(h,ThreadedSpikeGameTest.energy(t)-before,.04*.8*chunk(60),"unequipped fragment no longer adds points");
                IncandescentGameTest.bind(t,"eruption","chorus_d2:ember_of_eruption","");before=ThreadedSpikeGameTest.energy(t);EnergyGainGameTest.send(t,"base","amount",.04,Unit.CHARGE);near(h,ThreadedSpikeGameTest.energy(t)-before,.04*.8*chunk(70),"current effective melee stat changes actual grant");
                near(h,FirespriteGameTest.buff(t,t.owner,"chorus_d2:melee_stat").orElseThrow().components().numbers().get("points"),60,"raw melee input retained");
            });
        }catch(Exception|Error e){t.close();throw e;}
    }
}
