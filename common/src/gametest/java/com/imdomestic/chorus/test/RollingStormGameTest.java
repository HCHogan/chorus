package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class RollingStormGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final MinecraftEffectRuntime runtime;
        final List<LivingEntity> targets = new ArrayList<>(); final List<EffectProjectile> projectiles = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<DamageReceipt> damage = new ArrayList<>();
        Harness(GameTestHelper h) throws Exception { this(h, true, false); }
        Harness(GameTestHelper h, boolean weaponCredit, boolean amplified) throws Exception {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "rolling-test"), false);
            owner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, cookie);
            owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); owner.setHealth(10);
            var fragments = new ArrayList<EffectProgram>();
            for (String fixture : List.of("rolling_storm_weapon", "rolling_storm", "bolt_charge")) try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject();
                if (!weaponCredit && fixture.equals("rolling_storm_weapon")) data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags", new JsonArray());
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
            }
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> new DamageSource(type, null, owner), (_, _) -> true, _ -> {});
            var program = CompiledEffects.link(fragments);
            var origin = new BuffInstance.Origin(owner.getUUID().toString(), "system", "", "");
            var initial = EffectState.empty().withSource(new EffectSource("system", "chorus_d2:bolt_charge_system", owner.getUUID().toString(), origin, Set.of()));
            // Test host installs the system; the marker tests a condition, not full Amplified behavior.
            if (amplified) initial = initial.withBuffs(Buffs.grant(initial.buffs(), program.buff("test:amplified"), origin.owner(), origin.owner(), origin, 1, 1, 1_000_000).store());
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, initial, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof ProjectileFlight.Receipt launch && launch.entity().isPresent()) {
                    projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(launch.entity().orElseThrow())));
                }
                if (receipt instanceof DamageReceipt result) { damage.add(result); hits.add((DamageCommand) request.command()); }
                return receipt;
            }, MinecraftEffectRuntime::nativeSource);
            var equipment = PlayerEquipment.get(owner);
            for (String weapon : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(weapon, "test:rifle", Map.of("perk", weapon.equals("a") ? "enhanced" : "normal")));
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
        int stacks() { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:bolt_charge")).mapToInt(BuffInstance::count).sum(); }
        double energy() { var r = state().resources().get(new ResourceState.Key(owner.getUUID().toString(), "chorus_d2:melee")); return r == null ? 0 : r.value(); }
        void melee() throws CommandSyntaxException {
            owner.setPos(h.absoluteVec(new Vec3(2.5, 44, 3.5)));
            String id = owner.getUUID().toString();
            runtime.abilities(new AbilityChange(id, state().abilities().getOrDefault(id, AbilityLoadout.EMPTY), new AbilityLoadout(Map.of("test:melee", "test:melee"))));
            command("chorus ability use test:melee");
        }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "fire runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); targets.forEach(Entity::discard); owner.discard(); }
    }
    @GameCase(environment = "chorus_gametest:rolling_cycle", maxTicks = 130)
    public void actualWeaponKillsFillChargeAndOrdinaryMeleeDischargeKillCannotRegrantThePerk(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try { killNext(h, t); } catch (Exception | Error error) { t.close(); throw error; }
    }
    private static void killNext(GameTestHelper h, Harness t) throws CommandSyntaxException {
        var target = t.cow(5); t.command("chorus weapon fire");
        h.runAfterDelay(10, () -> {
            try {
                t.settled(); h.assertTrue(!target.isAlive() && t.damage.getLast().lethal(), "physical shot must kill"); target.discard();
                if (t.stacks() < 10) {
                    if (t.magazine("a") == 0) {
                        t.command("chorus weapon reload");
                        h.runAfterDelay(6, () -> { try { t.settled(); killNext(h, t); } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); } });
                    } else killNext(h, t);
                    return;
                }
                h.assertValueEqual(t.hits.size(), 7, "enhanced seed plus independently counted hits and weapon kills");
                near(h, t.energy(), .275, "all eleven credited stacks return energy, including overflow");
                var dischargeVictim = t.cow(40); t.melee();
                h.assertValueEqual(t.stacks(), 0, "melee consumes ready charge before delayed world action");
                near(h, dischargeVictim.getHealth(), 30, "actual unpowered melee");
                h.runAfterDelay(12, () -> {
                    try (t) {
                        t.settled(); h.assertTrue(!dischargeVictim.isAlive(), "delayed Bolt Charge must really kill");
                        var bolts = t.hits.stream().filter(c -> c.tags().contains("chorus_d2:bolt_charge_damage")).toList();
                        h.assertValueEqual(bolts.size(), 2, "both delayed components attempted");
                        h.assertTrue(bolts.stream().allMatch(c -> c.killTags().isEmpty()), "generic arc discharge invented weapon kill credit");
                        h.assertValueEqual(t.stacks(), 0, "discharge kill reactivated Rolling Storm"); near(h, t.energy(), .275, "no phantom energy after discharge kill"); h.succeed();
                    }
                });
            } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
        });
    }
    @GameCase(environment = "chorus_gametest:rolling_stowed", maxTicks = 25)
    public void enhancedAmplifiedKillAfterStowGrantsThreeStacksToTheOwner(GameTestHelper h) throws Exception {
        var t = new Harness(h, true, true);
        try {
            var target = t.cow(5); t.command("chorus weapon fire"); t.draw("secondary");
            h.assertValueEqual(t.stacks(), 0, "launch cannot grant a kill reward");
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); h.assertTrue(!target.isAlive() && t.damage.getFirst().lethal(), "actual delayed weapon kill");
                    h.assertValueEqual(t.hits.getFirst().source().weapon(), "a", "stow must preserve shot provenance");
                    h.assertValueEqual(t.stacks(), 3, "enhanced first kill while Amplified"); near(h, t.energy(), .075, "three stacks of energy");
                    h.assertValueEqual(t.magazine("b"), 5, "other equipped weapon remains untouched"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:rolling_credit", maxTicks = 25)
    public void actualKillWithWeaponOriginButNoWeaponKillCreditCannotBootstrapCharge(GameTestHelper h) throws Exception {
        var t = new Harness(h, false, false);
        try {
            var target = t.cow(5); t.command("chorus weapon fire");
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); h.assertTrue(!target.isAlive() && t.damage.getFirst().lethal(), "actual kill");
                    h.assertValueEqual(t.hits.getFirst().source().weapon(), "a", "weapon identity remains available");
                    h.assertValueEqual(t.stacks(), 0, "weapon identity alone cannot grant Rolling Storm"); near(h, t.energy(), 0, "no phantom energy"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
