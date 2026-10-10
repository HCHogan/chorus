package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.weapon.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class WeaponFireGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final MinecraftEffectRuntime runtime;
        final List<LivingEntity> targets = new ArrayList<>(); final List<EffectProjectile> projectiles = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<DamageReceipt> damage = new ArrayList<>(); final List<EffectState> atLaunch = new ArrayList<>();
        boolean failLaunch;
        Harness(GameTestHelper h) throws Exception { this(h, true); }
        Harness(GameTestHelper h, boolean weaponCredit) throws Exception {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "fire-test"), false);
            owner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, cookie);
            owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); owner.setHealth(10);
            var fragments = new ArrayList<EffectProgram>();
            for (String fixture : List.of("weapon_fire", "kill_clip")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject();
                if (!weaponCredit && fixture.equals("weapon_fire")) data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags", new JsonArray());
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
            }
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> new DamageSource(type, null, owner), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), CompiledEffects.link(fragments), EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof ProjectileFlight.Launch) atLaunch.add(state());
                var receipt = world.apply(request);
                if (receipt instanceof ProjectileFlight.Receipt launch && launch.entity().isPresent()) {
                    projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(launch.entity().orElseThrow())));
                    if (failLaunch) throw new IllegalStateException("Injected unknown physical weapon launch outcome");
                }
                if (receipt instanceof DamageReceipt result) { damage.add(result); hits.add((DamageCommand) request.command()); }
                return receipt;
            }, MinecraftEffectRuntime::nativeSource);
            var equipment = PlayerEquipment.get(owner);
            for (String weapon : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:rifle", Map.of("perk", "kill_clip")));
                owner.getInventory().setItem(0, stack); equipment.swap(owner, weapon.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            draw("primary");
        }
        EffectState state() { return runtime.state().engine().domain(); }
        int magazine(String weapon) { return state().ammunition().get(weapon).magazine(); }
        void draw(String slot) { var equipment = PlayerEquipment.get(owner); equipment.draw(owner, Optional.of("test:" + slot), equipment.revision()); }
        int command(String text) throws CommandSyntaxException { return h.getLevel().getServer().getCommands().getDispatcher().execute(text, owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)); }
        LivingEntity cow(float health) {
            var target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(h.absoluteVec(new Vec3(2.5, 46, 3.5))); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(health); targets.add(target); return target;
        }
        boolean active(String id, String weapon) { return state().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:" + id) && b.origin().weapon().equals(weapon)); }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "fire runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); targets.forEach(Entity::discard); owner.discard(); }
    }
    @GameCase(environment = "chorus_gametest:weapon_fire_kill_clip", maxTicks = 50)
    public void ownedWeaponShotKillAndReloadActivateKillClipAndNextFlightKeepsItsSnapshotAfterStow(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var first = t.cow(5); t.command("chorus weapon fire");
            h.assertValueEqual(t.projectiles.size(), 1, "physical launch"); h.assertTrue(t.hits.isEmpty(), "launch is not an immediate hit");
            h.assertTrue(t.atLaunch.getFirst().ammunition().get("a").magazine() == 0 && t.atLaunch.getFirst().shots().containsKey("a"), "launch saw unpaid shot");
            boolean rejected = false; try { t.command("chorus weapon fire"); } catch (CommandSyntaxException expected) { rejected = true; }
            h.assertTrue(rejected && t.projectiles.size() == 1, "ordinary command bypassed cadence");
            h.runAfterDelay(10, () -> {
                try {
                    t.settled(); h.assertTrue(!first.isAlive() && t.damage.getFirst().lethal(), "physical damage did not confirm kill");
                    h.assertTrue(t.active("kill_clip_window", "a") && !t.active("kill_clip_window", "b"), "kill credited another weapon");
                    first.discard(); t.runtime.reload(t.owner);
                    h.runAfterDelay(6, () -> {
                        try {
                            t.settled(); h.assertTrue(t.active("kill_clip", "a") && !t.active("kill_clip", "b"), "owned reload did not activate matching perk");
                            h.assertValueEqual(t.magazine("a"), 5, "real refill"); h.assertValueEqual(t.state().ammunition().get("a").reserve().orElseThrow().rounds(), 7, "reserve conservation");
                            var next = t.cow(100); var accepted = t.runtime.fire(t.owner).shot().orElseThrow(); t.draw("secondary");
                            h.assertTrue(!t.active("kill_clip", "a"), "stow should remove live weapon buff");
                            h.runAfterDelay(10, () -> {
                                try (t) {
                                    t.settled(); near(h, next.getHealth(), 87.5, "captured Kill Clip 25 percent on real next hit");
                                    h.assertValueEqual(t.hits.size(), 2, "one actual hit per physical projectile");
                                    h.assertValueEqual(t.hits.getLast().source(), accepted.origin(), "switched held weapon rewrote flight source");
                                    h.assertTrue(t.hits.getLast().source().source().startsWith("shot/") && t.hits.getLast().source().weapon().equals("a"), "shot and weapon identity");
                                    h.assertValueEqual(t.magazine("a"), 4, "next shot charged once"); h.assertValueEqual(t.magazine("b"), 1, "other weapon not charged"); h.succeed();
                                }
                            });
                        } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
                    });
                } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:weapon_fire_credit", maxTicks = 20)
    public void weaponOriginWithoutDeclaredKillCreditDoesNotActivateWeaponKillPerk(GameTestHelper h) throws Exception {
        var t = new Harness(h, false);
        try {
            var target = t.cow(5); t.runtime.fire(t.owner);
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); h.assertTrue(!target.isAlive() && t.damage.getFirst().lethal(), "real kill occurred");
                    h.assertValueEqual(t.hits.getFirst().source().weapon(), "a", "weapon provenance retained");
                    h.assertTrue(t.hits.getFirst().killTags().isEmpty() && !t.active("kill_clip_window", "a"), "provenance invented weapon kill credit"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:weapon_fire_liveness", maxTicks = 20)
    public void ineligibleOwnersCannotShootAndAcceptedFireInterruptsPhysicalReload(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.runtime.reload(t.owner); var plan = t.state().reloads().get(t.owner.getUUID().toString());
            t.owner.setGameMode(GameType.SPECTATOR); boolean spectator = false; try { t.runtime.fire(t.owner); } catch (IllegalArgumentException expected) { spectator = true; }
            h.assertTrue(spectator && t.magazine("a") == 1 && t.projectiles.isEmpty(), "spectator mutated ammunition or launched");
            t.owner.setGameMode(GameType.SURVIVAL); t.runtime.fire(t.owner);
            h.assertTrue(t.state().reloads().isEmpty() && !t.state().timers().containsKey(plan.timerId()), "accepted shot retained reload");
            t.draw("secondary"); t.owner.setHealth(0); boolean dead = false; try { t.runtime.fire(t.owner); } catch (IllegalArgumentException expected) { dead = true; }
            h.assertTrue(dead && t.magazine("b") == 1 && t.projectiles.size() == 1, "dead owner fired another weapon");
            t.owner.discard(); boolean removed = false; try { t.runtime.fire(t.owner); } catch (IllegalArgumentException expected) { removed = true; }
            h.assertTrue(removed && t.magazine("b") == 1, "removed owner fired"); h.succeed();
        }
    }
    @GameCase(environment = "chorus_gametest:weapon_fire_unknown", maxTicks = 20)
    public void unknownPhysicalLaunchKeepsCostAndStopsFlightWithoutReplayingShot(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var target = t.cow(100); t.failLaunch = true; boolean failed = false;
            try { t.runtime.fire(t.owner); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown launch disappeared");
            h.assertTrue(t.magazine("a") == 0 && t.state().shots().size() == 1 && t.projectiles.size() == 1, "unknown spawn lost accepted debit");
            h.runAfterDelay(10, () -> {
                try (t) {
                    near(h, target.getHealth(), 100, "unacknowledged launch cannot execute continuation");
                    boolean rejected = false; try { t.runtime.fire(t.owner); } catch (IllegalStateException expected) { rejected = true; }
                    h.assertTrue(rejected && t.projectiles.size() == 1 && t.projectiles.getFirst().isRemoved(), "failed input replayed physical launch"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
