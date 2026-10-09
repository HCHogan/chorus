package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FixedPositionTest {
    private static final EffectSource SOURCE = source("test:fixed_burst");
    private static final WorldPosition PLACE = new WorldPosition("test:world", -12.5, 64.25, 38);
    private static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    private static final class Harness {
        final EffectSession session;
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        final List<TargetQuery> queries = new ArrayList<>();
        final List<Long> queryTimes = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>();
        Optional<WorldPosition> point = Optional.of(PLACE);
        List<String> members = List.of("a");
        Harness() throws Exception {
            var program = load("fixed_position");
            session = new EffectSession(engine(program), EffectState.empty().withSource(SOURCE), request -> {
                requests.add(request);
                return switch (request.command()) {
                    case PositionQuery query -> new PositionQuery.Result(query, point);
                    case TargetQuery query -> {
                        queries.add(query); queryTimes.add(state().buffs().timeMicros());
                        yield new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE,
                                members.stream().sorted().map(id -> new TargetQuery.Target(id, 0)).toList());
                    }
                    case DamageCommand damage -> {
                        hits.add(damage);
                        double loss = program.outgoing(state(), damage, damage.amount()).orElseThrow().output().value();
                        yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, loss, Optional.empty(), false);
                    }
                    default -> throw new AssertionError(request.command());
                };
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void fire() { session.start(state().buffs().timeMicros(), new RuleEngine.Signal("test:burst", event(SOURCE))); }
        void advance(long time) { session.observe(time, List.of()); assertTrue(session.state().idle()); }
    }
    @Test void nestedWavesRetainOneCapturedPointAndSelectFreshMembersAfterSourceRemoval() throws Exception {
        var h = new Harness(); h.fire();
        assertEquals(List.of(new PositionQuery("target")), h.requests.stream().map(RuleEngine.WorldRequest::command).toList());
        h.session.start(0, SourceChange.remove(SOURCE.instance()));
        h.point = Optional.empty(); h.members = List.of("late"); h.advance(100_000);
        h.members = List.of("later"); h.advance(150_000);
        h.members = List.of("last"); h.advance(200_000);
        assertEquals(List.of(100_000L, 150_000L, 200_000L), h.queryTimes);
        assertTrue(h.queries.stream().allMatch(q -> q.center().equals(new TargetQuery.PositionCenter(PLACE))));
        assertEquals(List.of("late", "later", "last"), h.hits.stream().map(DamageCommand::target).toList());
        assertEquals(1, h.requests.stream().filter(r -> r.command() instanceof PositionQuery).count());
        assertEquals(h.hits.getFirst().snapshot(), h.hits.getLast().snapshot());
        assertEquals(SOURCE.origin(), h.hits.getLast().source());
        assertEquals(h.requests.size(), h.requests.stream().map(RuleEngine.WorldRequest::id).distinct().count());
        assertTrue(h.state().timers().isEmpty());
    }
    @Test void independentCastsAtDifferentPositionsDoNotFollowOrOverwriteEachOther() throws Exception {
        var h = new Harness(); h.fire(); var second = new WorldPosition(PLACE.dimension(), 100, 90, 80);
        h.point = Optional.of(second); h.fire(); h.point = Optional.empty(); h.advance(200_000);
        assertEquals(List.of(PLACE, second, PLACE, second, PLACE, second), h.queries.stream()
                .map(q -> ((TargetQuery.PositionCenter) q.center()).position().orElseThrow()).toList());
        assertEquals(List.of(100_000L, 100_000L, 150_000L, 150_000L, 200_000L, 200_000L), h.queryTimes);
    }
    @Test void missingCaptureCanBranchWithoutSchedulingAndRemainsAbsentWhenUsedAsACenter() throws Exception {
        var h = new Harness(); h.point = Optional.empty(); h.fire(); h.advance(500_000);
        assertTrue(h.queries.isEmpty()); assertTrue(h.state().timers().isEmpty()); assertTrue(h.hits.isEmpty());
        var data = json("fixed_position"); var body = actions(data); body.remove(1);
        body.add(JsonParser.parseString("""
                {"action":{"type":"chorus:select_targets","center":{"position":"place"},
                  "radius":{"type":"chorus:constant","value":1,"unit":"meter"}},"as":"nearby"}
                """));
        var engine = engine(compile(data)); var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:burst", event(SOURCE));
        var absent = new PositionQuery.Result((PositionQuery) waiting.actions().getFirst().command(), Optional.empty());
        assertTrue(ResultShape.POSITION.flag("missing", absent)); assertFalse(ResultShape.POSITION.flag("available", absent));
        var query = complete(engine, waiting, absent); var command = (TargetQuery) query.actions().getFirst().command();
        assertEquals(new TargetQuery.PositionCenter(Optional.empty()), command.center());
        var result = new TargetQuery.Result(command, TargetQuery.Outcome.MISSING_CENTER, List.of());
        assertTrue(ResultShape.TARGETS.flag("missing_center", result)); assertFalse(ResultShape.TARGETS.flag("available", result));
        assertTrue(complete(engine, query, result).state().idle());
    }
    @Test void capturedPositionReceiptIsBoundToTheRequestAndDuplicatesDoNotScheduleAgain() throws Exception {
        var engine = engine(load("fixed_position"));
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:burst", event(SOURCE));
        var operation = waiting.actions().getFirst(); var receipt = new PositionQuery.Result((PositionQuery) operation.command(), Optional.of(PLACE));
        var scheduled = complete(engine, waiting, receipt);
        var duplicate = engine.transition(scheduled.state(), new RuleEngine.Completed(operation.id(), receipt));
        assertEquals(scheduled.state(), duplicate.state()); assertTrue(duplicate.actions().isEmpty());
        assertEquals(1, duplicate.state().engine().domain().timers().size());
        var invalid = engine.transition(waiting.state(), new RuleEngine.Completed(operation.id(), new PositionQuery.Result(new PositionQuery("different"), Optional.of(PLACE))));
        while (invalid.needsPump()) invalid = engine.transition(invalid.state(), RuleEngine.Pump.INSTANCE);
        assertTrue(invalid.state().engine().failure().isPresent()); assertTrue(invalid.state().engine().domain().timers().isEmpty());
    }
    @Test void positionBindingsAreTypedLexicalReferencesAndStrictJsonRoundTrips() throws Exception {
        var baseline = json("fixed_position"); var program = compile(baseline);
        var encoded = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program.program()).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        for (String step : List.of(
                "{\"type\":\"chorus:heal\",\"target\":{\"binding\":\"place\"},\"amount\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"damage\"}}",
                "{\"for_each\":\"place\",\"as\":\"target\",\"do\":[]}",
                "{\"type\":\"chorus:damage_snapshot\",\"snapshot\":\"place\"}",
                "{\"type\":\"chorus:select_targets\",\"center\":{\"position\":\"later\"},\"radius\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"meter\"}}",
                "{\"type\":\"chorus:select_targets\",\"center\":{\"position\":\"place\",\"binding\":\"place\"},\"radius\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"meter\"}}")) {
            var invalid = baseline.deepCopy(); actions(invalid).add(JsonParser.parseString(step));
            assertThrows(RuntimeException.class, () -> compile(invalid), step);
        }
        var wrongShape = baseline.deepCopy(); actions(wrongShape).get(0).getAsJsonObject().add("action", JsonParser.parseString("""
                {"type":"chorus:capture_value","value":{"type":"chorus:constant","value":1,"unit":"meter"}}
                """));
        assertThrows(RuntimeException.class, () -> compile(wrongShape));
        var typo = baseline.deepCopy(); actions(typo).get(0).getAsJsonObject().getAsJsonObject("action").addProperty("targets", "self");
        assertThrows(RuntimeException.class, () -> compile(typo));
    }
    @Test void loopTargetsCanBeCapturedSeparatelyAndPositionsDoNotEscapeTheirBranch() throws Exception {
        var data = json("fixed_position"); var body = actions(data); body.remove(1); body.remove(0);
        body.add(JsonParser.parseString("""
                {"action":{"type":"chorus:select_targets","radius":{"type":"chorus:constant","value":5,"unit":"meter"}},"as":"nearby"}
                """));
        body.add(JsonParser.parseString("""
                {"for_each":"nearby","as":"target","do":[
                  {"action":{"type":"chorus:capture_position","target":{"binding":"target"}},"as":"place"},
                  {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[
                    {"type":"chorus:select_targets","center":{"position":"place"},"radius":{"type":"chorus:constant","value":1,"unit":"meter"}}
                  ]}
                ]}
                """));
        var engine = engine(compile(data)); var waiting = send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:burst", event(SOURCE));
        var selected = new TargetQuery.Result((TargetQuery) waiting.actions().getFirst().command(), TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("a", 1), new TargetQuery.Target("b", 2)));
        var first = complete(engine, waiting, selected); assertEquals(new PositionQuery("a"), first.actions().getFirst().command());
        var next = complete(engine, first, new PositionQuery.Result(new PositionQuery("a"), Optional.of(PLACE)));
        var other = new WorldPosition("test:world", 1, 2, 3);
        var done = complete(engine, next, new PositionQuery.Result(new PositionQuery("b"), Optional.of(other)));
        var late = pump(engine, engine.transition(done.state(), new RuleEngine.Start(100_000, List.of())));
        assertEquals(new TargetQuery.PositionCenter(PLACE), ((TargetQuery) late.actions().getFirst().command()).center());
        var last = complete(engine, late, new TargetQuery.Result((TargetQuery) late.actions().getFirst().command(), TargetQuery.Outcome.AVAILABLE, List.of()));
        assertEquals(new TargetQuery.PositionCenter(other), ((TargetQuery) last.actions().getFirst().command()).center());
        var escaped = data.deepCopy(); actions(escaped).add(JsonParser.parseString("""
                {"type":"chorus:select_targets","center":{"position":"place"},"radius":{"type":"chorus:constant","value":1,"unit":"meter"}}
                """));
        assertThrows(RuntimeException.class, () -> compile(escaped));
    }
    @Test void finiteCoordinatesDimensionAndExplicitMissingResultsAreRequired() {
        for (double bad : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new WorldPosition("test:world", bad, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new WorldPosition("test:world", 0, bad, 0));
            assertThrows(IllegalArgumentException.class, () -> new WorldPosition("test:world", 0, 0, bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new WorldPosition("", 0, 0, 0));
        var query = new TargetQuery(new TargetQuery.PositionCenter(PLACE), 0, TargetQuery.Relation.ANY, "owner", false);
        var atPoint = new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("a", 0)));
        assertEquals(1, atPoint.targets().size()); // A point has no entity to implicitly exclude.
        for (var outcome : List.of(TargetQuery.Outcome.WRONG_DIMENSION, TargetQuery.Outcome.MISSING_CENTER, TargetQuery.Outcome.MISSING_RELATIVE)) {
            assertThrows(IllegalArgumentException.class, () -> new TargetQuery.Result(query, outcome, atPoint.targets()));
        }
        var wrongDimension = new TargetQuery.Result(query, TargetQuery.Outcome.WRONG_DIMENSION, List.of());
        assertTrue(ResultShape.TARGETS.flag("wrong_dimension", wrongDimension));
    }
}
