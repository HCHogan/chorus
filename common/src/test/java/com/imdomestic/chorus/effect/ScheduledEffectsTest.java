package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScheduledEffectsTest {
    private record Heal(long time, HealingCommand command) {}
    private static final class Harness {
        final EffectSession session;
        final EffectSource source;
        final List<Heal> heals = new ArrayList<>();
        Harness(CompiledEffects program, String bundle, EffectState.Mode mode) {
            source = source(bundle);
            session = new EffectSession(engine(program), EffectState.empty().withSource(source).withMode(mode), request -> {
                var command = (HealingCommand) request.command(); heals.add(new Heal(time(), command));
                return new HealingReceipt("heal/" + heals.size(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
            });
        }
        long time() { return session.state().engine().timeMicros(); }
        EffectState state() { return session.state().engine().domain(); }
        void send(long at, String type, int tier) {
            session.start(at, new RuleEngine.Signal(type, new EffectEvent(source.holder(), "target", source.origin(), Set.of("test:captured"),
                    Map.of("tier", new Measure(tier, Unit.COUNT), "amount", new Measure(tier, Unit.DAMAGE)), Map.of("ready", true), Map.of("marker", "original"))));
        }
    }
    @Test void refreshKeepsCadenceAndNextPulseReadsCurrentTierNotStackCount() throws Exception {
        var test = new Harness(load("periodic"), "test:periodic", EffectState.Mode.PVE);
        test.send(0, "test:apply", 1); long generation = test.state().buffs().instances().values().iterator().next().generation();
        test.send(50_000, "test:apply", 2);
        assertEquals(1, test.state().timers().size()); assertEquals(70_001, test.state().timers().values().iterator().next().dueAt());
        test.send(140_002, "test:noop", 1);
        assertEquals(List.of(70_001L, 140_002L), test.heals.stream().map(Heal::time).toList());
        assertEquals(List.of(2.0, 2.0), test.heals.stream().map(value -> value.command().amount()).toList());
        var buff = test.state().buffs().instances().values().iterator().next(); assertEquals(generation, buff.generation()); assertEquals(1, buff.count());
    }
    @Test void buffPauseFreezesTimerRemainderAndResumeDoesNotCatchUpPausedPulses() throws Exception {
        var test = new Harness(load("periodic"), "test:periodic", EffectState.Mode.PVE);
        test.send(0, "test:apply", 1); test.send(50_000, "chorus:weapon_stowed", 1);
        var paused = test.state().timers().values().iterator().next(); assertEquals(Long.MAX_VALUE, paused.dueAt()); assertEquals(20_001, paused.pausedRemaining().orElseThrow());
        var inconsistent = new EffectState.Timer(paused.id(), 250_000, paused.intervalMicros(), paused.remaining(), paused.signal(), paused.lifetime());
        assertThrows(IllegalArgumentException.class, () -> test.state().cancel(paused.id()).schedule(inconsistent));
        test.send(190_000, "test:noop", 1); assertTrue(test.heals.isEmpty());
        test.send(200_000, "chorus:weapon_drawn", 1);
        assertEquals(220_001, test.state().timers().values().iterator().next().dueAt());
        test.send(220_001, "test:noop", 1); assertEquals(List.of(220_001L), test.heals.stream().map(Heal::time).toList());
    }
    @Test void expiryWinsAtTheSameInstantAndNewGenerationDoesNotInheritOldTimer() throws Exception {
        var data = json("periodic"); data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("duration", .210003);
        var expires = new Harness(compile(data), "test:periodic", EffectState.Mode.PVE);
        expires.send(0, "test:apply", 1); expires.send(210_003, "test:noop", 1);
        assertEquals(List.of(70_001L, 140_002L), expires.heals.stream().map(Heal::time).toList()); assertTrue(expires.state().timers().isEmpty());
        var test = new Harness(load("periodic"), "test:periodic", EffectState.Mode.PVE);
        test.send(0, "test:apply", 1); String first = test.state().timers().keySet().iterator().next();
        test.send(50_000, "test:stop", 1); assertTrue(test.state().timers().isEmpty());
        test.send(60_000, "test:apply", 1); assertFalse(test.state().timers().containsKey(first));
        test.send(200_002, "test:noop", 1); assertEquals(List.of(130_001L, 200_002L), test.heals.stream().map(Heal::time).toList());
    }
    @Test void sourceTimerCapturesContextAndSupportsKeepReplaceCancelAndDetach() throws Exception {
        var keep = new Harness(load("periodic"), "test:periodic", EffectState.Mode.PVE);
        keep.send(0, "test:later_start", 3); keep.send(50_000, "test:later_start", 7); keep.send(100_000, "test:noop", 0);
        assertEquals(3, keep.heals.getFirst().command().amount()); assertEquals("target", keep.heals.getFirst().command().target());
        var data = json("periodic"); sourceSchedule(data).addProperty("policy", "replace");
        var replace = new Harness(compile(data), "test:periodic", EffectState.Mode.PVE);
        replace.send(0, "test:later_start", 3); replace.send(50_000, "test:later_start", 7); replace.send(100_000, "test:noop", 0); assertTrue(replace.heals.isEmpty());
        replace.send(150_000, "test:noop", 0); assertEquals(7, replace.heals.getFirst().command().amount());
        var cancel = new Harness(load("periodic"), "test:periodic", EffectState.Mode.PVE);
        cancel.send(0, "test:later_start", 3); cancel.send(50_000, "test:later_cancel", 0); cancel.send(100_000, "test:noop", 0); assertTrue(cancel.heals.isEmpty());
        var detached = new Harness(load("periodic"), "test:periodic", EffectState.Mode.PVE); detached.send(0, "test:later_start", 3);
        assertTrue(detached.state().withoutSource(detached.source.instance()).timers().isEmpty());
        var replacedSource = new EffectSource(detached.source.instance(), detached.source.bundle(), detached.source.holder(),
                new BuffInstance.Origin("player", "different", "different_weapon", ""), Set.of());
        assertTrue(detached.state().withSource(replacedSource).timers().isEmpty());
        var strictData = json("periodic"); sourceSchedule(strictData).addProperty("policy", "error");
        var strict = new Harness(compile(strictData), "test:periodic", EffectState.Mode.PVE); strict.send(0, "test:later_start", 3);
        assertThrows(IllegalStateException.class, () -> strict.send(50_000, "test:later_start", 7));
    }
    @Test void twoBuffOwnersWithIdenticalTimerNamesDoNotTriggerEachOthersPulse() throws Exception {
        var program = load("periodic"); var first = source("test:periodic");
        var second = new EffectSource("other", "test:periodic", "other", new BuffInstance.Origin("other", "other", "other_weapon", ""), Set.of());
        var calls = new ArrayList<HealingCommand>();
        var session = new EffectSession(engine(program), EffectState.empty().withSource(first).withSource(second), request -> {
            var command = (HealingCommand) request.command(); calls.add(command);
            return new HealingReceipt("heal-" + calls.size(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
        });
        for (var source : List.of(first, second)) session.start(0, new RuleEngine.Signal("test:apply", new EffectEvent(source.holder(), "target", source.origin(), Set.of(), Map.of("tier", new Measure(1, Unit.COUNT)))));
        session.start(70_001, new RuleEngine.Signal("test:noop", RuleEngine.Empty.INSTANCE));
        assertEquals(2, calls.size()); assertEquals(Set.of("player", "other"), calls.stream().map(HealingCommand::target).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void invalidScheduleAndTierSchemasFailAndCodecsRoundTrip() throws Exception {
        for (String fixture : List.of("periodic", "cure")) {
            var program = load(fixture); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
            assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        }
        for (double delay : List.of(0.0, -.1, .0000001)) {
            var data = json("periodic"); sourceSchedule(data).getAsJsonObject("delay").addProperty("value", delay);
            assertThrows(IllegalStateException.class, () -> compile(data));
        }
        var data = json("periodic"); sourceSchedule(data).addProperty("repeat", -1); assertThrows(IllegalStateException.class, () -> compile(data));
        var reserved = json("periodic"); sourceSchedule(reserved).addProperty("event", "chorus:internal/invalid"); assertThrows(IllegalStateException.class, () -> compile(reserved));
        assertThrows(IllegalArgumentException.class, () -> new Value.ByBuffTier(List.of(1.0), Unit.DAMAGE).unit(new Validation(Map.of(), Map.of(), false)));
    }
    @Test void cureHasTwoPulsesAndCooldownRejectsExtraActivationsWithoutRefreshingIt() throws Exception {
        var test = new Harness(load("cure"), "chorus_d2:cure", EffectState.Mode.PVE);
        test.send(0, "chorus:cure_requested", 2); test.send(50_000, "chorus:cure_requested", 3); test.send(500_000, "chorus:cure_requested", 3);
        assertEquals(List.of(50_000L, 100_000L), test.heals.stream().map(Heal::time).toList());
        assertEquals(List.of(6.0, 6.0), test.heals.stream().map(value -> value.command().amount()).toList());
        assertTrue(test.state().timers().isEmpty());
        test.send(1_000_000, "chorus:cure_requested", 1); test.send(1_100_000, "test:noop", 1);
        assertEquals(List.of(50_000L, 100_000L, 1_050_000L, 1_100_000L), test.heals.stream().map(Heal::time).toList());
        assertEquals(3, test.heals.getLast().command().amount());
        var pvp = new Harness(load("cure"), "chorus_d2:cure", EffectState.Mode.PVP);
        pvp.send(0, "chorus:cure_requested", 3); pvp.send(100_000, "test:noop", 1);
        assertEquals(9, pvp.heals.stream().mapToDouble(value -> value.command().amount()).sum());
    }
    private static JsonObject sourceSchedule(JsonObject data) {
        return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject();
    }
}
