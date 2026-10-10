package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Actual server entities, private collection, live collector stats and failure after committed reward. */
public class PickupGameTest {
    static String id(Entity e) { return e.getUUID().toString(); }
    static JsonObject spec(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(2).getAsJsonObject().getAsJsonObject("pickup"); }
    static class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final LivingEntity collector, outsider;
        final MinecraftEffectRuntime runtime; final MinecraftWorldActions world; final EffectSource source;
        final List<EffectObject> objects = new ArrayList<>(); final List<HealingCommand> heals = new ArrayList<>(); final List<Action.CueCommand> cues = new ArrayList<>();
        final Map<BlockPos, BlockState> blocks = new HashMap<>(); boolean failAfterHealing;
        Harness(GameTestHelper h) throws Exception { this(h, _ -> {}); }
        Harness(GameTestHelper h, Consumer<JsonObject> edit) throws Exception {
            this.h = h; owner = h.makeMockServerPlayerInLevel(); owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var base = h.absoluteVec(new Vec3(2.5, 40, 2.5));
            // Keep the entire scenario in one already-loaded chunk.
            owner.setPos((Math.floor(base.x / 16) * 16) + 5.5, base.y, (Math.floor(base.z / 16) * 16) + 5.5); owner.setNoGravity(true);
            collector = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 40, 2); outsider = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 40, 2);
            collector.setPos(owner.position().add(3, 0, 0)); outsider.setPos(owner.position());
            for (var e : List.of(owner, collector, outsider)) { e.setNoGravity(true); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.setHealth(10); }
            CompiledEffects program;
            try (var r = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/pickup.json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(r).getAsJsonObject(); edit.accept(data); program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
            }
            source = new EffectSource("producer", "test:pickup", id(owner), new BuffInstance.Origin(id(owner), "producer", "", ""), Set.of());
            var initial = EffectState.empty().withSource(source);
            for (var e : List.of(owner, collector, outsider)) initial = initial.withSource(new EffectSource("listener/" + id(e), "test:collector", id(e), new BuffInstance.Origin(id(e), "listener", "", ""), Set.of()));
            world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity living ? living : null,
                    _ -> owner.damageSources().generic(), (_, _) -> true, cues::add);
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, initial, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (result instanceof WorldPickup.Receipt receipt && receipt.entity().isPresent()) objects.add((EffectObject) h.getLevel().getEntity(UUID.fromString(receipt.entity().orElseThrow())));
                if (request.command() instanceof HealingCommand heal) { heals.add(heal); if (failAfterHealing) throw new IllegalStateException("Injected unknown pickup reward"); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        void spawn() { runtime.start(new RuleEngine.Signal("test:spawn", new EffectEvent(id(owner), id(collector), source.origin(), Set.of(), Map.of()))); }
        void magnet(LivingEntity who) { runtime.bind(new EffectSource("magnet/" + id(who), "test:magnet", id(who), new BuffInstance.Origin(id(who), "magnet", "", ""), Set.of())); }
        void finish(int ticks, Runnable checks) {
            h.runAfterDelay(ticks, () -> { try { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Pickup runtime failed: " + runtime.failure()); checks.run(); h.succeed(); } finally { close(); } });
        }
        @Override public void close() { runtime.close(); objects.forEach(Entity::discard); collector.discard(); outsider.discard(); owner.discard(); blocks.forEach((p, b) -> h.getLevel().setBlockAndUpdate(p, b)); }
    }
    @GameCase(environment="chorus_gametest:pickup_private", maxTicks=60)
    public void onlyNamedCollectorReceivesEachUnitAfterProducerUnequips(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.spawn(); t.spawn(); t.runtime.unbind("producer");
            h.runAfterDelay(4, () -> { h.assertTrue(t.heals.isEmpty() && t.cues.isEmpty(), "producer or outsider stole private units"); t.collector.setPos(t.objects.getFirst().position()); });
            t.finish(10, () -> {
                near(h, t.collector.getHealth(), 24, "two independent rewards"); near(h, t.owner.getHealth(), 10, "producer did not collect"); near(h, t.outsider.getHealth(), 10, "outsider did not collect");
                h.assertValueEqual(t.heals.size(), 2, "each unit rewarded once"); h.assertValueEqual(t.cues.size(), 2, "one pickup fact per unit");
                h.assertTrue(t.cues.stream().allMatch(c -> c.target().equals(id(t.collector))), "only collector listeners qualify");
                h.assertTrue(t.heals.stream().allMatch(c -> c.source().equals(t.source.origin())), "producer attribution lost");
                h.assertTrue(t.objects.stream().allMatch(Entity::isRemoved), "collected objects remain");
                t.objects.forEach(EffectObject::tick); h.assertValueEqual(t.heals.size(), 2, "consumed units replayed");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:pickup_attraction", maxTicks=60)
    public void attractionReadsCurrentCollectorModifiersInsteadOfProducerSnapshot(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.magnet(t.owner); t.spawn(); var object = t.objects.getFirst(); var original = object.position();
            h.runAfterDelay(4, () -> { h.assertValueEqual(object.position(), original, "producer magnet affected recipient"); t.magnet(t.collector); });
            t.finish(20, () -> { near(h, t.collector.getHealth(), 17, "late collector magnet attracts at six meters per second"); h.assertTrue(object.isRemoved(), "attracted object did not collect"); h.assertValueEqual(t.cues.size(), 1, "one collected fact"); });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:pickup_expiry", maxTicks=60)
    public void expiryRunsOnceWithoutAwardOrPickupFact(GameTestHelper h) throws Exception {
        var t = new Harness(h, data -> spec(data).getAsJsonObject("lifetime").addProperty("value", 0.1));
        try { t.spawn(); t.finish(8, () -> {
            h.assertTrue(t.objects.getFirst().isRemoved() && t.heals.isEmpty(), "expiry awarded energy");
            h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(), List.of("test:expired"), "expiry is not pickup");
            t.objects.getFirst().tick(); h.assertValueEqual(t.cues.size(), 1, "expiry replayed");
        }); } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:pickup_wall", maxTicks=60)
    public void liveAttractionCannotCrossSolidWallAndRemovalStopsMovement(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var pos = BlockPos.containing(t.owner.position().add(1, 0, 0)); t.blocks.put(pos, h.getLevel().getBlockState(pos)); h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            t.magnet(t.collector); t.spawn(); var object = t.objects.getFirst();
            h.runAfterDelay(8, () -> {
                h.assertTrue(object.getX() < pos.getX() && t.heals.isEmpty(), "pickup crossed solid terrain");
                t.runtime.unbind("magnet/" + id(t.collector)); h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            });
            t.finish(16, () -> { h.assertTrue(!object.isRemoved() && object.getX() < pos.getX() && t.heals.isEmpty(), "removed magnet stayed frozen on pickup"); });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase public void missingGeometryRecipientAndUnloadedChunkRejectSpawnAndClosedRuntimeDiscardsObjects(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.spawn(); var original = t.objects.getFirst().pickup().orElseThrow(); var far = new WorldPosition(h.getLevel().dimension().identifier().toString(), 1_000_001, 80, 1_000_001);
            var points = List.of(Optional.<WorldPosition>empty(), Optional.of(new WorldPosition("other:world", 1, 40, 1)), Optional.of(far), original.position());
            var outcomes = List.of(WorldPickup.Outcome.MISSING_POSITION, WorldPickup.Outcome.WRONG_DIMENSION, WorldPickup.Outcome.UNLOADED, WorldPickup.Outcome.MISSING_RECIPIENT);
            for (int i=0; i<points.size(); i++) {
                var spawn = new WorldPickup.Spawn(points.get(i), original.kind(), i==3 ? UUID.randomUUID().toString() : original.recipient(), original.origin(), original.parameters(), original.continuation(), original.contactSlot());
                var receipt = (WorldPickup.Receipt) t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(999, i, 0), spawn));
                h.assertValueEqual(receipt.outcome(), outcomes.get(i), "explicit rejection reason"); h.assertTrue(receipt.entity().isEmpty(), "rejection spawned object");
            }
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(1_000_001 >> 4, 1_000_001 >> 4)==null, "pickup loaded missing chunk");
            t.runtime.close(); t.objects.getFirst().tick(); h.assertTrue(t.objects.getFirst().isRemoved() && t.heals.isEmpty(), "closed runtime left executable pickup");
        }
        h.succeed();
    }
    @GameCase(environment="chorus_gametest:pickup_failure", maxTicks=60)
    public void unknownRewardOutcomeConsumesUnitAndNeverReplaysCommittedHealing(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.collector.setPos(t.owner.position()); t.failAfterHealing = true; t.spawn();
            h.runAfterDelay(8, () -> { try {
                h.assertTrue(t.runtime.failure().isPresent() && t.objects.getFirst().isRemoved(), "failed reward left claim available");
                near(h, t.collector.getHealth(), 17, "committed reward retained"); h.assertValueEqual(t.heals.size(), 1, "single attempted reward");
                t.objects.getFirst().tick(); h.assertValueEqual(t.heals.size(), 1, "unknown reward replayed"); h.succeed();
            } finally { t.close(); } });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:pickup_dead", maxTicks=60)
    public void deadRecipientNeitherCollectsExistingUnitNorAcceptsNewSpawn(GameTestHelper h) throws Exception {
        var t = new Harness(h, data -> spec(data).getAsJsonObject("lifetime").addProperty("value", 0.1));
        try {
            t.spawn(); var original = t.objects.getFirst().pickup().orElseThrow(); t.collector.setHealth(0); t.collector.setPos(t.owner.position());
            t.objects.getFirst().tick(); h.assertTrue(t.heals.isEmpty(), "dead recipient collected");
            var receipt = (WorldPickup.Receipt) t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(998, 0, 0), original));
            h.assertValueEqual(receipt.outcome(), WorldPickup.Outcome.MISSING_RECIPIENT, "dead recipient accepted new unit");
            t.finish(8, () -> { h.assertTrue(t.heals.isEmpty() && t.objects.getFirst().isRemoved(), "dead recipient rewarded"); h.assertValueEqual(t.cues.stream().map(Action.CueCommand::cue).toList(), List.of("test:expired"), "dead recipient invented pickup"); });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:pickup_player", maxTicks=60)
    public void nativePlayerCollectsItsOwnPrivateUnit(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.runtime.start(new RuleEngine.Signal("test:spawn", new EffectEvent(id(t.owner), id(t.owner), t.source.origin(), Set.of(), Map.of())));
            t.finish(6, () -> { near(h, t.owner.getHealth(), 17, "server player reward"); h.assertTrue(t.objects.getFirst().isRemoved(), "player collection failed"); h.assertValueEqual(t.cues.stream().map(Action.CueCommand::target).toList(), List.of(id(t.owner)), "actual player fact"); });
        } catch (Throwable error) { t.close(); throw error; }
    }
}
