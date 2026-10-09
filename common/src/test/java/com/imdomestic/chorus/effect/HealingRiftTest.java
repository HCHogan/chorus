package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HealingRiftTest {
    private static final String FIELD = "chorus_d2:healing_rift_field", PRESENCE = "chorus_d2:healing_rift_presence";
    private static WorldPosition point(double x) { return new WorldPosition("minecraft:overworld", x, 70, 0); }
    private static EffectSource cast(String name) { return new EffectSource(name, "chorus_d2:healing_rift", "owner", new BuffInstance.Origin("owner", name, "", "healing_rift"), Set.of()); }
    private static final EffectSource A = cast("cast-a"), B = cast("cast-b");
    private static final class Harness {
        final EffectSession session;
        final Map<String, WorldPosition> positions = new HashMap<>(Map.of("owner", point(0), "ally", point(5), "enemy", point(1), "late", point(5.01)));
        final List<TargetQuery> queries = new ArrayList<>();
        final List<HealingCommand> heals = new ArrayList<>();
        int captures;
        Harness(EffectState.Mode mode) throws Exception {
            var fragments = new ArrayList<EffectProgram>();
            for (String name : List.of("healing_rift", "restoration")) fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(name)).getOrThrow());
            var compiled = CompiledEffects.link(fragments);
            session = new EffectSession(engine(compiled), EffectState.empty().withMode(mode), request -> switch (request.command()) {
                case EntityQuery query -> new EntityQuery.Result(query, positions.containsKey(query.target()) ? Optional.of(new EntityQuery.View(true, false, 10, 100, 0)) : Optional.empty());
                case PositionQuery query -> { captures++; yield new PositionQuery.Result(query, Optional.ofNullable(positions.get(query.target()))); }
                case TargetQuery query -> {
                    queries.add(query); assertEquals(5, query.radius()); assertEquals(TargetQuery.Relation.ALLIED, query.relation());
                    var position = ((TargetQuery.PositionCenter) query.center()).position();
                    var outcome = position.isEmpty() ? TargetQuery.Outcome.MISSING_CENTER
                            : !position.orElseThrow().dimension().equals("minecraft:overworld") ? TargetQuery.Outcome.WRONG_DIMENSION
                            : !positions.containsKey(query.relativeTo()) ? TargetQuery.Outcome.MISSING_RELATIVE : TargetQuery.Outcome.AVAILABLE;
                    var targets = new ArrayList<TargetQuery.Target>();
                    if (outcome == TargetQuery.Outcome.AVAILABLE) for (var entry : positions.entrySet()) {
                        double distance = Math.abs(entry.getValue().x() - position.orElseThrow().x());
                        if (!entry.getKey().equals("enemy") && distance <= query.radius()) targets.add(new TargetQuery.Target(entry.getKey(), distance));
                    }
                    targets.sort(query.comparator()); yield new TargetQuery.Result(query, outcome, targets);
                }
                case HealingCommand heal -> { heals.add(heal); yield new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0); }
                default -> throw new AssertionError(request.command());
            });
        }
        void bind(long time, EffectSource source) { session.start(time, SourceChange.bind(source)); settled(); }
        void advance(long time) { session.observe(time, List.of()); settled(); }
        void settled() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty(), session.state().engine().failure().toString()); }
        Optional<BuffInstance> buff(String id, String holder, EffectSource source) { return session.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.definition().id().equals(id) && b.key().holder().equals(holder) && b.origin().source().equals(source.origin().source())).findFirst(); }
        BuffInstance field(EffectSource source) { return buff(FIELD, "owner", source).orElseThrow(); }
        Set<String> members(EffectSource source) { return field(source).components().targetSets().get("members").ids(); }
        double healed(String target) { return heals.stream().filter(h -> h.target().equals(target)).mapToDouble(HealingCommand::amount).sum(); }
    }
    @Test void fixedFiveMeterFieldIncludesCasterAndAlliesAndUsesBothModeRatesUntilFifteenSeconds() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var test = new Harness(mode); test.bind(0, A); assertEquals(Set.of("owner", "ally"), test.members(A));
            assertEquals(15_000_000, test.field(A).deadline()); assertEquals(Optional.of(point(0)), test.field(A).components().positions().get("anchor"));
            test.positions.put("owner", point(20)); test.advance(50_000); assertEquals(Set.of("ally"), test.members(A));
            test.session.start(50_000, SourceChange.remove(A.instance())); test.advance(15_000_000);
            double rate = mode == EffectState.Mode.PVE ? 4 : 3.5;
            assertEquals(rate * 15, test.healed("ally"), 1e-9); assertEquals(rate * .05, test.healed("owner"), 1e-9); assertEquals(0, test.healed("enemy"));
            assertTrue(test.session.state().engine().domain().buffs().instances().isEmpty()); assertTrue(test.session.state().engine().domain().timers().isEmpty());
            assertEquals(1, test.captures); assertTrue(test.queries.stream().allMatch(q -> q.center().equals(new TargetQuery.PositionCenter(point(0)))));
        }
    }
    @Test void lateEntryStartsAtObservationAndFieldEndRemovesItsLongerPresence() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.bind(0, A); test.advance(50_000);
        test.positions.put("late", point(3)); test.advance(100_000);
        assertEquals(15_100_000, test.buff(PRESENCE, "late", A).orElseThrow().deadline());
        test.advance(15_000_000); assertEquals(14.9 * 4, test.healed("late"), 1e-9); assertTrue(test.buff(PRESENCE, "late", A).isEmpty());
        test.advance(15_100_000); assertEquals(14.9 * 4, test.healed("late"), 1e-9, "presence cannot outlive its field");
    }
    @Test void independentCastIdentitiesKeepDifferentAnchorsAndOverlapWithoutStacking() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.bind(0, A); test.positions.put("owner", point(10)); test.bind(100_000, B);
        assertEquals(Optional.of(point(0)), test.field(A).components().positions().get("anchor")); assertEquals(Optional.of(point(10)), test.field(B).components().positions().get("anchor"));
        test.bind(100_000, A); assertEquals(2, test.captures, "binding the same cast again is idempotent"); assertEquals(15_000_000, test.field(A).deadline());
        test.advance(15_000_000); assertTrue(test.buff(FIELD, "owner", A).isEmpty()); assertTrue(test.buff(PRESENCE, "ally", B).isPresent());
        assertEquals(60, test.healed("ally"), 1e-9); test.advance(15_100_000); assertEquals(60.4, test.healed("ally"), 1e-9);
        assertTrue(test.session.state().engine().domain().buffs().instances().isEmpty());
    }
    @Test void sharedRestorationDefinitionsCompeteWithSpatialRiftOnTheSameTarget() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.bind(0, A);
        var restoration = new EffectSource("restoration", "test:recovery_inputs", "ally", new BuffInstance.Origin("ally", "restoration", "", ""), Set.of());
        test.bind(50_000, restoration);
        test.session.start(50_000, new RuleEngine.Signal("test:restoration", new EffectEvent("ally", "ally", restoration.origin(), Set.of(),
                Map.of("tier", new Measure(2, Unit.COUNT), "duration", new Measure(.1, Unit.SECOND)))));
        test.advance(200_000); assertEquals(.05 * 4 + .1 * 5 + .05 * 4, test.healed("ally"), 1e-9);
        assertTrue(test.buff(PRESENCE, "ally", A).isPresent());
    }
    @Test void absentCastPositionDoesNotCreateAFieldOrAnOriginFallback() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.positions.remove("owner"); test.bind(0, A);
        assertTrue(test.session.state().engine().domain().buffs().instances().isEmpty()); assertTrue(test.queries.isEmpty());
        test.positions.put("owner", point(0)); test.bind(0, B); assertEquals(Set.of("owner", "ally"), test.members(B));
    }
    @Test void lostAllegianceReferenceAndWrongDimensionFollowTheExplicitEndPolicy() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.bind(0, A); test.positions.remove("owner"); test.advance(50_000);
        assertTrue(test.session.state().engine().domain().buffs().instances().isEmpty()); assertEquals(.2, test.healed("ally"), 1e-9);
        var foreign = new Harness(EffectState.Mode.PVE); foreign.positions.put("owner", new WorldPosition("minecraft:the_nether", 0, 70, 0)); foreign.bind(0, A);
        assertTrue(foreign.session.state().engine().domain().buffs().instances().isEmpty()); assertTrue(foreign.heals.isEmpty());
    }
}
