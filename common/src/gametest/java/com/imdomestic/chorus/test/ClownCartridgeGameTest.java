package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
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
import net.minecraft.world.phys.Vec3;

public class ClownCartridgeGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer player; final MinecraftEffectRuntime runtime;
        final List<EffectState> atHeal = new ArrayList<>(); boolean failOverflow;
        Harness(GameTestHelper h, int reserves) throws Exception {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "clown-test"), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket()); player.setPos(h.absoluteVec(new Vec3(3, 2, 3))); player.setNoGravity(true);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(10);
            var fragments = new ArrayList<EffectProgram>();
            for (String fixture : List.of("clown_cartridge", "clown_weapon")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject();
                if (fixture.equals("clown_weapon")) data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition").getAsJsonObject("reserves").addProperty("rounds", reserves);
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
            }
            var world = new MinecraftWorldActions(h.getLevel(), id -> id.equals(player.getUUID().toString()) ? player : null, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), CompiledEffects.link(fragments), EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof HealingCommand) atHeal.add(state());
                var result = world.apply(request);
                if (failOverflow && request.command() instanceof HealingCommand && state().random().cursor() > 0) throw new IllegalStateException("Injected unknown Clown Cartridge reaction");
                return result;
            }, MinecraftEffectRuntime::nativeSource);
            var equipment = PlayerEquipment.get(player);
            for (String weapon : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:rifle", Map.of("perk", weapon.equals("a") ? "normal" : "enhanced")));
                player.getInventory().setItem(0, stack); equipment.swap(player, weapon.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            draw("primary");
        }
        EffectState state() { return runtime.state().engine().domain(); }
        int magazine(String id) { return state().ammunition().get(id).magazine(); }
        int reserve(String id) { return state().ammunition().get(id).reserve().orElseThrow().rounds(); }
        void draw(String slot) { var equipment = PlayerEquipment.get(player); equipment.draw(player, Optional.of("test:" + slot), equipment.revision()); }
        void reloadCommand() throws Exception { h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon reload", player.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)); }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Clown runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); player.discard(); }
    }
    @GameCase(environment = "chorus_gametest:clown_reload", maxTicks = 25)
    public void realReloadOverflowsFromReservesAndKeepsTwoWeaponInstancesAndEnhancedChoiceSeparate(GameTestHelper h) throws Exception {
        var t = new Harness(h, 12);
        try {
            t.reloadCommand(); h.assertValueEqual(t.state().random().cursor(), 0L, "starting reload must not roll");
            h.runAfterDelay(6, () -> {
                try {
                    t.settled(); h.assertValueEqual(t.magazine("a"), 8, "normal seeded overflow"); h.assertValueEqual(t.reserve("a"), 5, "finite reserves paid");
                    h.assertValueEqual(t.magazine("b"), 1, "other weapon untouched"); near(h, t.player.getHealth(), 17, "real reactions read actual base and overflow transfer");
                    h.assertValueEqual(t.runtime.program().ammoCapacity(t.state(), "a").capacity(), 5, "overflow did not mutate base capacity");
                    t.draw("secondary"); t.reloadCommand();
                    h.runAfterDelay(6, () -> {
                        try (t) {
                            t.settled(); h.assertValueEqual(t.magazine("b"), 7, "enhanced choice uses second sample"); h.assertValueEqual(t.reserve("b"), 6, "enhanced transfer conserved");
                            h.assertValueEqual(t.magazine("a"), 8, "stow preserved loaded overflow"); h.assertValueEqual(t.state().random().cursor(), 2L, "one sample per completed reload");
                            h.assertTrue(t.atHeal.getLast().ammunition().get("b").magazine() == 7 && t.atHeal.getLast().random().cursor() == 2, "world effect saw uncommitted overflow");
                            near(h, t.player.getHealth(), 23, "both actual transfers applied once"); h.succeed();
                        }
                    });
                } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:clown_shortfall", maxTicks = 15)
    public void completedReloadCanOnlyOverflowByTheRemainingFiniteReserve(GameTestHelper h) throws Exception {
        var t = new Harness(h, 5);
        try {
            t.reloadCommand(); h.runAfterDelay(6, () -> {
                try (t) {
                    t.settled(); h.assertValueEqual(t.magazine("a"), 6, "only one extra round remained"); h.assertValueEqual(t.reserve("a"), 0, "cannot generate ammo");
                    h.assertValueEqual(t.state().random().cursor(), 1L, "completed reload rolled once"); near(h, t.player.getHealth(), 15, "only five total rounds actually transferred"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:clown_unknown", maxTicks = 15)
    public void unknownOverflowWorldReactionCannotRerollOrDuplicateAlreadyTransferredRounds(GameTestHelper h) throws Exception {
        var t = new Harness(h, 12);
        try {
            t.failOverflow = true; t.reloadCommand(); h.runAfterDelay(6, () -> {
                try (t) {
                    t.runtime.prepare(); h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown reaction was hidden");
                    h.assertValueEqual(t.magazine("a"), 8, "committed overflow retained"); h.assertValueEqual(t.reserve("a"), 5, "committed reserve debit retained");
                    near(h, t.player.getHealth(), 17, "world healing already occurred exactly once"); h.assertValueEqual(t.state().random().cursor(), 1L, "sample retained");
                    boolean rejected = false; try { t.runtime.reload(t.player); } catch (IllegalStateException expected) { rejected = true; }
                    h.assertTrue(rejected && t.atHeal.size() == 2 && t.state().random().cursor() == 1, "failed runtime rerolled or repeated transfer"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
