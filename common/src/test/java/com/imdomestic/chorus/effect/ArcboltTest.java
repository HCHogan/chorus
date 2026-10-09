package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArcboltTest {
    private static final EffectSource SOURCE = new EffectSource("arcbolt", "chorus_d2:arcbolt", "owner",
            new BuffInstance.Origin("owner", "selection", "", "chorus_d2:arcbolt"), Set.of());
    private static final class Harness {
        final CompiledEffects program = load("arcbolt");
        final EffectSession session;
        final List<TargetQuery> queries = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>();
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        final List<Double> amounts = new ArrayList<>();
        List<String> candidates = List.of("a", "b", "c", "d", "e");
        TargetQuery.Outcome observation = TargetQuery.Outcome.AVAILABLE;
        DamageReceipt.Outcome outcome = DamageReceipt.Outcome.APPLIED;
        String stoppedAt = "";
        boolean noLoss, absorptionOnly;
        double height;
        Harness(EffectState.Mode mode) throws Exception {
            session = new EffectSession(engine(program), EffectState.empty().withMode(mode).withSource(SOURCE), request -> {
                requests.add(request);
                return switch (request.command()) {
                    case TargetQuery q -> {
                        queries.add(q);
                        var targets = observation == TargetQuery.Outcome.AVAILABLE ? candidates.stream().filter(t -> !q.exclude().contains(t))
                                .limit(1).map(t -> new TargetQuery.Target(t, 1)).toList() : List.<TargetQuery.Target>of();
                        yield new TargetQuery.Result(q, observation, targets);
                    }
                    case PositionQuery q -> new PositionQuery.Result(q, Optional.of(new WorldPosition("world", q.target().charAt(0), height, 0)));
                    case DamageCommand d -> {
                        hits.add(d); double amount = program.outgoing(sessionState(), d, d.amount()).orElseThrow().output().value(); amounts.add(amount);
                        var result = d.target().equals(stoppedAt) ? outcome : DamageReceipt.Outcome.APPLIED;
                        double loss = result == DamageReceipt.Outcome.APPLIED && !(noLoss && d.target().equals(stoppedAt)) ? amount : 0;
                        yield new DamageReceipt(request.id().toString(), result, 0, absorptionOnly ? loss : 0, absorptionOnly ? 0 : loss, Optional.empty(), false);
                    }
                    default -> throw new AssertionError(request.command());
                };
            });
        }
        EffectState sessionState() { return session.state().engine().domain(); }
        void fire(String cast) { fire(new BuffInstance.Origin("owner", cast, "", "chorus_d2:arcbolt")); }
        void fire(BuffInstance.Origin origin) { session.start(0, new RuleEngine.Signal("test:arcbolt_impact", new EffectEvent("owner", "impact", origin, Set.of(), Map.of()))); }
        void advance(long time) { session.observe(time, List.of()); assertTrue(session.state().idle(), session.state().engine().failure().toString()); }
    }
    @Test void frozenFirstTargetWaitsOneSecondThenChainsAtCurrentHitPositionsToFourDistinctEnemies() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode); h.fire("cast");
            assertEquals(1, h.queries.size()); assertTrue(h.hits.isEmpty());
            var first = h.queries.getFirst(); assertEquals(12, first.radius()); assertTrue(first.lineOfSight());
            assertEquals(TargetQuery.Order.NEAREST, first.order()); assertEquals(OptionalInt.of(1), first.limit());
            h.session.start(0, SourceChange.remove(SOURCE.instance())); h.advance(999_999); assertTrue(h.hits.isEmpty());
            h.height = 80; h.candidates = List.of("b", "a", "c", "d", "e"); h.advance(1_000_000);
            assertEquals(List.of("a", "b", "c", "d"), h.hits.stream().map(DamageCommand::target).toList());
            assertEquals(4, h.queries.size());
            for (int i = 1; i < 4; i++) {
                var q = h.queries.get(i); assertEquals(10, q.radius()); assertFalse(q.lineOfSight());
                assertEquals(80, ((TargetQuery.PositionCenter) q.center()).position().orElseThrow().y());
                assertEquals((double) ('a' + i - 1), ((TargetQuery.PositionCenter) q.center()).position().orElseThrow().x());
                assertTrue(q.exclude().containsAll(List.of("a", "b", "c").subList(0, i)));
            }
            h.amounts.forEach(v -> assertEquals(mode == EffectState.Mode.PVE ? 52.1 : 8.5, v, 1e-9));
            assertTrue(h.hits.stream().allMatch(d -> d.source().source().equals("cast") && d.snapshot().equals(h.hits.getFirst().snapshot())
                    && d.killTags().contains("chorus:grenade_kill") && !d.killTags().contains("chorus:weapon_kill")));
            assertEquals(h.requests.size(), h.requests.stream().map(RuleEngine.WorldRequest::id).distinct().count());
        }
    }
    @Test void chainRequiresCommittedLossAndIncludesAbsorptionButCannotSkipARejectedSelectedEnemy() throws Exception {
        for (var outcome : DamageReceipt.Outcome.values()) {
            var h = new Harness(EffectState.Mode.PVP); h.stoppedAt = "b"; h.outcome = outcome; h.noLoss = true;
            h.fire("cast"); h.advance(1_000_000);
            assertEquals(List.of("a", "b"), h.hits.stream().map(DamageCommand::target).toList()); assertEquals(2, h.queries.size());
        }
        var absorbed = new Harness(EffectState.Mode.PVE); absorbed.absorptionOnly = true; absorbed.fire("cast"); absorbed.advance(1_000_000);
        assertEquals(4, absorbed.hits.size());
    }
    @Test void separateCastsCanHitSameEnemiesAndRetainTheirOwnOrigin() throws Exception {
        var h = new Harness(EffectState.Mode.PVP); h.fire("first-cast"); h.fire("second-cast"); h.advance(1_000_000);
        assertEquals(List.of("a", "b", "c", "d", "a", "b", "c", "d"), h.hits.stream().map(DamageCommand::target).toList());
        assertEquals(List.of("first-cast", "second-cast"), h.hits.stream().map(d -> d.source().source()).distinct().toList());
        assertEquals(8, h.hits.size()); assertTrue(h.sessionState().timers().isEmpty());
    }
    @Test void emptyOrUnavailableScanDoesNotScheduleAndForeignOwnerOrAbilityCannotTrigger() throws Exception {
        for (var observation : List.of(TargetQuery.Outcome.AVAILABLE, TargetQuery.Outcome.MISSING_CENTER, TargetQuery.Outcome.MISSING_RELATIVE)) {
            var h = new Harness(EffectState.Mode.PVE); h.candidates = List.of(); h.observation = observation; h.fire("empty");
            assertTrue(h.sessionState().timers().isEmpty()); h.advance(1_000_000); assertTrue(h.hits.isEmpty());
        }
        var h = new Harness(EffectState.Mode.PVE);
        h.fire(new BuffInstance.Origin("other", "cast", "", "chorus_d2:arcbolt"));
        h.fire(new BuffInstance.Origin("owner", "cast", "", "other:ability")); assertTrue(h.requests.isEmpty());
        var p = h.program.program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
    }
}
