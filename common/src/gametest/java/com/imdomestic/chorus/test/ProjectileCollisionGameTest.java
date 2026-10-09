package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ProjectileCollisionGameTest {
    private static String id(Entity e) { return e.getUUID().toString(); }
    private static JsonObject spec(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile"); }
    private static void limit(JsonObject spec, String name, int count) { spec.getAsJsonObject("collision").add(name, JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":" + count + ",\"unit\":\"count\"}")); }
    private static void ceiling(ProjectileGameTest.Harness t, int y) {
        var p = t.h.absolutePos(new BlockPos(2, y, 3)); t.blocks.putIfAbsent(p, t.h.getLevel().getBlockState(p)); t.h.getLevel().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
    }
    @GameCase public void exactEndpointAndNearFaceContactsBounceButEmbeddedLaunchTerminates(GameTestHelper h) throws Exception {
        for (double gap : new double[] {0.25, 0.0001, 0, -0.5}) {
            try (var t = new ProjectileGameTest.Harness(h, "projectile_collisions", _ -> {})) {
                ceiling(t, 45); t.fire(); var p = t.projectiles.getFirst(); var wall = h.absolutePos(new BlockPos(2, 45, 3));
                p.setPos(wall.getX() + (gap == 0.25 ? 0 : 0.5), wall.getY() - gap, wall.getZ() + 0.5); p.setDeltaMovement(0, 0.25, 0); p.tick();
                h.assertValueEqual(p.progress().sequence(), 1L, "one contact even at closed segment endpoint");
                if (gap < 0) h.assertTrue(p.isRemoved() && p.progress().bounces() == 0, "truly embedded flight terminates");
                else {
                    h.assertTrue(!p.isRemoved() && p.progress().bounces() == 1, "outside and boundary starts reflect");
                    near(h, p.getDeltaMovement().y, -0.25, "real face normal reverses motion");
                    near(h, p.getY(), wall.getY() - (0.25 - gap) - 0.00001, "remaining travel after exact contact");
                }
            }
        }
        h.succeed();
    }
    @GameCase public void finitePiercingHitsTwoOrderedEnemiesInOneSweepAndStopsBeforeTheThird(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_collisions", data -> limit(spec(data), "entity_pierces", 1))) {
            var first = t.cow(2.5, 43, 3.5); var second = t.cow(2.5, 46, 3.5); var third = t.cow(2.5, 49, 3.5); t.fire(); var p = t.projectiles.getFirst(); p.tick();
            h.assertValueEqual(t.hits.stream().map(d -> d.target()).toList(), List.of(id(first), id(second)), "same-tick ordered piercing");
            near(h, first.getHealth(), 80, "first impact"); near(h, second.getHealth(), 80, "second impact"); near(h, third.getHealth(), 100, "pierce budget stops third impact");
            h.assertTrue(p.isRemoved() && p.progress().terminal(), "final allowed contact consumes projectile"); h.assertValueEqual(p.progress().entityContacts(), 2L, "additional pierce count");
            h.assertValueEqual(t.cues.size(), 2, "one action body per contact"); h.assertValueEqual(t.operations.stream().distinct().count(), (long) t.operations.size(), "contact operations cannot collide");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:projectile_reentry", maxTicks = 60)
    public void bouncingProjectileCanReenterEachEnemyTwiceWithCapturedDamageAndLiveBounceFalloff(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "projectile_collisions", data -> limit(spec(data), "block_bounces", 3));
        try {
            var first = t.cow(2.5, 43, 3.5); var second = t.cow(2.5, 46, 3.5); var third = t.cow(2.5, 49, 3.5);
            ceiling(t, 39); ceiling(t, 52); t.fire(); var p = t.projectiles.getFirst(); t.runtime.unbind("launch"); t.runtime.unbind("boost");
            t.finish(9, () -> {
                h.assertValueEqual(t.hits.stream().map(d -> d.target()).toList(), List.of(id(first), id(second), id(third), id(third), id(second), id(first)), "two physical passages per enemy");
                for (var e : List.of(first, second, third)) near(h, e.getHealth(), 70, "20 outbound plus 10 reflected damage");
                h.assertValueEqual(t.hits.stream().map(d -> d.impact().number("target_contacts", Unit.COUNT).value()).toList(), List.of(1.0, 1.0, 1.0, 2.0, 2.0, 2.0), "per-target repeat count");
                h.assertValueEqual(t.hits.stream().map(d -> d.impact().number("bounces", Unit.COUNT).value()).toList(), List.of(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), "frozen attack reads each contact's measurements");
                h.assertTrue(p.isRemoved() && p.progress().terminal(), "last wall terminates after three bounces");
                h.assertValueEqual(p.progress().bounces(), 3L, "configured surface bounce count"); h.assertValueEqual(p.progress().sequence(), 10L, "six entity contacts and four wall contacts");
                h.assertValueEqual(t.cues.size(), 10, "all contacts produce bodies, including nonterminal bounces");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase public void twoSurfaceReflectionsUseRemainingTravelInTheSameTickAndExhaustOnlyTheConfiguredBudget(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_collisions", data -> limit(spec(data), "block_bounces", 2))) {
            ceiling(t, 39); ceiling(t, 44); t.fire(); var p = t.projectiles.getFirst(); double start = p.getY(); p.tick();
            h.assertValueEqual(p.progress().bounces(), 2L, "two reflections in one sweep"); near(h, p.getY(), start + 2, "remaining travel preserved through ceiling and floor");
            near(h, p.getDeltaMovement().y, 10, "two reflections restore direction"); h.assertTrue(!p.isRemoved(), "budget permits both bounces");
            p.tick(); h.assertTrue(p.isRemoved(), "third surface contact consumes projectile"); h.assertValueEqual(p.progress().sequence(), 3L, "no duplicate contact from face epsilon");
            h.assertValueEqual(t.cues.size(), 3, "per-contact body count");
        }
        h.succeed();
    }
    @GameCase public void anEnemyContainingTheProjectileCannotBeHitRepeatedlyUntilTheProjectileExits(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_collisions", data -> {
            spec(data).getAsJsonObject("speed").addProperty("value", 1); spec(data).getAsJsonObject("lifetime").addProperty("value", 1);
            spec(data).getAsJsonObject("collision").addProperty("max_hits_per_target", "unlimited");
        })) {
            var target = t.cow(2.5, 40.5, 3.5); t.fire(); var p = t.projectiles.getFirst();
            for (int i = 0; i < 5; i++) p.tick();
            h.assertValueEqual(t.hits.size(), 1, "continuous overlap is one entry, not five ticks of hits"); near(h, target.getHealth(), 80, "one physical entry");
            h.assertTrue(!p.isRemoved() && p.progress().canHit(id(target), p.launch().orElseThrow().parameters().collision()), "reentry policy is unlimited; geometric overlap alone suppresses immediate repeats");
        }
        h.succeed();
    }
    @GameCase public void nativeCancellationStillConsumesContactBudgetWhileLaterPiercedEnemyCanTakeDamage(GameTestHelper h) throws Exception {
        String denied = "piercing-denied-" + UUID.randomUUID(); TestDamageHooks.ALLOW_DAMAGE.register((target, _, _) -> !target.entityTags().contains(denied));
        try (var t = new ProjectileGameTest.Harness(h, "projectile_collisions", data -> limit(spec(data), "entity_pierces", 1))) {
            var first = t.cow(2.5, 43, 3.5); first.addTag(denied); var second = t.cow(2.5, 46, 3.5); t.fire(); t.projectiles.getFirst().tick();
            h.assertValueEqual(t.receipts.stream().map(DamageReceipt::outcome).toList(), List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.APPLIED), "collision and damage qualification are separate");
            near(h, first.getHealth(), 100, "cancelled first impact"); near(h, second.getHealth(), 80, "second impact after cancellation"); near(h, t.owner.getHealth(), 30, "only actual loss drives healing");
            h.assertValueEqual(t.projectiles.getFirst().progress().entityContacts(), 2L, "physical contact count includes rejected damage");
        }
        h.succeed();
    }
    @GameCase public void failureOnNonterminalContactConsumesFlightAndDoesNotProcessRemainingSweep(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_collisions", _ -> {})) {
            var first = t.cow(2.5, 43, 3.5); var second = t.cow(2.5, 46, 3.5); t.failAfterDamage = true; t.fire(); var p = t.projectiles.getFirst(); p.tick();
            h.assertTrue(t.runtime.failure().isPresent() && p.isRemoved() && p.progress().terminal(), "failed nonterminal contact must consume flight");
            near(h, first.getHealth(), 80, "committed first hit preserved"); near(h, second.getHealth(), 100, "remaining sweep not executed"); near(h, t.owner.getHealth(), 10, "failed hit cannot issue subsequent heal");
            p.tick(); h.assertValueEqual(t.hits.size(), 1, "failure cannot retry contact");
        }
        h.succeed();
    }
}
