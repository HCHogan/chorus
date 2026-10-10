package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ConstructGameTest {
    public static CompiledEffects program() { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("construct")).getOrThrow(); }
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final LivingEntity owner; final EffectSource source;
        final MinecraftWorldActions world; final MinecraftEffectRuntime runtime;
        final List<WorldConstruct.Receipt> receipts = new ArrayList<>(); final List<Action.CueCommand> cues = new ArrayList<>();
        WorldPosition point; boolean fail;
        Harness(GameTestHelper h) {
            this.h = h; owner = h.spawnWithNoFreeWill(EntityTypes.COW, 1, 40, 1); owner.setNoGravity(true);
            String id = owner.getUUID().toString(); source = new EffectSource("source", "test:construct", id, new BuffInstance.Origin(id, "cast", "", "test:ability"), Set.of());
            var pos = h.absoluteVec(new Vec3(3.5, 40, 3.5)); point = new WorldPosition(h.getLevel().dimension().identifier().toString(), pos.x, pos.y, pos.z);
            world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, cues::add);
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program(), EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof PositionQuery q) return new PositionQuery.Result(q, Optional.of(point));
                var result = world.apply(request);
                if (result instanceof WorldConstruct.Receipt receipt) { receipts.add(receipt); if (fail) throw new IllegalStateException("Unknown construct spawn outcome"); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        EffectState state() { return runtime.state().engine().domain(); }
        void cast(double seconds) { runtime.start(new RuleEngine.Signal("test:spawn", new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(),
                Map.of("health", new Measure(150, Unit.DAMAGE), "duration", new Measure(seconds, Unit.SECOND))))); }
        EffectConstruct construct() { return (EffectConstruct) h.getLevel().getEntity(UUID.fromString(receipts.getLast().entity().orElseThrow())); }
        WorldConstruct.Spawn command(Optional<WorldPosition> at, WorldConstruct.Parameters parameters) {
            return new WorldConstruct.Spawn(at, "test:turret", source.origin(), parameters, Set.of("test:damageable"), "test-1");
        }
        WorldConstruct.Receipt spawn(WorldConstruct.Spawn command) { return (WorldConstruct.Receipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), command)); }
        boolean behavior(EffectConstruct target) { return state().buffs().instances().values().stream().anyMatch(b -> b.key().holder().equals(target.getUUID().toString())); }
        void healthy() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Construct runtime failed: " + runtime.failure()); }
        void at(int ticks, Runnable check) { h.runAfterDelay(ticks, () -> { try { healthy(); check.run(); } catch (RuntimeException | Error e) { close(); throw e; } }); }
        @Override public void close() { runtime.close(); owner.discard(); }
    }
    @GameCase(environment="chorus_gametest:construct_damage", maxTicks=30)
    public void realHealthDeathAndTargetObservationStopOnlyTheDestroyedConstruct(GameTestHelper h) {
        var t = new Harness(h);
        try {
            t.cast(1); var first = t.construct(); t.point = new WorldPosition(t.point.dimension(), t.point.x() + 2, t.point.y(), t.point.z()); t.cast(1); var second = t.construct();
            near(h, first.getHealth(), 150, "construct health"); near(h, first.getBbWidth(), .5, "physical width"); near(h, first.getBbHeight(), .75, "physical height");
            h.assertValueEqual(first.construct().orElseThrow().origin(), t.source.origin(), "original caster credit");
            var query = new EntityQuery(first.getUUID().toString());
            var observed = (EntityQuery.Result) t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), query));
            h.assertTrue(observed.available().alive() && observed.available().entityTags().contains("chorus:construct"), "construct missing from ordinary entity observation");
            h.assertTrue(first.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(t.owner), 25), "native hit rejected"); near(h, first.getHealth(), 125, "real health loss");
            t.at(3, () -> {
                h.assertValueEqual(t.cues.size(), 2, "each construct fired once");
                h.assertTrue(first.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(t.owner), 1000), "lethal hit rejected");
                h.assertTrue(!first.isAlive() && !t.behavior(first), "native death did not remove behavior"); h.assertTrue(t.behavior(second), "other construct lost its behavior");
            });
            t.at(9, () -> { h.assertTrue(first.isRemoved(), "dead construct not discarded"); h.assertValueEqual(t.cues.size(), 5, "destroyed construct kept firing"); t.close(); h.succeed(); });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:construct_lifetime", maxTicks=25)
    public void ownerRemovalDoesNotEndLifetimeAndNoPulseRunsAtTheExpiryBoundary(GameTestHelper h) {
        var t = new Harness(h);
        try {
            t.cast(.5); var entity = t.construct(); t.runtime.unbind("source"); t.owner.discard();
            t.at(9, () -> { h.assertTrue(entity.isAlive(), "owner removal killed construct"); h.assertValueEqual(t.cues.size(), 4, "independent timer pulses"); });
            t.at(12, () -> { h.assertTrue(entity.isRemoved() && !entity.isAlive(), "expired construct remained active"); h.assertValueEqual(t.cues.size(), 4, "pulse at deadline"); h.assertTrue(t.state().timers().isEmpty(), "expired behavior left timers"); t.close(); h.succeed(); });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:construct_rejected")
    public void unavailableTerrainFullBodyObstructionAndNativeLimitsRejectWithoutLoadingChunks(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var p = new WorldConstruct.Parameters(150, .5, .75, 500_000);
            h.assertValueEqual(t.spawn(t.command(Optional.empty(), p)).outcome(), WorldConstruct.Outcome.MISSING_POSITION, "missing position");
            h.assertValueEqual(t.spawn(t.command(Optional.of(new WorldPosition("minecraft:the_nether", 0, 40, 0)), p)).outcome(), WorldConstruct.Outcome.WRONG_DIMENSION, "wrong dimension");
            h.assertValueEqual(t.spawn(t.command(Optional.of(t.point), new WorldConstruct.Parameters(150, 17, 1, 500_000))).outcome(), WorldConstruct.Outcome.UNSUPPORTED_PARAMETERS, "native query budget");
            h.assertValueEqual(t.spawn(t.command(Optional.of(t.point), new WorldConstruct.Parameters(1e9, 1, 1, 500_000))).outcome(), WorldConstruct.Outcome.UNSUPPORTED_PARAMETERS, "silently clamped health");
            var far = new WorldPosition(t.point.dimension(), 1_000_008, 40, 1_000_008); int chunk = 1_000_008 >> 4;
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(chunk, chunk) == null, "fixture chunk loaded");
            h.assertValueEqual(t.spawn(t.command(Optional.of(far), p)).outcome(), WorldConstruct.Outcome.UNLOADED, "unloaded destination");
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(chunk, chunk) == null, "spawn loaded unknown terrain");
            var block = BlockPos.containing(t.point.x(), t.point.y() + 1, t.point.z()); var old = h.getLevel().getBlockState(block);
            try { h.getLevel().setBlockAndUpdate(block, Blocks.STONE.defaultBlockState()); h.assertValueEqual(t.spawn(t.command(Optional.of(t.point), new WorldConstruct.Parameters(150, .5, 2, 500_000))).outcome(), WorldConstruct.Outcome.OBSTRUCTED, "upper body intersected block"); }
            finally { h.getLevel().setBlockAndUpdate(block, old); }
            t.healthy();
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:construct_unknown", maxTicks=15)
    public void unknownSpawnCompletionRetainsWorldMutationWithoutRetryOrBehavior(GameTestHelper h) {
        var t = new Harness(h); t.fail = true;
        try {
            try { t.cast(1); throw new AssertionError("Unknown spawn outcome was accepted"); } catch (IllegalStateException expected) {}
            h.assertValueEqual(t.receipts.size(), 1, "spawn count"); var entity = t.construct();
            h.assertTrue(t.runtime.failure().isPresent() && !t.behavior(entity), "unknown spawn ran dependent behavior");
            h.assertTrue(t.state().buffs().instances().values().stream().anyMatch(b -> b.key().definition().equals("test:paid")), "prior state rolled back");
            h.runAfterDelay(3, () -> { try (t) { h.assertTrue(entity.isRemoved(), "failed runtime left an active construct"); h.assertValueEqual(t.receipts.size(), 1, "unknown spawn replayed"); h.succeed(); } });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:construct_close")
    public void runtimeCloseImmediatelyDiscardsOnlyItsOwnConstructs(GameTestHelper h) {
        try (var t = new Harness(h)) {
            t.cast(1); var entity = t.construct(); t.runtime.close();
            h.assertTrue(entity.isRemoved(), "runtime close left a construct"); h.assertTrue(t.owner.isAlive(), "runtime cleanup removed caster");
        } h.succeed();
    }
}
