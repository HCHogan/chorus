package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntityLifeDeathTest {
    static EntityLifecycle.Died died() {
        var view = new EntityQuery.View(false, false, 0, 10, 0, Set.of(), Set.of());
        var obs = new EntityObservation(0, Map.of("target", Optional.of(view)), EntityLifecycleTest.observation().positions());
        return new EntityLifecycle.Died(EntityLifecycleTest.REF, "damage/death", obs);
    }
    @Test void exactDeathObservationKeepsIdentityWithoutAnotherKillCredit() {
        var d = died(); assertEquals(EntityLifecycle.DIED, d.signal().type());
        assertEquals("damage/death", d.event().references().get("death_id")); assertTrue(d.event().actor().isEmpty());
        for (var anchor : TargetQuery.Anchor.values()) {
            assertEquals(EntityLifecycleTest.POINT, d.anchor(anchor).position().orElseThrow());
            assertEquals(EntityLifecycleTest.REF, d.anchor(anchor).requireObservation().reference());
            assertFalse(d.anchor(anchor).requireObservation().alive());
        }
        assertThrows(IllegalArgumentException.class, () -> new EntityLifecycle.Died(d.reference(), "", d.observation()));
        assertThrows(IllegalArgumentException.class, () -> new EntityLifecycle.Died(d.reference(), "d", EntityLifecycleTest.observation()));
        var live = new EntityQuery.View(true, false, 10, 10, 0, Set.of(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> new EntityLifecycle.Died(d.reference(), "d", new EntityObservation(0, Map.of("target", Optional.of(live)), d.observation().positions())));
        assertThrows(IllegalArgumentException.class, () -> new EntityLifecycle.Died(new EntityAnchor.Reference("target", "old-life", "other"), "d", d.observation()));
        var points = new HashMap<>(d.observation().positions()); points.remove(new PositionQuery("target", TargetQuery.Anchor.EYES));
        assertThrows(IllegalArgumentException.class, () -> new EntityLifecycle.Died(d.reference(), "d", new EntityObservation(0, d.observation().entities(), points)));
    }
    @Test void deathReadIsPureTypedAndRoundTripsBothCodecs() {
        var d = died(); var action = new EntityLifecycleActions.ReadDeath(TargetQuery.Anchor.EYES);
        var e = new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0, d.signal()), "test", RuleEngine.Empty.INSTANCE, Map.of()), Map.of(), Map.of());
        assertEquals(d.anchor(TargetQuery.Anchor.EYES), ((RuleEngine.Local<EffectState>) action.execute(e)).result());
        assertThrows(IllegalArgumentException.class, () -> action.execute(PositionDistanceTest.evaluation(Map.of(), Map.of())));
        assertEquals(action, EffectCodecs.ACTION.parse(JsonOps.INSTANCE, EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE, action).getOrThrow()).getOrThrow());
        assertEquals(d, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(d), EntityLifecycle.Died.class));
    }
    @Test void deathCauseSurvivesDetachedWorkSourceRemovalAndCheckpoint() throws Exception {
        var p = load("entity_death"); var queries = new ArrayList<TargetQuery>(); var cues = new ArrayList<String>();
        java.util.function.Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world = r -> switch (r.command()) {
            case TargetQuery q -> { queries.add(q); yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, List.of()); }
            case Action.CueCommand c -> { cues.add(c.cue()); yield RuleEngine.Empty.INSTANCE; }
            default -> throw new AssertionError("Historical death read queried the world: " + r.command());
        };
        var source = source("test:life_death");
        var session = new EffectSession(p, EffectCheckpointTest.clock(), 256, EffectState.empty().withSource(source), world);
        session.start(0, died().signal()); session.start(0, SourceChange.remove(source.instance()));
        var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
        session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 256, saved, world);
        session.observe(100_000, List.of()); assertTrue(session.state().idle());
        assertEquals(List.of("test:life_death", "test:delayed_death"), cues); assertEquals(2, queries.size());
        assertTrue(queries.stream().allMatch(q -> ((TargetQuery.PositionCenter) q.center()).position().equals(Optional.of(EntityLifecycleTest.POINT))));
    }
}
