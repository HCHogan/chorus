package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.platform.minecraft.EffectConstruct;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public class ProjectileEmitterGameTest {
    private static String id(Entity entity) { return entity.getUUID().toString(); }
    private static void configure(JsonObject data) {
        var steps = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
        var position = steps.get(0).getAsJsonObject().getAsJsonObject("action"); position.addProperty("target", "victim"); position.addProperty("anchor", "body");
        var launch = steps.get(3).getAsJsonObject(); launch.addProperty("launch_as", "launch");
        var spec = launch.getAsJsonObject("projectile"); spec.addProperty("emitter", "victim");
        spec.getAsJsonObject("tracking").getAsJsonObject("turn_rate").addProperty("value", 0);
        steps.add(JsonParser.parseString("""
                {"if":{"type":"chorus:result_flag","binding":"launch","field":"launched"},
                 "then":[{"type":"chorus:play_cue","cue":"test:fired"}],
                 "else":[{"type":"chorus:play_cue","cue":"test:not_fired"}]}
                """));
    }
    private static void fire(ProjectileGameTest.Harness t, String emitter) {
        t.runtime.start(new RuleEngine.Signal("test:launch", new EffectEvent(id(t.owner), emitter,
                new BuffInstance.Origin(id(t.owner), "source", "", "test:bolt"), Set.of(), Map.of())));
    }
    @GameCase public void constructEmitterIsExcludedWhilePlayerAllianceAndDetachedCreditRemainOriginal(GameTestHelper h) throws Exception {
        var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("emitter-" + UUID.randomUUID());
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", ProjectileEmitterGameTest::configure)) {
            var at = h.absoluteVec(new Vec3(5.5, 41, 3.5));
            var spawn = new WorldConstruct.Spawn(Optional.of(new WorldPosition(h.getLevel().dimension().identifier().toString(), at.x, at.y, at.z)),
                    "test:emitter", new BuffInstance.Origin(id(t.owner), "turret", "", "test:ability"),
                    new WorldConstruct.Parameters(150, 1, 2, 2_000_000), Set.of(), t.runtime.program().program().version());
            var receipt = (WorldConstruct.Receipt) t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), spawn));
            var emitter = (EffectConstruct) h.getLevel().getEntity(UUID.fromString(receipt.entity().orElseThrow()));
            var ally = t.cow(6.5, 44, 3.5); var target = t.cow(5.5, 46, 3.5);
            scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
            t.onCue = cue -> { if (cue.cue().equals("test:fired")) h.assertValueEqual(t.projectiles.size(), 1, "firing reaction preceded physical launch"); };
            fire(t, id(emitter)); var projectile = t.projectiles.getFirst();
            h.assertValueEqual(projectile.getOwner(), t.owner, "emitter replaced native credited owner"); projectile.tick();
            h.assertValueEqual(projectile.trackingTarget(), Optional.of(id(target)), "emitter or player's ally was acquired");
            near(h, emitter.getHealth(), 150, "projectile hit its own emitter");
            emitter.discard(); t.runtime.unbind("launch"); t.runtime.unbind("boost");
            for (int i = 0; i < 10 && !projectile.isRemoved(); i++) projectile.tick();
            h.assertValueEqual(t.hits.stream().map(d -> d.target()).toList(), List.of(id(target)), "detached physical hit");
            h.assertValueEqual(t.hits.getFirst().source().owner(), id(t.owner), "damage credit changed to construct");
            h.assertValueEqual(target.getLastDamageSource().getEntity(), t.owner, "native damage attacker changed");
            near(h, target.getHealth(), 80, "captured damage survived emitter destruction"); near(h, ally.getHealth(), 100, "ally damaged");
            h.assertTrue(t.runtime.failure().isEmpty(), "emitter runtime failed");
        } finally { scoreboard.removePlayerTeam(team); }
        h.succeed();
    }
    @GameCase public void missingEmitterPositionReturnsFailureWithoutPretendingAProjectileWasFired(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "projectile_tracking", ProjectileEmitterGameTest::configure)) {
            fire(t, UUID.randomUUID().toString());
            h.assertTrue(t.projectiles.isEmpty() && t.hits.isEmpty(), "missing position created a projectile");
            h.assertValueEqual(t.cues.size(), 1, "missing rejection reaction"); h.assertValueEqual(t.cues.getFirst().cue(), "test:not_fired", "failed launch removed readiness");
            h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "known rejection poisoned runtime");
        } h.succeed();
    }
}
