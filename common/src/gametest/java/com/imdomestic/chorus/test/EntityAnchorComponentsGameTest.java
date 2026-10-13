package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

public class EntityAnchorComponentsGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        return new ProjectileGameTest.Harness(h, "entity_anchor_components", _ -> {}, true);
    }
    static void arm(ProjectileGameTest.Harness t, LivingEntity entity) {
        String id = entity.getUUID().toString(); var origin = new BuffInstance.Origin(id, "cast", "", "test:dive");
        if (!t.runtime.state().engine().domain().sources().containsKey("arm/" + id))
            t.runtime.bind(new EffectSource("arm/" + id, "test:arm", id, origin, Set.of()));
        t.runtime.start(new RuleEngine.Signal("test:arm", new EffectEvent(id, id, origin, Set.of(), Map.of())));
    }
    static void land(ProjectileGameTest.Harness t, LivingEntity entity) {
        entity.move(MoverType.SELF, new Vec3(0, -3, 0)); t.runtime.prepare(); ShieldThrowGameTest.settled(t);
    }
    @GameCase(environment = "chorus_gametest:anchor_component_landing", maxTicks = 60)
    public void pendingStateMatchesRealLandingAndDelayedWorkRetainsItsHistoricalCenter(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h);
            try {
                GroundContactGameTest.floor(t); var actor = t.cow(5.5, 42, 3.5); arm(t, actor); land(t, actor); var point = actor.position();
                h.assertTrue(t.cues.isEmpty(), "completion should still be delayed"); actor.setPos(actor.position().add(0, 5, 0));
                t.finish(4, () -> {
                    h.assertValueEqual(t.cues.stream().filter(c -> c.cue().equals("test:completed")).count(), 1L, "one same-life completion");
                    var center = ((TargetQuery.PositionCenter)t.queries.getFirst().center()).position().orElseThrow();
                    HealingGameTest.near(h, center.y(), point.y, "historical landing center moved with actor");
                });
            } catch (Exception | Error e) { t.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:anchor_component_replacement", maxTicks = 60)
    public void replacementWithTheSameUuidCannotCompleteAnOldCastButCanBeArmedIndependently(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h);
            try {
                GroundContactGameTest.floor(t); var actor = t.cow(5.5, 42, 3.5); arm(t, actor); var uuid = actor.getUUID(); actor.discard();
                var replacement = EntityTypes.COW.create(h.getLevel(), EntitySpawnReason.COMMAND); replacement.setUUID(uuid);
                replacement.setPos(h.absoluteVec(new Vec3(5.5, 42, 3.5))); replacement.setNoGravity(true); h.getLevel().addFreshEntity(replacement); t.entities.add(replacement);
                land(t, replacement); h.assertTrue(t.cues.isEmpty(), "new life received old pending completion");
                var pending = t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:pending")).findFirst().orElseThrow();
                h.assertTrue(!pending.components().entityAnchors().get("initial").requireObservation().reference().life().equals(com.imdomestic.chorus.platform.minecraft.MinecraftEntityLife.get(replacement).id()), "saved life was silently retargeted");
                replacement.setPos(h.absoluteVec(new Vec3(5.5, 42, 3.5))); arm(t, replacement); land(t, replacement);
                t.finish(4, () -> h.assertValueEqual(t.cues.stream().filter(c -> c.cue().equals("test:completed")).count(), 1L, "new independent cast should complete once"));
            } catch (Exception | Error e) { t.close(); throw e; }
        });
    }
}
