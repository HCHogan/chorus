package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
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
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

/** Ordinary player commands, owned containers and physical impacts produce every kill/reload/hit fact. */
public class VoltshotWeaponGameTest {
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final MinecraftEffectRuntime runtime;
        final List<LivingEntity> targets = new ArrayList<>(); final List<EffectProjectile> projectiles = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<DamageReceipt> receipts = new ArrayList<>();
        int checks; boolean unknownStatus;
        Harness(GameTestHelper h, boolean credit) throws Exception {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "voltshot-test"), false);
            owner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, cookie);
            owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            var modules = new HashMap<String, ProgramModule>();
            for (String fixture : List.of("voltshot_weapon", "voltshot", "jolt")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject();
                if (!fixture.equals("voltshot_weapon")) data.addProperty("fragment", true);
                else if (!credit) data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags", new JsonArray());
                modules.put("chorus_d2:" + fixture, ProgramModule.CODEC.parse(JsonOps.INSTANCE, data).getOrThrow());
            }
            var program = ProgramCatalogue.compile(modules).get("chorus_d2:voltshot_weapon");
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> new DamageSource(type, null, owner), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof StatusResult.Check) { checks++; if (unknownStatus) throw new IllegalStateException("Injected unknown physical Voltshot status result"); }
                var result = world.apply(request);
                if (result instanceof ProjectileFlight.Receipt launch && launch.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(launch.entity().orElseThrow())));
                if (result instanceof DamageReceipt hit) { hits.add((DamageCommand) request.command()); receipts.add(hit); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
            var equipment = PlayerEquipment.get(owner);
            for (String weapon : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:rifle", Map.of("perk", weapon.equals("a") ? "enhanced" : "normal")));
                owner.getInventory().setItem(0, stack); equipment.swap(owner, weapon.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            draw("primary");
        }
        EffectState state() { return runtime.state().engine().domain(); }
        void draw(String slot) { var equipment = PlayerEquipment.get(owner); equipment.draw(owner, Optional.of("test:" + slot), equipment.revision()); }
        void command(String text) throws CommandSyntaxException { h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon " + text, owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)); }
        int magazine(String weapon) { return state().ammunition().get(weapon).magazine(); }
        Optional<BuffInstance> buff(String type, String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:voltshot_" + type) && b.origin().weapon().equals(weapon)).findFirst(); }
        Optional<BuffInstance> jolt(LivingEntity target) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:jolt") && b.key().holder().equals(target.getUUID().toString())).findFirst(); }
        LivingEntity cow(float health, boolean neighbor) {
            var target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3);
            double offset = neighbor ? ((owner.getBlockX() & 15) < 8 ? 2 : -2) : 0;
            target.setPos(owner.getX() + offset, owner.getY() + 6, owner.getZ()); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(health); targets.add(target);
            h.assertTrue(h.getLevel().getEntity(target.getUUID()) == target, "physical target must be loaded"); return target;
        }
        void later(int ticks, Checked action) { h.runAfterDelay(ticks, () -> { try { action.run(); } catch (Exception | Error error) { close(); throw new RuntimeException(error); } }); }
        void healthy() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Voltshot weapon runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); targets.forEach(Entity::discard); owner.discard(); }
    }
    @GameCase(environment = "chorus_gametest:voltshot_weapon_cycle", maxTicks = 70)
    public void ownedKillReloadAndStowedFlightApplyJoltAndItsKillCannotRefreshTheWeaponWindow(GameTestHelper h) throws Exception {
        var t = new Harness(h, true);
        try {
            var first = t.cow(5, false); t.command("fire");
            h.assertTrue(t.buff("window", "a").isEmpty() && t.receipts.isEmpty(), "launch cannot create a kill fact");
            t.later(10, () -> {
                t.healthy(); h.assertTrue(!first.isAlive(), "physical first shot must kill"); first.discard();
                long windowEnd = t.buff("window", "a").orElseThrow().deadline();
                t.draw("secondary"); t.command("reload");
                t.later(6, () -> {
                    t.healthy(); h.assertTrue(t.buff("ready", "a").isEmpty() && t.buff("ready", "b").isEmpty(), "another owned weapon's completed refill cannot arm Voltshot");
                    t.draw("primary"); t.command("reload"); long due = t.state().reloads().get(t.owner.getUUID().toString()).dueAt();
                    t.later(6, () -> {
                        t.healthy(); h.assertValueEqual(t.buff("ready", "a").orElseThrow().deadline(), due + 8_000_000, "equipped enhancement controls ready duration");
                        h.assertValueEqual(t.magazine("a"), 5, "actual refill");
                        var target = t.cow(100, false); var neighbor = t.cow(5, true); t.command("fire"); t.draw("secondary");
                        h.assertTrue(t.buff("ready", "a").isPresent(), "stow must retain ready during flight");
                        t.later(10, () -> {
                            t.healthy(); near(h, target.getHealth(), 90, "charged projectile actual damage");
                            h.assertTrue(t.jolt(target).isPresent() && t.buff("ready", "a").isEmpty(), "physical stowed shot spends its weapon's ready state");
                            h.assertValueEqual(t.hits.getLast().source().weapon(), "a", "flight retains originating weapon");
                            near(h, neighbor.getHealth(), 5, "first hit remains below Jolt threshold");
                            t.draw("primary"); t.command("fire");
                            t.later(10, () -> {
                                try (t) {
                                    t.healthy(); near(h, target.getHealth(), 68.1, "two 10-point weapon hits plus actual 11.9 Jolt");
                                    h.assertTrue(!neighbor.isAlive(), "Jolt must really kill the neighbor");
                                    h.assertValueEqual(t.buff("window", "a").orElseThrow().deadline(), windowEnd, "Jolt kill must not refresh weapon-kill window");
                                    h.assertValueEqual(t.magazine("a"), 3, "each real shot spends ammunition"); h.assertValueEqual(t.magazine("b"), 5, "other weapon remains loaded");
                                    h.assertValueEqual(t.state().ammunition().get("a").reserve().orElseThrow().rounds(), 7, "reload transfers conserved reserves");
                                    h.assertValueEqual(t.hits.stream().filter(c -> c.tags().contains("chorus_d2:jolt_damage") && c.killTags().isEmpty()).count(), 2L, "two generic chain components without weapon-kill credit"); h.succeed();
                                }
                            });
                        });
                    });
                });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:voltshot_weapon_cancel", maxTicks = 35)
    public void stowingBeforeReloadCompletesPreservesTheKillWindowButDoesNotArm(GameTestHelper h) throws Exception {
        var t = new Harness(h, true);
        try {
            var first = t.cow(5, false); t.command("fire");
            t.later(10, () -> {
                t.healthy(); h.assertTrue(!first.isAlive(), "physical kill"); first.discard();
                t.command("reload"); t.draw("secondary");
                t.later(6, () -> {
                    t.healthy(); h.assertTrue(t.state().reloads().isEmpty() && t.buff("ready", "a").isEmpty(), "cancelled reload cannot arm");
                    h.assertValueEqual(t.magazine("a"), 0, "cancelled reload transfers no rounds"); h.assertTrue(t.buff("window", "a").isPresent(), "kill window persists through stow");
                    t.draw("primary"); t.command("reload");
                    t.later(6, () -> { try (t) { t.healthy(); h.assertTrue(t.buff("ready", "a").isPresent(), "later completed reload inside window arms"); h.succeed(); } });
                });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:voltshot_weapon_credit", maxTicks = 40)
    public void actualWeaponKillWithoutDeclaredCreditAndRealRefillDoNotActivateVoltshot(GameTestHelper h) throws Exception {
        var t = new Harness(h, false);
        try {
            var first = t.cow(5, false); t.command("fire");
            t.later(10, () -> {
                t.healthy(); h.assertTrue(!first.isAlive() && t.receipts.getFirst().lethal(), "actual kill required"); first.discard();
                h.assertValueEqual(t.hits.getFirst().source().weapon(), "a", "weapon provenance is still present");
                h.assertTrue(t.buff("window", "a").isEmpty(), "provenance cannot invent weapon-kill credit"); t.command("reload");
                t.later(6, () -> {
                    t.healthy(); h.assertValueEqual(t.magazine("a"), 5, "real reload completed"); var next = t.cow(100, false); t.command("fire");
                    t.later(10, () -> { try (t) { t.healthy(); near(h, next.getHealth(), 90, "ordinary next shot"); h.assertTrue(t.jolt(next).isEmpty() && t.checks == 0, "no phantom status application"); h.succeed(); } });
                });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:voltshot_weapon_unknown", maxTicks = 40)
    public void unknownStatusAfterChargedProjectileKeepsAmmoDamageAndSpentChargeWithoutReplay(GameTestHelper h) throws Exception {
        var t = new Harness(h, true);
        try {
            var first = t.cow(5, false); t.command("fire");
            t.later(10, () -> {
                t.healthy(); h.assertTrue(!first.isAlive(), "actual first kill"); first.discard(); t.command("reload");
                t.later(6, () -> {
                    t.healthy(); var target = t.cow(100, false); t.unknownStatus = true; t.command("fire");
                    t.later(10, () -> {
                        try (t) {
                            h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown status stops the boundary");
                            near(h, target.getHealth(), 90, "confirmed physical damage is retained");
                            h.assertValueEqual(t.magazine("a"), 4, "confirmed shot cost is retained");
                            h.assertTrue(t.buff("ready", "a").isEmpty() && t.jolt(target).isEmpty(), "charge spent but status remains unknown");
                            t.unknownStatus = false; MinecraftEffectRuntime.tick(h.getLevel());
                            h.assertValueEqual(t.checks, 1, "failed world status must not replay"); h.assertValueEqual(t.projectiles.size(), 2, "no repeated launch"); h.succeed();
                        }
                    });
                });
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
