package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.GroundContact;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class GroundContactTest {
    static final EntityAnchor.Reference REF = new EntityAnchor.Reference("player", "life-1", "world");
    static final WorldPosition POINT = new WorldPosition("world", 1, 40, 3);
    static EntityObservation observation() {
        var points = new HashMap<PositionQuery, Optional<WorldPosition>>();
        for (var anchor : TargetQuery.Anchor.values()) points.put(new PositionQuery("player", anchor), Optional.of(POINT));
        // Physical collision and the client's native onGround flag are intentionally independent.
        return new EntityObservation(0, Map.of("player", Optional.of(new EntityQuery.View(true, true, 20, 20, 0))), points);
    }
    static GroundContact.Landed landed() { return new GroundContact.Landed(REF, "landing/1", -2, -2, observation()); }
    @Test void factPreservesPhysicalPositionLifeAndDisplacementWithoutInventingAbilityAttribution() {
        var fact = landed(); var event = fact.event(); assertEquals("chorus:landed", fact.signal().type());
        assertEquals("player", event.actor()); assertEquals("player", event.victim()); assertEquals("player", event.source().owner());
        assertEquals("", event.source().ability()); assertEquals("", event.source().weapon()); assertEquals("life-1", event.references().get("entity_life"));
        assertEquals("landing/1", event.references().get("landing_id")); assertEquals(-2, event.numbers().get("moved_y").value());
        for (var anchor : TargetQuery.Anchor.values()) { assertEquals(REF, fact.anchor(anchor).requireObservation().reference()); assertEquals(POINT, fact.anchor(anchor).position().orElseThrow()); }
        assertFalse(event.tags().contains("chorus:ability_used"));
    }
    @Test void incompleteForeignNonfiniteOrNondownwardEvidenceIsRejected() {
        for (double n : List.of(0., 1., Double.NaN, Double.NEGATIVE_INFINITY)) assertThrows(IllegalArgumentException.class, () -> new GroundContact.Landed(REF, "landing/1", n, -1, observation()));
        assertThrows(IllegalArgumentException.class, () -> new GroundContact.Landed(REF, "", -2, -1, observation()));
        assertThrows(IllegalArgumentException.class, () -> new GroundContact.Landed(REF, "landing/1", -2, Double.NaN, observation()));
        assertThrows(IllegalArgumentException.class, () -> new GroundContact.Landed(new EntityAnchor.Reference("player", "life-1", "other"), "landing/1", -2, -1, observation()));
        assertThrows(IllegalArgumentException.class, () -> new GroundContact.Landed(REF, "landing/1", -2, -1, new EntityObservation(0, Map.of())));
    }
    @Test void typedHistoricalReadRequiresTheActualCarrierAndBothCodecsPreserveItsIdentity() {
        var fact = landed(); var read = new GroundContactActions.ReadLanding(TargetQuery.Anchor.EYES);
        var e = new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0, fact.signal()), "test", RuleEngine.Empty.INSTANCE, Map.of()), Map.of(), Map.of());
        assertEquals(fact.anchor(TargetQuery.Anchor.EYES), ((RuleEngine.Local<EffectState>)read.execute(e)).result());
        var fake = new Evaluation(EffectState.empty(), new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0, new RuleEngine.Signal(GroundContact.LANDED, fact.event())), "test", RuleEngine.Empty.INSTANCE, Map.of()), Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> read.execute(fake));
        assertEquals(read, EffectCodecs.ACTION.parse(JsonOps.INSTANCE, EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE, read).getOrThrow()).getOrThrow());
        assertEquals(fact, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(fact), GroundContact.Landed.class));
    }
    @Test void delayedLandingKeepsHistoricalPositionAndChecksTheOldLifeAfterCheckpointAndSourceDetach() throws Exception {
        var p = load("ground_contact"); var source = source("test:landing"); var queries = new ArrayList<TargetQuery>(); var cues = new ArrayList<String>();
        java.util.function.Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world = r -> switch (r.command()) {
            case TargetQuery q -> { queries.add(q); yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, List.of()); }
            case EntityAnchor.Query q -> { assertEquals(Optional.of(REF), q.expected()); yield new EntityAnchor.Result(q, EntityAnchor.Outcome.REPLACED, Optional.empty()); }
            case Action.CueCommand c -> { cues.add(c.cue()); yield RuleEngine.Empty.INSTANCE; }
            default -> throw new AssertionError(r.command());
        };
        var session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withSource(source), world);
        session.start(0, landed().signal()); assertEquals(List.of("test:landed"), cues); session.start(0, SourceChange.remove(source.instance()));
        var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
        session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 1, saved, world); session.observe(100_000, List.of());
        assertEquals(List.of("test:landed", "test:old_life", "test:delayed_landing"), cues); assertEquals(2, queries.size());
        assertTrue(queries.stream().allMatch(q -> ((TargetQuery.PositionCenter)q.center()).position().equals(Optional.of(POINT))));
    }
}
