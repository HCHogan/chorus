package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class InputDirectionTest {
    static final class Harness {
        final CompiledEffects p; EffectSession session; boolean mismatch, unknown;
        InputDirection.Outcome outcome = InputDirection.Outcome.OBSERVED; Optional<WorldDirection> axis = Optional.of(new WorldDirection("world", 1, 0, 1));
        final List<InputDirection.Query> queries = new ArrayList<>(); final List<Impulse.Command> impulses = new ArrayList<>(); final List<String> cues = new ArrayList<>();
        Harness() throws Exception { p = load("input_direction"); session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world); send(new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:class", "test:input_dodge"))).signal()); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case InputDirection.Query q -> { queries.add(q); if (unknown) throw new IllegalStateException("Unknown input observation"); yield new InputDirection.Result(mismatch ? new InputDirection.Query("other") : q, outcome, axis); }
                case Impulse.Command c -> { impulses.add(c); var zero = new Impulse.Velocity(0, 0, 0); yield new Impulse.Receipt(c, Impulse.Outcome.APPLIED, Optional.of(new Impulse.Change(zero, c.delta().orElseThrow()))); }
                case Action.CueCommand c -> { cues.add(c.cue()); yield RuleEngine.Empty.INSTANCE; }
                default -> throw new AssertionError(r.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        void send(RuleEngine.Signal s) { session.start(state().buffs().timeMicros(), s); }
        void cast() { send(new AbilityUse.Request("player", "test:class", "cast", new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of())).signal()); }
        double energy() { return state().resources().get(new ResourceState.Key("player", "test:input_energy")).value(); }
    }
    @Test void heldKeysUseYawAndNormalizeDiagonalsWhileOpposingInputsCancel() {
        var forward = InputDirection.held("world", 0, true, false, false, false).orElseThrow(); assertEquals(0, forward.x(), 1e-12); assertEquals(1, forward.z(), 1e-12);
        var back = InputDirection.held("world", 90, false, true, false, false).orElseThrow(); assertEquals(1, back.x(), 1e-12); assertEquals(0, back.z(), 1e-12);
        var left = InputDirection.held("world", 90, false, false, true, false).orElseThrow(); assertEquals(0, left.x(), 1e-12); assertEquals(1, left.z(), 1e-12);
        var diagonal = InputDirection.held("world", 0, true, false, false, true).orElseThrow(); assertEquals(-Math.sqrt(.5), diagonal.x(), 1e-12); assertEquals(Math.sqrt(.5), diagonal.z(), 1e-12); assertEquals(0, diagonal.y());
        assertTrue(InputDirection.held("world", 30, true, true, true, true).isEmpty()); assertTrue(InputDirection.held("world", 30, false, false, false, false).isEmpty());
        var cancelled = InputDirection.held("world", 0, true, true, true, false).orElseThrow(); assertEquals(1, cancelled.x()); assertEquals(0, cancelled.z());
        assertThrows(IllegalArgumentException.class, () -> InputDirection.held("", 0, false, false, false, false)); assertThrows(IllegalArgumentException.class, () -> InputDirection.held("world", Double.NaN, false, false, false, false));
    }
    @Test void neutralObservationIsNotMissingAndCannotInventAFacingDirection() throws Exception {
        for (var outcome : InputDirection.Outcome.values()) {
            var h = new Harness(); h.outcome = outcome; h.axis = Optional.empty(); h.cast(); h.session.observe(200_000, List.of());
            assertEquals(List.of(outcome == InputDirection.Outcome.OBSERVED ? "test:neutral" : "test:unavailable"), h.cues); assertTrue(h.impulses.isEmpty()); assertEquals(0, h.energy());
            var r = new InputDirection.Result(new InputDirection.Query("player"), outcome, Optional.empty()); assertEquals(outcome == InputDirection.Outcome.OBSERVED, r.neutral());
        }
    }
    @Test void capturedAxisSurvivesNewInputSelectionRemovalAndSessionRestore() throws Exception {
        var h = new Harness(); h.cast(); var original = h.axis.orElseThrow(); h.axis = Optional.of(new WorldDirection("world", -1, 0, 0));
        h.send(new AbilityChange("player", h.state().abilities().get("player"), AbilityLoadout.EMPTY).signal());
        h.session = EffectSession.restore(h.p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(h.session.checkpoint())), h::world);
        h.session.observe(99_999, List.of()); assertTrue(h.impulses.isEmpty()); h.session.observe(100_000, List.of());
        var restored = h.impulses.getFirst().direction().orElseThrow(); assertEquals(original.dimension(), restored.dimension()); assertEquals(original.x(), restored.x(), 1e-12); assertEquals(original.y(), restored.y(), 1e-12); assertEquals(original.z(), restored.z(), 1e-12);
        assertEquals("test:input_dodge", h.impulses.getFirst().origin().ability()); assertEquals(1, h.queries.size()); assertEquals(0, h.energy());
    }
    @Test void mismatchedAndUnknownObservationsStopTheAcceptedCastWithoutReplayOrRefund() throws Exception {
        for (boolean mismatch : new boolean[] {true, false}) { var h = new Harness(); h.mismatch = mismatch; h.unknown = !mismatch;
            assertThrows(RuntimeException.class, h::cast); assertEquals(0, h.energy()); assertEquals(1, h.queries.size()); assertTrue(h.impulses.isEmpty()); assertThrows(IllegalStateException.class, () -> h.session.observe(200_000, List.of())); assertEquals(1, h.queries.size());
        }
    }
    @Test void strictReceiptAndDslValidationPreserveExistingDirectionStorageShapes() throws Exception {
        var q = new InputDirection.Query("player");
        assertThrows(IllegalArgumentException.class, () -> new InputDirection.Query(""));
        assertThrows(IllegalArgumentException.class, () -> new InputDirection.Result(q, InputDirection.Outcome.MISSING_TARGET, Optional.of(new WorldDirection("world", 1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new InputDirection.Result(q, InputDirection.Outcome.OBSERVED, Optional.of(new WorldDirection("world", 0, 1, 0))));
        var p = load("input_direction").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        var invalid = json("input_direction"); invalid.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("target", "invented"); assertThrows(RuntimeException.class, () -> compile(invalid));
    }
}
