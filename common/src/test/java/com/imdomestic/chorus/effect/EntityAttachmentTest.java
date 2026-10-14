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

class EntityAttachmentTest {
    static final EntityAnchor.Reference REF = new EntityAnchor.Reference("target", "life", "world");
    static WorldPosition point(double x) { return new WorldPosition("world", x, 2, 3); }
    static EntityAnchor.Observation anchor(double x, boolean alive) { return new EntityAnchor.Observation(REF, point(x), TargetQuery.Anchor.FEET, alive); }
    static EntityAttachments.Policy policy(EntityAttachments.DeathPolicy death, EntityAttachments.RemovalPolicy removal) {
        return new EntityAttachments.Policy(death, removal, removal, removal, removal);
    }
    static EntityAttachments.Handle handle(String id, double contact, EntityAttachments.Policy policy) {
        return new EntityAttachments.Handle(id, 0, 1_000_000, anchor(1, true), point(contact), policy);
    }
    static EntityAttachments.Query query(EffectState state, EntityAttachments.Handle handle) {
        return (EntityAttachments.Query) ((RuleEngine.Await<EffectState>) EntityAttachments.resolve(state, handle)).command();
    }
    static EntityAttachments.Observed observed(EntityAttachments.Query q, double x, boolean alive) {
        return new EntityAttachments.Observed(q, new EntityAnchor.Result(q.expected().handle().query(), EntityAnchor.Outcome.AVAILABLE, Optional.of(anchor(x, alive))));
    }
    static EntityObservation observation(boolean present, boolean alive, double x) {
        var positions = new HashMap<PositionQuery, Optional<WorldPosition>>();
        for (var a : TargetQuery.Anchor.values()) positions.put(new PositionQuery("target", a), Optional.of(point(x)));
        return new EntityObservation(0, Map.of("target", present ? Optional.of(new EntityQuery.View(alive, false, alive ? 10 : 0, 10, 0, Set.of(), Set.of())) : Optional.empty()), positions);
    }
    static RuleEngine.Signal died(double x) { return new EntityLifecycle.Died(REF, "damage/death", observation(true, false, x)).signal(); }
    static RuleEngine.Signal removed(double x, EntityLifecycle.Kind kind) { return new EntityLifecycle.Removed(REF, kind, "test:remove", true, observation(false, false, x)).signal(); }
    static EffectState observe(EffectState state, RuleEngine.Signal fact) { return new EntityAttachments.Commit(RuleEngine.Empty.INSTANCE, List.of(fact)).apply(state); }
    static EntityAttachments.Resolution local(EffectState state, EntityAttachments.Handle h) { return (EntityAttachments.Resolution) ((RuleEngine.Local<EffectState>) EntityAttachments.resolve(state, h)).result(); }

