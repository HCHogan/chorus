package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

public class ScheduledEffectsGameTest {
    private static CompiledEffects program(String name) throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(ScheduledEffectsGameTest.class.getResourceAsStream("/effects/" + name + ".json")), StandardCharsets.UTF_8)) {
            return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }
    private static EffectSource source(LivingEntity target, String bundle) {
        return new EffectSource("scheduled", bundle, target.getUUID().toString(), new BuffInstance.Origin(target.getUUID().toString(), "scheduled", "test-weapon", ""), Set.of());
    }
    private static RuleEngine.Signal input(EffectSource source, String type, int tier) {
        return new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(), Map.of("tier", new Measure(tier, Unit.COUNT))));
    }
    private static MinecraftWorldActions world(GameTestHelper helper, LivingEntity target) {
        return new MinecraftWorldActions(helper.getLevel(), id -> id.equals(target.getUUID().toString()) ? target : null,
                _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
    }
    @GameCase public void jsonPeriodicBuffRefreshPauseResumeAndRemovalControlRealHealing(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(1);
        var source = source(target, "test:periodic"); var receipts = new ArrayList<HealingReceipt>(); var world = world(helper, target);
        var session = new EffectSession(program("periodic").engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1),
                EffectState.empty().withSource(source), request -> { var result = (HealingReceipt) world.apply(request); receipts.add(result); return result; });
        session.start(0, input(source, "test:apply", 1)); session.start(50_000, input(source, "test:apply", 2));
        session.start(70_001, input(source, "test:noop", 1)); near(helper, target.getHealth(), 3, "current tier after refresh");
        session.start(80_000, input(source, "chorus:weapon_stowed", 1)); session.start(200_000, input(source, "test:noop", 1));
        near(helper, target.getHealth(), 3, "paused healing");
        session.start(200_000, input(source, "chorus:weapon_drawn", 1));
        session.start(260_002, input(source, "test:noop", 1)); near(helper, target.getHealth(), 5, "resumed remaining delay");
        session.start(270_000, input(source, "test:stop", 1)); session.start(500_000, input(source, "test:noop", 1));
        helper.assertValueEqual(receipts.size(), 2, "two real periodic healing commands");
        helper.assertTrue(session.state().engine().domain().timers().isEmpty(), "Removed buff retained a timer");
        helper.succeed();
    }
    @GameCase public void curePvpTimingUsesRuleEnvironmentAndCooldown(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(1);
        var source = source(target, "chorus_d2:cure"); var receipts = new ArrayList<HealingReceipt>(); var world = world(helper, target);
        var session = new EffectSession(program("cure").engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1),
                EffectState.empty().withSource(source).withMode(EffectState.Mode.PVP), request -> { var result = (HealingReceipt) world.apply(request); receipts.add(result); return result; });
        session.start(0, input(source, "chorus:cure_requested", 1)); session.start(50_000, input(source, "chorus:cure_requested", 3));
        near(helper, target.getHealth(), 2.5, "first PvP pulse against a nonplayer target");
        session.start(100_000, input(source, "test:noop", 1)); near(helper, target.getHealth(), 4, "second PvP pulse");
        session.start(500_000, input(source, "chorus:cure_requested", 3));
        helper.assertValueEqual(receipts.size(), 2, "blocked activations did not schedule more healing");
        session.start(1_000_000, input(source, "chorus:cure_requested", 1)); session.start(1_100_000, input(source, "test:noop", 1));
        near(helper, target.getHealth(), 7, "new activation at cooldown boundary"); helper.assertValueEqual(receipts.size(), 4, "second activation pulses");
        helper.succeed();
    }
    // One long-lived runtime in a separate dimension, isolated from other GameTests' installations.
    @GameCase(dimension = "minecraft:the_end", maxTicks = 85)
    public void serverTicksExecuteJsonCureRecoveryAndResourceProfiles(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setNoGravity(true); target.setHealth(1);
        var source = source(target, "chorus_d2:cure"); var receipts = new ArrayList<HealingReceipt>(); var times = new ArrayList<Long>();
        var recoverySource = new EffectSource("recovery", "test:recovery_inputs", source.holder(), source.origin(), Set.of());
        var resourceSource = new EffectSource("resources", "test:resources", source.holder(), source.origin(), Set.of());
        var cure = program("cure").program(); var recovery = program("restoration").program(); var resources = program("resource_regeneration").program();
        var buffs = new ArrayList<>(cure.buffs()); buffs.addAll(recovery.buffs()); buffs.addAll(resources.buffs());
        var bundles = new ArrayList<>(cure.bundles()); bundles.addAll(recovery.bundles()); bundles.addAll(resources.bundles());
        var combined = new CompiledEffects(new EffectProgram(cure.version(), buffs, bundles, resources.profiles(), Optional.empty(), resources.resources()));
        final long[] recoveryStart = new long[1]; final long[] resourceStart = new long[1]; final int[] priorHeals = new int[1];
        var world = world(helper, target); final MinecraftEffectRuntime[] holder = new MinecraftEffectRuntime[1];
        var runtime = MinecraftEffectRuntime.install(helper.getLevel(), combined, EffectState.empty().withSource(source).withSource(recoverySource),
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                    times.add(holder[0].state().engine().timeMicros()); var result = (HealingReceipt) world.apply(request); receipts.add(result); return result;
                }, MinecraftEffectRuntime::nativeSource);
        holder[0] = runtime;
        try { runtime.start(input(source, "chorus:cure_requested", 1)); }
        catch (RuntimeException | Error error) { runtime.close(); throw error; }
        helper.runAfterDelay(4, () -> {
            try {
                near(helper, target.getHealth(), 7, "real tick healing"); helper.assertValueEqual(times, List.of(50_000L, 100_000L), "exact logical pulse times");
                runtime.start(input(source, "chorus:cure_requested", 3)); target.setHealth(3);
            } catch (RuntimeException | Error error) { runtime.close(); throw error; }
        });
        helper.runAfterDelay(24, () -> {
            try {
                near(helper, target.getHealth(), 3, "no healing from blocked activation"); helper.assertValueEqual(receipts.size(), 2, "only two accepted pulses");
                helper.assertTrue(runtime.state().engine().domain().buffs().instances().isEmpty(), "Real ticks did not expire cooldown");
                helper.assertTrue(runtime.state().engine().domain().timers().isEmpty(), "Completed timer retained");
                helper.assertTrue(runtime.failure().isEmpty(), "Scheduled runtime failed");
                recoveryStart[0] = runtime.nowMicros();
                runtime.start(new RuleEngine.Signal("test:restoration", new EffectEvent(recoverySource.holder(), recoverySource.holder(), recoverySource.origin(), Set.of(),
                        Map.of("tier", new Measure(1, Unit.COUNT), "duration", new Measure(.070001, Unit.SECOND)))));
            } catch (RuntimeException | Error error) { runtime.close(); throw error; }
        });
        helper.runAfterDelay(28, () -> {
            try {
                near(helper, target.getHealth(), 3 + .070001 * 3.5, "continuous recovery from real ticks");
                helper.assertValueEqual(times.subList(2, times.size()), List.of(recoveryStart[0] + 50_000, recoveryStart[0] + 70_001), "real tick residual settlement");
                helper.assertTrue(runtime.state().engine().domain().buffs().instances().isEmpty(), "Recovery did not expire");
                helper.assertTrue(runtime.failure().isEmpty(), "Recovery runtime failed");
                target.setHealth(1); resourceStart[0] = runtime.nowMicros(); priorHeals[0] = receipts.size();
                runtime.bind(resourceSource); runtime.start(input(resourceSource, "test:boost", 1));
                helper.assertValueEqual(runtime.state().engine().domain().resources().get(new ResourceState.Key(source.holder(), "test:energy")).value(), 0.0, "source binding initializes resource");
            } catch (RuntimeException | Error error) { runtime.close(); throw error; }
        });
        helper.runAfterDelay(68, () -> {
            try (runtime) {
                near(helper, target.getHealth(), 2, "resource threshold triggered real healing");
                helper.assertValueEqual(times.subList(priorHeals[0], times.size()), List.of(resourceStart[0] + 1_500_000), "profile boost and expiry determine exact threshold time");
                var key = new ResourceState.Key(source.holder(), "test:energy");
                double before = runtime.state().engine().domain().resources().get(key).value();
                helper.assertTrue(before >= 1 && before < 2, "Unexpected real-tick resource value " + before);
                runtime.start(input(resourceSource, "test:spend", 1)); near(helper, target.getHealth(), 3, "successful cost permits world action");
                near(helper, runtime.state().engine().domain().resources().get(key).value(), before - 1, "paid cost");
                runtime.start(input(resourceSource, "test:spend", 1)); near(helper, target.getHealth(), 3, "failed cost skips world action");
                helper.assertTrue(runtime.failure().isEmpty(), "Resource runtime failed"); helper.succeed();
            }
        });
    }
}
