package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PositionComponentTest {
    private static final WorldPosition PLACE = new WorldPosition("minecraft:overworld", -2.5, 74.25, 30);
    private static final EffectSource SOURCE = source("test:positions");
    private static final class Harness {
        final EffectSession session;
        final List<TargetQuery> queries = new ArrayList<>();
        Optional<WorldPosition> current = Optional.of(PLACE);
        int captures;
        Harness() throws Exception {
            session = new EffectSession(engine(load("stored_position")), EffectState.empty().withSource(SOURCE), request -> switch (request.command()) {
                case PositionQuery query -> { captures++; yield new PositionQuery.Result(query, current); }
                case TargetQuery query -> {
                    queries.add(query); var point = ((TargetQuery.PositionCenter) query.center()).position();
                    yield new TargetQuery.Result(query, point.isPresent() ? TargetQuery.Outcome.AVAILABLE : TargetQuery.Outcome.MISSING_CENTER, List.of());
                }
                default -> throw new AssertionError(request.command());
            });
        }
        void send(long time, String name) { session.start(time, new RuleEngine.Signal("test:" + name, event(SOURCE))); settled(); }
        void settled() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty()); }
        BuffInstance instance() { return buff(session.state(), "test:anchor", "player"); }
        Optional<WorldPosition> last() { return ((TargetQuery.PositionCenter) queries.getLast().center()).position(); }
    }
    @Test void positionComponentsAreImmutableAndOtherMutationsPreserveThem() {
        var schema = new BuffSchema(Map.of("n", new Measure(0, Unit.COUNT)), Set.of("seen"), Set.of("ref"), Set.of("members"), Set.of("anchor"));
        var blank = schema.initial(); assertEquals(Optional.empty(), blank.positions().get("anchor"));
        var components = blank.position("anchor", Optional.of(PLACE)).number("n", BuffComponents.Update.ADD, 1)
                .remember("seen", "id").reference("ref", "other").targets("members", Targets.Identities.of(List.of("member")));
        assertEquals(Optional.of(PLACE), components.positions().get("anchor")); assertEquals(Optional.empty(), blank.positions().get("anchor"));
        assertThrows(UnsupportedOperationException.class, () -> components.positions().clear());
        assertThrows(IllegalArgumentException.class, () -> components.position("missing", Optional.of(PLACE)));
        assertThrows(IllegalArgumentException.class, () -> new BuffSchema(Map.of(), Set.of(), Set.of(), Set.of("same"), Set.of("same")));
        assertThrows(IllegalArgumentException.class, () -> schema.requirePosition("ref"));
        assertThrows(NullPointerException.class, () -> new PositionResult.Stored(null));
    }
    @Test void uninitializedPositionIsMissingAndNeverFallsBackToTheHolder() throws Exception {
        var test = new Harness(); test.send(0, "init"); test.send(0, "read");
        assertTrue(test.last().isEmpty()); assertEquals(0, test.captures);
        var absent = new PositionResult.Stored(Optional.empty()); assertTrue(ResultShape.POSITION.flag("missing", absent));
        assertFalse(ResultShape.POSITION.flag("available", absent));
    }
    @Test void captureCopyAndRefreshKeepTheOriginalCoordinatesWithoutAnotherWorldRead() throws Exception {
        var test = new Harness(); test.send(0, "init"); test.send(0, "save"); test.send(0, "copy");
        long generation = test.instance().generation(); test.current = Optional.of(new WorldPosition("minecraft:the_nether", 1, 2, 3));
        test.send(100_000, "init"); test.send(100_000, "read_copy");
        assertEquals(generation, test.instance().generation()); assertEquals(Optional.of(PLACE), test.last()); assertEquals(1, test.captures);
        test.send(100_000, "save"); test.send(100_000, "read");
        assertEquals(test.current, test.last(), "dimension is preserved, not rewritten to current world");
        test.send(100_000, "read_copy"); assertEquals(Optional.of(PLACE), test.last(), "copy is an immutable value");
    }
    @Test void writingMissingIsAnExplicitClearAndDoesNotKeepOrInventCoordinates() throws Exception {
        var test = new Harness(); test.send(0, "init"); test.send(0, "save"); test.current = Optional.empty(); test.send(0, "save");
        assertEquals(Optional.empty(), test.instance().components().positions().get("anchor")); test.send(0, "read"); assertTrue(test.last().isEmpty());
    }
    @Test void endedRulesReadOldCoordinatesEvenAfterSameKeyReplacementHasInitializedEmpty() throws Exception {
        var test = new Harness(); test.send(0, "init"); test.send(0, "save"); long old = test.instance().generation();
        test.send(0, "replace"); assertNotEquals(old, test.instance().generation()); assertEquals(Optional.of(PLACE), test.last());
        test.send(0, "read"); assertTrue(test.last().isEmpty());
    }
    @Test void detachedContinuationRetainsTheReadValueAcrossOverwriteRemovalAndSourceUnbinding() throws Exception {
        var test = new Harness(); test.send(0, "init"); test.send(0, "save"); test.send(0, "delay");
        test.current = Optional.of(new WorldPosition("minecraft:overworld", 100, 1, 2)); test.send(0, "save"); test.send(0, "remove");
        assertEquals(test.current, test.last(), "ended snapshot sees the last written position");
        test.session.start(0, SourceChange.remove(SOURCE.instance())); test.session.observe(100_000, List.of()); test.settled();
        assertEquals(Optional.of(PLACE), test.last(), "delayed read keeps the previously bound position"); assertEquals(2, test.captures);
    }
    @Test void schemasAndPositionBindingsRoundTripAndRejectWrongKindsAndForwardReferences() throws Exception {
        var data = json("stored_position"); var compiled = compile(data);
        assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, compiled).getOrThrow()).getOrThrow().program());
        for (String name : List.of("members", "seen", "ref", "n", "unknown")) {
            var invalid = data.deepCopy(); var write = invalid.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("do").get(1).getAsJsonObject();
            write.addProperty("component", name); assertThrows(RuntimeException.class, () -> compile(invalid));
        }
        var invalid = data.deepCopy(); var body = invalid.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("do");
        body.get(0).getAsJsonObject().add("action", JsonParser.parseString("{\"type\":\"chorus:read_targets\",\"buff\":\"test:anchor\",\"component\":\"members\"}"));
        assertThrows(RuntimeException.class, () -> compile(invalid)); body.remove(0); assertThrows(RuntimeException.class, () -> compile(invalid));
        assertThrows(IllegalArgumentException.class, () -> ResultShape.POSITION.requireTarget());
        assertThrows(IllegalArgumentException.class, () -> ResultShape.POSITION.requireTargets());
        assertThrows(IllegalArgumentException.class, () -> ResultShape.POSITION.unit("distance"));
    }
}
