package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ProjectileTrackingGameTest {
    private static String id(Entity e) { return e.getUUID().toString(); }
    private static JsonObject spec(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile"); }
    private static JsonObject tracking(JsonObject data) { return spec(data).getAsJsonObject("tracking"); }
    private static void number(JsonObject object, String name, double value) { object.getAsJsonObject(name).addProperty("value", value); }
    private static void wall(ProjectileGameTest.Harness t, int y) {
        var pos = t.h.absolutePos(new BlockPos(2, y, 3)); t.blocks.put(pos, t.h.getLevel().getBlockState(pos)); t.h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
    }
    @GameCase public void turnsAtCapturedAngularRateAndRetainsMovingIdentityInsteadOfSwitchingToNearerEnemy(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> number(spec(data), "speed", 2))) {
            var target = t.cow(4.5, 46, 3.5); t.fire(); var p = t.projectiles.getFirst(); t.runtime.unbind("launch"); t.runtime.unbind("boost"); p.tick();
            h.assertValueEqual(p.trackingTarget(), Optional.of(id(target)), "initial nearest qualified target");
            near(h, p.getDeltaMovement().length(), 0.1, "steering preserves speed"); near(h, p.getDeltaMovement().x, 0.1 * Math.sin(Math.toRadians(9)), "180 degrees per second means 9 degrees this tick");
            var closer = t.cow(1, 43, 3.5); target.setPos(h.absoluteVec(new Vec3(1, 46, 3.5)));
            for (int i = 0; i < 3; i++) p.tick();
            h.assertValueEqual(p.trackingTarget(), Optional.of(id(target)), "lock survives nearer candidate and target movement");
            h.assertTrue(p.getDeltaMovement().x < 0 && !p.isRemoved(), "current target position bends trajectory left");
            near(h, closer.getHealth(), 100, "acquisition does not apply damage");
        }
        h.succeed();
    }
    @GameCase public void acquisitionConeAndRadiusFilterTargetsWhileLostLockKeepsBallisticVelocity(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> { number(spec(data), "speed", 2); number(tracking(data), "acquisition_angle", 0); })) {
            t.cow(4, 42, 3.5); var target = t.cow(2.5, 45, 3.5); t.fire(); var p = t.projectiles.getFirst(); p.tick();
            h.assertValueEqual(p.trackingTarget(), Optional.of(id(target)), "zero cone excludes closer off-axis target");
            target.setPos(target.getX(), target.getY() + 30, target.getZ()); var velocity = p.getDeltaMovement(); p.tick();
            h.assertTrue(p.trackingTarget().isEmpty() && !p.isRemoved(), "out-of-radius lock is released without killing flight");
            near(h, p.getDeltaMovement().distanceTo(velocity), 0, "no eligible target preserves ballistic direction");
        }
        h.succeed();
    }
    @GameCase public void contactRedirectVisitsThreeDistinctEnemiesInOneTickAndHonorsPierceBudget(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> {
            number(spec(data), "speed", 200); number(tracking(data), "turn_rate", 0); number(tracking(data), "acquisition_angle", 180);
            number(spec(data).getAsJsonObject("collision"), "entity_pierces", 2);
        })) {
            var first = t.cow(2.5, 43, 3.5); var second = t.cow(5, 43, 3.5); var third = t.cow(5, 46, 3.5); var fourth = t.cow(2.5, 46, 3.5);
            t.fire(); t.runtime.unbind("launch"); t.runtime.unbind("boost"); var p = t.projectiles.getFirst(); p.tick();
            h.assertValueEqual(t.hits.stream().map(d -> d.target()).toList(), List.of(id(first), id(second), id(third)), "redirect uses new geometry and excludes exhausted previous targets");
            for (var e : List.of(first, second, third)) near(h, e.getHealth(), 80, "captured damage on redirected flight");
            near(h, fourth.getHealth(), 100, "third entity contact terminates before fourth");
            h.assertValueEqual(t.hits.stream().map(d -> d.impact().number("entity_contacts", Unit.COUNT).value()).toList(), List.of(1.0, 2.0, 3.0), "per-contact measurements survive turning");
            h.assertTrue(p.isRemoved() && p.progress().entityContacts() == 3 && p.progress().bounces() == 0, "redirect does not consume a surface bounce");
        }
        h.succeed();
    }
    @GameCase public void reflectedFlightCanRedirectTowardEnemyAndApplyLiveBounceDamage(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> {
            number(spec(data), "speed", 200); number(tracking(data), "turn_rate", 0); number(tracking(data), "acquisition_angle", 180);
            number(spec(data).getAsJsonObject("collision"), "entity_pierces", 0);
        })) {
            var target = t.cow(5, 41, 3.5); wall(t, 44); t.fire(); var p = t.projectiles.getFirst(); p.tick();
            h.assertValueEqual(t.hits.stream().map(d -> d.target()).toList(), List.of(id(target)), "bounce turns toward side target rather than straight reflection");
            near(h, target.getHealth(), 90, "one wall bounce selects captured half-damage curve");
            h.assertTrue(p.isRemoved() && p.progress().bounces() == 1 && p.progress().entityContacts() == 1, "independent surface and entity counts");
        }
        h.succeed();
    }
    @GameCase public void allianceAndLineOfSightAreRecheckedAndMissingOwnerDisablesRelativeTracking(GameTestHelper h) throws Exception {
        var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("tracking-" + UUID.randomUUID());
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> { number(spec(data), "speed", 2); number(tracking(data), "turn_rate", 0); })) {
            var ally = t.cow(2.5, 43, 3.5); var hidden = t.cow(2.5, 46, 3.5); var visible = t.cow(5, 44, 3.5);
            scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team); wall(t, 44);
            t.fire(); var p = t.projectiles.getFirst(); p.tick(); h.assertValueEqual(p.trackingTarget(), Optional.of(id(visible)), "skip closer ally and occluded enemy before selecting target");
            scoreboard.addPlayerToTeam(visible.getScoreboardName(), team); p.tick(); h.assertTrue(p.trackingTarget().isEmpty(), "changed alliance invalidates lock");
            scoreboard.removePlayerFromTeam(visible.getScoreboardName(), team); t.owner.discard(); p.tick();
            h.assertTrue(p.trackingTarget().isEmpty(), "relative tracking cannot invent a missing owner's faction"); near(h, hidden.getHealth(), 100, "tracking never damages through a wall");
        } finally { scoreboard.removePlayerTeam(team); }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:projectile_tracking", maxTicks = 60)
    public void actualTicksTrackMovingEnemyAndRetainAttackAfterSourcesAreRemoved(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> number(tracking(data), "turn_rate", 720));
        try {
            var target = t.cow(5, 46, 3.5); t.fire(); var p = t.projectiles.getFirst(); t.runtime.unbind("launch"); t.runtime.unbind("boost");
            h.runAfterDelay(2, () -> target.setPos(h.absoluteVec(new Vec3(1, 46, 3.5))));
            t.finish(16, () -> {
                h.assertValueEqual(t.hits.stream().map(d -> d.target()).toList(), List.of(id(target)), "moving target is physically hit once");
                near(h, target.getHealth(), 80, "retained attack amount"); near(h, t.owner.getHealth(), 30, "actual damage receipt drives healing");
                h.assertValueEqual(p.progress().hits().get(id(target)), 1L, "per-target limit survives reacquisition");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase public void anyRelationCanReacquireAfterOwnerRemovalAndTargetDeathWithoutIgnoringPhysicalWalls(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> {
            number(spec(data), "speed", 2); number(tracking(data), "turn_rate", 0); tracking(data).addProperty("relation", "any"); tracking(data).addProperty("line_of_sight", false);
        })) {
            var dead = t.cow(3.5, 42, 3.5); dead.setHealth(0); var hidden = t.cow(2.5, 46, 3.5); var next = t.cow(5, 48, 3.5); wall(t, 44);
            t.fire(); var p = t.projectiles.getFirst(); t.owner.discard(); p.tick();
            h.assertValueEqual(p.trackingTarget(), Optional.of(id(hidden)), "ANY can observe hidden enemy without an owner; dead candidate excluded");
            hidden.setHealth(0); p.tick(); h.assertValueEqual(p.trackingTarget(), Optional.of(id(next)), "death invalidates lock and allows new acquisition");
            next.discard(); p.tick(); h.assertTrue(p.trackingTarget().isEmpty(), "removed target cannot remain locked");
            p.setDeltaMovement(0, 10, 0); p.tick();
            h.assertTrue(p.progress().bounces() == 1 && p.getY() < h.absolutePos(new BlockPos(2, 44, 3)).getY(), "ignoring acquisition sight never disables physical wall collision");
        }
        h.succeed();
    }
    @GameCase public void disabledContactRedirectionKeepsPiercingAlongCurrentTrajectory(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", data -> {
            number(spec(data), "speed", 200); number(tracking(data), "turn_rate", 0); tracking(data).addProperty("redirect_on_contact", false);
            number(spec(data).getAsJsonObject("collision"), "entity_pierces", 1);
        })) {
            var first = t.cow(2.5, 43, 3.5); var side = t.cow(5, 43, 3.5); var straight = t.cow(2.5, 46, 3.5); t.fire(); t.projectiles.getFirst().tick();
            h.assertValueEqual(t.hits.stream().map(d -> d.target()).toList(), List.of(id(first), id(straight)), "zero turn rate and disabled redirects preserve the straight path");
            near(h, side.getHealth(), 100, "nearer side enemy is not selected by an implicit bounce");
        }
        h.succeed();
    }
}
