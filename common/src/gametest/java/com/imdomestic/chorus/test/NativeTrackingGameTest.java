package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.EffectSource;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.platform.minecraft.MinecraftNativeTracking;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/** Real vanilla ticks and save/load, not a substitute homing simulator. */
public class NativeTrackingGameTest {
    static String id(Entity e) { return e.getUUID().toString(); }
    static CompoundTag save(ShulkerBullet p) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, p.level().registryAccess());
        p.saveWithoutId(output); return output.buildResult();
    }
    static ValueInput input(GameTestHelper h, CompoundTag tag) { return TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag); }
    static ShulkerBullet shot(GameTestHelper h, LivingEntity owner, Entity target) {
        var p = new ShulkerBullet(h.getLevel(), owner, target, Direction.Axis.Y);
        p.setPos(target.getBoundingBox().getCenter().add(0, 8, 0)); p.setNoGravity(true);
        h.getLevel().addFreshEntity(p); return p;
    }
    private static final class Harness implements AutoCloseable {
        final NativeRangedGameTest.Harness base;
        final GameTestHelper h; final Mob owner, target;
        final List<ShulkerBullet> shots = new ArrayList<>();
        Harness(GameTestHelper h) {
            this.h = h; base = new NativeRangedGameTest.Harness(h, EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("target_gates")).getOrThrow());
            owner = base.mob(EntityTypes.SHULKER, 2, 2); target = base.mob(EntityTypes.COW, 2, 4);
            owner.addTag("test:trackable_origin");
        }
        ShulkerBullet shot() { var p = NativeTrackingGameTest.shot(h, owner, target); shots.add(p); return p; }
        void gate(String name, String bundle, Entity holder) {
            base.runtime.bind(new EffectSource(name, "test:" + bundle, id(holder), new BuffInstance.Origin(id(owner), name, "", ""), Set.of()));
        }
        void allowed(boolean allowed) { h.assertValueEqual(base.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(), allowed, "native tracking qualification"); base.settled(); }
        ShulkerBullet load(CompoundTag tag) {
            var p = new ShulkerBullet(EntityTypes.SHULKER_BULLET, h.getLevel()); p.load(input(h, tag));
            h.getLevel().addFreshEntity(p); shots.add(p); return p;
        }
        @Override public void close() { shots.forEach(Entity::discard); base.close(); }
    }
    @GameCase public void absentRuntimeKeepsVanillaHomingAndActuallyMissingTargetsAreCleared(GameTestHelper h) {
        try (var t = new Harness(h)) {
            t.base.runtime.close(); var p = t.shot(); p.tick();
            h.assertTrue(p.getDeltaMovement().lengthSqr() > 0 && !p.isRemoved(), "vanilla homing did not run without a runtime");
            t.target.discard(); p.tick();
            h.assertTrue(input(h, save(p)).read("Target", UUIDUtil.CODEC).isEmpty(), "actual target removal must retain vanilla clearing behavior");
        } h.succeed();
    }
    @GameCase public void nativeTargetDenialPreservesGravityReferenceAndCapturedCreditUntilReacquisition(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var p = t.shot(); p.tick(); p.tick(); t.allowed(true);
            h.assertValueEqual(t.base.runtime.projectileTrackingReport().orElseThrow().phase(), ActionGate.Phase.CONTINUE, "retained native target is continue");
            t.gate("hidden", "target_gate", t.target); t.owner.removeTag("test:trackable_origin"); t.owner.discard();
            p.setNoGravity(false); var before = p.getDeltaMovement(); p.tick(); t.allowed(false);
            near(h, p.getDeltaMovement().x, before.x, "denied homing changed horizontal velocity");
            near(h, p.getDeltaMovement().y, before.y - .04, "native no-target gravity was bypassed");
            near(h, p.getDeltaMovement().z, before.z, "denied homing changed depth velocity");
            var report = t.base.runtime.projectileTrackingReport().orElseThrow();
            h.assertValueEqual(report.input().references().get("emitter"), "", "native credit owner is not proof of physical emitter");
            h.assertValueEqual(report.input().references().get("tracking_owner_kind"), "mob", "owner removal erased captured combatant identity");
            h.assertTrue(report.input().observedEntities().orElseThrow().require(id(t.owner)).isEmpty(), "removed owner must be unavailable in current observations");
            h.assertValueEqual(input(h, save(p)).read("Target", UUIDUtil.CODEC), Optional.of(t.target.getUUID()), "temporary denial erased the real saved target");
            t.base.runtime.unbind("hidden"); p.tick(); t.allowed(true);
            h.assertValueEqual(t.base.runtime.projectileTrackingReport().orElseThrow().phase(), ActionGate.Phase.START, "released denial must reacquire");
            h.assertValueEqual(t.base.runtime.projectileTrackingReport().orElseThrow().input().source().source(), id(p), "native projectile credit identity");
        } h.succeed();
    }
    @GameCase public void actorGateAndContinueOnlyGateUseNativeOwnerAndPhase(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var p = t.shot(); t.gate("retained", "retain_gate", t.target);
            p.tick(); t.allowed(true); p.tick(); t.allowed(false); p.tick(); t.allowed(true);
            t.base.runtime.unbind("retained"); t.gate("blind", "actor_gate", t.owner); p.tick(); t.allowed(false);
            h.assertValueEqual(t.base.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().denials().getFirst().holder(), id(t.owner), "actor gate holder changed to target");
            t.base.runtime.unbind("blind"); p.tick(); t.allowed(true);
        } h.succeed();
    }
    @GameCase public void deniedNativeSteeringStillHitsDamagesAndAppliesLevitationOnce(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var p = t.shot(); t.gate("hidden", "target_gate", t.target);
            p.setPos(t.target.getBoundingBox().getCenter().add(0, 0, -2)); p.setDeltaMovement(new Vec3(0, 0, 3)); p.tick(); t.allowed(false);
            h.assertTrue(p.isRemoved() && t.target.hasEffect(MobEffects.LEVITATION), "native impact/destruction or levitation was bypassed");
            near(h, t.target.getHealth(), 96, "tracking restriction must not prevent native four-damage impact");
            p.tick(); near(h, t.target.getHealth(), 96, "removed native projectile replayed impact");
        } h.succeed();
    }
    @GameCase public void unknownNativeQueryStopsBeforeMovementAndAffectedSiblingsStopOnTheirNextTick(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var p = t.shot(); var sibling = t.shot(); var before = p.position();
            t.gate("bad", "bad_tracking", t.target); p.tick();
            h.assertTrue(t.base.runtime.failure().isPresent() && p.isRemoved(), "unknown tracking query did not stop native flight");
            var report = t.base.runtime.projectileTrackingReport().orElseThrow();
            h.assertTrue(report.failure().isPresent() && report.decision().isEmpty(), "unknown query was misreported as a normal denial");
            sibling.tick(); p.tick(); h.assertTrue(sibling.isRemoved(), "failed runtime authorized affected sibling");
            near(h, p.position().distanceTo(before), 0, "failed flight moved"); near(h, t.target.getHealth(), 100, "failed flight dealt damage");
        } h.succeed();
    }
    @GameCase public void nativeCreditSurvivesSaveLoadWithoutOwnerAndChangesWhenOwnerIsReassigned(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var p = t.shot(); t.gate("hidden", "target_gate", t.target); t.owner.discard(); p.tick(); t.allowed(false);
            var saved = save(p); p.discard(); var restored = t.load(saved); restored.tick(); t.allowed(false);
            near(h, t.base.runtime.projectileTrackingReport().orElseThrow().input().numbers().get("tracking_age").value(), .05, "reload reset native simulated flight age");
            h.assertValueEqual(t.base.runtime.projectileTrackingReport().orElseThrow().input().references().get("tracking_owner_kind"), "mob", "restored credit forgot removed owner class");
            var player = h.makeMockServerPlayerInLevel();
            try {
                restored.setOwner(player); restored.tick(); t.allowed(true);
                var report = t.base.runtime.projectileTrackingReport().orElseThrow();
                h.assertValueEqual(report.input().actor(), id(player), "reassigned owner was ignored");
                h.assertValueEqual(report.input().references().get("tracking_owner_kind"), "player", "reassigned owner kept old combatant class");
                h.assertTrue(!report.input().source().tags().contains("test:trackable_origin"), "reassigned owner inherited old source tags");
            } finally { player.discard(); }
        } h.succeed();
    }
    @GameCase public void mismatchedPersistedCreditCannotInventAnUnavailableOwnersClassification(GameTestHelper h) {
        try (var t = new Harness(h)) {
            var p = t.shot(); var saved = save(p); p.discard(); t.owner.discard();
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            output.store("Owner", UUIDUtil.CODEC, UUID.randomUUID()); saved.merge(output.buildResult());
            t.gate("hidden", "target_gate", t.target); var restored = t.load(saved); restored.tick(); t.allowed(true);
            var report = t.base.runtime.projectileTrackingReport().orElseThrow();
            h.assertValueEqual(report.input().references().get("tracking_owner_kind"), "unavailable", "stale credit guessed new owner's class");
            h.assertTrue(report.input().source().tags().isEmpty(), "mismatched credit retained old tags");
            h.assertValueEqual(input(h, save(restored)).read("chorus:tracking_credit", MinecraftNativeTracking.Credit.CODEC).orElseThrow().kind(), MinecraftNativeTracking.OwnerKind.UNAVAILABLE, "saved credit did not reconcile");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:native_gambler_expiry", maxTicks = 30)
    public void actualDodgeBlocksNativeCombatantHomingUntilRealExpiry(GameTestHelper h) throws Exception {
        var t = GamblersTrackingGameTest.open(h); ShulkerBullet p = null;
        try {
            var caster = GamblersTrackingGameTest.shooter(t, false); p = shot(h, caster, t.owner); p.tick();
            h.assertTrue(t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(), "initial native lock denied");
            GamblersDodgeGameTest.dodge(t); caster.discard(); GamblersDodgeGameTest.select(t, false); var before = p.getDeltaMovement(); p.tick();
            h.assertTrue(!t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(), "real Dodge did not suppress native combatant homing");
            near(h, p.getDeltaMovement().distanceTo(before), 0, "no-gravity native denied flight accelerated");
            var bullet = p;
            h.runAfterDelay(8, () -> { try {
                t.runtime.prepare(); GamblersTrackingGameTest.healthy(t);
                h.assertTrue(GamblersTrackingGameTest.active(t).isEmpty(), "Dodge window did not expire");
                bullet.tick(); h.assertTrue(t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(), "native target not reacquired after real expiry");
                near(h, t.owner.getHealth(), 10, "distant bullet unexpectedly hit"); h.succeed();
            } finally { bullet.discard(); t.close(); } });
        } catch (Throwable error) { if (p != null) p.discard(); t.close(); throw error; }
    }
    @GameCase public void actualDodgeLeavesNativeGuardianHomingAndCombatantCollisionIntact(GameTestHelper h) throws Exception {
        try (var t = GamblersTrackingGameTest.open(h)) {
            var player = GamblersTrackingGameTest.shooter(t, true); var mob = GamblersTrackingGameTest.shooter(t, false);
            var guardianBullet = shot(h, player, t.owner); var combatantBullet = shot(h, mob, t.owner);
            try {
                GamblersDodgeGameTest.dodge(t); guardianBullet.tick();
                h.assertTrue(t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(), "Guardian native projectile was denied");
                combatantBullet.setPos(t.owner.getBoundingBox().getCenter().add(0, 0, -2)); combatantBullet.setDeltaMovement(new Vec3(0, 0, 3)); combatantBullet.tick();
                h.assertTrue(!t.runtime.projectileTrackingReport().orElseThrow().decision().orElseThrow().allowed(), "Combatant native homing was allowed");
                near(h, t.owner.getHealth(), 6, "native impact during Dodge did not retain four damage");
                h.assertTrue(t.owner.hasEffect(MobEffects.LEVITATION) && GamblersTrackingGameTest.active(t).isPresent(), "native impact or paid Dodge window lost");
                GamblersTrackingGameTest.healthy(t);
            } finally { guardianBullet.discard(); combatantBullet.discard(); }
        } h.succeed();
    }
}
