package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
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
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Synthetic phase ordering and explicit host measurements; no claim about Destiny's distance curve. */
public class ImpactSnapshotGameTest {
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static LivingEntity cow(GameTestHelper h, int x) {
        var entity = h.spawnWithNoFreeWill(EntityTypes.COW, x, 40, 4); entity.setNoGravity(true);
        entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        entity.setHealth(100); return entity;
    }
    private static CompiledEffects load() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(ImpactSnapshotGameTest.class.getResourceAsStream("/effects/impact_snapshot.json")), StandardCharsets.UTF_8)) {
            return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }
    private static EffectState grant(CompiledEffects program, EffectState state, EffectSource source, String buff, String target) {
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff(buff), target, target, source.origin(), 1, 1, program.buff(buff).timer().durationMicros()).store());
    }
    private static EffectSource source(LivingEntity owner) {
        return new EffectSource("attack", "test:impact_attack", id(owner), new BuffInstance.Origin(id(owner), "impact-test", "weapon", ""), Set.of());
    }
    private static EffectState launch(CompiledEffects program, EffectSource source) {
        return grant(program, EffectState.empty().withSource(source)
                .withSource(new EffectSource("observer", "test:impact_observer", source.holder(), source.origin(), Set.of())), source, "test:empower", source.holder());
    }
    private static double logged(MinecraftEffectRuntime runtime, LivingEntity target, String field) {
        return runtime.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.definition().id().equals("test:impact_log") && b.key().holder().equals(id(target)))
                .findFirst().orElseThrow().components().numbers().get(field);
    }
    private static MinecraftWorldActions world(GameTestHelper h) {
        var level = h.getLevel();
        return new MinecraftWorldActions(level, ref -> level.getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                _ -> level.damageSources().generic(), (_, _) -> true, _ -> {});
    }
    @GameCase(environment = "chorus_gametest:impact_snapshot")
    public void delayedExplosionUsesFrozenBonusesAndPerTargetDistancesAfterQueryMovement(GameTestHelper h) throws Exception {
        var owner = cow(h, 2); var center = cow(h, 4); var near = cow(h, 7); var far = cow(h, 9); var edge = cow(h, 11); var outside = cow(h, 12);
        var entities = List.of(owner, center, near, far, edge, outside); var runtime = new MinecraftEffectRuntime[1];
        var commands = new ArrayList<DamageCommand>(); var receipts = new ArrayList<DamageReceipt>();
        try {
            var program = load(); var source = source(owner); var world = world(h);
            runtime[0] = MinecraftEffectRuntime.install(h.getLevel(), program, launch(program, source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (result instanceof TargetQuery.Result) far.setPos(far.getX(), far.getY() + 30, far.getZ());
                if (request.command() instanceof DamageCommand damage) { commands.add(damage); receipts.add((DamageReceipt) result); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
            runtime[0].start(new RuleEngine.Signal("test:burst", new EffectEvent(id(owner), id(center), source.origin(), Set.of(), Map.of())));
            runtime[0].unbind(source.instance());
            h.runAfterDelay(4, () -> {
                try {
                    h.assertTrue(runtime[0].failure().isEmpty(), "Impact runtime failed: " + runtime[0].failure());
                    h.assertTrue(runtime[0].state().idle(), "Delayed impact did not settle");
                    h.assertValueEqual(commands.size(), 4, "four selected targets");
                    h.assertValueEqual(commands.stream().map(c -> c.impact().number("distance", Unit.METER).value()).toList(), List.of(0d, 3d, 5d, 7d), "query-time distances");
                    h.assertValueEqual(commands.stream().map(DamageCommand::snapshot).distinct().count(), 1L, "one shared launch snapshot");
                    h.assertTrue(commands.stream().allMatch(c -> c.amount() == 10 && c.source().equals(source.origin())), "frozen attack metadata changed");
                    h.assertTrue(runtime[0].state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:empower")), "empower should have expired before impact");
                    near(h, center.getHealth(), 82, "center: (10 + 5) * 1.2"); near(h, near.getHealth(), 82, "inner boundary");
                    near(h, far.getHealth(), 91, "captured five-meter distance after movement"); near(h, edge.getHealth(), 100, "outer edge zero");
                    near(h, outside.getHealth(), 100, "outside query"); near(h, owner.getHealth(), 100, "excluded owner");
                    near(h, logged(runtime[0], far, "distance"), 5, "impact input on actual hit fact");
                    near(h, logged(runtime[0], far, "actual"), 9, "actual receipt measurement");
                    near(h, receipts.get(2).outgoing().orElseThrow().output().value(), 9, "profile trace includes staged falloff");
                    h.succeed();
                } finally { runtime[0].close(); entities.forEach(LivingEntity::discard); }
            });
        } catch (Throwable error) { if (runtime[0] != null) runtime[0].close(); entities.forEach(LivingEntity::discard); throw error; }
    }
    @GameCase public void nativeSnapshotUsesExplicitImpactForLiveMaxDefenseAndReceiptFacts(GameTestHelper h) throws Exception {
        var owner = cow(h, 2); var target = cow(h, 4); var program = load(); var source = source(owner); var snapshot = new DamageSnapshot[1];
        var state = grant(program, launch(program, source), source, "test:vulnerability", id(target));
        var impact = new ImpactData(Map.of("distance", new Measure(5, Unit.METER), "effective_damage", new Measure(999, Unit.DAMAGE)));
        try (var runtime = MinecraftEffectRuntime.install(h.getLevel(), program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world(h),
                (victim, damage, amount) -> snapshot[0] == null ? MinecraftEffectRuntime.nativeSource(victim, damage, amount) : snapshot[0].command(id(victim), impact))) {
            snapshot[0] = runtime.captureDamage(new DamageCommand("unknown", source.origin(), 10, "minecraft:generic", Set.of("test:impact"), Set.of(), false, Optional.of("test:attack_damage")));
            runtime.unbind(source.instance()); runtime.bind(new EffectSource("live", "test:live_impact", id(owner), source.origin(), Set.of()));
            var receipt = MinecraftDamageExecutor.execute("explicit-impact", target, h.getLevel().damageSources().generic(), 10, false);
            h.assertTrue(runtime.failure().isEmpty(), "Native impact failed: " + runtime.failure());
            near(h, receipt.outgoing().orElseThrow().output().value(), 10.5, "frozen flat, MAX(.2,.4), then .5 falloff");
            near(h, receipt.defense().orElseThrow().output().value(), 15.75, "current impact-conditioned vulnerability");
            near(h, target.getHealth(), 84.25, "actual native HP");
            near(h, logged(runtime, target, "distance"), 5, "explicit host distance, not inferred two-meter separation");
            near(h, logged(runtime, target, "actual"), 15.75, "actual damage cannot be overwritten by impact key");
        } finally { owner.discard(); target.discard(); }
        h.succeed();
    }
}
