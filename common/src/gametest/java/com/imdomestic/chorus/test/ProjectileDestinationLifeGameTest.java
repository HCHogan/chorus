package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.EntityAnchor;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;

/** A locked life is rechecked by native steering/arrival and immediately at catch input. */
public class ProjectileDestinationLifeGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean guarded) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "projectile", data -> {
            var part = ThreadedSpikeGameTest.json("projectile_destination_life");
            var body = part.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
            var delayed = body.remove(3).getAsJsonObject().getAsJsonArray("do");
            if (!guarded) delayed.get(0).getAsJsonObject().getAsJsonObject("projectile").getAsJsonObject("destination").remove("reference");
            // Core tests retain the one-second scan delay; these cases isolate native in-flight checks.
            delayed.forEach(body::add); GamblersDodgeGameTest.merge(data, part);
        }, true);
        t.owner.setGameMode(GameType.SURVIVAL); t.owner.setHealth(20); t.owner.getFoodData().setFoodLevel(0);
        String id = t.owner.getUUID().toString();
        t.runtime.bind(new EffectSource("destination", "test:destination_life", id, new BuffInstance.Origin(id, "destination", "", "test:seeker"), Set.of()));
        return t;
    }
    static EffectProjectile fire(ProjectileGameTest.Harness t, LivingEntity target) {
        String owner = t.owner.getUUID().toString();
        t.runtime.start(new RuleEngine.Signal("test:destination_launch", new EffectEvent(owner, target.getUUID().toString(), new BuffInstance.Origin(owner, "destination", "", "test:seeker"), Set.of(), Map.of())));
        t.h.assertValueEqual(t.projectiles.size(), 1, "one physical seeker"); return t.projectiles.getFirst();
    }
    static LivingEntity victim(ProjectileGameTest.Harness t) { var e = t.cow(2.5, 45, 3.5); e.setHealth(20); return e; }
    static LivingEntity replace(ProjectileGameTest.Harness t, LivingEntity old, boolean saveReload) {
        var saved = EntityAnchorGameTest.save(t.h, old); var pos = old.position(); var uuid = old.getUUID(); int numeric = old.getId(); old.discard();
        var replacement = EntityTypes.COW.create(t.h.getLevel(), EntitySpawnReason.COMMAND);
        if (saveReload) replacement.load(TagValueInput.create(ProblemReporter.DISCARDING, t.h.getLevel().registryAccess(), saved));
        else {
            replacement.setUUID(uuid); replacement.setPos(pos); replacement.setNoGravity(true); replacement.setNoAi(true);
            replacement.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100); replacement.setHealth(20);
        }
        replacement.setId(numeric); t.h.assertTrue(t.h.getLevel().addFreshEntity(replacement), "replacement indexed"); t.entities.add(replacement);
        t.h.assertTrue(t.resolve(uuid.toString()) == replacement, "same UUID resolves to replacement"); return replacement;
    }
    static void arrived(ProjectileGameTest.Harness t, EffectProjectile p, LivingEntity target) {
        for (int i = 0; i < 30 && !p.isRemoved(); i++) p.tick();
        t.h.assertTrue(p.isRemoved(), "seeker reached terminal arrival"); near(t.h, target.getHealth(), 25, "actual arrival-dependent health write");
        t.h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:arrived"), "one arrival continuation");
        ShieldThrowGameTest.settled(t);
    }
    static void lost(ProjectileGameTest.Harness t, EffectProjectile p) {
        p.tick(); p.tick(); t.h.assertTrue(p.isRemoved(), "lost life consumed physical seeker");
        t.h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:lost"), "one loss and no arrival/catch");
        ShieldThrowGameTest.settled(t);
    }
    @GameCase(environment = "chorus_gametest:destination_life_move", maxTicks = 60)
    public void sameLifeMovementKeepsTrackingAndArrivalAfterSourceRemoval(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var target = victim(t); var p = fire(t, target); String life = MinecraftEntityLife.get(target).id();
            p.tick(); target.setPos(target.position().add(1, 0, 0)); t.runtime.unbind("destination");
            h.assertValueEqual(p.launch().orElseThrow().parameters().destination().orElseThrow().reference().orElseThrow().life(), life, "life frozen independently of position/source"); arrived(t, p, target);
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:destination_life_replace", maxTicks = 60)
    public void sameUuidAndNumericIdReplacementCannotInheritTheLockedFlight(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var old = victim(t); var p = fire(t, old); p.tick(); String life = MinecraftEntityLife.get(old).id(); var replacement = replace(t, old, false);
            h.assertTrue(!life.equals(MinecraftEntityLife.get(replacement).id()), "replacement needs another life"); lost(t, p); near(h, replacement.getHealth(), 20, "new life received old arrival reward");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:destination_life_reload", maxTicks = 60)
    public void nativeSaveReloadOfTheSameLifeRemainsAValidDestination(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var old = victim(t); var p = fire(t, old); String life = MinecraftEntityLife.get(old).id(); var reloaded = replace(t, old, true);
            h.assertValueEqual(MinecraftEntityLife.get(reloaded).id(), life, "persisted life identity"); arrived(t, p, reloaded);
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:destination_life_missing", maxTicks = 60)
    public void removedOrDeadDestinationEndsWithoutAnArrivalReward(GameTestHelper h) throws Exception {
        for (boolean removed : List.of(false, true)) try (var t = open(h, true)) {
            var target = victim(t); var p = fire(t, target); if (removed) target.discard(); else target.setHealth(0); lost(t, p);
        } h.succeed();
    }
    static ServerPlayer respawn(ProjectileGameTest.Harness t, boolean sameLife) {
        var pos = t.owner.position(); String oldLife = MinecraftEntityLife.get(t.owner).id();
        if (!sameLife) { t.owner.hurtServer(t.h.getLevel(), t.h.getLevel().damageSources().genericKill(), 1000); t.h.assertTrue(t.owner.isDeadOrDying(), "actual death before respawn"); }
        var replacement = t.h.getLevel().getServer().getPlayerList().respawn(t.owner, sameLife, sameLife ? Entity.RemovalReason.CHANGED_DIMENSION : Entity.RemovalReason.KILLED);
        t.entities.add(replacement); replacement.setPos(pos); replacement.setNoGravity(true); replacement.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100); replacement.setHealth(20); replacement.setGameMode(GameType.SURVIVAL);
        t.h.assertValueEqual(replacement.getUUID(), t.owner.getUUID(), "respawn reused UUID");
        t.h.assertValueEqual(MinecraftEntityLife.get(replacement).id().equals(oldLife), sameLife, "native life replacement policy"); return replacement;
    }
    @GameCase(environment = "chorus_gametest:destination_life_respawn", maxTicks = 60)
    public void actualNewPlayerLifeCannotCatchBeforeTheNextProjectileTick(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var p = fire(t, t.owner); var replacement = respawn(t, false);
            h.assertTrue(t.runtime.catchProjectile(replacement).isEmpty(), "new life caught old flight before tracking could reject it");
            lost(t, p); near(h, replacement.getHealth(), 20, "new life received old catch reward");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:destination_life_preserve", maxTicks = 60)
    public void keepAllPlayerReplacementPreservesCatchEligibility(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var p = fire(t, t.owner); var replacement = respawn(t, true);
            h.assertValueEqual(t.runtime.catchProjectile(replacement).orElseThrow(), p.getUUID(), "same life can catch"); near(h, replacement.getHealth(), 30, "actual same-life catch reward");
            h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:caught"), "one same-life catch"); ShieldThrowGameTest.settled(t);
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:destination_life_legacy", maxTicks = 60)
    public void omittedReferencePreservesExplicitIdentityOnlyLegacyBehavior(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            var p = fire(t, t.owner); var replacement = respawn(t, false);
            h.assertTrue(p.launch().orElseThrow().parameters().destination().orElseThrow().reference().isEmpty(), "legacy destination acquired an invented life");
            h.assertValueEqual(t.runtime.catchProjectile(replacement).orElseThrow(), p.getUUID(), "legacy target remains UUID-only"); near(h, replacement.getHealth(), 30, "legacy reward policy preserved");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:destination_life_dimension", maxTicks = 60)
    public void foreignDimensionReferenceCannotMatchEvenTheSameUuidAndLife(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var original = fire(t, t.owner); var launch = original.launch().orElseThrow(); original.discard();
            var parameters = launch.parameters(); var destination = parameters.destination().orElseThrow(); var ref = destination.reference().orElseThrow();
            var foreign = new ProjectileDestination(destination.target(), destination.turnRate(), destination.arrivalRadius(), destination.anchor(), destination.collideEntities(), destination.catching(),
                    Optional.of(new EntityAnchor.Reference(ref.target(), ref.life(), "minecraft:the_nether")));
            var command = new ProjectileFlight.Launch(launch.position(), launch.direction(),
                    new ProjectileFlight.Parameters(parameters.speed(), parameters.gravity(), parameters.drag(), parameters.lifetimeMicros(), parameters.collision(), parameters.tracking(), Optional.of(foreign)),
                    launch.owner(), launch.continuation(), launch.impactSlot(), launch.member(), launch.emitter());
            var receipt = (ProjectileFlight.Receipt) t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), command));
            var p = (EffectProjectile) h.getLevel().getEntity(UUID.fromString(receipt.entity().orElseThrow())); t.projectiles.add(p);
            h.assertTrue(t.runtime.catchProjectile(t.owner).isEmpty(), "foreign dimension life authorized catch"); lost(t, p); near(h, t.owner.getHealth(), 20, "foreign reference rewarded local entity");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:destination_life_unknown", maxTicks = 60)
    public void unknownArrivalRewardRetainsNativeWriteWithoutRepeatingTheLockedFlight(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var target = victim(t); var p = fire(t, target); t.failAfterHealing = true;
            for (int i = 0; i < 30 && !p.isRemoved(); i++) p.tick();
            h.assertTrue(t.runtime.failure().isPresent() && p.isRemoved(), "unknown arrival should stop runtime after physical arrival"); near(h, target.getHealth(), 25, "committed healing retained");
            p.tick(); MinecraftEffectRuntime.tick(h.getLevel()); near(h, target.getHealth(), 25, "failed continuation replayed health write"); h.assertTrue(t.cues.isEmpty(), "unknown healing acknowledged downstream reward");
        } h.succeed();
    }
}
