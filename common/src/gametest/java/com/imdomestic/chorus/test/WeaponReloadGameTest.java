package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class WeaponReloadGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final List<ServerPlayer> players = new ArrayList<>();
        final MinecraftEffectRuntime runtime; final List<HealingCommand> heals = new ArrayList<>();
        final List<EffectState> beforeHeals = new ArrayList<>(); boolean failHeal;
        Harness(GameTestHelper h) throws Exception { this(h, _ -> {}); }
        Harness(GameTestHelper h, java.util.function.Consumer<com.google.gson.JsonObject> edit) throws Exception {
            this.h = h; var fragments = new ArrayList<EffectProgram>();
            for (String fixture : List.of("weapons", "kill_clip")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject(); if (fixture.equals("weapons")) edit.accept(data);
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
            }
            var program = CompiledEffects.link(fragments);
            var world = new MinecraftWorldActions(h.getLevel(), id -> players.stream().filter(p -> p.getUUID().toString().equals(id)).findFirst().orElse(null),
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof HealingCommand heal) { heals.add(heal); beforeHeals.add(state()); }
                var receipt = world.apply(request);
                if (failHeal && request.command() instanceof HealingCommand) throw new IllegalStateException("Injected unknown reload reaction outcome");
                return receipt;
            }, MinecraftEffectRuntime::nativeSource);
        }
        ServerPlayer player(String prefix) { return player(prefix, Map.of("perk", "kill_clip")); }
        ServerPlayer player(String prefix, Map<String, String> choices) {
            // GameTestHelper's deprecated mock overrides gameMode() to CREATIVE even after setGameMode.
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "reload-test"), false);
            var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setPos(h.absoluteVec(new Vec3(3, 2, 3))); player.setNoGravity(true); player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(10); players.add(player);
            var equipment = PlayerEquipment.get(player);
            for (String suffix : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(prefix + suffix, "test:rifle", choices));
                player.getInventory().setItem(0, stack); equipment.swap(player, suffix.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            equipment.draw(player, Optional.of("test:primary"), equipment.revision()); return player;
        }
        EffectState state() { return runtime.state().engine().domain(); }
        int magazine(String weapon) { return state().ammunition().get(weapon).magazine(); }
        int reserve(String weapon) { return state().ammunition().get(weapon).reserve().orElseThrow().rounds(); }
        void draw(ServerPlayer player, String slot) { var equipment = PlayerEquipment.get(player); equipment.draw(player, Optional.of("test:" + slot), equipment.revision()); }
        int command(ServerPlayer player, String text) throws CommandSyntaxException { return h.getLevel().getServer().getCommands().getDispatcher().execute(text, player.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)); }
        void killFact(ServerPlayer player, String weapon) {
            String holder = player.getUUID().toString(); var origin = new BuffInstance.Origin(holder, weapon, weapon, "");
            runtime.start(new RuleEngine.Signal("chorus:kill", new EffectEvent(holder, "fixture-target", origin, Set.of("chorus:weapon_kill"), Map.of())));
        }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "reload runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); players.forEach(ServerPlayer::discard); }
    }
    private static void incremental(com.google.gson.JsonObject data) {
        var weapon = data.getAsJsonArray("weapons").get(0).getAsJsonObject(); var settings = ThreadedSpikeGameTest.json("incremental_reload_settings");
        weapon.getAsJsonObject("reload").add("insert", settings.get("insert")); weapon.add("fire", settings.get("fire"));
    }
    @GameCase(environment = "chorus_gametest:weapon_reload_incremental", maxTicks = 25)
    public void ordinaryReloadCommandLoadsOneRoundPerDeadlineAndActivatesPerkOnFirstInsertion(GameTestHelper h) throws Exception {
        var t = new Harness(h, WeaponReloadGameTest::incremental);
        try {
            var player = t.player("insert-"); t.killFact(player, "insert-a"); t.command(player, "chorus weapon reload");
            h.runAfterDelay(5, () -> { try {
                t.runtime.prepare(); t.settled(); h.assertValueEqual(t.magazine("insert-a"), 2, "only the first insertion committed");
                h.assertTrue(t.state().reloads().containsKey(player.getUUID().toString()), "incremental reload stopped early");
                h.assertTrue(t.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:kill_clip") && b.origin().weapon().equals("insert-a")), "first insertion did not activate reload perk");
            } catch (Exception | Error error) { t.close(); throw error; } });
            h.runAfterDelay(12, () -> { try (t) {
                t.runtime.prepare(); t.settled(); h.assertValueEqual(t.magazine("insert-a"), 5, "four timed insertions reached full");
                h.assertValueEqual(t.reserve("insert-a"), 8, "finite reserves conserved"); h.assertValueEqual(t.heals.size(), 4, "one completion reaction per actual insertion");
                near(h, player.getHealth(), 14, "world reactions use committed rounds"); h.assertTrue(t.state().reloads().isEmpty(), "full reload retained a plan"); h.succeed();
            } });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:weapon_reload_insert_fire", maxTicks = 25)
    public void ordinaryFireCommandInterruptsRemainingInsertionsAndKeepsTransferredAmmunition(GameTestHelper h) throws Exception {
        var t = new Harness(h, WeaponReloadGameTest::incremental);
        try {
            var player = t.player("interrupt-"); t.command(player, "chorus weapon reload");
            h.runAfterDelay(5, () -> { try {
                t.runtime.prepare(); t.command(player, "chorus weapon fire"); t.settled();
                h.assertValueEqual(t.magazine("interrupt-a"), 1, "one loaded round minus accepted shot"); h.assertTrue(t.state().reloads().isEmpty(), "accepted fire kept reload plan");
            } catch (Exception error) { t.close(); throw new RuntimeException(error); } catch (Error error) { t.close(); throw error; } });
            h.runAfterDelay(12, () -> { try (t) {
                t.runtime.prepare(); t.settled(); h.assertValueEqual(t.magazine("interrupt-a"), 1, "cancelled future insertion transferred ammunition");
                h.assertValueEqual(t.reserve("interrupt-a"), 11, "the committed insertion was preserved"); h.assertValueEqual(t.heals.size(), 1, "future reload completion did not run"); h.succeed();
            } });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:dual_loader", maxTicks = 25)
    public void physicalDualLoaderRollsInsertTwoOrThreeRoundsAndClipTheLastInsertion(GameTestHelper h) throws Exception {
        var t = new Harness(h, data -> {
            incremental(data);
            data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload").getAsJsonObject("insert").addProperty("rounds_profile", "chorus_d2:reload_insert_rounds");
            data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("sockets").add("loader", ThreadedSpikeGameTest.json("dual_loader_options"));
            // Keep the older synthetic weapon harness version; the content file retains its Compendium version.
            var profile = ThreadedSpikeGameTest.json("reload_insert_rounds").getAsJsonArray("profiles").get(0).getAsJsonObject(); profile.addProperty("version", "test-1");
            data.getAsJsonArray("profiles").add(profile);
            ThreadedSpikeGameTest.json("dual_loader").getAsJsonArray("bundles").forEach(v -> data.getAsJsonArray("bundles").add(v));
        });
        try {
            var base = t.player("dual-base-", Map.of("perk","kill_clip","loader","base"));
            var enhanced = t.player("dual-enhanced-", Map.of("perk","kill_clip","loader","enhanced"));
            t.command(base, "chorus weapon reload"); t.command(enhanced, "chorus weapon reload");
            h.runAfterDelay(5, () -> { try {
                t.runtime.prepare(); t.settled(); h.assertValueEqual(t.magazine("dual-base-a"), 3, "normal roll inserts two");
                h.assertValueEqual(t.magazine("dual-enhanced-a"), 4, "enhanced roll inserts three");
                h.assertValueEqual(t.magazine("dual-base-b"), 1, "stowed instance unchanged");
            } catch (Exception | Error error) { t.close(); throw error; } });
            h.runAfterDelay(9, () -> { try (t) {
                t.runtime.prepare(); t.settled(); h.assertValueEqual(t.magazine("dual-base-a"), 5, "normal second insertion");
                h.assertValueEqual(t.magazine("dual-enhanced-a"), 5, "enhanced final insertion clipped to capacity");
                h.assertValueEqual(t.reserve("dual-base-a"), 8, "base total conservation"); h.assertValueEqual(t.reserve("dual-enhanced-a"), 8, "enhanced total conservation");
                h.assertValueEqual(t.heals.stream().filter(x -> x.target().equals(base.getUUID().toString())).map(HealingCommand::amount).toList(), List.of(2.,2.), "base actual transferred rounds");
                h.assertValueEqual(t.heals.stream().filter(x -> x.target().equals(enhanced.getUUID().toString())).map(HealingCommand::amount).toList(), List.of(3.,1.), "enhanced actual transferred rounds");
                h.assertTrue(t.state().reloads().isEmpty(), "full weapons retained insertion plans"); h.succeed();
            } });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:weapon_reload_complete", maxTicks = 14)
    public void ordinaryPlayerCommandCompletesOwnedWeaponReloadAndActivatesItsPerk(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var player = t.player("complete-"); t.killFact(player, "complete-a");
            t.command(player, "chorus weapon reload"); var plan = t.state().reloads().get(player.getUUID().toString());
            boolean rejected = false; try { t.command(player, "chorus weapon reload"); } catch (CommandSyntaxException expected) { rejected = true; }
            h.assertTrue(rejected && plan.equals(t.state().reloads().get(player.getUUID().toString())), "duplicate command restarted accepted reload");
            h.assertValueEqual(t.magazine("complete-a"), 1, "start did not transfer ammunition");
            h.runAfterDelay(6, () -> {
                try (t) {
                    t.runtime.prepare(); t.settled(); near(h, player.getHealth(), 14, "completion rule healed by actual transferred rounds");
                    h.assertValueEqual(t.magazine("complete-a"), 5, "magazine filled"); h.assertValueEqual(t.reserve("complete-a"), 8, "finite reserve conserved");
                    h.assertValueEqual(t.magazine("complete-b"), 1, "other weapon unchanged"); h.assertValueEqual(t.heals.size(), 1, "exactly one completion reaction");
                    h.assertTrue(t.beforeHeals.getFirst().reloads().isEmpty() && t.beforeHeals.getFirst().ammunition().get("complete-a").magazine() == 5, "world reaction saw uncommitted reload");
                    h.assertTrue(t.state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:kill_clip") && b.origin().weapon().equals("complete-a")), "real reload completion did not activate weapon perk");
                    h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:weapon_reload_cancel", maxTicks = 14)
    public void physicalWeaponSwitchCancelsWithoutRefillOrRestartOnRedraw(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var player = t.player("switch-"); t.runtime.reload(player); t.draw(player, "secondary"); t.draw(player, "primary");
            h.assertTrue(t.state().reloads().isEmpty(), "stow retained reload plan");
            h.runAfterDelay(6, () -> {
                try (t) {
                    t.runtime.prepare(); t.settled(); h.assertValueEqual(t.magazine("switch-a"), 1, "cancelled reload transferred ammunition");
                    h.assertValueEqual(t.reserve("switch-a"), 12, "cancelled reload consumed reserves"); h.assertTrue(t.heals.isEmpty(), "cancelled reload emitted finished");
                    near(h, player.getHealth(), 10, "no world completion effect"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:weapon_reload_liveness", maxTicks = 14)
    public void deathSpectatorAndRemovedOwnerCannotFinishAnAcceptedReload(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var dead = t.player("dead-"); var spectator = t.player("spectator-"); var removed = t.player("removed-");
            h.assertValueEqual(Set.of(dead.getUUID(), spectator.getUUID(), removed.getUUID()).size(), 3, "distinct mock identities");
            for (var player : List.of(dead, spectator, removed)) t.runtime.reload(player);
            dead.setHealth(0); spectator.setGameMode(GameType.SPECTATOR); removed.discard();
            h.assertTrue(!dead.isAlive() && spectator.isSpectator() && removed.isRemoved(), "ineligible physical owner setup");
            h.runAfterDelay(6, () -> {
                try (t) {
                    t.runtime.prepare(); t.settled();
                    for (String prefix : List.of("dead-", "spectator-", "removed-")) {
                        h.assertValueEqual(t.magazine(prefix + "a"), 1, "ineligible owner received ammunition: " + prefix);
                        h.assertValueEqual(t.reserve(prefix + "a"), 12, "ineligible owner paid reserves");
                    }
                    h.assertTrue(t.state().reloads().isEmpty() && t.heals.isEmpty(), "ineligible reload did not cancel");
                    boolean rejected = false; try { t.runtime.reload(spectator); } catch (IllegalArgumentException expected) { rejected = true; }
                    h.assertTrue(rejected, "spectator could begin a new reload"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:weapon_reload_failure", maxTicks = 14)
    public void unknownCompletionReactionKeepsTransferredAmmoAndCannotReplay(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var player = t.player("failure-"); t.runtime.reload(player); t.failHeal = true;
            h.runAfterDelay(6, () -> {
                try (t) {
                    t.runtime.prepare(); h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown reaction was lost");
                    near(h, player.getHealth(), 14, "native healing occurred once"); h.assertValueEqual(t.magazine("failure-a"), 5, "accepted transfer retained");
                    h.assertValueEqual(t.reserve("failure-a"), 8, "accepted reserve debit retained"); h.assertTrue(t.state().reloads().isEmpty(), "completed reload could replay");
                    boolean rejected = false; try { t.runtime.reload(player); } catch (IllegalStateException expected) { rejected = true; }
                    h.assertTrue(rejected && t.heals.size() == 1, "failed input repeated world reaction"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
