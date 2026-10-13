package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftEntityLife;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class GroundContactGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "ground_contact", _ -> {}, true); t.owner.setGameMode(GameType.SURVIVAL); return t;
    }
    static void floor(ProjectileGameTest.Harness t) {
        for (int x = 4; x <= 6; x++) for (int z = 2; z <= 4; z++) {
            var p = t.h.absolutePos(new BlockPos(x, 39, z)); t.blocks.put(p, t.h.getLevel().getBlockState(p)); t.h.getLevel().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
        }
    }
    static void watch(ProjectileGameTest.Harness t, LivingEntity actor) {
        String id = actor.getUUID().toString(); t.runtime.bind(new EffectSource("watch/" + id, "test:landing", id, new BuffInstance.Origin(id, "watch", "", ""), Set.of()));
    }
    static long count(ProjectileGameTest.Harness t) { return t.cues.stream().filter(c -> c.cue().equals("test:landed")).count(); }
    static void settled(ProjectileGameTest.Harness t) { t.runtime.prepare(); ShieldThrowGameTest.settled(t); }
    static void placePlayer(ProjectileGameTest.Harness t) throws Exception {
        var pos = t.h.absoluteVec(new Vec3(5.5, 42, 3.5)); t.owner.connection.teleport(pos.x, pos.y, pos.z, 0, 0);
        // 26.3 validates the position carried by teleport acknowledgement against the tick baseline.
        t.owner.connection.resetPosition(); NativeMotionGameTest.acknowledge(t.owner); t.owner.setOnGround(false);
    }
    static void packet(ProjectileGameTest.Harness t, double y, boolean grounded) {
        t.owner.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE); t.owner.connection.resetPosition(); t.owner.connection.tick();
        t.owner.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(t.owner.getX(), t.h.absoluteVec(new Vec3(0, y, 0)).y, t.owner.getZ(), 0, 0, grounded, false));
    }
    @GameCase(environment = "chorus_gametest:ground_contact_native", maxTicks = 60)
    public void actualNativeCollisionEmitsOnceAndDelayedReadersKeepTheContactAfterTheEntityMoves(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h);
            try {
                floor(t); var actor = t.cow(5.5, 42, 3.5); watch(t, actor); actor.move(MoverType.SELF, new Vec3(0, -3, 0));
                near(h, actor.getY(), h.absoluteVec(new Vec3(0, 40, 0)).y, "actual collision point"); h.assertValueEqual(count(t), 0L, "native callback reentered interpreter");
                settled(t); h.assertValueEqual(count(t), 1L, "one landing"); var old = ((TargetQuery.PositionCenter)t.queries.getFirst().center()).position().orElseThrow();
                actor.move(MoverType.SELF, new Vec3(0, -.2, 0)); settled(t); h.assertValueEqual(count(t), 1L, "standing contact repeated landing");
                actor.setPos(actor.position().add(0, 5, 0)); t.runtime.unbind("watch/" + actor.getUUID());
                t.finish(4, () -> {
                    h.assertValueEqual(t.queries.size(), 2, "historical immediate and delayed query");
                    h.assertValueEqual(((TargetQuery.PositionCenter)t.queries.getLast().center()).position().orElseThrow(), old, "moved entity rewrote landing location");
                    h.assertTrue(t.cues.stream().anyMatch(c -> c.cue().equals("test:current_life")), "same-life recheck failed after movement");
                });
            } catch (Exception | Error e) { t.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:ground_contact_flags", maxTicks = 60)
    public void flagsTeleportAndStandingMovementCannotInventAContactButAnotherRealDescentCan(GameTestHelper h) {
        ready(h, () -> { try (var t = open(h)) {
            floor(t); var actor = t.cow(5.5, 42, 3.5); watch(t, actor);
            actor.setOnGround(true); settled(t); h.assertValueEqual(count(t), 0L, "flag is not collision");
            actor.setPos(h.absoluteVec(new Vec3(5.5, 40, 3.5))); actor.setOnGround(false); actor.move(MoverType.SELF, new Vec3(0, -.1, 0)); settled(t);
            h.assertValueEqual(count(t), 0L, "teleport or forged airborne flag while supported");
            for (int i = 0; i < 2; i++) { actor.setPos(h.absoluteVec(new Vec3(5.5, 42, 3.5))); actor.setOnGround(true); actor.move(MoverType.SELF, new Vec3(0, -2, 0)); settled(t); }
            h.assertValueEqual(count(t), 2L, "independent exact-distance descents remain legitimate"); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:ground_contact_player", maxTicks = 60)
    public void acceptedPlayerPacketProducesOnePhysicalLandingEvenIfItsGroundFlagWasAlreadyTrue(GameTestHelper h) {
        ready(h, () -> { try (var t = open(h)) {
            floor(t); placePlayer(t); watch(t, t.owner); packet(t, 42, true); settled(t); h.assertValueEqual(count(t), 0L, "airborne client ground claim");
            packet(t, 40, true); settled(t); near(h, t.owner.getY(), h.absoluteVec(new Vec3(0, 40, 0)).y, "accepted floor endpoint");
            h.assertValueEqual(count(t), 1L, "accepted physical descent"); packet(t, 40, false); packet(t, 40, true); settled(t);
            h.assertValueEqual(count(t), 1L, "repeated flags cannot replay landing");
            placePlayer(t); packet(t, 40, false); settled(t);
            h.assertValueEqual(count(t), 2L, "physical support must not depend on the client's ground flag"); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:ground_contact_rejected", maxTicks = 60)
    public void collisionDuringARejectedPlayerPacketDoesNotPublishAProvisionalLanding(GameTestHelper h) {
        ready(h, () -> { try (var t = open(h)) {
            floor(t); placePlayer(t); watch(t, t.owner); var start = t.owner.position();
            packet(t, 39.5, true); settled(t); near(h, t.owner.getY(), start.y, "vanilla rejected an endpoint inside the floor");
            h.assertValueEqual(count(t), 0L, "rejected packet leaked provisional collision"); NativeMotionGameTest.acknowledge(t.owner);
            packet(t, 40, true); settled(t); h.assertValueEqual(count(t), 1L, "later valid packet was swallowed"); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:ground_contact_managed", maxTicks = 60)
    public void nativeMovementInsideAWorldOperationQueuesItsFactAfterTheOwningRuleFrame(GameTestHelper h) {
        ready(h, () -> { try (var t = open(h)) {
            floor(t); var actor = t.cow(5.5, 42, 3.5); watch(t, actor);
            t.onCue = c -> { if (c.cue().equals("test:move")) actor.move(MoverType.SELF, new Vec3(0, -3, 0)); };
            String id = actor.getUUID().toString(); t.runtime.start(new RuleEngine.Signal("test:move", new EffectEvent(id, id, new BuffInstance.Origin(id, "input", "", ""), Set.of(), Map.of()))); settled(t);
            h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:move", "test:after_move", "test:landed"), "native fact recursively entered its producer"); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:ground_contact_unknown", maxTicks = 60)
    public void unknownLandingReactionRetainsThePhysicalLandingWithoutReplayingItsCue(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h);
            try {
                floor(t); var actor = t.cow(5.5, 42, 3.5); watch(t, actor); t.failAfterCue = true;
                actor.move(MoverType.SELF, new Vec3(0, -3, 0)); t.runtime.prepare(); h.assertTrue(t.runtime.failure().isPresent(), "unknown reaction recorded");
                h.runAfterDelay(4, () -> { try {
                    t.runtime.prepare(); near(h, actor.getY(), h.absoluteVec(new Vec3(0, 40, 0)).y, "physical move remained committed");
                    h.assertValueEqual(count(t), 1L, "failed fact replayed its external cue"); h.assertValueEqual(t.queries.size(), 1, "failed frame ran delayed work"); h.succeed();
                } finally { t.close(); } });
            } catch (Exception | Error e) { t.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:ground_contact_replaced", maxTicks = 60)
    public void historicalLandingCannotResolveToANewLifeWithTheSameUuid(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h);
            try {
                floor(t); var actor = t.cow(5.5, 42, 3.5); watch(t, actor); actor.move(MoverType.SELF, new Vec3(0, -3, 0)); settled(t);
                var uuid = actor.getUUID(); var life = MinecraftEntityLife.get(actor).id(); actor.discard();
                var replacement = EntityTypes.COW.create(h.getLevel(), EntitySpawnReason.COMMAND); replacement.setUUID(uuid); replacement.setPos(h.absoluteVec(new Vec3(5.5, 45, 3.5))); replacement.setNoGravity(true); h.getLevel().addFreshEntity(replacement); t.entities.add(replacement);
                h.assertTrue(!life.equals(MinecraftEntityLife.get(replacement).id()), "replacement life");
                t.finish(4, () -> h.assertTrue(t.cues.stream().anyMatch(c -> c.cue().equals("test:old_life")), "historical landing followed replacement"));
            } catch (Exception | Error e) { t.close(); throw e; }
        });
    }
}
