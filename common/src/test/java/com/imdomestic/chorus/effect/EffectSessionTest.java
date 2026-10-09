package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EffectSessionTest {
    @Test void worldFailureRetainsCommittedStateAndPendingFrameWithoutReplayingTheSideEffect() throws Exception {
        var compiled = load("credited_resource"); var source = source("test:credited_resource");
        var energy = new ResourceState.Key("player", "test:energy"); var worldCalls = new AtomicInteger();
        var session = new EffectSession(engine(compiled), EffectState.empty().withSource(source).withResource(new ResourceState(energy, 0, 2, 0)), _ -> {
            worldCalls.incrementAndGet(); throw new IllegalStateException("world outcome unknown");
        });
        var signal = new RuleEngine.Signal("test:gain", new EffectEvent("player", "target", source.origin(), Set.of(), Map.of("stacks", new Measure(4, Unit.COUNT))));
        assertThrows(IllegalStateException.class, () -> session.start(0, signal));
        assertTrue(session.state().engine().pending().isPresent());
        assertEquals(4, session.state().engine().domain().buffs().instances().values().iterator().next().count());
        assertEquals(0, session.state().engine().domain().resources().get(energy).value());
        assertThrows(IllegalStateException.class, () -> session.start(0, signal));
        assertEquals(1, worldCalls.get());
    }
}
