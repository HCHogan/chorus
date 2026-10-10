package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
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

public class OneTwoPunchGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final LivingEntity target; final MinecraftEffectRuntime runtime;
        final List<EffectProjectile> projectiles = new ArrayList<>(); final List<Double> melee = new ArrayList<>(); final List<DamageReceipt> hits = new ArrayList<>();
        boolean unknownMelee;
        Harness(GameTestHelper h) throws Exception { this(h, false, false, EffectState.Mode.PVE); }
        Harness(GameTestHelper h, boolean enhanced, boolean handCannon, EffectState.Mode mode, String... extra) throws Exception {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "one-two-test"), false);
            owner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, cookie); owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(h.absoluteVec(new Vec3(2.5, 46, 3.5))); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(1000);
            var fragments = new ArrayList<EffectProgram>();
            var fixtures = new ArrayList<>(List.of("one_two_punch", "one_two_punch_weapon", "combat_damage")); fixtures.addAll(List.of(extra));
            for (String fixture : fixtures) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject();
                if (fixture.equals("one_two_punch_weapon")) {
                    if (handCannon) data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonArray("tags").set(1, new JsonPrimitive("chorus_d2:hand_cannon"));
                    if (enhanced) {
                        var steps = data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");
                        for (int i : List.of(14, 15)) steps.get(i).getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("speed").addProperty("value", 0);
                    }
                }
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
            }
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> new DamageSource(type, null, owner), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), CompiledEffects.link(fragments), EffectState.empty().withMode(mode), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof ProjectileFlight.Receipt launch && launch.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(launch.entity().orElseThrow())));
                if (receipt instanceof DamageReceipt hit) {
                    hits.add(hit);
                    if (((DamageCommand) request.command()).tags().contains("chorus:melee_damage")) {
                        melee.add(hit.effective(true)); if (unknownMelee) throw new IllegalStateException("Injected unknown One-Two Punch melee receipt");
                    }
                }
                return receipt;
            }, MinecraftEffectRuntime::nativeSource);
            var equipment = PlayerEquipment.get(owner);
            for (String weapon : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:pellet_weapon", Map.of("perk", enhanced && weapon.equals("a") ? "enhanced" : "one_two_punch")));
                owner.getInventory().setItem(0, stack); equipment.swap(owner, weapon.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            draw("primary");
        }
        EffectState state() { return runtime.state().engine().domain(); }
        Optional<BuffInstance> ready(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:one_two_punch") && b.origin().weapon().equals(weapon)).findFirst(); }
        void draw(String slot) { var equipment = PlayerEquipment.get(owner); equipment.draw(owner, Optional.of("test:" + slot), equipment.revision()); }
        void command(String command) throws Exception { h.getLevel().getServer().getCommands().getDispatcher().execute(command, owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS)); }
        void meleeRange() { owner.setPos(h.absoluteVec(new Vec3(2.5, 44, 3.5))); }
        void ability(String name) throws Exception {
            String holder = owner.getUUID().toString();
            runtime.abilities(new AbilityChange(holder, state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY), new AbilityLoadout(Map.of("test:melee", "test:" + name))));
            command("chorus ability use test:melee");
        }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "One-Two Punch runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); target.discard(); owner.discard(); }
    }
    @GameCase(environment = "chorus_gametest:one_two_punch_split", maxTicks = 25)
    public void declaredSingleMeleeAttackSharesTheBuffAcrossTwoActualComponents(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.command("chorus weapon fire");
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); h.assertTrue(t.ready("a").isPresent(), "real pellet hits must arm the perk");
                    t.meleeRange(); t.ability("split_melee"); t.ability("melee");
                    h.assertValueEqual(t.melee, List.of(12.5, 12.5, 10.0), "one split attack versus following independent attack");
                    near(h, t.target.getHealth(), 941, "actual pellets plus shared melee components and independent melee");
                    h.assertTrue(t.ready("a").isEmpty() && t.state().damageGroups().isEmpty(), "buff or group leaked"); h.succeed();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:one_two_punch_shotgun", maxTicks = 25)
    public void ownedShotgunPelletsArmOneActualMeleeWhileGrenadeAndSecondStrikeUseBaseDamage(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.command("chorus weapon fire"); h.assertValueEqual(t.projectiles.size(), 12, "physical pellets"); h.assertTrue(t.ready("a").isEmpty(), "launch cannot arm perk");
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); near(h, t.target.getHealth(), 976, "twelve actual pellet hits");
                    h.assertTrue(t.ready("a").isPresent() && t.ready("b").isEmpty(), "only matching owned weapon arms");
                    t.meleeRange(); t.ability("blast"); h.assertTrue(t.ready("a").isPresent(), "grenade consumed melee buff");
                    t.ability("double_melee"); h.assertValueEqual(t.melee, List.of(25.0, 10.0), "first melee buff and second base damage");
                    near(h, t.target.getHealth(), 931, "actual grenade and two-strike damage"); h.assertTrue(t.ready("a").isEmpty(), "first melee consumed charge");
                    h.assertValueEqual(t.state().ammunition().get("a").magazine(), 1, "one round paid for twelve pellets"); h.succeed();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:one_two_punch_enhanced", maxTicks = 40)
    public void enhancedHandCannonArmsAtTenWhileTwoPelletsStillFlyAndUsesPvpMeleeBonus(GameTestHelper h) throws Exception {
        var t = new Harness(h, true, true, EffectState.Mode.PVP);
        try {
            t.command("chorus weapon fire"); h.runAfterDelay(10, () -> {
                try {
                    t.settled(); near(h, t.target.getHealth(), 980, "ten physical hits");
                    h.assertTrue(t.ready("a").isPresent() && t.state().shotGroups().size() == 1, "enhanced perk waited for whole shot to end");
                    h.assertTrue(!t.projectiles.get(10).isRemoved() && !t.projectiles.get(11).isRemoved(), "remaining pellets should still fly");
                    t.meleeRange(); t.ability("double_melee"); h.assertValueEqual(t.melee, List.of(15.0, 10.0), "hand cannon PvP bonus");
                    h.runAfterDelay(15, () -> {
                        try (t) { t.settled(); h.assertTrue(t.state().shotGroups().isEmpty() && t.ready("a").isEmpty(), "late misses rearmed perk"); near(h, t.target.getHealth(), 955, "actual damage"); h.succeed(); }
                    });
                } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:one_two_punch_stow", maxTicks = 40)
    public void stowPreventsLateArmingAndRemovesAnAlreadyArmedOtherWeapon(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.command("chorus weapon fire"); t.draw("secondary"); h.runAfterDelay(10, () -> {
                try {
                    t.settled(); h.assertTrue(t.ready("a").isEmpty() && t.ready("b").isEmpty(), "stowed or other weapon armed");
                    t.command("chorus weapon fire"); h.runAfterDelay(10, () -> {
                        try (t) {
                            t.settled(); h.assertTrue(t.ready("b").isPresent(), "second weapon actual shot did not arm"); t.draw("primary");
                            h.assertTrue(t.ready("b").isEmpty(), "stow retained buff"); t.meleeRange(); t.ability("melee"); h.assertValueEqual(t.melee, List.of(10.0), "stowed perk cannot boost melee"); h.succeed();
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:one_two_punch_expiry", maxTicks = 95)
    public void realTicksExpireTheThreeSecondBuffBeforeTheNextMelee(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.command("chorus weapon fire"); h.runAfterDelay(10, () -> {
                try {
                    t.settled(); h.assertTrue(t.ready("a").isPresent(), "shot did not arm");
                    h.runAfterDelay(65, () -> {
                        try (t) {
                            t.settled(); h.assertTrue(t.ready("a").isEmpty(), "three-second deadline did not expire"); t.meleeRange(); t.ability("melee");
                            near(h, t.target.getHealth(), 966, "expired melee uses base damage"); h.succeed();
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:one_two_punch_unknown", maxTicks = 25)
    public void unknownBoostedMeleeReceiptStopsBeforeSecondStrikeWithoutReplaying(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.command("chorus weapon fire"); h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); t.meleeRange(); t.unknownMelee = true; boolean failed = false;
                    try { t.ability("double_melee"); } catch (Exception expected) { failed = true; }
                    h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown melee did not preserve pending operation");
                    near(h, t.target.getHealth(), 951, "one boosted melee already applied"); h.assertValueEqual(t.melee, List.of(25.0), "second strike must not run");
                    h.assertTrue(t.ready("a").isPresent(), "missing receipt cannot infer consumption"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:one_two_freeze",maxTicks=35)
    public void actualPelletsAndFrozenVictimUseOneSharedMeleeMaximumAndConsumeOnlyThePerk(GameTestHelper h)throws Exception{
        var t=new Harness(h,false,false,EffectState.Mode.PVE,"freeze","freeze_test_falloff");try{
            t.command("chorus weapon fire");h.runAfterDelay(10,()->{try(t){
                t.settled();h.assertTrue(t.ready("a").isPresent(),"real pellet hits did not arm One-Two Punch");t.target.addTag("chorus_d2:elite");var freezer=FreezeGameTest.source(t.owner);t.runtime.bind(freezer);FreezeGameTest.event(t.runtime,freezer,t.target,"apply_freeze");
                double before=t.target.getHealth();t.meleeRange();t.ability("melee");t.ability("melee");h.assertValueEqual(t.melee,List.of(25.0,22.0),"perk MAX then live frozen bonus");near(h,t.target.getHealth(),before-47,"actual combined loss");h.assertTrue(t.ready("a").isEmpty(),"One-Two Punch did not consume");h.assertTrue(t.state().buffs().instances().values().stream().anyMatch(b->b.definition().id().equals("chorus_d2:freeze")),"melee consumption removed target Freeze");t.settled();h.succeed();
            }catch(Exception e){throw new RuntimeException(e);}});
        }catch(Exception|Error e){t.close();throw e;}
    }

}
