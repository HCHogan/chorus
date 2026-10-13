package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

public class ConstructConsumptionGameTest {
    static ConstructGameTest.Harness open(GameTestHelper h) {
        var t = new ConstructGameTest.Harness(h, false, data -> {
            var consumption = ThreadedSpikeGameTest.json("construct_consumption");
            var own = consumption.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).deepCopy().getAsJsonObject();
            own.addProperty("on", "test:consume_self");
            own.add("if", com.google.gson.JsonParser.parseString("{\"type\":\"chorus:target_is\",\"left\":\"self\",\"right\":\"event_actor\"}"));
            own.getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonObject("action").addProperty("target", "self");
            // The own-behavior scenario does not grant the unrelated ten-second test:paid buff to the construct.
            own.getAsJsonArray("do").remove(0);
            for (var bundle : data.getAsJsonArray("bundles")) if (bundle.getAsJsonObject().get("id").getAsString().equals("test:construct_behavior"))
                bundle.getAsJsonObject().getAsJsonArray("rules").add(own);
            GamblersDodgeGameTest.merge(data, consumption);
        });
        t.runtime.bind(new EffectSource("consume", "test:consume_construct", t.source.holder(), t.source.origin(), Set.of()));
        return t;
    }
    static EntityAnchor.Reference anchor(ConstructGameTest.Harness t, LivingEntity entity) {
        return ((EntityAnchor.Result)t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(90, 0, 0), new EntityAnchor.Query(entity.getUUID().toString(), TargetQuery.Anchor.FEET, Optional.empty())))).requireObservation().reference();
    }
    static WorldConstruct.ConsumeReceipt claim(ConstructGameTest.Harness t, EntityAnchor.Reference reference) {
        return (WorldConstruct.ConsumeReceipt)t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(91, 0, 0), new WorldConstruct.Consume(reference)));
    }
    static void consume(ConstructGameTest.Harness t, LivingEntity entity, boolean self) {
        String id = entity.getUUID().toString();
        t.runtime.start(new RuleEngine.Signal(self ? "test:consume_self" : "test:consume", new EffectEvent(self ? id : t.source.holder(), id, t.source.origin(), Set.of(), Map.of())));
    }
    static void consumed(GameTestHelper h, ConstructGameTest.Harness t, EffectConstruct entity) {
        t.healthy(); h.assertTrue(entity.isRemoved() && !t.behavior(entity), "native claim did not remove construct behavior");
        h.assertValueEqual(t.consumptions.size(), 1, "one native consume"); h.assertValueEqual(t.consumptions.getFirst().outcome(), WorldConstruct.ConsumeOutcome.CONSUMED, "confirmed claim");
        h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:reward", "test:ended"), "parent reward then lifecycle; no fabricated kill/death");
        var end = (EffectEvent)entity.ended().orElseThrow().payload(); h.assertTrue(end.flags().get("removed") && !end.flags().get("destroyed"), "consumption invented destruction");
    }
    @GameCase(environment = "chorus_gametest:construct_consume_once")
    public void nativeConsumeRemovesOneExactConstructAndRewardsOnceWithoutDamageOrDeath(GameTestHelper h) {
        try (var t = open(h)) {
            t.cast(10); var first = t.construct(); var reference = anchor(t, first);
            t.point = new WorldPosition(t.point.dimension(), t.point.x() + 3, t.point.y(), t.point.z()); t.cast(10); var second = t.construct();
            consume(t, first, false); consumed(h, t, first);
            h.assertTrue(second.isAlive() && t.behavior(second) && t.owner.isAlive(), "unrelated life was consumed");
            h.assertValueEqual(claim(t, reference).outcome(), WorldConstruct.ConsumeOutcome.MISSING, "repeated exact claim");
            h.assertValueEqual(first.getHealth(), 150f, "consumption changed health"); h.succeed();
        }
    }
    @GameCase(environment = "chorus_gametest:construct_consume_self")
    public void consumingFromItsOwnBuffFinishesDependentActionsBeforeQueuedLifecycleCleanup(GameTestHelper h) {
        try (var t = open(h)) { t.cast(10); var entity = t.construct(); consume(t, entity, true); consumed(h, t, entity); h.succeed(); }
    }
    @GameCase(environment = "chorus_gametest:construct_consume_identity")
    public void staleLifeWrongDimensionAndOrdinaryEntitiesCannotBeConsumed(GameTestHelper h) {
        try (var t = open(h)) {
            t.cast(10); var entity = t.construct(); var reference = anchor(t, entity);
            h.assertValueEqual(claim(t, new EntityAnchor.Reference(reference.target(), "old-life", reference.dimension())).outcome(), WorldConstruct.ConsumeOutcome.REPLACED, "stale life");
            h.assertValueEqual(claim(t, new EntityAnchor.Reference(reference.target(), reference.life(), "minecraft:the_nether")).outcome(), WorldConstruct.ConsumeOutcome.WRONG_DIMENSION, "wrong dimension");
            h.assertValueEqual(claim(t, anchor(t, t.owner)).outcome(), WorldConstruct.ConsumeOutcome.NOT_CONSTRUCT, "ordinary entity");
            h.assertTrue(entity.isAlive() && t.owner.isAlive() && t.behavior(entity), "rejection mutated world"); h.succeed();
        }
    }
    @GameCase(environment = "chorus_gametest:construct_consume_inactive")
    public void deadAndUnmanagedConstructsCannotBeClaimedForRewards(GameTestHelper h) {
        try (var t = open(h)) {
            t.cast(10); var entity = t.construct(); var reference = anchor(t, entity); entity.setHealth(0);
            h.assertValueEqual(claim(t, reference).outcome(), WorldConstruct.ConsumeOutcome.INACTIVE, "dead construct");
            var unmanaged = new EffectConstruct(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_CONSTRUCT.get(), h.getLevel());
            unmanaged.setPos(t.point.x() + 4, t.point.y(), t.point.z());
            try {
                h.assertTrue(h.getLevel().addFreshEntity(unmanaged), "unmanaged fixture added");
                h.assertValueEqual(claim(t, anchor(t, unmanaged)).outcome(), WorldConstruct.ConsumeOutcome.FOREIGN_RUNTIME, "unmanaged construct");
                h.assertTrue(!unmanaged.isRemoved(), "rejected unmanaged construct removed");
            } finally { unmanaged.discard(); }
            h.succeed();
        }
    }
    @GameCase(environment = "chorus_gametest:construct_consume_expired", maxTicks = 20)
    public void anExpiredConstructCannotProduceAConsumptionReward(GameTestHelper h) {
        var t = open(h); try {
            t.cast(.1); var entity = t.construct(); var reference = anchor(t, entity);
            t.at(4, () -> { try (t) {
                h.assertValueEqual(claim(t, reference).outcome(), WorldConstruct.ConsumeOutcome.MISSING, "expired life");
                h.assertTrue(t.cues.stream().noneMatch(c -> c.cue().equals("test:reward")), "expiry fabricated reward"); h.succeed();
            } });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:construct_consume_unknown", maxTicks = 20)
    public void unknownNativeConsumptionKeepsRemovalAndPriorCommitWithoutRewardOrRetry(GameTestHelper h) {
        var t = open(h); try {
            t.cast(10); var entity = t.construct(); t.consumeThrows = true;
            try { consume(t, entity, false); throw new AssertionError("unknown claim accepted"); } catch (IllegalStateException expected) {}
            h.assertTrue(t.runtime.failure().isPresent() && entity.isRemoved(), "unknown mutation not retained");
            h.assertTrue(t.state().buffs().instances().values().stream().anyMatch(b -> b.key().holder().equals(t.source.holder()) && b.definition().id().equals("test:paid")), "prior local commit lost");
            h.runAfterDelay(3, () -> { try (t) {
                t.runtime.prepare(); h.assertValueEqual(t.consumptions.size(), 1, "claim retried"); h.assertTrue(t.cues.isEmpty(), "unknown claim invented reward or ran queued facts"); h.succeed();
            } });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
}
