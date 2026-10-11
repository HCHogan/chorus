package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftHealingExecutor;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;

/** File round-trip and session restore against the same live world; world-save coordination is not simulated. */
public class EffectCheckpointGameTest {
    private static EffectClock clock() { return new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())); }
    private static RuleEngine.Signal signal(EffectSource source, String type) {
        return new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(),
                Map.of("tier", new Measure(1, Unit.COUNT))));
    }
    private static void refused(GameTestHelper helper, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Unsettled session was accepted");
    }
    @GameCase public void restoredPausedPeriodicHealingUsesOriginalRemainderAndDoesNotRepeatCompletedHealing(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2);
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("periodic")).getOrThrow();
        String id = target.getUUID().toString();
        var source = new EffectSource("perk", "test:periodic", id, new BuffInstance.Origin(id, "perk", "weapon", ""), Set.of());
        var calls = new ArrayList<RuleEngine.OperationId>();
        java.util.function.Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world = request -> {
            calls.add(request.id());
            var command = (HealingCommand) request.command();
            helper.assertValueEqual(command.target(), id, "retained recipient");
            return MinecraftHealingExecutor.execute(request.id().toString(), target, command);
        };
        var session = new EffectSession(program, clock(), 1, EffectState.empty().withSource(source), world);
        session.start(0, signal(source, "test:apply")); session.observe(70_001, List.of()); near(helper, target.getHealth(), 3, "first pulse");
        session.start(100_000, signal(source, "chorus:weapon_stowed"));
        var beforeSave = session.checkpoint();
        var directory = java.nio.file.Files.createTempDirectory("chorus-checkpoint-world-");
        EffectSession.Checkpoint saved;
        try {
            var path = directory.resolve("session.json");
            com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.write(path, beforeSave);
            saved = com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.read(path);
            helper.assertValueEqual(saved, beforeSave, "complete checkpoint file round-trip");
        } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("session.json")); java.nio.file.Files.delete(directory);
        }
        var restored = EffectSession.restore(new CompiledEffects(saved.program()), clock(), 256, saved, world);
        helper.assertValueEqual(restored.checkpoint(), saved, "restored state and identity counters");
        near(helper, target.getHealth(), 3, "restore performed no world action");
        restored.observe(200_000, List.of()); near(helper, target.getHealth(), 3, "paused pulse did not catch up");
        restored.start(200_000, signal(source, "chorus:weapon_drawn"));
        restored.observe(240_001, List.of()); near(helper, target.getHealth(), 3, "remainder has not elapsed");
        restored.observe(240_002, List.of()); near(helper, target.getHealth(), 4, "resumed pulse");
        helper.assertValueEqual(calls.size(), 2, "only two actual world writes");
        helper.assertTrue(calls.getLast().frame() >= saved.timeline().nextFrame(), "Restored frame ID reused old space");
        helper.succeed();
    }
    @GameCase public void actualHealingWithLostAcknowledgementCannotBeSavedAsSettledOrRepeated(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2);
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("periodic")).getOrThrow();
        String id = target.getUUID().toString();
        var source = new EffectSource("perk", "test:periodic", id, new BuffInstance.Origin(id, "perk", "weapon", ""), Set.of());
        var calls = new ArrayList<RuleEngine.OperationId>();
        var session = new EffectSession(program, clock(), 1, EffectState.empty().withSource(source), request -> {
            calls.add(request.id()); MinecraftHealingExecutor.execute(request.id().toString(), target, (HealingCommand) request.command());
            throw new IllegalStateException("Lost acknowledgement after actual healing");
        });
        session.start(0, signal(source, "test:apply")); refused(helper, () -> session.observe(70_001, List.of()));
        near(helper, target.getHealth(), 3, "unknown operation still changed the world");
        helper.assertTrue(session.state().engine().pending().isPresent(), "Pending command evidence was lost");
        refused(helper, session::checkpoint); refused(helper, () -> session.observe(140_002, List.of()));
        helper.assertValueEqual(calls.size(), 1, "no automatic replay"); near(helper, target.getHealth(), 3, "no repeated healing");
        helper.succeed();
    }
}
