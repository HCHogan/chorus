package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TargetMembershipTest {
    private static EffectSource source(String name) { return new EffectSource(name, "test:aura", "owner", new BuffInstance.Origin("owner", name, "", name), Set.of()); }
    private static final EffectSource A = source("a"), B = source("b");
    private static final class Harness {
        final EffectSession session;
        final List<Action.CueCommand> cues = new ArrayList<>();
        final List<HealingCommand> heals = new ArrayList<>();
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        List<String> members = List.of("alpha", "beta");
        TargetQuery.Outcome outcome = TargetQuery.Outcome.AVAILABLE;
        Harness() throws Exception { this(json("membership_aura")); }
        Harness(JsonObject data) {
            session = new EffectSession(engine(compile(data)), EffectState.empty().withSource(A).withSource(B), request -> {
                requests.add(request);
                return switch (request.command()) {
                    case TargetQuery query -> new TargetQuery.Result(query, outcome, outcome == TargetQuery.Outcome.AVAILABLE
                            ? members.stream().map(id -> new TargetQuery.Target(id, 1)).sorted(query.comparator()).toList() : List.of());
                    case Action.CueCommand cue -> { cues.add(cue); yield RuleEngine.Empty.INSTANCE; }
                    case HealingCommand heal -> {
                        heals.add(heal); yield new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
                    }
                    default -> throw new AssertionError(request.command());
                };
            });
        }
        void start(long time, EffectSource source) { signal(time, "test:start", source); }
        void stop(long time, EffectSource source) { signal(time, "test:stop", source); }
        void signal(long time, String type, EffectSource source) { session.start(time, new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(), Map.of()))); settled(); }
        void advance(long time) { session.observe(time, List.of()); settled(); }
        void settled() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty()); }
        Optional<BuffInstance> field(EffectSource source) { return buff("test:field", "owner", source); }
        Optional<BuffInstance> buff(String definition, String holder, EffectSource source) { return session.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.definition().id().equals(definition) && b.key().holder().equals(holder) && b.origin().source().equals(source.origin().source())).findFirst(); }
        Set<String> members(EffectSource source) { return field(source).orElseThrow().components().targetSets().get("members").ids(); }
        List<String> cues(String name) { return cues.stream().filter(c -> c.cue().equals("test:" + name)).map(Action.CueCommand::target).toList(); }
        double healed(String target) { return heals.stream().filter(h -> h.target().equals(target)).mapToDouble(HealingCommand::amount).sum(); }
    }
    @Test void identitiesAreImmutableCanonicalAndDistinctFromDistancesAndGenericStringSets() {
        var input = new ArrayList<>(List.of("beta", "alpha", "alpha")); var identities = Targets.Identities.of(input); input.clear();
        assertEquals(List.of(new Targets.Identity("alpha"), new Targets.Identity("beta")), identities.targets());
        assertThrows(UnsupportedOperationException.class, () -> identities.targets().clear());
        assertThrows(IllegalArgumentException.class, () -> new Targets.Identity(" "));
        assertThrows(IllegalArgumentException.class, () -> new Targets.Identities(List.of(new Targets.Identity("b"), new Targets.Identity("a"))));
        assertThrows(IllegalArgumentException.class, () -> new Targets.Identities(List.of(new Targets.Identity("a"), new Targets.Identity("a"))));
        var schema = new BuffSchema(Map.of("n", new Measure(0, Unit.COUNT)), Set.of("seen"), Set.of("ref"), Set.of("members"));
        var components = schema.initial().targets("members", identities).number("n", BuffComponents.Update.ADD, 1).remember("seen", "damage-id").reference("ref", "value");
        assertEquals(identities, components.targetSets().get("members")); assertEquals(Set.of("damage-id"), components.sets().get("seen"));
        assertThrows(IllegalArgumentException.class, () -> schema.requireTargets("seen"));
        assertThrows(IllegalArgumentException.class, () -> schema.initial().targets("undeclared", identities));
        assertThrows(IllegalArgumentException.class, () -> new BuffSchema(Map.of(), Set.of("same"), Set.of(), Set.of("same")));
        assertThrows(IllegalArgumentException.class, () -> ResultShape.TARGET_IDENTITIES.targetElement().unit("distance"));
        assertEquals("alpha", ResultShape.TARGET_IDENTITY.target(identities.targets().getFirst()));
    }
    @Test void membershipDifferencesSeparateBeforeAfterEntriesExitsAndUnavailableObservation() {
        var before = Targets.Identities.of(List.of("alpha", "beta")); var after = Targets.Identities.of(List.of("beta", "gamma"));
        var difference = new Targets.Difference(true, before, after);
        assertEquals(before, difference.part(Targets.Part.BEFORE)); assertEquals(after, difference.part(Targets.Part.AFTER));
        assertEquals(Set.of("gamma"), difference.part(Targets.Part.ENTERED).ids()); assertEquals(Set.of("alpha"), difference.part(Targets.Part.EXITED).ids());
        assertTrue(ResultShape.TARGET_DIFFERENCE.flag("observed", difference)); assertTrue(ResultShape.TARGET_DIFFERENCE.flag("changed", difference));
        assertEquals(2, ResultShape.TARGET_DIFFERENCE.read("after_count", difference).value()); assertEquals(1, ResultShape.TARGET_DIFFERENCE.read("exited_count", difference).value());
        var missing = new Targets.Difference(false, before, before); assertFalse(missing.changed()); assertTrue(missing.part(Targets.Part.EXITED).targets().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Targets.Difference(false, before, after));
    }
    @Test void periodicDifferencesApplyEntryAndExitOnceAndHealingIntegratesThePreviousMembership() throws Exception {
        var test = new Harness(); test.start(0, A);
        assertEquals(Set.of("alpha", "beta"), test.members(A)); assertEquals(List.of("alpha", "beta"), test.cues("enter"));
        test.members = List.of("beta", "gamma"); test.advance(50_000);
        assertEquals(Set.of("beta", "gamma"), test.members(A)); assertEquals(List.of("alpha"), test.cues("exit"));
        assertEquals(List.of("alpha", "beta", "gamma"), test.cues("enter"));
        test.advance(100_000); assertEquals(3, test.cues("enter").size()); assertEquals(1, test.cues("exit").size());
        test.members = List.of(); test.advance(150_000);
        assertEquals(Set.of(), test.members(A)); assertEquals(List.of("alpha", "beta", "gamma"), test.cues("exit"));
        assertEquals(.1, test.healed("alpha"), 1e-9); assertEquals(.3, test.healed("beta"), 1e-9); assertEquals(.2, test.healed("gamma"), 1e-9);
        test.advance(300_000); assertTrue(test.field(A).isEmpty()); assertEquals(3, test.cues("exit").size()); assertTrue(test.session.state().engine().domain().timers().isEmpty());
    }
    @Test void overlapAndCleanupAreSourceScopedAndDoNotRemoveAnotherFieldsPresence() throws Exception {
        var test = new Harness(); test.start(0, A); test.start(0, B);
        assertEquals(4, test.cues("enter").size()); test.stop(50_000, A);
        assertTrue(test.field(A).isEmpty()); assertTrue(test.buff("test:presence", "alpha", A).isEmpty()); assertTrue(test.buff("test:presence", "alpha", B).isPresent());
        assertEquals(2, test.cues("exit").size()); test.advance(100_000);
        assertEquals(.2, test.healed("alpha"), 1e-9, "shared recovery channel does not stack overlapping auras");
        test.session.start(100_000, SourceChange.remove(B.instance())); test.advance(300_000);
        assertTrue(test.field(B).isEmpty()); assertTrue(test.buff("test:presence", "alpha", B).isEmpty()); assertEquals(4, test.cues("exit").size());
        assertEquals(.6, test.healed("alpha"), 1e-9, "buff owns its polling lifetime even after the casting source is unbound");
    }
    @Test void refreshKeepsMembershipAndGenerationButRemovalAndNewGenerationStartEmpty() throws Exception {
        var test = new Harness(); test.start(0, A); long generation = test.field(A).orElseThrow().generation();
        test.start(100_000, A); assertEquals(generation, test.field(A).orElseThrow().generation()); assertEquals(400_000, test.field(A).orElseThrow().deadline());
        assertEquals(2, test.cues("enter").size()); test.stop(150_000, A); assertEquals(2, test.cues("exit").size());
        test.start(150_000, A); assertNotEquals(generation, test.field(A).orElseThrow().generation()); assertEquals(4, test.cues("enter").size());
        test.advance(450_000); assertEquals(4, test.cues("exit").size()); assertTrue(test.field(A).isEmpty());
    }
    @Test void queryFailureHasExplicitContentPolicyAndCoreSynchronizationDoesNotTreatItAsAnEmptySet() throws Exception {
        var stopping = new Harness(); stopping.start(0, A); stopping.outcome = TargetQuery.Outcome.MISSING_CENTER; stopping.advance(50_000);
        assertTrue(stopping.field(A).isEmpty()); assertEquals(List.of("alpha", "beta"), stopping.cues("exit"));
        var data = json("membership_aura");
        for (var raw : data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules")) {
            var rule = raw.getAsJsonObject(); if (rule.get("id").getAsString().equals("cleanup")) continue;
            var actions = rule.getAsJsonArray("do"); var branch = actions.remove(actions.size() - 1).getAsJsonObject();
            branch.getAsJsonArray("then").forEach(actions::add); // This variant elects to preserve membership on unavailable observations.
        }
        for (var outcome : List.of(TargetQuery.Outcome.MISSING_CENTER, TargetQuery.Outcome.MISSING_RELATIVE, TargetQuery.Outcome.WRONG_DIMENSION)) {
            var preserving = new Harness(data); preserving.start(0, A); preserving.outcome = outcome; preserving.advance(50_000);
            assertEquals(Set.of("alpha", "beta"), preserving.members(A)); assertEquals(2, preserving.cues("enter").size()); assertTrue(preserving.cues("exit").isEmpty());
            preserving.outcome = TargetQuery.Outcome.AVAILABLE; preserving.members = List.of(); preserving.advance(100_000);
            assertTrue(preserving.members(A).isEmpty()); assertEquals(List.of("alpha", "beta"), preserving.cues("exit"));
        }
    }
    @Test void membershipsCommitBeforeEntryWorldWaitAndDuplicateReceiptCannotRestartTheLoop() throws Exception {
        var engine = engine(load("membership_aura")); var initial = engine.initial(EffectState.empty().withSource(A));
        var queryWait = send(engine, initial, 0, "test:start", new EffectEvent("owner", "owner", A.origin(), Set.of(), Map.of()));
        var query = (TargetQuery) queryWait.actions().getFirst().command(); var queryOp = queryWait.actions().getFirst().id();
        var selected = new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("alpha", 1), new TargetQuery.Target("beta", 2)));
        var first = complete(engine, queryWait, selected); assertEquals("alpha", ((Action.CueCommand) first.actions().getFirst().command()).target());
        assertEquals(Set.of("alpha", "beta"), buff(first.state(), "test:field", "owner").components().targetSets().get("members").ids());
        assertEquals(first.state(), engine.transition(first.state(), new RuleEngine.Completed(queryOp, selected)).state());
        var second = complete(engine, first, RuleEngine.Empty.INSTANCE); assertEquals("beta", ((Action.CueCommand) second.actions().getFirst().command()).target());
        assertNotEquals(first.actions().getFirst().id(), second.actions().getFirst().id());
        assertEquals(second.state(), engine.transition(second.state(), new RuleEngine.Completed(first.actions().getFirst().id(), RuleEngine.Empty.INSTANCE)).state());
        assertTrue(complete(engine, second, RuleEngine.Empty.INSTANCE).state().idle());
    }
    @Test void delayedEntryActionsCaptureIdentityWithoutBorrowingDistancesOrRequiringTheFieldToSurvive() throws Exception {
        var data = json("membership_aura");
        for (var raw : data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules")) {
            var rule = raw.getAsJsonObject(); if (rule.get("id").getAsString().equals("cleanup")) continue;
            var actions = rule.getAsJsonArray("do"); var then = actions.get(actions.size() - 1).getAsJsonObject().getAsJsonArray("then");
            var enter = then.get(4).getAsJsonObject().getAsJsonArray("do"); var cue = enter.remove(1); enter.remove(0);
            var delayed = JsonParser.parseString("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"},\"lifetime\":\"detached\",\"do\":[]}").getAsJsonObject();
            delayed.getAsJsonArray("do").add(cue); enter.add(delayed);
        }
        var test = new Harness(data); test.start(0, A); test.stop(50_000, A); assertTrue(test.cues("enter").isEmpty());
        test.members = List.of("different"); test.advance(100_000); assertEquals(List.of("alpha", "beta"), test.cues("enter")); assertTrue(test.field(A).isEmpty());
    }
    @Test void strictTypesRejectDistanceReadsOnRememberedIdentitiesAndComponentKindConfusion() throws Exception {
        var compiled = load("membership_aura"); assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, compiled).getOrThrow()).getOrThrow().program());
        var data = json("membership_aura"); var cleanup = data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("do");
        cleanup.get(1).getAsJsonObject().getAsJsonArray("do").add(JsonParser.parseString("""
                {"type":"chorus:capture_value","value":{"type":"chorus:result","binding":"member","field":"distance"}}
                """));
        assertThrows(RuntimeException.class, () -> compile(data));
        for (String bad : List.of("seen", "count", "marker", "unknown")) {
            var invalid = json("membership_aura"); var read = invalid.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject()
                    .getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action"); read.addProperty("component", bad);
            assertThrows(RuntimeException.class, () -> compile(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> ResultShape.TARGET_DIFFERENCE.requireTargets());
        assertThrows(IllegalArgumentException.class, () -> ResultShape.TARGET_IDENTITIES.requireTargetDifference());
        assertThrows(IllegalArgumentException.class, () -> ResultShape.TARGET_IDENTITY.requirePosition());
    }
    @Test void savedIdentitiesCanBeCopiedAndEndReadsTheOldGenerationAfterImmediateReplacement() throws Exception {
        var data = json("membership_aura");
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").getAsJsonObject("components").getAsJsonArray("target_sets").add("copy");
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
                {"id":"replace","on":"test:replace","if":{"type":"chorus:source_is","source":"this_ability"},"do":[
                  {"action":{"type":"chorus:read_targets","buff":"test:field","component":"members"},"as":"old"},
                  {"action":{"type":"chorus:sync_targets","buff":"test:field","component":"copy","targets":"old"},"as":"copied"},
                  {"type":"chorus:remove_buff","buff":"test:field"},
                  {"type":"chorus:grant_buff","buff":"test:field"}
                ]}
                """));
        // Read the copied set during old-generation cleanup. The new generation already exists and is still empty.
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("do")
                .get(0).getAsJsonObject().getAsJsonObject("action").addProperty("component", "copy");
        var test = new Harness(data); test.start(0, A); long old = test.field(A).orElseThrow().generation();
        test.members = List.of("gamma"); test.signal(0, "test:replace", A);
        assertNotEquals(old, test.field(A).orElseThrow().generation());
        assertEquals(List.of("alpha", "beta"), test.cues("exit"), "ended snapshot survives the replacement's empty components");
        assertEquals(Set.of("gamma"), test.members(A));
        assertTrue(test.field(A).orElseThrow().components().targetSets().get("copy").targets().isEmpty());
        assertTrue(test.buff("test:presence", "alpha", A).isEmpty()); assertTrue(test.buff("test:presence", "gamma", A).isPresent());
    }
}
