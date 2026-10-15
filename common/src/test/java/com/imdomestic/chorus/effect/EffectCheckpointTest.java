package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.random.RandomState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EffectCheckpointTest {
    static EffectClock clock() { return new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())); }
    private record Seen(long time, RuleEngine.WorldRequest request) {}
    private static final class Harness {
        final CompiledEffects program;
        EffectSession session;
        final List<Seen> seen = new ArrayList<>();
        final List<Double> heals = new ArrayList<>();
        final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        boolean unknown, wrongReceipt, checkDuringWorld;
        Harness(CompiledEffects program, EffectState initial) {
            this.program = program; session = new EffectSession(program, clock(), 1, initial, this::world);
        }
        EffectState state() { return session.state().engine().domain(); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if (checkDuringWorld) assertThrows(IllegalStateException.class, session::checkpoint);
            seen.add(new Seen(state().buffs().timeMicros(), request));
            if (unknown) throw new IllegalStateException("world result unknown");
            if (wrongReceipt) return RuleEngine.Empty.INSTANCE;
            return switch (request.command()) {
                case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                case HealingCommand heal -> {
                    heals.add(heal.amount());
                    yield new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
                }
                case DamageCommand damage -> {
                    double value = program.outgoing(state(), damage, damage.amount()).orElseThrow().output().value();
                    value = program.defense(state(), damage, value).orElseThrow().output().value();
                    yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, value, Optional.empty(), false);
                }
                case PositionQuery query -> new PositionQuery.Result(query, Optional.of(RetainedCostTest.POINT));
                case DirectionQuery query -> new DirectionQuery.Result(query, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> {
                    launches.add(launch);
                    yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight-" + launches.size()));
                }
                default -> throw new AssertionError(request.command());
            };
        }
        void restore() {
            var before = session.checkpoint();
            var saved = com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.decode(
                    com.google.gson.JsonParser.parseString(com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.encode(before).toString()));
            assertEquals(before, saved, "Disk representation must retain the full checkpoint");
            var rebuilt = new CompiledEffects(saved.program());
            int calls = seen.size();
            session = EffectSession.restore(rebuilt, clock(), 256, saved, this::world);
            assertEquals(calls, seen.size(), "Restoring cannot replay source initialization, healing or launch actions");
            assertEquals(saved, session.checkpoint());
        }
        void send(long time, String event, EffectSource source, int amount) {
            session.start(time, new RuleEngine.Signal(event, new EffectEvent(source.holder(), "target", source.origin(), Set.of("test:captured"),
                    Map.of("tier", new Measure(amount, Unit.COUNT), "amount", new Measure(amount, Unit.DAMAGE)), Map.of("ready", true), Map.of("marker", "original"))));
        }
        void at(long time) { session.observe(time, List.of()); }
    }
    private static Harness delayed() throws Exception {
        var p = load("delayed_snapshot"); var source = source("test:launcher");
        var state = EffectState.empty().withSource(source).withSource(new EffectSource("controls", "test:controls", "player", source.origin(), Set.of()));
        state = state.withBuffs(Buffs.grant(state.buffs(), p.buff("test:empower"), "player", "player", source.origin(), 1, 1, 50_000).store());
        return new Harness(p, state);
    }
    @Test void delayedSnapshotsContinueWithIdenticalFutureCommandsCountersAndLiveVictimModifiers() throws Exception {
        var original = delayed(); var restored = delayed(); var source = source("test:launcher");
        for (var h : List.of(original, restored)) {
            h.send(0, "test:fire", source, 0);
            h.session.start(0, SourceChange.remove(source.instance()));
            h.send(0, "test:weaken", source, 0);
            h.at(75_000); // Source absent and empowering buff expired, captured attack remains in after.
        }
        var checkpoint = restored.session.checkpoint();
        assertFalse(checkpoint.timeline().domain().timers().isEmpty());
        assertTrue(checkpoint.timeline().nextFrame() > 1);
        restored.restore();
        for (var h : List.of(original, restored)) { h.at(99_999); assertTrue(h.heals.isEmpty()); h.at(100_000); }
        assertEquals(List.of(9.0), restored.heals);
        assertEquals(original.seen, restored.seen);
        var originalAfter = original.session.checkpoint(); var restoredAfter = restored.session.checkpoint();
        assertEquals(originalAfter.program(), restoredAfter.program()); assertEquals(originalAfter.timeline(), restoredAfter.timeline());
        assertEquals(originalAfter.resourceRouting(), restoredAfter.resourceRouting());
        assertEquals(checkpoint.identity(), restoredAfter.identity()); assertNotEquals(originalAfter.identity(), restoredAfter.identity());
        assertTrue(restored.seen.getLast().request().id().frame() >= checkpoint.timeline().nextFrame());
    }
    @Test void pausedBuffGenerationAndTimerRemainderSurviveRestoreWithoutCatchUpPulses() throws Exception {
        var source = source("test:periodic"); var h = new Harness(load("periodic"), EffectState.empty().withSource(source));
        h.send(0, "test:apply", source, 2); h.send(50_000, "chorus:weapon_stowed", source, 2);
        var before = h.session.checkpoint(); var generation = h.state().buffs().nextGeneration();
        assertEquals(20_001, h.state().timers().values().iterator().next().pausedRemaining().orElseThrow());
        h.restore(); h.at(200_000); assertTrue(h.heals.isEmpty());
        h.send(200_000, "chorus:weapon_drawn", source, 2); h.at(220_000); assertTrue(h.heals.isEmpty());
        h.at(220_001); assertEquals(List.of(2.0), h.heals);
        h.send(220_001, "test:stop", source, 2); h.send(220_001, "test:apply", source, 1);
        assertEquals(generation + 1, h.state().buffs().nextGeneration());
        assertTrue(Collections.disjoint(before.timeline().domain().timers().keySet(), h.state().timers().keySet()));
    }
    @Test void newCastsDoNotReplaceDeferredWorkWithReusedFrameIdentities() throws Exception {
        var h = delayed(); var source = source("test:launcher");
        h.send(0, "test:fire", source, 0);
        String first = h.state().timers().keySet().iterator().next();
        h.restore(); h.send(0, "test:fire", source, 0);
        assertEquals(2, h.state().timers().size()); assertTrue(h.state().timers().containsKey(first));
        h.at(100_000); assertEquals(List.of(6.0, 6.0), h.heals);
        assertEquals(h.seen.size(), h.seen.stream().map(value -> value.request().id()).distinct().count());
    }
    @Test void retainedRefundsPreserveAlreadyClaimedBudgetAlongsideSelectionsAmmoAndRandomStream() throws Exception {
        var initial = EffectState.empty().withMode(EffectState.Mode.PVP).withRandom(new RandomState(-19, 47))
                .withAmmo(new AmmoState("weapon", 13, 10, Optional.of(new AmmoState.Reserve(23, 90))));
        var h = new Harness(load("retained_cost"), initial);
        h.session.start(0, new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))).signal());
        h.session.start(0, new AbilityUse.Request("player", "test:melee", "cast-1", new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of())).signal());
        h.session.start(100_000, h.launches.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, RetainedCostTest.POINT, Optional.of("target"), 0, 0, 0, 100_000)));
        assertEquals(1.7, h.state().resources().get(RetainedCostTest.KEY).value());
        assertEquals(.7, h.state().retainedCosts().values().iterator().next().receipt().refundClaimed());
        h.restore(); h.at(400_000);
        assertEquals(List.of(7.0, 3.0), h.heals); assertEquals(2, h.state().resources().get(RetainedCostTest.KEY).value());
        h.at(700_000); assertTrue(h.state().retainedCosts().isEmpty()); h.at(800_000); assertEquals(List.of(7.0, 3.0, 0.0), h.heals);
        assertEquals(initial.random().next(), h.state().random().next()); assertEquals(initial.ammunition(), h.state().ammunition());
        assertEquals(EffectState.Mode.PVP, h.state().mode()); assertEquals("test:return", h.state().abilities().get("player").slots().get("test:melee"));
    }
    @Test void restoreRequiresCatalogueContentEqualityEvenWhenVersionLabelIsUnchanged() throws Exception {
        var h = delayed(); var saved = h.session.checkpoint();
        var changed = json("delayed_snapshot");
        changed.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("duration", 1);
        var replacement = compile(changed); assertEquals(h.program.program().version(), replacement.program().version());
        assertThrows(IllegalArgumentException.class, () -> EffectSession.restore(replacement, clock(), 1, saved, h::world));
        assertThrows(IllegalArgumentException.class, () -> EffectSession.restore(load("periodic"), clock(), 1, saved, h::world));
        h.restore(); assertTrue(h.seen.isEmpty());
    }
    @Test void unknownWorldResultCannotBecomeASuccessfulCheckpointOrAutomaticRetry() throws Exception {
        var h = delayed(); h.unknown = true;
        assertThrows(IllegalStateException.class, () -> h.send(0, "test:fire", source("test:launcher"), 0));
        assertTrue(h.session.state().engine().pending().isPresent());
        assertThrows(IllegalStateException.class, h.session::checkpoint);
        h.unknown = false; assertThrows(IllegalStateException.class, () -> h.at(100_000)); assertEquals(1, h.seen.size());
    }
    @Test void knownWorldReceiptFollowedByRuleFailureStillCannotBeCheckpointed() throws Exception {
        var source = source("test:periodic"); var h = new Harness(load("periodic"), EffectState.empty().withSource(source));
        h.send(0, "test:apply", source, 1); h.wrongReceipt = true;
        assertThrows(IllegalStateException.class, () -> h.at(70_001));
        assertTrue(h.session.state().engine().failure().isPresent());
        assertThrows(IllegalStateException.class, h.session::checkpoint);
        assertEquals(1, h.seen.size());
    }
    @Test void captureInsideWorldExecutorIsRejectedAndUnpinnedCustomSessionCannotClaimCatalogueIdentity() throws Exception {
        var h = delayed(); h.checkDuringWorld = true; h.send(0, "test:fire", source("test:launcher"), 0);
        assertEquals(1, h.seen.size()); assertDoesNotThrow(h.session::checkpoint);
        var custom = new EffectSession(engine(h.program), h.state(), h::world);
        assertThrows(IllegalStateException.class, custom::checkpoint);
    }
}
