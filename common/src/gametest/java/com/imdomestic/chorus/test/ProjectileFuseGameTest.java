package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.platform.minecraft.EffectProjectile;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ProjectileFuseGameTest {
    static JsonObject spec(JsonObject d) { return d.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile"); }
    static JsonObject constant(double n, String unit) { var o = new JsonObject(); o.addProperty("type", "chorus:constant"); o.addProperty("value", n); o.addProperty("unit", unit); return o; }
    static void value(JsonObject d, String key, double n) { d.getAsJsonObject(key).addProperty("value", n); }
    static ProjectileGameTest.Harness open(GameTestHelper h, Consumer<JsonObject> edit) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "projectile_fuse", d -> { var s = spec(d); value(s, "speed", 200); value(s, "range", 12); value(s.getAsJsonObject("proximity"), "radius", 1.5); edit.accept(s); });
        t.owner.setYRot(-90); t.owner.setXRot(0); return t;
    }
    static LivingEntity target(ProjectileGameTest.Harness t, double dx, double dz) {
        var e = t.cow(3, 40, 3); e.setPos(t.owner.getEyePosition().add(dx, -e.getBbHeight() / 2, dz)); return e;
    }
    static EffectProjectile fire(ProjectileGameTest.Harness t) { t.fire(); return t.projectiles.getLast(); }
    static void outcome(ProjectileGameTest.Harness t, String name) {
        t.h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "fuse runtime: " + t.runtime.failure());
        t.h.assertValueEqual(t.cues.getLast().cue(), "test:" + name, "terminal outcome");
        t.h.assertTrue(t.projectiles.getLast().isRemoved() && t.projectiles.getLast().progress().terminal(), "flight consumed before callback");
    }
    static void block(ProjectileGameTest.Harness t, int x, int y, int z) {
        var p = t.h.absolutePos(new BlockPos(x, y, z)); t.blocks.putIfAbsent(p, t.h.getLevel().getBlockState(p)); t.h.getLevel().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_range")
    public void rangeClipsFastFlightBeforeFartherCollisionAndRunsOnlyOnce(GameTestHelper h) throws Exception {
        try (var t = open(h, s -> { s.remove("proximity"); value(s, "range", 2.25); })) {
            target(t, 4, 0); block(t, 8, 41, 3); var p = fire(t); var start = p.position(); p.tick(); outcome(t, "range");
            near(h, p.getX() - start.x, 2.25, "exact range point"); near(h, p.traveled(), 2.25, "actual length");
            h.assertValueEqual(p.progress().entityContacts(), 0L, "farther target not hit"); p.tick(); p.tick(); h.assertValueEqual(t.cues.size(), 1, "no repeated terminal callback");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_bounce")
    public void bounceAndPiercingConsumeOneCumulativeRangeAcrossContactSegments(GameTestHelper h) throws Exception {
        try (var t = open(h, s -> {
            s.remove("proximity"); value(s, "range", 4); s.add("collision", JsonParser.parseString("{\"block_bounces\":{\"type\":\"chorus:constant\",\"unit\":\"count\",\"value\":1},\"entity_pierces\":{\"type\":\"chorus:constant\",\"unit\":\"count\",\"value\":1}}"));
        })) {
            t.wall(); var e = target(t, -.6, 0); var p = fire(t); var start = p.position(); p.tick(); outcome(t, "range");
            near(h, p.traveled(), 4, "bounce does not reset range"); near(h, p.getX(), start.x - 1 - 2e-5, "remaining range after bounce and pierce");
            h.assertValueEqual(p.progress().bounces(), 1L, "one reflection"); h.assertValueEqual(p.progress().entityContacts(), 1L, "one pierced target");
            h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:block", "test:entity", "test:range"), "ordered independent contacts"); near(h, e.getHealth(), 100, "fuse observations do not invent damage");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_curve")
    public void gravityAndDragUseActualSegmentLengthsInsteadOfInitialSpeedTimesAge(GameTestHelper h) throws Exception {
        try (var t = open(h, s -> { s.remove("proximity"); value(s, "speed", 20); value(s, "gravity", 20); value(s, "drag", .5); value(s, "range", .65); })) {
            var p = fire(t); var start = p.position(); p.tick(); h.assertTrue(!p.isRemoved(), "first curved segment still in range"); p.tick(); outcome(t, "range");
            double fraction = (.65 - Math.hypot(.5, .025)) / Math.hypot(.25, .0375);
            near(h, p.getX() - start.x, .5 + .25 * fraction, "second segment clipped at cumulative length"); near(h, p.getY() - start.y, -.025 - .0375 * fraction, "gravity retained"); near(h, p.traveled(), .65, "curved path length");
            h.assertTrue(p.position().distanceTo(start) < .65, "path is longer than displacement");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_fast")
    public void highSpeedProximitySweepFindsFirstSphereEntryWithoutPhysicalContact(GameTestHelper h) throws Exception {
        try (var t = open(h, _ -> {})) {
            var e = target(t, 4, 1); var p = fire(t); var start = p.position(); t.runtime.unbind("launch"); t.runtime.unbind("boost"); p.tick(); outcome(t, "proximity");
            near(h, p.getX() - start.x, 4 - Math.sqrt(1.25), "closed segment sphere entry"); h.assertValueEqual(t.cues.getLast().target(), e.getUUID().toString(), "trigger entity observation");
            h.assertValueEqual(p.progress().entityContacts(), 0L, "near miss is not direct collision credit"); near(h, e.getHealth(), 100, "damage requires a content action");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_arm")
    public void armingInsideSphereExplodesThereAndArmingAfterPassingDoesNotRewind(GameTestHelper h) throws Exception {
        for (double arm : List.of(4.0, 6.0)) try (var t = open(h, s -> { value(s, "range", 8); value(s.getAsJsonObject("proximity"), "arm_distance", arm); })) {
            target(t, 4, 1); var p = fire(t); p.tick(); outcome(t, arm == 4 ? "proximity" : "range"); near(h, p.traveled(), arm == 4 ? 4 : 8, "only armed prefix participates");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_wall")
    public void physicalWallBeforeFuseWinsButFartherWallCannotOverrideEarlierFuse(GameTestHelper h) throws Exception {
        for (int wall : List.of(4, 8)) try (var t = open(h, _ -> {})) {
            target(t, 4, 1); block(t, wall, 41, 3); block(t, wall, 42, 3); var p = fire(t); p.tick(); outcome(t, wall == 4 ? "block" : "proximity");
            near(h, p.traveled(), wall == 4 ? 1.5 : 4 - Math.sqrt(1.25), "earliest physical outcome");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_sight")
    public void sideWallRequiresLineOfSightOnlyWhenRequested(GameTestHelper h) throws Exception {
        for (boolean sight : List.of(true, false)) try (var t = open(h, s -> { value(s, "range", 8); var f = s.getAsJsonObject("proximity"); value(f, "radius", 3); f.addProperty("line_of_sight", sight); })) {
            target(t, 4, 2); block(t, 4, 41, 4); block(t, 4, 42, 4); fire(t).tick(); outcome(t, sight ? "range" : "proximity");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_relation")
    public void alliedTargetsAreSkippedAndMissingOwnerDoesNotInventRelativeRelation(GameTestHelper h) throws Exception {
        var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("fuse-" + UUID.randomUUID());
        try (var t = open(h, s -> value(s, "range", 8))) {
            var ally = target(t, 2, 1); var enemy = target(t, 4, 1); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
            fire(t).tick(); outcome(t, "proximity"); h.assertValueEqual(t.cues.getLast().target(), enemy.getUUID().toString(), "closer ally skipped");
            var p = fire(t); t.owner.discard(); p.tick(); outcome(t, "range");
        } finally { scoreboard.removePlayerTeam(team); } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_order")
    public void directCollisionAndRangeCanEachPrecedeAnArmedProximityTrigger(GameTestHelper h) throws Exception {
        try (var t = open(h, s -> value(s.getAsJsonObject("proximity"), "arm_distance", 6))) {
            target(t, 3, 0); fire(t).tick(); outcome(t, "entity"); h.assertValueEqual(t.projectiles.getLast().progress().entityContacts(), 1L, "direct impact wins before arming");
        }
        try (var t = open(h, s -> value(s, "range", 2))) {
            target(t, 4, 1); fire(t).tick(); outcome(t, "range"); near(h, t.projectiles.getLast().traveled(), 2, "range before sphere entry");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_stationary")
    public void stationaryArmedOverlapCanTriggerButUnarmedStationaryFlightOnlyExpires(GameTestHelper h) throws Exception {
        for (double arm : List.of(0.0, 1.0)) try (var t = open(h, s -> { value(s, "speed", 0); value(s, "lifetime", .05); value(s.getAsJsonObject("proximity"), "arm_distance", arm); })) {
            target(t, 0, 1); var p = fire(t); p.tick(); outcome(t, arm == 0 ? "proximity" : "expired"); near(h, p.traveled(), 0, "waiting does not arm distance fuse");
        }
        try (var t = open(h, s -> { s.remove("proximity"); value(s, "range", 0); value(s, "speed", 0); })) { fire(t).tick(); outcome(t, "range"); }
        h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_unknown")
    public void unknownTerminalCallbackConsumesFlightAndCannotReplayOnLaterTicks(GameTestHelper h) throws Exception {
        try (var t = open(h, _ -> {})) {
            target(t, 4, 1); var p = fire(t); t.failAfterCue = true; p.tick();
            h.assertTrue(t.runtime.failure().isPresent() && p.isRemoved() && p.progress().terminal(), "unknown action still consumes flight");
            h.assertValueEqual(t.cues.size(), 1, "world action applied once"); p.tick(); p.tick(); h.assertValueEqual(t.cues.size(), 1, "no replay after failure");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_identity")
    public void equalEntryUsesStableIdentityAndDeadTargetsCannotTriggerAgain(GameTestHelper h) throws Exception {
        try (var t = open(h, _ -> {})) {
            var a = target(t, 4, 1); var b = target(t, 4, 1);
            var first = a.getUUID().compareTo(b.getUUID()) < 0 ? a : b; var second = first == a ? b : a;
            fire(t).tick(); outcome(t, "proximity"); h.assertValueEqual(t.cues.getLast().target(), first.getUUID().toString(), "identity tie break");
            first.setHealth(0); fire(t).tick(); outcome(t, "proximity"); h.assertValueEqual(t.cues.getLast().target(), second.getUUID().toString(), "dead candidate skipped");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_prefix")
    public void fuseBeforeInactiveTerrainCompletesWithoutScanningOrEnteringTheUnavailableSuffix(GameTestHelper h) throws Exception {
        for (boolean proximity : List.of(false, true)) try (var t = open(h, s -> { value(s, "range", proximity ? 8 : .5); if (!proximity) s.remove("proximity"); })) {
            var center = h.absolutePos(BlockPos.ZERO); Vec3 boundary = null, direction = null;
            search: for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
                var inside = new BlockPos(((center.getX() >> 4) + x) * 16 + 8, center.getY() + 40, ((center.getZ() >> 4) + z) * 16 + 8);
                if (!h.getLevel().isPositionEntityTicking(inside)) continue;
                for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    var outside = inside.relative(d, 16);
                    if (h.getLevel().getChunkSource().getChunkNow(outside.getX() >> 4, outside.getZ() >> 4) == null || h.getLevel().isPositionEntityTicking(outside)) continue;
                    direction = new Vec3(d.getStepX(), 0, d.getStepZ()); boundary = Vec3.atLowerCornerOf(inside).add(direction.scale(8)).add(0, .7, 0); break search;
                }
            }
            h.assertTrue(boundary != null, "active/inactive boundary required");
            var from = boundary.subtract(direction.scale(2));
            if (proximity) {
                var e = target(t, 4, 1); var side = new Vec3(-direction.z, 0, direction.x);
                e.setPos(boundary.subtract(direction.scale(.5)).add(side).add(0, -e.getBbHeight() / 2, 0));
                h.assertTrue(h.getLevel().isPositionEntityTicking(e.blockPosition()), "candidate remains available");
            }
            var p = fire(t); p.setPos(from); p.setDeltaMovement(direction.scale(4)); p.tick(); outcome(t, proximity ? "proximity" : "range");
            near(h, p.traveled(), proximity ? 1.5 - Math.sqrt(1.25) : .5, "complete in available prefix");
        } h.succeed();
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:projectile_fuse_endpoint")
    public void continuingContactExactlyAtRangeEndsAtThatPointEvenWhenBounceStopsAllMotion(GameTestHelper h) throws Exception {
        for (double restitution : List.of(0.0, 1.0)) try (var t = open(h, s -> {
            s.remove("proximity"); value(s, "speed", 30); value(s, "range", 1.5);
            var collision = new JsonObject(); collision.add("block_bounces", constant(1, "count")); collision.add("restitution", constant(restitution, "multiplier")); s.add("collision", collision);
        })) {
            t.wall(); var p = fire(t); p.tick(); outcome(t, "range");
            h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:block", "test:range"), "contact and exhausted range in same tick");
            near(h, p.getX(), h.absoluteVec(new Vec3(4, 40, 3)).x, "no separation beyond completed endpoint"); near(h, p.traveled(), 1.5, "no extra tick of travel");
        } h.succeed();
    }
}
