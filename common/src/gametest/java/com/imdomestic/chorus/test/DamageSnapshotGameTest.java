package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** A host retains immutable launch data, then supplies it on a later native damage call. No projectile physics are simulated here. */
public class DamageSnapshotGameTest {
    private static CompiledEffects load(boolean replacement) throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(DamageSnapshotGameTest.class.getResourceAsStream("/effects/damage_snapshot.json")), StandardCharsets.UTF_8)) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            if (replacement) {
                data = JsonParser.parseString(data.toString().replace("test-snapshot-v1", "test-snapshot-v2")).getAsJsonObject();
                data.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("group").addProperty("reduction", "product");
            }
            return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
        }
    }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final LivingEntity owner, target;
        final BuffInstance.Origin origin;
        CompiledEffects program;
        MinecraftEffectRuntime runtime;
        DamageSnapshot snapshot;
        Harness(GameTestHelper helper) throws Exception {
            this.helper = helper; owner = helper.spawnWithNoFreeWill(EntityTypes.COW, 1, 2, 1); target = helper.spawnWithNoFreeWill(EntityTypes.COW, 3, 2, 1);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
            origin = new BuffInstance.Origin(owner.getUUID().toString(), "launch", "original-weapon", "");
            program = load(false); install(true);
        }
        void install(boolean empower) {
            String holder = owner.getUUID().toString();
            var state = EffectState.empty().withSource(new EffectSource("controls", "test:controls", holder, origin, Set.of()))
                    .withSource(new EffectSource("attack", "test:attack", holder, origin, Set.of()))
                    .withResource(new ResourceState(new ResourceState.Key(holder, "test:power"), .3, 1, 0));
            if (empower) state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:empower"), holder, holder, origin, 1, 1, 100_000).store());
            var level = helper.getLevel();
            var world = new MinecraftWorldActions(level, id -> level.getEntity(UUID.fromString(id)) instanceof LivingEntity living ? living : null,
                    _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(level, program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world,
                    (victim, source, amount) -> snapshot == null ? MinecraftEffectRuntime.nativeSource(victim, source, amount) : snapshot.command(victim.getUUID().toString()));
        }
        void capture() {
            snapshot = runtime.captureDamage(new DamageCommand("target-not-yet-known", origin, 10, "minecraft:generic", Set.of("test:projectile"), Set.of("test:weapon_kill"), false, Optional.of("test:attack_damage")));
        }
        void signal(String type) {
            runtime.start(new RuleEngine.Signal(type, new EffectEvent(origin.owner(), target.getUUID().toString(), origin, Set.of(), Map.of())));
        }
        DamageReceipt hit() {
            target.damageCooldownTime = 0;
            var receipt = MinecraftDamageExecutor.execute("snapshot-" + UUID.randomUUID(), target, helper.getLevel().damageSources().generic(), 10, false);
            helper.assertTrue(runtime.failure().isEmpty(), "Snapshot runtime failure: " + runtime.failure());
            return receipt;
        }
        @Override public void close() { if (runtime != null) runtime.close(); owner.discard(); target.discard(); }
    }
    private static void near(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-4, label + ": expected " + expected + ", got " + actual);
    }
    @GameCase(environment = "chorus_gametest:snapshot") public void delayedNativeHitRetainsExpiredSourceBonusAndUsesCurrentTargetDefense(GameTestHelper helper) throws Exception {
        var test = new Harness(helper);
        try { test.capture(); test.runtime.unbind("attack"); test.signal("test:mark"); test.signal("test:weaken"); }
        catch (Throwable error) { test.close(); throw error; }
        helper.runAfterDelay(4, () -> {
            try (test) {
                helper.assertTrue(test.runtime.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:empower")), "Source bonus has not expired");
                var receipt = test.hit();
                near(helper, receipt.outgoing().orElseThrow().output().value(), 13.5, "frozen source + current target MAX");
                near(helper, receipt.defense().orElseThrow().output().value(), 20.25, "current target vulnerability");
                near(helper, test.target.getHealth(), 79.75, "actual delayed damage");
                helper.assertValueEqual(test.snapshot.command(test.target.getUUID().toString()).source(), test.origin, "original weapon source");
                helper.succeed();
            }
        });
    }
    @GameCase public void onHitBonusAcquiredAfterLaunchParticipatesInTheSameMaxGroup(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper)) {
            test.capture(); test.signal("test:live"); test.signal("test:mark");
            var receipt = test.hit();
            near(helper, receipt.outgoing().orElseThrow().output().value(), 14, "MAX of frozen 20%, deferred 35%, and live 40%");
            near(helper, test.target.getHealth(), 86, "actual live damage");
            helper.assertValueEqual(receipt.outgoing().orElseThrow().trace().contributions().size(), 3, "each distinct contribution once");
        }
        helper.succeed();
    }
    @GameCase public void capturedAttackKeepsOldProfileAcrossRuntimeReplacement(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper)) {
            test.capture(); test.runtime.close(); test.program = load(true); test.install(false);
            test.signal("test:mark"); test.signal("test:weaken"); var receipt = test.hit();
            helper.assertValueEqual(receipt.outgoing().orElseThrow().trace().version(), "test-snapshot-v1", "pinned outgoing version");
            helper.assertValueEqual(receipt.defense().orElseThrow().trace().version(), "test-snapshot-v2", "current defensive version");
            near(helper, receipt.outgoing().orElseThrow().output().value(), 13.5, "old MAX profile, not replacement PRODUCT");
            near(helper, test.target.getHealth(), 79.75, "old snapshot executes in replacement runtime");
        }
        helper.succeed();
    }
}
