package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceBatchTest {
    @Test void sourceSetReplacementChecksAllInputsBeforeWritingAndUsesDeterministicFactOrder() {
        var a = source("test:attachment"); var b = new EffectSource("b", a.bundle(), a.holder(), a.origin(), Set.of());
        var newer = new EffectSource(a.instance(), a.bundle(), a.holder(), a.origin(), Set.of("test:changed"));
        var before = Map.of(a.instance(), a, b.instance(), b); var state = EffectState.empty().withSource(a).withSource(b);
        var batch = SourceBatch.between(before, Map.of(a.instance(), newer)); var result = batch.apply(state);
        assertEquals(Map.of(a.instance(), newer), result.state().sources());
        assertEquals(List.of("chorus:source_detached", "chorus:source_detached", "chorus:source_attached"), result.emitted().stream().map(s -> s.type()).toList());
        assertEquals(2, ((SourceBatch.Receipt) result.result()).detached().size());
        assertThrows(IllegalStateException.class, () -> batch.apply(state.withoutSource(b.instance())));
        assertEquals(before, state.sources());
    }
    @Test void unchangedMembersAreAlsoComparedAndUnrelatedSourcesArePreserved() {
        var a = source("test:attachment"); var b = new EffectSource("other", a.bundle(), a.holder(), a.origin(), Set.of());
        var state = EffectState.empty().withSource(a).withSource(b); var same = SourceBatch.between(Map.of(a.instance(), a), Map.of(a.instance(), a));
        assertSame(state, same.apply(state).state()); assertTrue(same.apply(state).emitted().isEmpty());
        assertThrows(IllegalStateException.class, () -> same.apply(state.withoutSource(a.instance())));
        assertEquals(Map.of(b.instance(), b), SourceBatch.between(Map.of(a.instance(), a), Map.of()).apply(state).state().sources());
    }
    @Test void duplicateEditsAndUnknownReplacementDefinitionsFailBeforeAnySourceMutation() throws Exception {
        var a = source("test:attachment"); var edit = new SourceBatch.Edit(a.instance(), Optional.empty(), Optional.of(a));
        assertThrows(IllegalArgumentException.class, () -> new SourceBatch(List.of(edit, edit)));
        var bad = new EffectSource("bad", "test:missing", a.holder(), a.origin(), Set.of());
        var program = load("source_attachment"); var batch = SourceBatch.between(Map.of(), Map.of(a.instance(), a, bad.instance(), bad));
        var session = new EffectSession(engine(program), EffectState.empty(), _ -> { fail("no world action before complete validation"); return null; });
        assertThrows(IllegalStateException.class, () -> session.start(0, batch.signal())); assertTrue(session.state().engine().domain().sources().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> program.validateSources(new SourceBatch(List.of(new SourceBatch.Edit("equipment/reserved", Optional.empty(), Optional.empty())))));
    }
}