    @Test void separateContactsFollowTheSameLifeWithoutOverwritingEachOther() {
        var policy = policy(EntityAttachments.DeathPolicy.FOLLOW, EntityAttachments.RemovalPolicy.WAIT);
        var a = handle("a", 2, policy); var b = handle("b", 4, policy);
        var state = EntityAttachments.begin(EntityAttachments.begin(EffectState.empty(), a).state(), b).state();
        var q = query(state, a); var result = EntityAttachments.resolved(state, q, observed(q, 10, true)); state = result.state();
        assertEquals(point(11), ((EntityAttachments.Resolution) result.result()).position().orElseThrow());
        q = query(state, b); result = EntityAttachments.resolved(state, q, observed(q, 20, true)); state = result.state();
        assertEquals(point(23), ((EntityAttachments.Resolution) result.result()).position().orElseThrow());
        state = EntityAttachments.close(state, a).state(); assertEquals(Set.of("b"), state.attachments().keySet());
        assertEquals(EntityAttachments.Outcome.CLOSED, local(state, a).outcome());
        assertThrows(IllegalArgumentException.class, () -> EntityAttachments.begin(EffectState.empty(), new EntityAttachments.Handle("bad", 2, 10, anchor(1, true), point(1), policy)));
    }
    @Test void deathCanFollowFreezeAtItsExactPointOrPermanentlyEndEachInstance() {
        for (var p : EntityAttachments.DeathPolicy.values()) {
            var h = handle("a", 2, policy(p, EntityAttachments.RemovalPolicy.FIX)); var state = EntityAttachments.begin(EffectState.empty(), h).state();
            state = observe(state, died(10));
            if (p == EntityAttachments.DeathPolicy.END) { assertEquals(EntityAttachments.Outcome.CLOSED, local(state, h).outcome()); continue; }
            if (p == EntityAttachments.DeathPolicy.FIX) {
                assertEquals(point(11), local(state, h).position().orElseThrow());
                state = observe(state, removed(30, EntityLifecycle.Kind.REMOVED));
                assertEquals(point(11), local(state, h).position().orElseThrow());
                assertTrue(local(state, h).entity().observation().isEmpty());
            } else {
                var q = query(state, h); var live = EntityAttachments.resolved(state, q, observed(q, 20, false)); state = live.state();
                assertEquals(point(21), ((EntityAttachments.Resolution) live.result()).position().orElseThrow());
                state = observe(state, removed(30, EntityLifecycle.Kind.REMOVED)); assertEquals(point(31), local(state, h).position().orElseThrow());
            }
        }
    }
    @Test void removalReasonsHaveIndependentEndFixAndWaitPolicies() {
        for (var kind : EntityLifecycle.Kind.values()) for (var p : EntityAttachments.RemovalPolicy.values()) {
            var policy = new EntityAttachments.Policy(EntityAttachments.DeathPolicy.FOLLOW,
                    kind == EntityLifecycle.Kind.DESTROYED ? p : EntityAttachments.RemovalPolicy.END,
                    kind == EntityLifecycle.Kind.REMOVED ? p : EntityAttachments.RemovalPolicy.END,
                    kind == EntityLifecycle.Kind.UNLOADED ? p : EntityAttachments.RemovalPolicy.END,
                    kind == EntityLifecycle.Kind.TRANSFERRED ? p : EntityAttachments.RemovalPolicy.END);
            var h = handle("a", 2, policy); var state = observe(EntityAttachments.begin(EffectState.empty(), h).state(), removed(10, kind));
            if (p == EntityAttachments.RemovalPolicy.END) assertEquals(EntityAttachments.Outcome.CLOSED, local(state, h).outcome());
            else if (p == EntityAttachments.RemovalPolicy.FIX) assertEquals(point(11), local(state, h).position().orElseThrow());
            else {
                var q = query(state, h); var missing = new EntityAttachments.Observed(q, new EntityAnchor.Result(h.query(), EntityAnchor.Outcome.MISSING, Optional.empty()));
                var wait = EntityAttachments.resolved(state, q, missing); assertEquals(EntityAttachments.Outcome.WAITING, ((EntityAttachments.Resolution) wait.result()).outcome());
                assertTrue(((EntityAttachments.Resolution) wait.result()).position().isEmpty());
                var resume = EntityAttachments.resolved(wait.state(), q, observed(q, 20, true)); assertEquals(point(21), ((EntityAttachments.Resolution) resume.result()).position().orElseThrow());
            }
        }
    }
    @Test void queryReceiptsCannotReviveAnEndedOrNewlyWaitingAttachment() {
        var h = handle("a", 2, policy(EntityAttachments.DeathPolicy.END, EntityAttachments.RemovalPolicy.WAIT));
        var state = EntityAttachments.begin(EffectState.empty(), h).state(); var q = query(state, h); var stale = observed(q, 20, true);
        var ended = EntityAttachments.resolved(observe(state, died(10)), q, stale);
        assertEquals(EntityAttachments.Outcome.CLOSED, ((EntityAttachments.Resolution) ended.result()).outcome());
        state = observe(state, removed(10, EntityLifecycle.Kind.UNLOADED));
        var waiting = EntityAttachments.resolved(state, q, stale); assertEquals(EntityAttachments.Outcome.WAITING, ((EntityAttachments.Resolution) waiting.result()).outcome());
        var fresh = query(waiting.state(), h); var resumed = EntityAttachments.resolved(waiting.state(), fresh, observed(fresh, 30, true));
        assertEquals(point(31), ((EntityAttachments.Resolution) resumed.result()).position().orElseThrow());
        var next = query(resumed.state(), h); var replacement = new EntityAttachments.Observed(next, new EntityAnchor.Result(h.query(), EntityAnchor.Outcome.REPLACED, Optional.empty()));
        var replaced = EntityAttachments.resolved(resumed.state(), next, replacement); assertEquals(EntityAttachments.Outcome.REPLACED, ((EntityAttachments.Resolution) replaced.result()).outcome());
        assertEquals(EntityAttachments.Outcome.CLOSED, local(replaced.state(), h).outcome());
    }
    @Test void lifecycleCommitsDoNotReplayAsLaterPublicEventsOrTouchAnotherLife() throws Exception {
        var h = handle("a", 2, policy(EntityAttachments.DeathPolicy.END, EntityAttachments.RemovalPolicy.END));
        var p = load("entity_attachment"); var clock = EffectCheckpointTest.clock();
        var session = new EffectSession(p, clock, 256, EntityAttachments.begin(EffectState.empty(), h).state(), _ -> { throw new AssertionError("Unexpected world work"); });
        var event = died(10); session.observe(0, List.of(event), EntityAttachments.commit(RuleEngine.Empty.INSTANCE, List.of(event)));
        assertTrue(session.state().engine().domain().attachments().isEmpty());
        var state = EntityAttachments.begin(session.state().engine().domain(), handle("new", 2, h.policy())).state();
        session = new EffectSession(p, clock, 256, state, _ -> { throw new AssertionError("Unexpected world work"); });
        session.start(0, event); assertEquals(Set.of("new"), session.state().engine().domain().attachments().keySet());
        var other = new EntityLifecycle.Died(new EntityAnchor.Reference("target", "other-life", "world"), "other/death", observation(true, false, 10)).signal();
        assertEquals(state, observe(state, other));
        assertThrows(IllegalArgumentException.class, () -> new EntityAttachments.Commit(RuleEngine.Empty.INSTANCE, List.of(new RuleEngine.Signal("test:fake", event.payload()))));
    }
    @Test void deadlinesCleanUnusedHandlesAndV3MigrationPreservesOldFields() throws Exception {
        var h = handle("a", 2, policy(EntityAttachments.DeathPolicy.FOLLOW, EntityAttachments.RemovalPolicy.WAIT));
        var state = EntityAttachments.begin(EffectState.empty(), h).state().withMode(EffectState.Mode.PVP);
        var clock = EffectCheckpointTest.clock(); assertEquals(h.expiresAt(), clock.nextDeadline(state));
        state = clock.advance(state, h.expiresAt()).state(); assertTrue(state.attachments().isEmpty());
        assertEquals(EntityAttachments.Outcome.EXPIRED, local(state, h).outcome());
        var encoded = EffectCheckpointCodec.DATA.encode(state).getAsJsonObject(); encoded.addProperty("$", "chorus:effect/effect_state_v3"); encoded.getAsJsonObject("v").remove("attachments"); encoded.getAsJsonObject("v").remove("charging"); encoded.getAsJsonObject("v").remove("actors");
        var migrated = EffectCheckpointCodec.DATA.decode(encoded, EffectState.class); assertEquals(state, migrated);
        assertEquals("chorus:effect/effect_state_v6", EffectCheckpointCodec.DATA.encode(migrated).getAsJsonObject().get("$").getAsString());
    }
    @Test void detachedFuseRetainsTheRecordedOffsetAcrossSourceRemovalAndCheckpoint() throws Exception {
        var p = load("entity_attachment"); var queries = new ArrayList<TargetQuery>(); var cues = new ArrayList<String>();
        java.util.function.Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world = r -> switch (r.command()) {
            case EntityAnchor.Query q -> new EntityAnchor.Result(q, EntityAnchor.Outcome.AVAILABLE, Optional.of(anchor(1, true)));
            case PositionQuery q -> new PositionQuery.Result(q, Optional.of(point(2)));
            case TargetQuery q -> { queries.add(q); yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, List.of()); }
            case Action.CueCommand c -> { cues.add(c.cue()); yield RuleEngine.Empty.INSTANCE; }
            default -> throw new AssertionError("Frozen attachment consulted the live world: " + r.command());
        };
        var source = source("test:attachment"); var session = new EffectSession(p, EffectCheckpointTest.clock(), 256, EffectState.empty().withSource(source), world);
        session.start(0, new RuleEngine.Signal("test:attach", event(source))); assertEquals(1, session.state().engine().domain().attachments().size());
        var removed = removed(10, EntityLifecycle.Kind.REMOVED); session.observe(0, List.of(removed), EntityAttachments.commit(RuleEngine.Empty.INSTANCE, List.of(removed)));
        session.start(0, SourceChange.remove(source.instance())); var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
        session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 256, saved, world); session.observe(100_000, List.of());
        assertEquals(List.of("test:attachment_due"), cues); assertEquals(Optional.of(point(11)), ((TargetQuery.PositionCenter) queries.getFirst().center()).position());
        assertTrue(session.state().engine().domain().attachments().isEmpty());
    }
    @Test void attachmentActionsKeepTheirTypedBindingsAndStrictPolicySchema() throws Exception {
        var p = json("entity_attachment"); var action = p.getAsJsonArray("bundles").get(2).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(2).getAsJsonObject().getAsJsonObject("action");
        var decoded = EffectCodecs.ACTION.parse(JsonOps.INSTANCE, action).getOrThrow();
        assertEquals(decoded, EffectCodecs.ACTION.parse(JsonOps.INSTANCE, EffectCodecs.ACTION.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow()).getOrThrow());
        action.getAsJsonObject("policy").addProperty("unknown", true); assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE, action).error().isPresent());
        assertThrows(IllegalArgumentException.class, () -> AttachmentActions.RESULT.requireEntityAnchor());
        assertThrows(IllegalArgumentException.class, () -> EntityAnchorActions.RESULT.requireAttachment());
    }
}
