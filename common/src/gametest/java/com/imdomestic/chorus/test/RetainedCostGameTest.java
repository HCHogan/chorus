package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
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
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Synthetic ability values; real player input, projectile contacts, ticks and healing. */
public class RetainedCostGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer player; final LivingEntity target; final MinecraftEffectRuntime runtime;
        final List<EffectProjectile> projectiles = new ArrayList<>(); final List<Double> heals = new ArrayList<>(); boolean failWorld;
        Harness(GameTestHelper h, double cost, double duration) throws Exception {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "refund-test"), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); player.setNoGravity(true); player.setXRot(-90); player.setYRot(0);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(10);
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(player.getX(), player.getY() + 6, player.getZ()); target.setNoGravity(true);
            JsonObject data;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/retained_cost.json")), StandardCharsets.UTF_8)) { data = JsonParser.parseReader(reader).getAsJsonObject(); }
            var ability = data.getAsJsonArray("abilities").get(0).getAsJsonObject(); ability.getAsJsonObject("cost").getAsJsonObject("amount").addProperty("value", cost);
            ability.getAsJsonArray("on_use").get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("duration").addProperty("value", duration);
            var world = new MinecraftWorldActions(h.getLevel(), id -> h.getLevel().getEntity(UUID.fromString(id)) instanceof LivingEntity entity ? entity : null,
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow(), EffectState.empty(),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request);
                        if (result instanceof ProjectileFlight.Receipt launch && launch.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(launch.entity().orElseThrow())));
                        if (request.command() instanceof HealingCommand heal) { heals.add(heal.amount()); if (failWorld) throw new IllegalStateException("Injected unknown retained refund healing result"); }
                        return result;
                    }, MinecraftEffectRuntime::nativeSource);
            runtime.abilities(new AbilityChange(player.getUUID().toString(), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))));
        }
        EffectState state() { return runtime.state().engine().domain(); }
        double energy() { return state().resources().get(new ResourceState.Key(player.getUUID().toString(), "test:energy")).value(); }
        void cast() throws Exception { h.getLevel().getServer().getCommands().getDispatcher().execute("chorus ability use test:melee", player.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)); }
        void finish(Runnable assertions) { h.runAfterDelay(20, () -> { try (this) { assertions.run(); h.succeed(); } }); }
        void healthy() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "retained refund runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); target.discard(); player.discard(); }
    }
    @GameCase(environment = "chorus_gametest:retained_cost_shared", maxTicks = 30)
    public void realAbilityProjectileAndDelayedRefundSharePaidBudgetAfterSelectionIsCleared(GameTestHelper h) throws Exception {
        var t = new Harness(h, 1, .7);
        try {
            t.cast(); near(h, t.energy(), 1, "original cost sealed after transfer"); near(h, t.player.getHealth(), 10, "launch is not an impact refund");
            t.runtime.abilities(new AbilityChange(t.player.getUUID().toString(), t.state().abilities().get(t.player.getUUID().toString()), AbilityLoadout.EMPTY));
            t.finish(() -> {
                t.healthy(); near(h, t.energy(), 2, "one paid unit shared between independent callbacks"); near(h, t.player.getHealth(), 20, "actual healing follows credited refunds");
                h.assertValueEqual(t.heals, List.of(7.0, 3.0, 0.0), "physical hit, delayed remainder, expired callback");
                h.assertTrue(t.state().retainedCosts().isEmpty(), "finite rights cleaned at deadline");
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:retained_cost_free", maxTicks = 30)
    public void freeAbilityCannotManufactureEnergyOrHealingOnPhysicalAndDelayedCallbacks(GameTestHelper h) throws Exception {
        var t = new Harness(h, 0, .7);
        try { t.cast(); t.finish(() -> { t.healthy(); near(h, t.energy(), 2, "zero cost budget"); near(h, t.player.getHealth(), 10, "zero refund-dependent healing"); h.assertValueEqual(t.heals, List.of(0.0, 0.0, 0.0), "all callbacks ran with zero grant"); }); }
        catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:retained_cost_expired", maxTicks = 30)
    public void expiryBeforePhysicalImpactRevokesCapturedRightWithoutFailingFlight(GameTestHelper h) throws Exception {
        var t = new Harness(h, 1, .1);
        try { t.cast(); t.finish(() -> { t.healthy(); near(h, t.energy(), 1, "expired right cannot refund"); near(h, t.player.getHealth(), 10, "expired right cannot heal"); h.assertValueEqual(t.heals, List.of(0.0, 0.0, 0.0), "late physical and scheduled callbacks settle"); h.assertTrue(t.state().retainedCosts().isEmpty(), "expired ledger removed"); }); }
        catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:retained_cost_unknown", maxTicks = 30)
    public void unknownHealingReceiptPreservesRefundAndActualHealingWithoutReplaying(GameTestHelper h) throws Exception {
        var t = new Harness(h, 1, .7);
        try {
            t.failWorld = true; t.cast(); t.finish(() -> {
                h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown world result stops execution");
                near(h, t.energy(), 1.7, "refund committed before world request"); near(h, t.player.getHealth(), 17, "world mutation retained");
                near(h, t.state().retainedCosts().values().iterator().next().receipt().refundClaimed(), .7, "claim committed once");
                t.failWorld = false; MinecraftEffectRuntime.tick(h.getLevel()); h.assertValueEqual(t.heals, List.of(7.0), "unknown request not replayed");
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
