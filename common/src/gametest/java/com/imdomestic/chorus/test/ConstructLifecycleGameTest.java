package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;

public class ConstructLifecycleGameTest {
    private static EffectEvent end(com.imdomestic.chorus.platform.minecraft.EffectConstruct entity) {
        return (EffectEvent) entity.ended().orElseThrow().payload();
    }
    private static long cues(ConstructGameTest.Harness t, String cue) { return t.cues.stream().filter(c -> c.cue().equals(cue)).count(); }
    @GameCase(environment="chorus_gametest:construct_end_discard")
    public void directRemovalSettlesBeforeCheckpointAndCancelsOnlyItsOwnTimersOnce(GameTestHelper h) {
        try (var t = new ConstructGameTest.Harness(h, true)) {
            t.cast(10); var first = t.construct();
            t.point = new WorldPosition(t.point.dimension(), t.point.x() + 2, t.point.y(), t.point.z()); t.cast(10); var second = t.construct();
            var before = first.position(); first.discard();
            h.assertTrue(t.behavior(first), "entity callback executed rule actions recursively");
            var ended = end(first); var positions = ended.observedEntities().orElseThrow();
            h.assertTrue(ended.flags().get("removed") && !ended.flags().get("destroyed") && !ended.flags().get("expired"), "discard became a kill or expiry");
            h.assertValueEqual(ended.references().get("reason"), "discarded", "native removal cause");
            h.assertValueEqual(ended.source(), t.source.origin(), "original caster credit");
            h.assertTrue(positions.require(first.getUUID().toString()).isEmpty(), "removed target claimed to be available");
            h.assertValueEqual(positions.requirePosition(first.getUUID().toString(), TargetQuery.Anchor.FEET).orElseThrow(),
                    new WorldPosition(t.point.dimension(), before.x, before.y, before.z), "last position snapshot");
            first.setPos(before.add(20, 0, 0)); first.setRemoved(Entity.RemovalReason.KILLED);
            h.assertValueEqual(end(first), ended, "repeat removal rewrote original fact");
            var saved = t.runtime.checkpoint();
            h.assertTrue(!t.behavior(first) && t.behavior(second), "checkpoint did not settle only the removed construct");
            h.assertValueEqual(saved.effects().timeline().domain().timers().size(), 1, "removed behavior timer persisted");
            h.assertValueEqual(cues(t, "test:construct_ended"), 1L, "duplicate end notification");
            h.assertValueEqual(cues(t, "test:kill"), 0L, "discard generated kill rewards");
            t.healthy(); h.assertValueEqual(cues(t, "test:construct_ended"), 1L, "prepare replayed fact");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:construct_end_unload", maxTicks=25)
    public void directSetRemovedAndNaturalExpiryKeepDistinctReasonsAfterOwnerDisappears(GameTestHelper h) {
        var t = new ConstructGameTest.Harness(h, true);
        try {
            t.cast(10); var unloaded = t.construct(); unloaded.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK); t.healthy();
            h.assertTrue(!t.behavior(unloaded) && end(unloaded).flags().get("removed"), "setRemoved bypassed end handling");
            h.assertValueEqual(end(unloaded).references().get("reason"), "unloaded_to_chunk", "unload cause");
            t.cast(.15); var expired = t.construct(); t.owner.discard();
            t.at(6, () -> {
                h.assertTrue(expired.isRemoved() && end(expired).flags().get("expired"), "natural expiry misclassified");
                h.assertTrue(end(expired).numbers().get("age").value() >= .15, "age was not captured at removal");
                h.assertValueEqual(end(expired).source(), t.source.origin(), "owner removal lost attribution");
                h.assertValueEqual(cues(t, "test:construct_ended"), 2L, "missing or duplicate endings");
                h.assertValueEqual(cues(t, "test:kill"), 0L, "unload or expiry generated a kill");
                h.assertTrue(t.state().timers().isEmpty(), "ended construct retained behavior timers"); t.close(); h.succeed();
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:construct_end_death", maxTicks=25)
    public void nativeDeathHasOneKillFollowedByOneDestructionFact(GameTestHelper h) {
        var t = new ConstructGameTest.Harness(h, true);
        try {
            t.cast(10); var entity = t.construct();
            h.assertTrue(entity.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(t.owner), 1000), "native lethal damage rejected");
            h.assertValueEqual(cues(t, "test:kill"), 1L, "native kill count");
            t.at(4, () -> {
                h.assertTrue(end(entity).flags().get("destroyed"), "lethal removal not classified as destroyed");
                h.assertValueEqual(cues(t, "test:construct_ended"), 1L, "destruction fact count");
                h.assertValueEqual(cues(t, "test:kill"), 1L, "destruction issued a second kill");
                t.close(); h.succeed();
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:construct_end_world")
    public void worldRemovalWaitsForTheParentFrameAndUnknownResultsNeverReplay(GameTestHelper h) {
        for (boolean unknown : List.of(false, true)) try (var t = new ConstructGameTest.Harness(h, true)) {
            t.cast(10); t.erase = t.construct(); t.eraseThrows = unknown;
            var signal = new RuleEngine.Signal("test:erase", new EffectEvent(t.source.holder(), t.source.holder(), t.source.origin(), Set.of(), Map.of()));
            if (unknown) {
                try { t.runtime.start(signal); throw new AssertionError("unknown world result accepted"); } catch (IllegalStateException expected) {}
                h.assertTrue(t.runtime.failure().isPresent() && t.erase.isRemoved() && t.behavior(t.erase), "unknown result replayed or rewound state");
                t.runtime.prepare();
                try { t.runtime.start(signal); throw new AssertionError("failed runtime resumed"); } catch (IllegalStateException expected) {}
                h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:erase"), "failed action or end reward replayed");
            } else {
                t.runtime.start(signal); t.healthy();
                h.assertTrue(!t.behavior(t.erase), "known removal left its behavior");
                h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:erase", "test:after_erase", "test:construct_ended"), "facts did not wait for the parent frame");
            }
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:construct_end_close")
    public void runtimeCloseDropsPendingEndFactsAndDoesNotInventRewards(GameTestHelper h) {
        try (var t = new ConstructGameTest.Harness(h, true)) {
            t.cast(10); var entity = t.construct(); entity.discard(); t.runtime.close();
            h.assertTrue(entity.isRemoved() && t.cues.isEmpty(), "runtime close executed pending rewards");
        }
        try (var t = new ConstructGameTest.Harness(h, true)) {
            t.cast(10); var entity = t.construct(); t.runtime.close();
            h.assertTrue(entity.isRemoved() && t.cues.isEmpty(), "runtime close fabricated an ending reward");
        } h.succeed();
    }
}
