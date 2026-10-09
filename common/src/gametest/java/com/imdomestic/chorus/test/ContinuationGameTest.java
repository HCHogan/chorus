package com.imdomestic.chorus.test;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** These tests execute capture and delayed bodies entirely through JSON; the host only supplies ordinary world bindings. */
public class ContinuationGameTest {
    private static String id(LivingEntity entity) { return entity.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final List<LivingEntity> entities = new ArrayList<>();
        final List<DamageReceipt> receipts = new ArrayList<>();
        final List<Long> damageTimes = new ArrayList<>();
        final List<RuleEngine.OperationId> operations = new ArrayList<>();
        final List<TargetQuery> queries = new ArrayList<>();
        final LivingEntity owner, target, neighbor;
        final EffectSource source;
        MinecraftEffectRuntime runtime;
        Harness(GameTestHelper helper) throws Exception {
            this.helper = helper; owner = cow(2, 2); owner.setHealth(40); target = cow(4, 4); neighbor = cow(5, 4);
            source = new EffectSource("launcher", "test:launcher", id(owner), new BuffInstance.Origin(id(owner), "launch", "test-weapon", ""), Set.of());
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(ContinuationGameTest.class.getResourceAsStream("/effects/delayed_snapshot.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var state = EffectState.empty().withSource(source).withSource(new EffectSource("controls", "test:controls", id(owner), source.origin(), Set.of()));
            state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:empower"), id(owner), id(owner), source.origin(), 1, 1, 50_000).store());
            var type = helper.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(helper.getLevel(), reference -> helper.getLevel().getEntity(UUID.fromString(reference)) instanceof LivingEntity living ? living : null,
                    _ -> new DamageSource(type, owner), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(helper.getLevel(), program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof TargetQuery query) queries.add(query);
                if (request.command() instanceof DamageCommand damage) {
                    helper.assertTrue(damage.snapshot().isPresent(), "DSL lost its captured attack");
                    helper.assertValueEqual(damage.source(), source.origin(), "original delayed origin");
                    damageTimes.add(runtime.state().engine().timeMicros()); operations.add(request.id());
                }
                var result = world.apply(request); if (result instanceof DamageReceipt receipt) receipts.add(receipt); return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity cow(int x, int z) {
            var entity = helper.spawnWithNoFreeWill(EntityTypes.COW, x, 40, z); entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); entity.setHealth(100); entities.add(entity); return entity;
        }
        void fire(String type) { runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent(id(owner), id(target), source.origin(), Set.of(), Map.of()))); }
        void settled() {
            helper.assertTrue(runtime.failure().isEmpty(), "Delayed execution failed: " + runtime.failure());
            helper.assertTrue(runtime.state().idle(), "Delayed boundary did not finish");
            helper.assertTrue(runtime.state().engine().domain().timers().isEmpty(), "Delayed job was retained after execution");
        }
        @Override public void close() { if (runtime != null) runtime.close(); entities.forEach(LivingEntity::discard); }
    }
    private static void near(GameTestHelper h, double actual, double expected, String field) { h.assertTrue(Math.abs(actual - expected) < 1e-4, field + ": expected " + expected + ", got " + actual); }
    @GameCase(environment = "chorus_gametest:continuation_damage")
    public void jsonCaptureSurvivesDetachAndExpiryThenUsesActualDamageForHealing(GameTestHelper helper) throws Exception {
        var test = new Harness(helper);
        try { test.fire("fire"); test.runtime.unbind("launcher"); test.fire("weaken"); }
        catch (Throwable error) { test.close(); throw error; }
        helper.runAfterDelay(4, () -> {
            try (test) {
                test.settled(); helper.assertValueEqual(test.damageTimes, List.of(100_000L), "logical impact time");
                near(helper, test.target.getHealth(), 82, "12 frozen attack times 1.5 live vulnerability");
                near(helper, test.owner.getHealth(), 49, "half of actual 18 HP loss");
                helper.assertTrue(test.runtime.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:empower")), "Expired launch buff remains");
                helper.succeed();
            }
        });
    }
    @GameCase(environment = "chorus_gametest:continuation_lifetime")
    public void defaultSourceLifetimeCancelsThePendingBodyWhenUnequipped(GameTestHelper helper) throws Exception {
        var test = new Harness(helper);
        try { test.fire("attached"); test.runtime.unbind("launcher"); }
        catch (Throwable error) { test.close(); throw error; }
        helper.runAfterDelay(4, () -> {
            try (test) {
                test.settled(); helper.assertTrue(test.receipts.isEmpty(), "Cancelled source still hit");
                near(helper, test.target.getHealth(), 100, "no cancelled damage"); near(helper, test.owner.getHealth(), 40, "no cancelled healing"); helper.succeed();
            }
        });
    }
    @GameCase(environment = "chorus_gametest:continuation_area")
    public void delayedAreaQueryUsesTheWorldAtExecutionInsteadOfTheWorldAtLaunch(GameTestHelper helper) throws Exception {
        var test = new Harness(helper); LivingEntity newcomer;
        try {
            test.fire("area"); test.runtime.unbind("launcher");
            helper.assertTrue(test.queries.isEmpty(), "Area queried before delay");
            test.neighbor.setPos(test.neighbor.getX(), test.neighbor.getY() + 30, test.neighbor.getZ()); newcomer = test.cow(5, 4);
        } catch (Throwable error) { test.close(); throw error; }
        helper.runAfterDelay(4, () -> {
            try (test) {
                test.settled(); helper.assertValueEqual(test.queries.size(), 1, "one late query"); helper.assertValueEqual(test.receipts.size(), 2, "current members only");
                near(helper, test.target.getHealth(), 88, "center damage"); near(helper, newcomer.getHealth(), 88, "new arrival damage"); near(helper, test.neighbor.getHealth(), 100, "departed target excluded"); helper.succeed();
            }
        });
    }
    @GameCase(environment = "chorus_gametest:continuation_burst")
    public void nestedDelayedHitsKeepTheirSnapshotAndUseSeparateWorldOperations(GameTestHelper helper) throws Exception {
        var test = new Harness(helper);
        try { test.fire("burst"); test.runtime.unbind("launcher"); }
        catch (Throwable error) { test.close(); throw error; }
        helper.runAfterDelay(5, () -> {
            try (test) {
                test.settled(); helper.assertValueEqual(test.damageTimes, List.of(100_000L, 150_000L), "nested delays use execution time");
                helper.assertValueEqual(test.operations.stream().distinct().count(), 2L, "independent operations");
                near(helper, test.target.getHealth(), 76, "both captured hits apply"); helper.succeed();
            }
        });
    }
}
