package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class SpatialQueryGameTest {
    private static LivingEntity cow(GameTestHelper h, double x, double y, double z) {
        var e = h.spawnWithNoFreeWill(EntityTypes.COW, (int) x, (int) y, (int) z); e.setPos(h.absoluteVec(new Vec3(x, y, z))); e.setHealth(2); return e;
    }
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static MinecraftWorldActions world(GameTestHelper h) {
        return new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
    }
    private static RuleEngine.ActionResult run(MinecraftWorldActions world, RuleEngine.WorldCommand command) {
        return world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), command));
    }
    private static TargetQuery select(LivingEntity center, TargetShape shape, TargetQuery.Anchor target, boolean sight, int limit) {
        return new TargetQuery(new TargetQuery.EntityCenter(id(center), TargetQuery.Anchor.BODY), shape, TargetQuery.Relation.ANY, id(center), false, Set.of(), TargetQuery.Order.NEAREST, OptionalInt.of(limit), target, sight);
    }
    private static List<String> ids(TargetQuery.Result result) { return result.targets().stream().map(TargetQuery.Target::entity).toList(); }
    private static WorldPosition point(GameTestHelper h, double x, double y, double z) {
        var p = h.absoluteVec(new Vec3(x, y, z)); return new WorldPosition(h.getLevel().dimension().identifier().toString(), p.x, p.y, p.z);
    }
    @GameCase public void lineOfSightFiltersBeforeNearestLimitAndCanBeExplicitlyDisabled(GameTestHelper h) {
        var center = cow(h, 2.5, 40, 3.5); var hidden = cow(h, 4.5, 40, 3.5); var visible = cow(h, 2.5, 40, 6);
        var wall = h.absolutePos(new BlockPos(3, 40, 3)); var old = h.getLevel().getBlockState(wall);
        try {
            h.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState()); var world = world(h);
            var limited = (TargetQuery.Result) run(world, select(center, new TargetShape.Sphere(4), TargetQuery.Anchor.BODY, true, 1));
            h.assertValueEqual(ids(limited), List.of(id(visible)), "occluded nearest target must not consume the limit");
            var xray = (TargetQuery.Result) run(world, select(center, new TargetShape.Sphere(4), TargetQuery.Anchor.BODY, false, 1));
            h.assertValueEqual(ids(xray), List.of(id(hidden)), "disabled visibility keeps nearest hidden target");
            h.getLevel().setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            h.assertValueEqual(ids(limited), List.of(id(visible)), "opening wall cannot rewrite retained snapshot");
            var refreshed = (TargetQuery.Result) run(world, select(center, new TargetShape.Sphere(4), TargetQuery.Anchor.BODY, true, 1));
            h.assertValueEqual(ids(refreshed), List.of(id(hidden)), "new scan observes changed terrain");
        } finally { h.getLevel().setBlockAndUpdate(wall, old); center.discard(); hidden.discard(); visible.discard(); }
        h.succeed();
    }
    @GameCase public void collisionShapesFluidsAndUnloadedTerrainHaveExplicitVisibilitySemantics(GameTestHelper h) {
        var block = h.absolutePos(new BlockPos(3, 40, 3)); var old = h.getLevel().getBlockState(block);
        try {
            h.getLevel().setBlockAndUpdate(block, Blocks.STONE_SLAB.defaultBlockState());
            h.assertTrue(!MinecraftVisibility.visible(h.getLevel(), point(h, 2, 40.25, 3.5), point(h, 5, 40.25, 3.5)), "lower slab occludes low ray");
            h.assertTrue(MinecraftVisibility.visible(h.getLevel(), point(h, 2, 40.75, 3.5), point(h, 5, 40.75, 3.5)), "empty space above slab should be visible");
            h.getLevel().setBlockAndUpdate(block, Blocks.GLASS.defaultBlockState());
            h.assertTrue(!MinecraftVisibility.visible(h.getLevel(), point(h, 2, 40.75, 3.5), point(h, 5, 40.75, 3.5)), "collision policy includes glass");
            h.getLevel().setBlockAndUpdate(block, Blocks.WATER.defaultBlockState());
            h.assertTrue(MinecraftVisibility.visible(h.getLevel(), point(h, 2, 40.75, 3.5), point(h, 5, 40.75, 3.5)), "fluid policy is none");
            var far = new WorldPosition(h.getLevel().dimension().identifier().toString(), 1_000_001, 80, 1_000_001);
            int chunk = 1_000_001 >> 4; h.assertTrue(h.getLevel().getChunkSource().getChunkNow(chunk, chunk) == null, "test endpoint must be unloaded");
            h.assertTrue(!MinecraftVisibility.visible(h.getLevel(), far, far), "unknown degenerate ray must not be visible");
            h.assertTrue(!MinecraftVisibility.visible(h.getLevel(), point(h, 2, 40.75, 3.5), far), "unloaded endpoint must be rejected");
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(chunk, chunk) == null, "visibility loaded an absent chunk");
        } finally { h.getLevel().setBlockAndUpdate(block, old); }
        h.succeed();
    }
    @GameCase public void cylinderAnchorsMissingDirectionAndCrossDimensionDirectionAreObservedExplicitly(GameTestHelper h) {
        var center = cow(h, 3, 40, 3); var upper = cow(h, 4, 42, 3); var near = cow(h, 4, 40, 3);
        try {
            var world = world(h);
            var eyes = (PositionQuery.Result) run(world, new PositionQuery(id(center), TargetQuery.Anchor.EYES));
            near(h, eyes.position().orElseThrow().y(), center.getEyeY(), "explicit captured eye anchor");
            var cylinder = select(center, new TargetShape.Cylinder(2, 2.8), TargetQuery.Anchor.FEET, false, 5);
            var feet = (TargetQuery.Result) run(world, cylinder); h.assertTrue(ids(feet).contains(id(upper)), "feet should be inside centered cylinder");
            var body = (TargetQuery.Result) run(world, select(center, cylinder.shape(), TargetQuery.Anchor.BODY, false, 5)); h.assertTrue(!ids(body).contains(id(upper)), "body anchor should be above cylinder");
            h.assertTrue(ids(body).contains(id(near)) && body.targets().getFirst().offset().isPresent(), "non-spherical observations retain relative geometry");
            var absent = (TargetQuery.Result) run(world, select(center, new TargetShape.Cone(4, 2, Optional.empty()), TargetQuery.Anchor.FEET, false, 5));
            h.assertValueEqual(absent.outcome(), TargetQuery.Outcome.MISSING_DIRECTION, "absent axis must not become default facing");
            var other = (TargetQuery.Result) run(world, select(center, new TargetShape.Cone(4, 2, Optional.of(new WorldDirection("other:dimension", 1, 0, 0))), TargetQuery.Anchor.FEET, false, 5));
            h.assertValueEqual(other.outcome(), TargetQuery.Outcome.WRONG_DIMENSION, "captured axis dimension");
            center.discard(); var lost = (DirectionQuery.Result) run(world, new DirectionQuery(id(center))); h.assertTrue(lost.direction().isEmpty(), "removed entity direction is missing");
        } finally { center.discard(); upper.discard(); near.discard(); }
        h.succeed();
    }
    @GameCase public void delayedConeUsesCapturedDirectionAndPositionAfterCasterTurnsAndDisappears(GameTestHelper h) throws Exception {
        var center = cow(h, 2, 40, 3); center.setYRot(-90); center.setXRot(0);
        var ahead = cow(h, 4, 40, 3); var farther = cow(h, 5, 40, 3); var side = cow(h, 2, 40, 5);
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/spatial_query.json")), StandardCharsets.UTF_8)) {
            var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var source = new EffectSource("spatial", "test:spatial", id(center), new BuffInstance.Origin(id(center), "spatial", "", "test:cone"), Set.of());
            var world = world(h); var observations = new ArrayList<TargetQuery.Result>();
            var session = new EffectSession(program.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), EffectState.empty().withSource(source), request -> {
                var result = world.apply(request); if (result instanceof TargetQuery.Result query) observations.add(query); return result;
            });
            session.start(0, new RuleEngine.Signal("test:scan", new EffectEvent(id(center), id(center), source.origin(), Set.of(), Map.of())));
            center.setYRot(90); center.setPos(center.position().add(0, 8, 0)); center.discard(); session.start(0, SourceChange.remove(source.instance()));
            session.observe(100_000, List.of());
            near(h, ahead.getHealth(), 4, "captured forward cone healed close target"); near(h, farther.getHealth(), 4, "captured forward cone healed second target"); near(h, side.getHealth(), 2, "side target outside cone");
            h.assertValueEqual(observations.size(), 1, "one delayed observation"); h.assertTrue(session.state().idle(), "cone effect settled");
        } finally { center.discard(); ahead.discard(); farther.discard(); side.discard(); }
        h.succeed();
    }
}
