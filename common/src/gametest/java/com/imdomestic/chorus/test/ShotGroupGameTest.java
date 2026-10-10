package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

public class ShotGroupGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final LivingEntity target; final MinecraftEffectRuntime runtime;
        final List<EffectProjectile> projectiles = new ArrayList<>(); final List<ShotGroups.Summary> summaries = new ArrayList<>();
        final List<DamageReceipt> damage = new ArrayList<>(); boolean unknownDamage;
        Harness(GameTestHelper h, boolean stationary) throws Exception {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "pellet-test"), false);
            owner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, cookie);
            owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); owner.setHealth(10);
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(h.absoluteVec(new Vec3(2.5, 46, 3.5))); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(100);
            JsonObject data;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/shot_weapon.json")), StandardCharsets.UTF_8)) { data = JsonParser.parseReader(reader).getAsJsonObject(); }
            if (stationary) {
                var steps = data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");
                steps.get(3).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("lifetime").addProperty("value", .2);
                for (int i = 4; i < 7; i++) steps.get(i).getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("speed").addProperty("value", 0);
            }
            var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> new DamageSource(type, null, owner), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof ProjectileFlight.Receipt launch && launch.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(launch.entity().orElseThrow())));
                if (receipt instanceof DamageReceipt hit) { damage.add(hit); if (unknownDamage) throw new IllegalStateException("Injected unknown grouped pellet damage"); }
                if (request.command() instanceof Action.CueCommand && payload() instanceof ShotGroups.Summary summary) summaries.add(summary);
                return receipt;
            }, MinecraftEffectRuntime::nativeSource);
            var equipment = PlayerEquipment.get(owner);
            for (String weapon : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:rifle", Map.of("perk", "none")));
                owner.getInventory().setItem(0, stack); equipment.swap(owner, weapon.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            equipment.draw(owner, Optional.of("test:primary"), equipment.revision());
        }
        com.imdomestic.chorus.rule.RuleEngine.Payload payload() { return runtime.state().engine().frames().getFirst().event().signal().payload(); }
        EffectState state() { return runtime.state().engine().domain(); }
        void fire() throws Exception {
            h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon fire", owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS));
        }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "group runtime failed: " + runtime.failure()); }
        double number(String key) { h.assertValueEqual(summaries.size(), 1, "one summary"); return summaries.getFirst().event().numbers().get(key).value(); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); target.discard(); owner.discard(); }
    }
    @GameCase(environment = "chorus_gametest:shot_group_hits", maxTicks = 20)
    public void physicalPelletsAggregateActualReceiptsOnceAfterStow(GameTestHelper h) throws Exception {
        var t = new Harness(h, false);
        try {
            t.fire(); h.assertValueEqual(t.projectiles.size(), 3, "physical pellets"); h.assertTrue(t.summaries.isEmpty(), "no speculative summary");
            var equipment = PlayerEquipment.get(t.owner); equipment.draw(t.owner, Optional.of("test:secondary"), equipment.revision());
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); near(h, t.target.getHealth(), 94, "three real damage components"); near(h, t.owner.getHealth(), 13, "single summary observed three hits");
                    near(h, t.number("max_pellets_on_target"), 3, "same target pellets"); near(h, t.number("pellets_effective"), 3, "effective pellets");
                    h.assertTrue(t.summaries.getFirst().complete() && t.summaries.getFirst().event().flags().get("all_hit"), "complete all-hit shot");
                    h.assertValueEqual(t.summaries.getFirst().shot().origin().weapon(), "a", "frozen weapon identity");
                    h.assertTrue(t.state().shotGroups().isEmpty() && t.state().timers().isEmpty(), "settled shot leaked state");
                    h.assertValueEqual(t.state().ammunition().get("a").magazine(), 0, "single-round shot cost"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:shot_group_removed", maxTicks = 60)
    public void externallyRemovedPelletProducesIncompleteSummaryAtDeadline(GameTestHelper h) throws Exception {
        var t = new Harness(h, false);
        try {
            t.fire(); t.projectiles.getLast().discard();
            h.runAfterDelay(10, () -> {
                t.settled(); h.assertTrue(t.summaries.isEmpty(), "missing pellet cannot be guessed complete"); near(h, t.target.getHealth(), 96, "two real hits");
                h.runAfterDelay(35, () -> {
                    try (t) {
                        t.settled(); near(h, t.number("pellets_hit"), 2, "observed hits"); near(h, t.number("pellets_unresolved"), 1, "removed pellet unknown");
                        h.assertTrue(!t.summaries.getFirst().complete() && t.state().shotGroups().isEmpty(), "incomplete shot cleanup"); near(h, t.owner.getHealth(), 12, "one incomplete summary"); h.succeed();
                    }
                });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:shot_group_lifetime", maxTicks = 20)
    public void explicitShotLifetimeStopsPhysicalFlightAndCleansUnknownSlots(GameTestHelper h) throws Exception {
        var t = new Harness(h, true);
        try {
            t.fire(); h.runAfterDelay(8, () -> {
                try (t) {
                    t.settled(); near(h, t.number("pellets_unresolved"), 3, "pending physical pellets"); near(h, t.number("pellets_hit"), 0, "no synthetic hits");
                    h.assertTrue(t.projectiles.stream().allMatch(Entity::isRemoved), "expired shot still flies");
                    near(h, t.target.getHealth(), 100, "expired attack cannot damage"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:shot_group_unknown", maxTicks = 20)
    public void unknownWorldDamageKeepsContactOpenAndCannotReplayOrResolve(GameTestHelper h) throws Exception {
        var t = new Harness(h, false);
        try {
            t.unknownDamage = true; t.fire(); h.runAfterDelay(10, () -> {
                try (t) {
                    h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown damage must stay pending");
                    near(h, t.target.getHealth(), 98, "one world damage before lost receipt"); h.assertTrue(t.summaries.isEmpty(), "unknown result invented summary");
                    var group = t.state().shotGroups().values().iterator().next(); h.assertTrue(group.pellets().values().stream().anyMatch(ShotGroups.Pellet::contactOpen), "lost contact reservation");
                    h.assertTrue(t.projectiles.stream().allMatch(Entity::isRemoved) && t.damage.size() == 1, "world damage replayed"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
