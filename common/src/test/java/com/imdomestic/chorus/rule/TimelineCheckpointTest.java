package com.imdomestic.chorus.rule;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TimelineCheckpointTest {
    private record Command() implements WorldCommand {}
    private static TimelineEngine<EffectState> engine() {
        var rule = new EventRule<EffectState>("rule", "run", (_, _) -> true,
                List.of(new Instruction<>((_, _) -> new Await<>(new Command()), "")));
        return new TimelineEngine<>("1", List.of(rule), (_, event) -> event.signal().type().equals("run")
                ? List.of(new RuleBinding("rule", "rule", Empty.INSTANCE)) : List.of(),
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 256);
    }
    @Test void restoredSequenceContinuesAndOldReceiptsCannotResumeAnyNewWork() {
        var e = engine(); var first = e.transition(e.initial(EffectState.empty()), new Start(0, new Signal("run", Empty.INSTANCE)));
        var request = first.actions().getFirst();
        var settled = e.transition(first.state(), new Completed(request.id(), Empty.INSTANCE)).state();
        var saved = e.checkpoint(settled); var restored = engine().restore(saved);
        assertTrue(restored.receipts().isEmpty()); assertTrue(restored.engine().receipts().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> e.transition(restored, new Completed(request.id(), Empty.INSTANCE)));
        var next = e.transition(restored, new Start(0, new Signal("run", Empty.INSTANCE)));
        assertEquals(saved.nextFrame(), next.actions().getFirst().id().frame());
        assertEquals(saved.nextEvent(), next.state().engine().frames().getFirst().event().id());
        assertThrows(IllegalArgumentException.class, () -> e.transition(next.state(), new Completed(request.id(), Empty.INSTANCE)));
    }
    @Test void pendingRequestedAbandonedAndFailedTimelinesAreNotQuiescentCheckpoints() {
        var e = engine(); var initial = e.initial(EffectState.empty()); var run = new Start(0, new Signal("run", Empty.INSTANCE));
        assertThrows(IllegalStateException.class, () -> e.checkpoint(e.transition(initial, run).state()));
        assertThrows(IllegalStateException.class, () -> e.checkpoint(new TimelineEngine.State<>(initial.engine(), Optional.of(run), Map.of(), Optional.empty())));
        assertThrows(IllegalStateException.class, () -> e.checkpoint(new TimelineEngine.State<>(initial.engine(), Optional.empty(), Map.of(), Optional.of(run))));
        var failed = new RuleEngine.State<>("1", EffectState.empty(), 0, 1, 1, List.of(), List.of(), Optional.empty(), Map.of(),
                Optional.of(new Failure(1, "rule", 0, "failure", 0, 0)));
        assertThrows(IllegalStateException.class, () -> e.checkpoint(new TimelineEngine.State<>(failed, Optional.empty(), Map.of(), Optional.empty())));
    }
    @Test void invalidSequenceVersionClockOrAlreadyDueTimerCannotBeRestored() {
        var e = engine(); var state = EffectState.empty();
        assertThrows(IllegalArgumentException.class, () -> new TimelineEngine.Checkpoint<>("1", state, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimelineEngine.Checkpoint<>("1", state, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimelineEngine.Checkpoint<>("1", state, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimelineEngine.Checkpoint<>("1", state, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> e.restore(new TimelineEngine.Checkpoint<>("other", state, 0, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> e.restore(new TimelineEngine.Checkpoint<>("1", state, 1, 1, 1)));
        var clock = new TimelineEngine.Clock<EffectState>() {
            public long time(EffectState ignored) { return 0; }
            public long nextDeadline(EffectState ignored) { return 0; }
            public Local<EffectState> advance(EffectState s, long until) { throw new AssertionError("Restore must not advance time"); }
        };
        var unsettled = new TimelineEngine<EffectState>("1", List.of(), (_, _) -> List.of(), clock, 1);
        assertThrows(IllegalArgumentException.class, () -> unsettled.restore(new TimelineEngine.Checkpoint<>("1", state, 0, 1, 1)));
    }
    @Test void restoredLongCountersFailAtExhaustionInsteadOfWrappingAndReusingIdentities() {
        var e = engine(); var run = new Start(0, new Signal("run", Empty.INSTANCE));
        var events = e.restore(new TimelineEngine.Checkpoint<>("1", EffectState.empty(), 0, Long.MAX_VALUE, 1));
        assertThrows(ArithmeticException.class, () -> e.transition(events, run));
        var frames = e.restore(new TimelineEngine.Checkpoint<>("1", EffectState.empty(), 0, 1, Long.MAX_VALUE));
        var failed = e.transition(frames, run);
        assertTrue(failed.state().engine().failure().isPresent()); assertTrue(failed.actions().isEmpty());
        assertEquals(Long.MAX_VALUE, failed.state().engine().nextFrame());
        assertThrows(IllegalStateException.class, () -> e.checkpoint(failed.state()));
    }
}
