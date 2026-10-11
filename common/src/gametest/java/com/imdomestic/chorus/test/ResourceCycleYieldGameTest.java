package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;

/** Synthetic cycle policy, with actual equipment transfers, server ticks and paid native ability bodies. */
public class ResourceCycleYieldGameTest {
    static BleakWatcherGameTest.Harness open(GameTestHelper h) {
        var p = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("resource_cycle_yield")).getOrThrow();
        var t = new BleakWatcherGameTest.Harness(h, p); t.owner.setHealth(10);
        String owner = BleakWatcherGameTest.id(t.owner);
        t.runtime.bind(new EffectSource("input", "test:yield_inputs", owner, new BuffInstance.Origin(owner, "input", "", ""), Set.of()));
        t.runtime.abilities(new AbilityChange(owner, AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("chorus_d2:melee", "test:yield_ability"))));
        return t;
    }
    static double value(BleakWatcherGameTest.Harness t, String resource) { return LinkedRechargeGameTest.value(t, "yield_" + resource); }
    static void use(GameTestHelper h, BleakWatcherGameTest.Harness t, AbilityUse.Outcome outcome) { LinkedRechargeGameTest.use(h, t, outcome); }
    static void send(BleakWatcherGameTest.Harness t, String event, double amount) { LinkedRechargeGameTest.send(t, event, amount); }
    static void equip(BleakWatcherGameTest.Harness t, String instance) {
        var e = PlayerEquipment.get(t.owner); var stack = instance.isEmpty() ? ItemStack.EMPTY : new ItemStack(Items.STRING);
        if (!stack.isEmpty()) stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(instance, "test:linked_armor", Map.of()));
        int inventorySlot = instance.isEmpty() ? 1 : 0;
        t.owner.getInventory().setItem(inventorySlot, stack); e.swap(t.owner, "test:armor", inventorySlot, e.revision());
    }
    @GameCase public void physicalReplacementPreservesProgressAndChangesOnlyTheNextCycleYield(GameTestHelper h) {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED);
            send(t, "base", 1); near(h, value(t, "progress"), .5, "base gain must apply CES once");
            send(t, "grant", .5); near(h, value(t, "uses"), 1, "normal cycle did not grant exactly one use");
            use(h, t, AbilityUse.Outcome.ACCEPTED); send(t, "grant", .4);
            equip(t, "first"); equip(t, "replacement"); near(h, value(t, "progress"), .4, "equipment replaced the recharge account");
            near(h, value(t, "uses"), 0, "equipping granted a free use"); use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY);
            h.assertValueEqual(t.owner.getInventory().getItem(0).get(ChorusComponents.EQUIPMENT.get()).instance(), "first", "old physical item not returned");
            send(t, "grant", .6); near(h, value(t, "uses"), 2, "equipped yield did not restore both missing uses");
            near(h, value(t, "progress"), 0, "completed meter not consumed"); near(h, t.owner.getHealth(), 16, "paid bodies or completion observations incorrect");
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY);
            near(h, t.owner.getHealth(), 18, "restored uses did not reach native bodies"); t.healthy();
        }
        h.succeed();
    }
    @GameCase(environment="chorus_gametest:cycle_yield_equip", maxTicks=35)
    public void equippingDuringRealRechargeRestoresBothUsesAtTheOriginalBoundary(GameTestHelper h) {
        var t = open(h);
        try {
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); long started = t.runtime.nowMicros();
            t.at(8, () -> {
                double progress = value(t, "progress"); equip(t, "first");
                near(h, value(t, "progress"), progress, "equipment reset live recharge"); near(h, value(t, "uses"), 0, "equipment created a use");
            });
            t.at(15, () -> {
                near(h, value(t, "progress"), (t.runtime.nowMicros() - started) / 1_000_000.0, "original cycle timing changed");
                use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY);
            });
            t.finish(22, () -> {
                near(h, value(t, "uses"), 2, "one cycle failed to restore both uses"); near(h, value(t, "progress"), 0, "full uses kept accumulating progress");
                near(h, t.owner.getHealth(), 14, "cycle observation missing or repeated");
            });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:cycle_yield_remove", maxTicks=55)
    public void removingEquipmentDuringRealRechargeReturnsToOneUsePerCycle(GameTestHelper h) {
        var t = open(h);
        try {
            equip(t, "first"); use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED);
            t.at(8, () -> {
                double progress = value(t, "progress"); equip(t, ""); near(h, value(t, "progress"), progress, "unequipping reset progress");
                h.assertTrue(PlayerEquipment.get(t.owner).snapshot().items().isEmpty(), "physical armor remained equipped");
            });
            t.at(22, () -> { near(h, value(t, "uses"), 1, "old equipment policy survived removal"); near(h, t.owner.getHealth(), 13, "first sequential completion observation"); });
            t.finish(42, () -> { near(h, value(t, "uses"), 2, "second sequential cycle did not finish"); near(h, value(t, "progress"), 0, "full meter did not stop"); near(h, t.owner.getHealth(), 14, "second completion observation"); });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase public void unknownCompletionObserverKeepsBothAccountsAndThePhysicalEquipment(GameTestHelper h) {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); equip(t, "first"); t.failGainHealing = true;
            try { send(t, "grant", 1); } catch (IllegalStateException expected) { /* both writes precede the native completion observation */ }
            h.assertTrue(t.runtime.failure().isPresent(), "unknown completion did not stop runtime");
            near(h, value(t, "uses"), 2, "restored uses rolled back"); near(h, value(t, "progress"), 0, "consumed progress rolled back");
            h.assertValueEqual(PlayerEquipment.get(t.owner).snapshot().items().get("test:armor").get(ChorusComponents.EQUIPMENT.get()).instance(), "first", "physical armor rolled back");
            near(h, t.owner.getHealth(), 14, "native completion observation lost"); t.runtime.prepare(); near(h, t.owner.getHealth(), 14, "native completion replayed");
        }
        h.succeed();
    }
}
