package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class PlayerEquipmentGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final List<ServerPlayer> players = new ArrayList<>();
        final CompiledEffects program;
        final List<PlayerEquipment.Stored> physical = new ArrayList<>();
        final List<EffectState> domains = new ArrayList<>();
        final List<HealingCommand> heals = new ArrayList<>();
        MinecraftEffectRuntime runtime;
        boolean failCleanup, dieOnAttach;
        Harness(GameTestHelper h) throws Exception {
            this.h = h;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/equipment.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            install(program);
        }
        void install(CompiledEffects next) {
            var world = new MinecraftWorldActions(h.getLevel(), id -> players.stream().filter(p -> p.getUUID().toString().equals(id)).findFirst().orElse(null),
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), next, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof HealingCommand command) {
                    heals.add(command); domains.add(runtime.state().engine().domain());
                    physical.add(PlayerEquipment.get(players.stream().filter(p -> p.getUUID().toString().equals(command.target())).findFirst().orElseThrow()).snapshot());
                }
                var result = world.apply(request);
                if (dieOnAttach && request.command() instanceof HealingCommand command && command.amount() == 1) {
                    dieOnAttach = false;
                    var player = players.stream().filter(p -> p.getUUID().toString().equals(command.target())).findFirst().orElseThrow();
                    player.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000);
                }
                if (failCleanup && request.command() instanceof HealingCommand command && command.amount() == 10) throw new IllegalStateException("Injected failure after physical equipment cleanup healed");
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        ServerPlayer player() {
            var p = h.makeMockServerPlayerInLevel(); p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            p.setPos(h.absoluteVec(new Vec3(3, 2, 3))); p.setNoGravity(true); p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); p.setHealth(10);
            players.add(p); return p;
        }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "physical equipment runtime failure: " + runtime.failure()); }
        @Override public void close() { runtime.close(); players.forEach(ServerPlayer::discard); }
    }
    private static ItemStack gun(String id, String option) {
        var item = new ItemStack(Items.DIAMOND_SWORD); item.setDamageValue(7); item.set(DataComponents.CUSTOM_NAME, Component.literal("Owned " + id));
        item.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", option))); return item;
    }
    private static int command(CommandSourceStack source, String text) throws CommandSyntaxException { return source.getServer().getCommands().getDispatcher().execute(text, source); }
    private static void reject(GameTestHelper h, Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        h.assertTrue(rejected, "invalid transfer succeeded");
    }
    private static long owned(ServerPlayer p, String instance) {
        long result = PlayerEquipment.get(p).snapshot().items().values().stream().filter(s -> identity(s, instance)).mapToLong(ItemStack::getCount).sum();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) { var s = p.getInventory().getItem(i); if (identity(s, instance)) result += s.getCount(); }
        return result;
    }
    private static boolean identity(ItemStack s, String instance) { var gear = s.get(ChorusComponents.EQUIPMENT.get()); return gear != null && gear.instance().equals(instance); }

    @GameCase public void commandsTransferOneOwnedStackPreserveComponentsAndValidateRevisionBeforeReactions(GameTestHelper h) throws Exception {
        try (var test = new Harness(h)) {
            var p = test.player(); var equipment = PlayerEquipment.get(p); var original = new ItemStack(Items.DIAMOND_SWORD); original.setDamageValue(7);
            original.set(DataComponents.CUSTOM_NAME, Component.literal("Test weapon")); p.getInventory().setItem(0, original);
            var source = p.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS);
            boolean denied = false; try { command(source, "chorus equipment stamp test:weapon_a test:rifle {\"perk\":\"normal\"}"); } catch (CommandSyntaxException expected) { denied = true; }
            h.assertTrue(denied && !original.has(ChorusComponents.EQUIPMENT.get()), "unprivileged stamp created an identity");
            command(source.withPermission(PermissionSet.ALL_PERMISSIONS), "chorus equipment stamp test:weapon_a test:rifle {\"perk\":\"normal\"}");
            var stamped = original.copy(); String id = original.get(ChorusComponents.EQUIPMENT.get()).instance();
            command(source, "chorus equipment swap test:weapon_a 0 0"); test.settled();
            h.assertTrue(p.getInventory().getItem(0).isEmpty() && ItemStack.matches(equipment.item("test:weapon_a"), stamped), "stack was copied or components changed");
            h.assertValueEqual(owned(p, id), 1L, "single physical owner"); h.assertValueEqual(test.physical.getFirst().items().keySet(), Set.of("test:weapon_a"), "attach saw actual container");
            h.assertTrue(test.domains.getFirst().equipment().containsKey(p.getUUID().toString()), "attach saw missing domain projection");
            near(h, p.getHealth(), 11, "attach occurred once");
            var escaped = equipment.snapshot().items().get("test:weapon_a"); escaped.setCount(99); escaped.setDamageValue(1);
            h.assertTrue(ItemStack.matches(equipment.item("test:weapon_a"), stamped), "snapshot leaked mutable owned stack");
            reject(h, () -> equipment.swap(p, "test:weapon_a", 0, 0));
            p.getInventory().setItem(1, gun("b", "enhanced")); var state = test.runtime.state().engine().domain();
            reject(h, () -> equipment.swap(p, "test:arms", 1, equipment.revision()));
            h.assertValueEqual(test.runtime.state().engine().domain(), state, "invalid slot changed effects"); h.assertValueEqual(owned(p, "b"), 1L, "rejected incoming stack retained");
            p.getInventory().setItem(2, stamped.copy()); reject(h, () -> equipment.swap(p, "test:weapon_a", 2, equipment.revision()));
            h.assertTrue(ItemStack.matches(p.getInventory().getItem(2), stamped), "duplicate identity check consumed inventory");
            var multiple = gun("multiple", "normal"); multiple.setCount(2); p.getInventory().setItem(2, multiple);
            reject(h, () -> equipment.swap(p, "test:weapon_b", 2, equipment.revision())); p.getInventory().setItem(2, ItemStack.EMPTY);
            h.assertValueEqual(equipment.revision(), 1L, "rejected duplicate/count advanced revision");
            command(source, "chorus equipment draw test:weapon_a 1"); var sources = test.runtime.state().engine().domain().sources(); var timers = test.runtime.state().engine().domain().timers();
            command(source, "chorus equipment move test:weapon_a test:weapon_b 2");
            h.assertValueEqual(equipment.snapshot().drawn(), Optional.of("test:weapon_b"), "drawn item follows move");
            h.assertValueEqual(test.runtime.state().engine().domain().sources(), sources, "moving physical item reattached sources");
            h.assertValueEqual(test.runtime.state().engine().domain().timers(), timers, "moving physical item reset timer");
            command(source, "chorus equipment swap test:weapon_b 0 3"); test.settled();
            h.assertTrue(equipment.isEmpty() && ItemStack.matches(p.getInventory().getItem(0), stamped), "unequip did not return exact stack");
            h.assertValueEqual(owned(p, id), 1L, "unequip single owner"); near(h, p.getHealth(), 21, "old cleanup once");
        }
        h.succeed();
    }
    @GameCase public void playerSaveRoundTripRetainsUnknownDefinitionsAndAllowsRetrievalWithoutAnActiveRuleset(GameTestHelper h) throws Exception {
        try (var test = new Harness(h)) {
            var first = test.player(); var equipped = PlayerEquipment.get(first); var original = gun("saved", "enhanced"); first.getInventory().setItem(0, original);
            equipped.swap(first, "test:weapon_a", 0, 0); equipped.draw(first, Optional.of("test:weapon_a"), equipped.revision());
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess()); first.saveWithoutId(output);
            first.discard(); test.runtime.prepare(); h.assertTrue(!test.runtime.state().engine().domain().equipment().containsKey(first.getUUID().toString()), "removed player retained equipment sources");
            test.runtime.close(); test.install(new CompiledEffects(new EffectProgram("test-1", List.of(), List.of(), List.of())));
            var restored = test.player(); restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
            var actual = PlayerEquipment.get(restored); test.runtime.trackEquipment(restored); test.runtime.prepare(); test.settled();
            h.assertTrue(ItemStack.matches(actual.item("test:weapon_a"), original), "player save lost stack components or roll");
            h.assertValueEqual(actual.revision(), 2L, "saved revision"); h.assertTrue(actual.inactiveReason().isPresent(), "unknown definition was silently active");
            h.assertTrue(test.runtime.state().engine().domain().equipment().isEmpty(), "unknown catalogue attached sources");
            test.runtime.close(); actual.swap(restored, "test:weapon_a", 1, actual.revision());
            h.assertTrue(actual.isEmpty() && ItemStack.matches(restored.getInventory().getItem(1), original), "missing runtime prevented recovering stored item");
            h.assertValueEqual(owned(restored, "saved"), 1L, "retrieved item ownership");
        }
        h.succeed();
    }
    @GameCase public void deathProtectionKeepInventoryVanishingAndRespawnPreservePhysicalOwnership(GameTestHelper h) throws Exception {
        boolean previous = h.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY);
        try {
            for (boolean keep : List.of(false, true)) try (var test = new Harness(h)) {
                h.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, keep, h.getLevel().getServer());
                var p = test.player(); var equipment = PlayerEquipment.get(p); String armorId = "drop-armor-" + UUID.randomUUID(); String cursedId = "cursed-" + UUID.randomUUID();
                var armor = new ItemStack(Items.DIAMOND_CHESTPLATE); armor.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(armorId, "test:arms", Map.of()));
                var cursed = gun(cursedId, "normal"); cursed.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 1);
                p.getInventory().setItem(0, armor); equipment.swap(p, "test:arms", 0, equipment.revision()); p.getInventory().setItem(0, cursed); equipment.swap(p, "test:weapon_a", 0, equipment.revision());
                p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING)); p.setHealth(2);
                p.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 100); test.settled();
                h.assertTrue(p.isAlive() && !equipment.isEmpty(), "death prevention dropped equipment"); h.assertValueEqual(owned(p, armorId), 1L, "protected ownership");
                p.removeAllEffects(); p.damageCooldownTime = 0; p.setHealth(2); p.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 100); test.settled();
                h.assertTrue(!p.isAlive() && !test.runtime.state().engine().domain().equipment().containsKey(p.getUUID().toString()), "dead player still has active equipment");
                var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(10));
                h.assertValueEqual(drops.stream().filter(e -> identity(e.getItem(), armorId)).mapToLong(e -> e.getItem().getCount()).sum(), keep ? 0L : 1L, "exactly one normal drop unless retained");
                h.assertTrue(drops.stream().noneMatch(e -> identity(e.getItem(), cursedId)), "vanishing item dropped");
                h.assertValueEqual(equipment.snapshot().items().size(), keep ? 2 : 0, "keepInventory physical retention");
                var replacement = test.player(); replacement.restoreFrom(p, false); test.runtime.trackEquipment(replacement); test.runtime.prepare(); test.settled();
                h.assertTrue(equipment.isEmpty(), "respawn copied rather than moved equipment"); h.assertValueEqual(owned(replacement, armorId), keep ? 1L : 0L, "respawn ownership");
                h.assertValueEqual(owned(replacement, cursedId), keep ? 1L : 0L, "vanishing respects keepInventory");
                drops.stream().filter(e -> identity(e.getItem(), armorId)).forEach(ItemEntity::discard);
            }
        } finally { h.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, previous, h.getLevel().getServer()); }
        h.succeed();
    }
    @GameCase public void unknownCleanupOutcomeRetainsTransferredItemsAndSupportsRecoveryWithoutRetry(GameTestHelper h) throws Exception {
        try (var test = new Harness(h)) {
            var p = test.player(); var equipment = PlayerEquipment.get(p); var old = gun("old", "normal"); var next = gun("next", "enhanced");
            p.getInventory().setItem(0, old); equipment.swap(p, "test:weapon_a", 0, 0); p.getInventory().setItem(1, next); test.failCleanup = true;
            reject(h, () -> equipment.swap(p, "test:weapon_a", 1, equipment.revision()));
            h.assertTrue(test.runtime.failure().isPresent() && test.runtime.state().engine().pending().isPresent(), "world failure lost pending operation");
            h.assertTrue(ItemStack.matches(equipment.item("test:weapon_a"), next) && ItemStack.matches(p.getInventory().getItem(1), old), "committed ownership was rolled back");
            h.assertValueEqual(test.runtime.state().engine().domain().equipment().get(p.getUUID().toString()).slots().get("test:weapon_a").instance(), "next", "committed metadata rolled back");
            h.assertTrue(ItemStack.matches(test.physical.getLast().items().get("test:weapon_a"), next), "cleanup did not see transferred item");
            near(h, p.getHealth(), 21, "old cleanup healed once; new attach did not execute");
            equipment.swap(p, "test:weapon_a", 2, equipment.revision());
            h.assertTrue(equipment.isEmpty(), "failed runtime blocked item recovery"); h.assertValueEqual(owned(p, "old"), 1L, "old item once"); h.assertValueEqual(owned(p, "next"), 1L, "new item once");
            near(h, p.getHealth(), 21, "unknown world operation retried during recovery"); h.assertValueEqual(test.heals.size(), 2, "unexpected additional effects");
        }
        h.succeed();
    }
    @GameCase public void deathInsideAttachReactionDropsTheAlreadyTransferredItemWithoutResurrectingOwnership(GameTestHelper h) throws Exception {
        boolean previous = h.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY);
        try (var test = new Harness(h)) {
            h.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, false, h.getLevel().getServer());
            var p = test.player(); var equipment = PlayerEquipment.get(p); String id = "attach-death-" + UUID.randomUUID();
            p.getInventory().setItem(0, gun(id, "normal")); test.dieOnAttach = true; equipment.swap(p, "test:weapon_a", 0, 0); test.settled();
            h.assertTrue(!p.isAlive() && equipment.isEmpty() && p.getInventory().getItem(0).isEmpty(), "reaction death restored a transferred item");
            h.assertTrue(!test.runtime.state().engine().domain().equipment().containsKey(p.getUUID().toString()), "reaction death retained equipped sources");
            var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(10), entity -> identity(entity.getItem(), id));
            h.assertValueEqual(drops.size(), 1, "one actual death drop during attach");
            h.assertValueEqual(equipment.revision(), 2L, "transfer and death both advanced physical revision"); drops.forEach(ItemEntity::discard);
        } finally { h.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, previous, h.getLevel().getServer()); }
        h.succeed();
    }
}
