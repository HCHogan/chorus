package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;

public class EntityAttachmentGameTest {
    static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    static ProjectileGameTest.Harness open(GameTestHelper h, Consumer<JsonObject> edit) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "entity_attachment", edit, true);
        IncandescentGameTest.bind(t, "driver", "test:attachment", ""); return t;
    }
    static void attach(ProjectileGameTest.Harness t, LivingEntity target) {
        String owner = t.owner.getUUID().toString();
        t.runtime.start(new RuleEngine.Signal("test:attach", new EffectEvent(owner, target.getUUID().toString(), new BuffInstance.Origin(owner, "driver", "", ""), Set.of(), Map.of())));
    }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static WorldPosition center(ProjectileGameTest.Harness t) { return ((TargetQuery.PositionCenter) t.queries.getFirst().center()).position().orElseThrow(); }
    static void completed(ProjectileGameTest.Harness t) {
        EntityRemovalGameTest.settled(t);
        t.h.assertValueEqual(EntityRemovalGameTest.cues(t), List.of("test:attachment_due"), "attachment did not resolve once");
        t.h.assertTrue(state(t).attachments().isEmpty(), "completed attachment leaked");
    }
    @GameCase(environment = "chorus_gametest:attachment_death", maxTicks = 25)
    public void fixedDeathUsesTheConfirmedPointBeforeCorpseCallbacksAndSurvivesSourceRemoval(GameTestHelper h) throws Exception {
        var t = open(h, d -> actions(d).get(2).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("policy").addProperty("death", "fix"));
        try {
            var target = EntityLifeDeathGameTest.special(t, false); var point = EventPositionObservationGameTest.point(target, TargetQuery.Anchor.EYES); attach(t, target);
            target.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000); EntityRemovalGameTest.settled(t);
            h.assertValueEqual(state(t).attachments().values().iterator().next().phase(), EntityAttachments.Phase.FIXED, "death did not commit before reactions");
            target.discard(); t.runtime.unbind("driver");
            t.finish(3, () -> { completed(t); h.assertValueEqual(center(t), point, "later corpse position changed fixed death contact"); });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:attachment_query", maxTicks = 25)
    public void removalDuringTheWorldQueryCommitsBeforeTheDependentContinuation(GameTestHelper h) throws Exception {
        var t = open(h, _ -> {});
        try {
            var target = t.cow(2.5, 46, 3.5); var point = EventPositionObservationGameTest.point(target, TargetQuery.Anchor.EYES); attach(t, target);
            t.beforeWorld = request -> { if (request.command() instanceof EntityAttachments.Query) { target.discard(); target.setPos(target.getX() + 100, target.getY(), target.getZ()); } };
            t.finish(3, () -> { completed(t); h.assertTrue(target.isRemoved(), "query boundary not reached"); h.assertValueEqual(center(t), point, "query receipt erased the committed fixed point"); });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:attachment_reload", maxTicks = 25)
    public void waitingAttachmentResumesOnlyTheLifeRestoredFromNativeEntityNbt(GameTestHelper h) throws Exception {
        var t = open(h, _ -> {});
        try {
            var target = t.cow(2.5, 46, 3.5); attach(t, target); var saved = EntityAnchorGameTest.save(h, target);
            target.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK); EntityRemovalGameTest.settled(t);
            h.assertValueEqual(state(t).attachments().values().iterator().next().phase(), EntityAttachments.Phase.WAITING, "unload did not wait");
            var loaded = EntityTypes.COW.create(h.getLevel(), EntitySpawnReason.COMMAND); loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), saved));
            loaded.setPos(loaded.getX() + 3, loaded.getY() + 1, loaded.getZ()); h.getLevel().addFreshEntity(loaded); t.entities.add(loaded);
            var point = EventPositionObservationGameTest.point(loaded, TargetQuery.Anchor.EYES);
            t.finish(3, () -> { completed(t); h.assertValueEqual(center(t), point, "saved same life did not resume at translated contact"); });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase public void aNewAttachmentCreatedAfterACommittedDeathIsNotClosedByItsQueuedNotification(GameTestHelper h) throws Exception {
        try (var t = open(h, d -> {
            var body = actions(d); var begin = body.get(2).deepCopy();
            begin.getAsJsonObject().getAsJsonObject("action").getAsJsonObject("policy").addProperty("death", "end"); body.set(2, begin);
            var cue = JsonParser.parseString("{\"type\":\"chorus:play_cue\",\"cue\":\"test:kill_between\"}");
            var after = body.get(3); body.set(3, cue); var next = begin.deepCopy().getAsJsonObject(); next.addProperty("as", "next_attachment"); body.add(next); body.add(JsonParser.parseString(after.toString().replace("\"attached\"", "\"next_attachment\"")));
        })) {
            var target = t.cow(2.5, 46, 3.5);
            t.onCue = _ -> target.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000);
            attach(t, target); EntityRemovalGameTest.settled(t);
            h.assertTrue(target.isDeadOrDying(), "real death did not occur");
            h.assertValueEqual(state(t).attachments().size(), 1, "old death notification ended the later instance");
            h.assertValueEqual(state(t).attachments().values().iterator().next().revision(), 0L, "new attachment received an earlier state write");
        } h.succeed();
    }
    @GameCase public void queuedRemovalCommitsBeforeAdvancingPastItsFuse(GameTestHelper h) throws Exception {
        try (var t = open(h, _ -> {})) {
            var target = t.cow(2.5, 46, 3.5); var point = EventPositionObservationGameTest.point(target, TargetQuery.Anchor.EYES); attach(t, target); target.discard();
            // Simulate a delayed host boundary without allowing its ordinary per-tick prepare to settle the queue.
            long tick = h.getLevel().getGameTime();
            try { ((ServerLevelData) h.getLevel().getLevelData()).setGameTime(tick + 3); completed(t); h.assertValueEqual(center(t), point, "fuse overtook the older removal"); }
            finally { ((ServerLevelData) h.getLevel().getLevelData()).setGameTime(tick); }
        } h.succeed();
    }
    @GameCase public void failedOlderReactionRetainsAllLaterQueuedRemovalEvidence(GameTestHelper h) throws Exception {
        try (var t = open(h, d -> {
            var rule = JsonParser.parseString("{\"id\":\"removal_failure\",\"on\":\"chorus:entity_removed\",\"do\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:removal\"}]}");
            d.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").add(rule);
        })) {
            var first = t.cow(2.5, 46, 3.5); var second = t.cow(3.5, 46, 3.5); first.discard(); long tick = h.getLevel().getGameTime();
            try {
                ((ServerLevelData) h.getLevel().getLevelData()).setGameTime(tick + 1); second.discard(); t.failAfterCue = true; t.runtime.prepare();
                h.assertTrue(t.runtime.failure().isPresent(), "injected reaction failure did not stop runtime");
                var targets = t.runtime.failure().orElseThrow().committedCombatFacts().stream().map(RuleEngine.Signal::payload).filter(EntityLifecycle.Removed.class::isInstance).map(EntityLifecycle.Removed.class::cast).map(r -> r.reference().target()).toList();
                h.assertValueEqual(targets, List.of(first.getUUID().toString(), second.getUUID().toString()), "settlement lost later known removal facts");
                t.runtime.prepare(); h.assertValueEqual(t.cues.size(), 1, "failed reaction replayed");
            } finally { ((ServerLevelData) h.getLevel().getLevelData()).setGameTime(tick); }
        } h.succeed();
    }
}
