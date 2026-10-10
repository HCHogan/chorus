package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DamageBatchTest {
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("player", "weapon", "weapon", "");
    static EffectSource source(String id, String bundle) { return new EffectSource(id, bundle, "player", ORIGIN, Set.of()); }
    static final class Harness {
        final CompiledEffects program; final EffectSession session;
        final List<DamageCommand> hits = new ArrayList<>(); final List<DamageReceipt> receipts = new ArrayList<>();
        final List<Long> roots = new ArrayList<>(); final List<ProjectileFlight.Launch> launches = new ArrayList<>();
        final Deque<DamageReceipt.Outcome> outcomes = new ArrayDeque<>(); int unknownAt;
        Harness() throws Exception { this(load("damage_batch")); }
        Harness(CompiledEffects program) {
            this.program = program;
            var state = EffectState.empty().withSource(source("actor", "test:actor")).withSource(source("observer", "test:observer"));
            state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:counter"), "player", "player", ORIGIN, 1, 1, 10_000_000).store());
            session = new EffectSession(engine(program), state, request -> switch (request.command()) {
                case DamageCommand command -> {
                    hits.add(command); roots.add(root());
                    if (unknownAt == hits.size()) throw new IllegalStateException("Unknown batch component outcome");
                    var outcome = outcomes.isEmpty() ? DamageReceipt.Outcome.APPLIED : outcomes.removeFirst();
                    var receipt = new DamageReceipt(request.id().toString(), outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? command.amount() : 0, Optional.empty(), false);
                    receipts.add(receipt); yield receipt;
                }
                case TargetQuery query -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, List.of(new TargetQuery.Target("enemy", 1), new TargetQuery.Target("other", 2)));
                case PositionQuery query -> new PositionQuery.Result(query, Optional.of(new WorldPosition("world", 0, 40, 0)));
                case DirectionQuery query -> new DirectionQuery.Result(query, Optional.of(new WorldDirection("world", 0, 1, 0)));
                case ProjectileFlight.Launch launch -> { launches.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("projectile")); }
                default -> throw new AssertionError(request.command());
            });
        }
        long root() { return session.state().engine().frames().getFirst().event().root(); }
        EffectState state() { return session.state().engine().domain(); }
        void input(String event) { session.start(0, new RuleEngine.Signal("test:" + event, new EffectEvent("player", "enemy", ORIGIN, Set.of(), Map.of()))); }
        double count(String field) { return state().buffs().instances().values().iterator().next().components().numbers().get(field); }
        void counts(int instances, int batches) { assertEquals(instances, count("instances")); assertEquals(batches, count("batches")); }
        String batch(int i) { return DamageBatch.reference(hits.get(i), receipts.get(i)); }
    }
    @Test void declaredBatchCountsOnceWhileStandaloneDamageAndRepeatedInvocationRemainIndependent() throws Exception {
        var h = new Harness(); h.input("pair"); h.counts(3, 2);
        assertEquals(h.batch(0), h.batch(1)); assertNotEquals(h.batch(0), h.batch(2)); assertEquals(1, new HashSet<>(h.roots).size());
        h.input("pair"); h.counts(6, 4); assertNotEquals(h.batch(0), h.batch(3));
    }
    @Test void twoDeclaredBatchesAtOneTimeWithinOneRootRemainDistinct() throws Exception {
        var h = new Harness(); h.input("separate"); h.counts(2, 2);
        assertEquals(h.roots.get(0), h.roots.get(1)); assertNotEquals(h.batch(0), h.batch(1));
    }
    @Test void beginInsideTargetIterationUsesItsInvocationIdentity() throws Exception {
        var h = new Harness(); h.input("area"); h.counts(4, 2);
        assertEquals(List.of("enemy", "enemy", "other", "other"), h.hits.stream().map(DamageCommand::target).toList());
        assertEquals(h.batch(0), h.batch(1)); assertEquals(h.batch(2), h.batch(3)); assertNotEquals(h.batch(0), h.batch(2));
    }
    @Test void sharedBatchAcrossTargetsIsExplicitlyDifferentFromOneBatchPerTarget() throws Exception {
        var data = json("damage_batch"); var actions = rule(data, "area").getAsJsonArray("do");
        var body = actions.get(1).getAsJsonObject().getAsJsonArray("do"); var begin = body.remove(0);
        var replacement = new JsonArray(); replacement.add(begin); actions.forEach(replacement::add); rule(data, "area").add("do", replacement);
        var h = new Harness(compile(data)); h.input("area"); h.counts(4, 1);
        assertEquals(h.batch(0), h.batch(3));
    }
    @Test void reusableSnapshotGroupsOnlyExplicitImpactCommands() throws Exception {
        var h = new Harness(); h.input("snapshot"); h.counts(3, 2);
        assertTrue(h.hits.stream().allMatch(c -> c.snapshot().orElseThrow().attack().batch().isEmpty()));
        assertEquals(h.hits.get(0).snapshot(), h.hits.get(2).snapshot()); assertTrue(h.hits.get(2).batch().isEmpty());
    }
    @Test void detachedContinuationPreservesAnExplicitLogicalBatchWithoutInferringClockEquality() throws Exception {
        var h = new Harness(); h.input("later"); h.counts(1, 1); h.session.start(0, SourceChange.remove("actor"));
        h.session.observe(100_000, List.of()); h.counts(3, 2); assertEquals(h.batch(0), h.batch(1));
        assertTrue(h.state().sources().values().stream().noneMatch(s -> s.bundle().equals("test:actor")));
    }
    @Test void projectileContactCanAssignOneBatchToTwoSnapshotComponentsAfterSourceRemoval() throws Exception {
        var h = new Harness(); h.input("launch"); h.session.start(0, SourceChange.remove("actor"));
        h.session.start(100_000, h.launches.getFirst().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY,
                new WorldPosition("world", 0, 46, 0), Optional.of("enemy"), 0, 0, 0, 100_000, 1, 0, 1, 1, true)));
        h.counts(2, 1); assertEquals(h.batch(0), h.batch(1));
    }
    @Test void derivedDamageDoesNotImplicitlyInheritItsTriggerBatch() throws Exception {
        var h = new Harness(); h.input("cascade"); h.counts(3, 2);
        assertEquals(1, new HashSet<>(h.roots).size()); assertTrue(h.hits.get(2).batch().isEmpty()); assertNotEquals(h.batch(0), h.batch(2));
    }
    @Test void cancelledFailedAndUnknownComponentsNeverInventConfirmedCounts() throws Exception {
        var h = new Harness(); h.outcomes.addAll(List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED));
        h.input("pair"); h.counts(2, 2);
        var failed = new Harness(); failed.outcomes.addAll(List.of(DamageReceipt.Outcome.FAILED, DamageReceipt.Outcome.FAILED)); failed.input("separate"); failed.counts(0, 0);
        var unknown = new Harness(); unknown.unknownAt = 1; assertThrows(IllegalStateException.class, () -> unknown.input("pair"));
        unknown.counts(0, 0); assertEquals(1, unknown.hits.size()); assertTrue(unknown.receipts.isEmpty()); assertTrue(unknown.session.state().engine().pending().isPresent());
        assertTrue(unknown.hits.getFirst().batch().isPresent()); assertThrows(IllegalStateException.class, () -> unknown.input("pair")); assertEquals(1, unknown.hits.size());
    }
    @Test void allReceiptFactsShareBatchButKeepActualDamageAndDeathIdentity() throws Exception {
        var h = new Harness(); h.input("pair");
        var command = h.hits.getFirst(); var receipt = new DamageReceipt("actual", DamageReceipt.Outcome.APPLIED, 0, 0, 2, Optional.of("death"), false);
        var facts = DamageFacts.from(command, receipt, Map.of("batch_id", "spoof", "shot", "independent-shot"));
        assertEquals(List.of("chorus:hit", "chorus:damage_taken", "chorus:death", "chorus:kill"), facts.stream().map(RuleEngine.Signal::type).toList());
        for (var fact : facts) {
            var e = (EffectEvent) fact.payload(); assertEquals(command.batch().orElseThrow().reference(), e.references().get("batch_id"));
            assertEquals("actual", e.references().get("damage_id")); assertEquals("death", e.references().get("death_id")); assertEquals("independent-shot", e.references().get("shot"));
        }
    }
    @Test void batchOwnershipAndSnapshotBoundaryAreValidatedAndMetadataCopiesKeepMembership() throws Exception {
        var h = new Harness(); h.input("snapshot"); var command = h.hits.getFirst(); var batch = command.batch().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> command.withBatch(new DamageBatch("other", "player")));
        assertThrows(IllegalArgumentException.class, () -> h.hits.get(2).withBatch(new DamageBatch("foreign", "other")));
        assertEquals(batch, command.withProc(command.proc()).withConsumptions(List.of()).withReactions(command.reactions().orElseThrow()).batch().orElseThrow());
        var attack = command.snapshot().orElseThrow().attack(); assertThrows(IllegalArgumentException.class, () -> h.program.captureDamage(h.state(), attack.withBatch(batch)));
        assertNotEquals(new DamageBatch("x", "player").reference(), DamageBatch.reference(attack, new DamageReceipt("x", DamageReceipt.Outcome.APPLIED, 0, 0, 2, Optional.empty(), false)));
    }
    @Test void codecChecksLexicalBatchTypesAndDisallowsBatchOnReleaseSnapshot() throws Exception {
        var p = load("damage_batch").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        for (String mutation : List.of("missing", "wrong_type", "capture")) {
            var data = json("damage_batch"); var actions = rule(data, "snapshot").getAsJsonArray("do");
            if (mutation.equals("capture")) actions.get(0).getAsJsonObject().getAsJsonObject("action").addProperty("batch", "batch");
            else actions.get(2).getAsJsonObject().addProperty("batch", mutation.equals("missing") ? "missing" : "attack");
            assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).error().isPresent(), mutation);
        }
    }
    @Test void eventOriginBatchMustBeExplicitAndCannotAttachToAnotherOwnersDamage() throws Exception {
        for (boolean eventBatch : List.of(false, true)) {
            var data = json("damage_batch"); var actions = rule(data, "pair").getAsJsonArray("do");
            if (eventBatch) actions.get(0).getAsJsonObject().getAsJsonObject("action").addProperty("origin", "event");
            for (int i = 1; i < actions.size(); i++) actions.get(i).getAsJsonObject().addProperty("origin", "event");
            var h = new Harness(compile(data)); var other = new BuffInstance.Origin("other", "other-weapon", "other-weapon", "");
            var signal = new RuleEngine.Signal("test:pair", new EffectEvent("other", "enemy", other, Set.of(), Map.of()));
            if (eventBatch) {
                h.session.start(0, signal); assertEquals(3, h.hits.size()); assertEquals("other", h.hits.getFirst().batch().orElseThrow().owner());
                h.counts(0, 0); // The player-owned observer must not count the other owner's hits.
            } else { assertThrows(IllegalStateException.class, () -> h.session.start(0, signal)); assertTrue(h.hits.isEmpty()); }
        }
        assertNotEquals(new DamageBatch("same-local-id", "one").reference(), new DamageBatch("same-local-id", "two").reference());
    }
    @Test void unknownSecondComponentKeepsTheFirstReceiptWithoutPublishingOrRetryingTheSecond() throws Exception {
        var h = new Harness(); h.unknownAt = 2; assertThrows(IllegalStateException.class, () -> h.input("pair"));
        assertEquals(2, h.hits.size()); assertEquals(1, h.receipts.size()); assertEquals(2, h.receipts.getFirst().healthLoss());
        assertEquals(h.hits.get(0).batch(), h.hits.get(1).batch()); assertTrue(h.session.state().engine().pending().isPresent());
        h.counts(0, 0); // The confirmed first hit is queued behind this unfinished action frame.
        assertThrows(IllegalStateException.class, () -> h.input("pair")); assertEquals(2, h.hits.size());
    }
    static JsonObject rule(JsonObject data, String id) {
        for (var r : data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules")) if (r.getAsJsonObject().get("id").getAsString().equals(id)) return r.getAsJsonObject();
        throw new AssertionError(id);
    }
}
