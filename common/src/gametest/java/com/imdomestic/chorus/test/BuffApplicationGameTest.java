package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.StatusApplicationGameTest.*;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** A reward driven by the committed grant fact reaches the actual applicant in both loaders. */
public class BuffApplicationGameTest {
    @FunctionalInterface interface Scenario { void run() throws Exception; }
    static void ready(GameTestHelper h, Scenario scenario) {
        var chunks = new HashSet<net.minecraft.world.level.ChunkPos>();
        for (int x : new int[] {0, 8}) for (int z : new int[] {0, 8}) {
            var chunk = net.minecraft.world.level.ChunkPos.containing(h.absolutePos(new net.minecraft.core.BlockPos(x, 40, z)));
            h.getLevel().getChunk(chunk.x(), chunk.z()); chunks.add(chunk);
        }
        h.startSequence().thenWaitUntil(() -> {
            for (var chunk : chunks) h.assertTrue(h.getLevel().areEntitiesLoaded(chunk.pack()), "grant fixture entity index is ready");
        }).thenExecute(() -> {
            try { scenario.run(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Grant scenario failed", failure); }
        });
    }
    static ProjectileGameTest.Harness openGrants(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "shield_throw", d -> {
            ShieldThrowGameTest.prepare(d);
            for (String name : List.of("suppression", "buff_application")) GamblersDodgeGameTest.merge(d, ThreadedSpikeGameTest.json(name));
        }, true);
        try { t.runtime.bind(source(t.owner)); return t; }
        catch (Exception | Error failure) { t.close(); throw failure; }
    }
    @GameCase(environment = "chorus_gametest:buff_applicants", maxTicks = 100)
    public void directAndAuthorizedReapplicationsRewardEachActualApplicant(GameTestHelper h) {
        ready(h, () -> {
            try (var t = openGrants(h)) {
                var target = t.cow(4.5, 40, 3.5); var second = t.cow(6.5, 40, 3.5); second.setHealth(10); t.runtime.bind(source(second));
                event(t, "grant", t.owner, target); var first = buff(t).orElseThrow();
                event(t, "grant", second, target); event(t, "apply", second, target);
                near(h, t.owner.getHealth(), 11, "original applicant reward"); near(h, second.getHealth(), 12, "direct and authorized current applicant rewards");
                h.assertValueEqual(buff(t).orElseThrow().origin(), first.origin(), "shared state keeps original source");
                h.assertValueEqual(buff(t).orElseThrow().generation(), first.generation(), "shared generation remains stable");
                near(h, target.getHealth(), 100, "reward belongs to applicant rather than recipient");
            } h.succeed();
        });
    }
    @GameCase(environment = "chorus_gametest:buff_authority", maxTicks = 100)
    public void qualificationTimerChangesAndDeniedStatusesDoNotImpersonateAGrant(GameTestHelper h) {
        ready(h, () -> {
            try (var t = openGrants(h)) {
                var target = t.cow(4.5, 40, 3.5); event(t, "check", t.owner, target); near(h, t.owner.getHealth(), 10, "qualification is read only");
                event(t, "grant", t.owner, target); event(t, "refresh", t.owner, target); near(h, t.owner.getHealth(), 11, "timer refresh is not a new grant");
                t.deniedStatusTargets.add(target.getUUID().toString()); event(t, "apply", t.owner, target); near(h, t.owner.getHealth(), 11, "denied application has no generic credit");
                event(t, "clear", t.owner, target); near(h, t.owner.getHealth(), 11, "removal has no generic credit");
                h.assertTrue(buff(t).isEmpty(), "explicit cleanup remains effective");
            } h.succeed();
        });
    }
    @GameCase(environment = "chorus_gametest:buff_unknown", maxTicks = 100)
    public void unknownRewardResultPreservesCommittedGrantAndActualHealthWithoutReplay(GameTestHelper h) {
        ready(h, () -> {
            try (var t = openGrants(h)) {
                var target = t.cow(4.5, 40, 3.5); t.failAfterHealing = true; var source = source(t.owner);
                var input = new RuleEngine.Signal("test:grant", new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of()));
                try { t.runtime.start(input); } catch (IllegalStateException expected) { }
                h.assertTrue(t.runtime.failure().isPresent() && buff(t).isPresent(), "unknown world result retains grant"); near(h, t.owner.getHealth(), 11, "reward already changed actual health");
                int operations = t.operations.size(); try { t.runtime.start(input); } catch (IllegalStateException expected) { }
                h.assertValueEqual(t.operations.size(), operations, "failed session cannot replay grant reward"); near(h, t.owner.getHealth(), 11, "no repeated health change");
            } h.succeed();
        });
    }
}
