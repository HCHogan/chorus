package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConstructTest {
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("owner", "cast", "weapon", "ability", Set.of("test:original"));
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<WorldConstruct.Spawn> spawns = new ArrayList<>(); final List<Action.CueCommand> cues = new ArrayList<>();
        final Map<String, Boolean> alive = new HashMap<>(); boolean missing, mismatch, fail;
        WorldConstruct.Outcome reject;
        Harness() throws Exception {
            program = load("construct"); session = new EffectSession(engine(program), EffectState.empty(), request -> switch (request.command()) {
                case PositionQuery q -> new PositionQuery.Result(q, missing ? Optional.empty() : Optional.of(new WorldPosition("world", 2, 3, 4)));
                case WorldConstruct.Spawn s -> {
                    spawns.add(s); String id = "construct-" + spawns.size();
                    var outcome = s.position().isEmpty() ? WorldConstruct.Outcome.MISSING_POSITION : reject == null ? WorldConstruct.Outcome.SPAWNED : reject;
                    var actual = mismatch ? new WorldConstruct.Spawn(s.position(), "test:wrong", s.origin(), s.parameters(), s.tags(), s.version()) : s;
                    if (outcome == WorldConstruct.Outcome.SPAWNED) alive.put(id, true);
                    if (fail) throw new IllegalStateException("Unknown spawn outcome");
                    yield new WorldConstruct.Receipt(actual, outcome, outcome == WorldConstruct.Outcome.SPAWNED ? Optional.of(id) : Optional.empty());
                }
                case EntityQuery q -> new EntityQuery.Result(q, alive.containsKey(q.target()) ? Optional.of(new EntityQuery.View(alive.get(q.target()), false, 150, 150, 0)) : Optional.empty());
                case Action.CueCommand cue -> { cues.add(cue); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(request.command());
            });
            send(SourceChange.bind(new EffectSource("source", "test:construct", "owner", ORIGIN, Set.of())));
        }
        EffectState state() { return session.state().engine().domain(); }
        void send(RuleEngine.Signal signal) { session.start(state().buffs().timeMicros(), signal); }
        void spawn(double health, double duration) { send(new RuleEngine.Signal("test:spawn", new EffectEvent("owner", "point", ORIGIN, Set.of(),
                Map.of("health", new Measure(health, Unit.DAMAGE), "duration", new Measure(duration, Unit.SECOND))))); }
        void until(long micros) { session.observe(micros, List.of()); }
    }
    @Test void receiptIdentityOwnsBehaviorWhileCasterAttributionSurvivesUnbinding() throws Exception {
        var h = new Harness(); h.spawn(150, .5); h.send(SourceChange.remove("source"));
        var spawn = h.spawns.getFirst(); assertEquals(ORIGIN, spawn.origin()); assertEquals(150, spawn.parameters().health());
        assertEquals(.5, spawn.parameters().width()); assertEquals(.75, spawn.parameters().height()); assertEquals("test-1", spawn.version());
        var behavior = h.state().buffs().instances().values().stream().filter(b -> b.key().holder().equals("construct-1")).findFirst().orElseThrow();
        assertEquals(ORIGIN, behavior.origin()); h.until(499_999); assertEquals(4, h.cues.size());
        h.until(500_000); assertEquals(4, h.cues.size()); assertTrue(h.state().timers().isEmpty());
    }
    @Test void independentInstancesAndMissingOrDeadObservationStopOnlyTheirOwnTimers() throws Exception {
        for (boolean missing : List.of(false, true)) {
            var h = new Harness(); h.spawn(150, .5); h.spawn(100, .5); h.until(100_000); assertEquals(2, h.cues.size());
            if (missing) h.alive.remove("construct-1"); else h.alive.put("construct-1", false);
            h.until(400_000); assertEquals(5, h.cues.size());
            assertTrue(h.state().buffs().instances().values().stream().noneMatch(b -> b.key().holder().equals("construct-1")));
            h.until(500_000); assertTrue(h.state().timers().isEmpty());
        }
    }
    @Test void unsuccessfulSpawnsCannotGrantBehaviorToAnInventedEntity() throws Exception {
        for (var outcome : WorldConstruct.Outcome.values()) if (outcome != WorldConstruct.Outcome.SPAWNED) {
            var h = new Harness(); h.reject = outcome; h.missing = outcome == WorldConstruct.Outcome.MISSING_POSITION;
            h.spawn(150, .5); h.until(500_000); assertTrue(h.cues.isEmpty()); assertTrue(h.state().timers().isEmpty()); assertTrue(h.alive.isEmpty());
        }
    }
    @Test void unknownAndMismatchedOutcomesKeepPriorCommitAndNeverRespawn() throws Exception {
        for (boolean mismatch : List.of(false, true)) {
            var h = new Harness(); h.mismatch = mismatch; h.fail = !mismatch;
            assertThrows(IllegalStateException.class, () -> h.spawn(150, .5));
            assertEquals(1, h.alive.size()); assertTrue(h.state().buffs().instances().values().stream().anyMatch(b -> b.key().definition().equals("test:paid")));
            assertTrue(h.state().timers().isEmpty()); assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(1, h.spawns.size());
        }
    }
    @Test void dynamicInvalidParametersFailBeforeTheWorldSpawn() throws Exception {
        for (double health : List.of(0., -1.)) { var h = new Harness(); assertThrows(IllegalStateException.class, () -> h.spawn(health, .5)); assertTrue(h.spawns.isEmpty()); }
        var h = new Harness(); assertThrows(IllegalStateException.class, () -> h.spawn(1, .0000001)); assertTrue(h.spawns.isEmpty());
    }
    @Test void codecRejectsWrongUnitsBindingsAndNonpositiveSizes() throws Exception {
        var p = load("construct"); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        for (String variant : List.of("health", "width", "height", "lifetime", "binding", "zero")) {
            var data = json("construct"); var spawn = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(2).getAsJsonObject().getAsJsonObject("action");
            if (variant.equals("binding")) spawn.addProperty("position", "missing");
            else if (variant.equals("zero")) spawn.getAsJsonObject("width").addProperty("value", 0);
            else spawn.getAsJsonObject(variant).addProperty("unit", "count");
            assertThrows(RuntimeException.class, () -> compile(data));
        }
    }
}
