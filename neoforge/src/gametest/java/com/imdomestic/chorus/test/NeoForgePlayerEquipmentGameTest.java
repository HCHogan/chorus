package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

public class NeoForgePlayerEquipmentGameTest {
    @GameCase public void equipmentJoinsDeathCaptureOnceAndHonorsDropCancellationWithoutTossEvents(GameTestHelper h) throws Exception {
        boolean previous = h.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY);
        try {
            h.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, false, h.getLevel().getServer());
            for (boolean cancel : List.of(false, true)) try (var test = new PlayerEquipmentGameTest.Harness(h)) {
                var player = test.player(); var equipment = PlayerEquipment.get(player); String id = UUID.randomUUID().toString();
                var stack = new ItemStack(Items.DIAMOND_CHESTPLATE); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:arms", Map.of()));
                player.getInventory().setItem(0, stack); equipment.swap(player, "test:arms", 0, 0);
                var calls = new AtomicInteger(); var tosses = new AtomicInteger(); var captured = new ArrayList<ItemEntity>();
                NeoForge.EVENT_BUS.addListener((LivingDropsEvent event) -> {
                    if (event.getEntity() != player) return;
                    calls.incrementAndGet(); captured.addAll(event.getDrops()); if (cancel) event.setCanceled(true);
                });
                NeoForge.EVENT_BUS.addListener((ItemTossEvent event) -> { if (event.getPlayer() == player) tosses.incrementAndGet(); });
                player.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 100); test.settled();
                h.assertValueEqual(calls.get(), 1, "one outer death-drop event"); h.assertValueEqual(tosses.get(), 0, "death equipment must not use toss pipeline");
                h.assertValueEqual(captured.size(), 1, "actual equipment in captured death drops");
                h.assertTrue(ItemStack.matches(captured.getFirst().getItem(), stack), "captured item components changed");
                h.assertTrue(equipment.isEmpty(), "death callback left duplicate equipment");
                var spawned = h.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(10), entity -> {
                    var gear = entity.getItem().get(ChorusComponents.EQUIPMENT.get()); return gear != null && gear.instance().equals(id);
                });
                h.assertValueEqual(spawned.size(), cancel ? 0 : 1, "drop cancellation honored"); spawned.forEach(ItemEntity::discard);
            }
        } finally { h.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, previous, h.getLevel().getServer()); }
        h.succeed();
    }
}
