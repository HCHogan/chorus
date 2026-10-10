package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public class BoltChargeGameTest {
    private static String id(Entity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final LivingEntity target; final MinecraftEffectRuntime runtime;
        final BuffInstance.Origin origin; final DamageSource nativeDamage;
        final List<DamageCommand> hits = new ArrayList<>();
        DamageBatch nativeBatch;
        Set<String> nativeTags = Set.of("chorus:weapon_damage"); ProcPolicy nativeProc = ProcPolicy.ALLOW; boolean failAfterDamage;
        Harness(GameTestHelper h) throws Exception { this(h, EffectState.Mode.PVE); }
        Harness(GameTestHelper h, EffectState.Mode mode) throws Exception {
            this.h = h; owner = h.makeMockServerPlayerInLevel(); owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(h.absoluteVec(new Vec3(2.5, 46, 3.5))); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(1000);
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/bolt_charge.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            origin = new BuffInstance.Origin(id(owner), "weapon", "weapon", "");
            var state = EffectState.empty().withMode(mode).withSource(new EffectSource("arc", "chorus_d2:bolt_charge_system", id(owner), origin, Set.of()))
                    .withAmmo(new AmmoState("weapon", 20, 20, Optional.empty()));
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            nativeDamage = new DamageSource(type, null, owner);
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> nativeDamage, (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (request.command() instanceof DamageCommand command) { hits.add(command); if (failAfterDamage) throw new IllegalStateException("Unknown result after actual bolt damage"); }
                return receipt;
            }, (victim, _, amount) -> {
                var command = new DamageCommand(id(victim), origin, amount, "chorus_gametest:delayed", nativeTags, Set.of(), false).withProc(nativeProc);
                return nativeBatch == null ? command : command.withBatch(nativeBatch);
            });
        }
        void gain(int stacks) { runtime.start(new RuleEngine.Signal("chorus_d2:grant_bolt_charge", new EffectEvent(id(owner), id(owner), origin, Set.of(), Map.of("stacks", new Measure(stacks, Unit.COUNT))))); }
        void hit() { target.hurtServer(h.getLevel(), nativeDamage, 2); }
        int stacks() { return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:bolt_charge")).mapToInt(BuffInstance::count).findFirst().orElse(0); }
        double energy() { return runtime.state().engine().domain().resources().get(new ResourceState.Key(id(owner), "chorus_d2:melee")).value(); }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Bolt runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); target.discard(); owner.discard(); }
    }
    @GameCase
    public void actualWeaponDamageArmsTheNextHitAndSharedComponentsGrantOnlyOneStack(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.hit(); h.assertValueEqual(t.stacks(), 0, "weapon damage seeded charge"); t.gain(1);
            for (int i = 0; i < 4; i++) t.hit(); t.settled(); h.assertValueEqual(t.stacks(), 1, "threshold hit granted too early");
            t.nativeTags = Set.of(); t.hit(); t.settled(); h.assertValueEqual(t.stacks(), 2, "generic following damage did not grant"); near(h, t.energy(), .05, "stack energy");
            t.nativeTags = Set.of("chorus:weapon_damage"); t.nativeBatch = new DamageBatch("multi-component", id(t.owner));
            for (int i = 0; i < 12; i++) t.hit(); t.settled(); h.assertValueEqual(t.stacks(), 3, "same batch granted multiple stacks");
            t.nativeBatch = null; t.nativeTags = Set.of(); t.hit(); t.settled(); h.assertValueEqual(t.stacks(), 4, "independent batch did not use armed counter");
            near(h, t.target.getHealth(), 962, "all real native damage retained"); near(h, t.energy(), .1, "four obtained stacks");
        }
        h.succeed();
    }
    @GameCase
    public void overflowGainsUseCreditedStacksRatherThanTheCappedStoredDifference(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.gain(9); t.gain(4); t.settled(); h.assertValueEqual(t.stacks(), 10, "storage cap"); near(h, t.energy(), .325, "13 credited stacks");
            t.gain(4); t.settled(); near(h, t.energy(), .425, "further gains while already full");
        }
        h.succeed();
    }
    private void discharge(GameTestHelper h, EffectState.Mode mode) throws Exception {
        var t = new Harness(h, mode);
        try {
            t.gain(10); t.nativeTags = Set.of("chorus:ability_damage"); t.nativeProc = new ProcPolicy(Set.of("chorus_d2:bolt_discharge"));
            t.hit(); h.assertValueEqual(t.stacks(), 10, "excluded trigger consumed charge"); t.nativeProc = ProcPolicy.ALLOW;
            t.hit(); t.hit(); h.assertValueEqual(t.stacks(), 0, "full charge was not consumed once");
            h.runAfterDelay(8, () -> { t.settled(); h.assertTrue(t.hits.isEmpty(), "discharge arrived before half a second"); });
            h.runAfterDelay(12, () -> {
                try (t) {
                    t.settled(); near(h, t.target.getHealth(), 994 - (mode == EffectState.Mode.PVE ? 67.5 : 6.6), "actual two-component center damage");
                    h.assertValueEqual(t.hits.size(), 2, "duplicate ready hits scheduled extra bolts");
                    h.assertValueEqual(t.hits.get(0).batch(), t.hits.get(1).batch(), "bolt components not grouped");
                    h.assertTrue(t.hits.stream().allMatch(c -> c.tags().contains("chorus:arc") && !c.tags().contains("chorus:ability_damage") && c.killTags().isEmpty()), "wrong ability or weapon credit"); h.succeed();
                }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:bolt_pve", maxTicks = 25)
    public void pveReadyChargeDischargesOnceAfterHalfASecondWithExplicitProcExclusions(GameTestHelper h) throws Exception { discharge(h, EffectState.Mode.PVE); }
    @GameCase(environment = "chorus_gametest:bolt_pvp", maxTicks = 25)
    public void pvpReadyChargeUsesBothGuardianCenterDamageComponents(GameTestHelper h) throws Exception { discharge(h, EffectState.Mode.PVP); }
    @GameCase(environment = "chorus_gametest:bolt_unknown", maxTicks = 25)
    public void failureAfterRealBoltDamageKeepsSpentChargeAndDoesNotReplayTheHit(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.gain(10); t.nativeTags = Set.of("chorus:ability_damage"); t.hit(); t.failAfterDamage = true;
            h.runAfterDelay(12, () -> {
                try (t) {
                    h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown outcome lost pending operation");
                    h.assertValueEqual(t.stacks(), 0, "spent charge refunded after unknown world result"); h.assertValueEqual(t.hits.size(), 1, "unknown first component was retried or followed by a second");
                    near(h, t.target.getHealth(), 957.5, "already committed native damage retained"); h.succeed();
                }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
}
