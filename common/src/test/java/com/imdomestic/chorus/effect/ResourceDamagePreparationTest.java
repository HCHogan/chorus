package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.CombatParticipantQueryTest.*;
import static com.imdomestic.chorus.effect.EffectTestSupport.compile;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real coordinated DSL preparation, isolated from the native world's separate combat settlement. */
class ResourceDamagePreparationTest {
    static final String D = "test:d", DISPATCHER = "dispatcher";
    static final EffectSource EMITTER = new EffectSource("dispatcher", "test:dispatcher", DISPATCHER,
            new BuffInstance.Origin(DISPATCHER, "dispatcher", "", ""), Set.of());
    static EffectSource reactor(String instance) { return new EffectSource(instance, "test:reaction", OWNER, ORIGIN, Set.of()); }
    static final ResourceState.Key ENERGY = new ResourceState.Key(OWNER, "test:energy");
    static JsonObject count() { return object("{\"type\":\"chorus:buff_count\",\"buff\":\"test:next_hit\",\"target\":\"event_actor\"}"); }
    static JsonObject damage() {
        var result = object("""
                {"type":"chorus:damage","target":"victim","origin":"event","damage_type":"minecraft:generic",
                 "scaling_profile":"test:melee","tags":["test:melee"],
                 "amount":{"type":"chorus:scale","of":{"type":"chorus:buff_count","buff":"test:next_hit","target":"event_actor"},"factor":10,"from":"count","to":"damage"}}
                """);
        return result;
    }
    static JsonArray steps(JsonElement... values) { var result = new JsonArray(); for (var value : values) result.add(value); return result; }
    static JsonObject bind(JsonObject action, String name) { var result = new JsonObject(); result.add("action", action); result.addProperty("as", name); return result; }
    static JsonObject delayed(JsonArray body) {
        var branch = object("{\"if\":{\"type\":\"chorus:constant\",\"value\":true}}"); branch.add("then", body);
        var after = object("{\"after\":{\"type\":\"chorus:constant\",\"value\":0.1,\"unit\":\"second\"},\"lifetime\":\"detached\"}");
        after.add("do", steps(branch)); return after;
    }
    static JsonArray capture(boolean tagged, boolean guarded) {
        var capture = damage(); capture.remove("target"); capture.addProperty("type", "chorus:capture_damage");
        if (tagged) {
            capture.remove("type"); var wrapped = object("{\"type\":\"chorus:capture_tagged_damage\",\"inherit_event_tags\":[\"test:inherited\"]}");
            wrapped.add("damage", capture); capture = wrapped;
        }
        var hit = object("{\"type\":\"chorus:damage_snapshot\",\"snapshot\":\"shot\",\"target\":\"victim\"}");
        var body = new JsonArray();
        if (guarded) {
            body.add(bind(object("{\"type\":\"chorus:capture_entity_anchor\",\"target\":\"victim\"}"), "life"));
            hit.addProperty("type", "chorus:damage_entity_snapshot"); hit.addProperty("reference", "life");
        }
        body.add(hit); return steps(bind(capture, "shot"), delayed(body));
    }
    static JsonObject programData(JsonArray body) throws Exception {
        var data = data();
        data.add("resources", JsonParser.parseString("[{\"id\":\"test:energy\",\"capacity\":1,\"initial\":0,\"base_rate\":0}]"));
        // One-use eligibility stays on_hit. A separate nonconsumable Buff proves use-time capture.
        var capturedBuff = data.getAsJsonArray("buffs").get(0).getAsJsonObject().deepCopy();
        capturedBuff.getAsJsonObject("definition").addProperty("id", "test:captured");
        capturedBuff.addProperty("bundle", "test:captured"); capturedBuff.remove("consume_on_damage");
        data.getAsJsonArray("buffs").add(capturedBuff);
        var capturedBundle = data.getAsJsonArray("bundles").get(1).getAsJsonObject().deepCopy(); capturedBundle.addProperty("id", "test:captured");
        var modifier = capturedBundle.getAsJsonArray("modifiers").get(0).getAsJsonObject(); modifier.addProperty("evaluate", "on_use");
        modifier.getAsJsonObject("value").addProperty("buff", "test:captured"); data.getAsJsonArray("bundles").add(capturedBundle);
        data.getAsJsonArray("bundles").add(object("""
                {"id":"test:reaction","rules":[{"id":"origin","on":"chorus:hit","binding":"origin_bundle","do":[]}]}
                """));
        var dispatcher = object("{\"id\":\"test:dispatcher\"}");
        var rule = object("{\"id\":\"go\",\"on\":\"test:go\"}"); rule.add("do", body);
        dispatcher.add("rules", steps(rule)); data.getAsJsonArray("bundles").add(dispatcher); return data;
    }
    record CueTrace(String domain, String cue, RuleEngine.Signal cause) {}
    static final class Fixture {
        final CompiledEffects program; final Map<String, EffectSession> sessions = new TreeMap<>();
        final List<DamageCommand> damages = new ArrayList<>(); final List<ActionRoutes.Receipt> routes = new ArrayList<>();
        final List<String> cues = new ArrayList<>(); final List<CueTrace> traces = new ArrayList<>();
        final List<ResourceRoutes.Receipt> resourceReplies = new ArrayList<>(); ResourceSessionAuthority host;
        java.util.function.Consumer<RuleEngine.WorldRequest> beforeRoute = _ -> {};
        java.util.function.Function<ActionRoutes.Receipt, RuleEngine.ActionResult> routeReply = receipt -> receipt;
        java.util.function.BiFunction<RuleEngine.WorldRequest, DamageCommand, DamageReceipt> damageResult = (request, command) ->
                new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.CANCELLED, 0, 0, 0, Optional.empty(), false);
        Fixture(JsonArray body) throws Exception { this(body, true); }
        Fixture(JsonArray body, boolean withReactor) throws Exception { this(body, withReactor, false); }
        Fixture(JsonArray body, boolean withReactor, boolean observeFacts) throws Exception {
            var data = programData(body);
            if (observeFacts) data.getAsJsonArray("bundles").add(object("""
                    {"id":"test:observer","rules":[
                     {"id":"hit","on":"chorus:hit","do":[{"type":"chorus:play_cue","cue":"test:observed_hit"}]},
                     {"id":"taken","on":"chorus:damage_taken","do":[{"type":"chorus:play_cue","cue":"test:observed_damage_taken"}]},
                     {"id":"ended","on":"chorus:buff_ended","do":[{"type":"chorus:play_cue","cue":"test:observed_ended"}]}]}
                    """));
            program = compile(data).withResourceRouting();
            var states = new TreeMap<>(Map.of(A, withReactor ? empty(0).withSource(reactor("old")) : empty(0),
                    B, empty(0).withResource(new ResourceState(ENERGY, .75, 1, 0)),
                    C, grant(program, grant(program, empty(0), "test:next_hit", OWNER, ORIGIN), "test:captured", OWNER, ORIGIN),
                    D, empty(0).withSource(EMITTER)));
            if (observeFacts) states.replaceAll((domain, state) -> state.withSource(new EffectSource("observer/" + domain, "test:observer", "observer/" + domain,
                    new BuffInstance.Origin("observer/" + domain, "observer/" + domain, "", ""), Set.of())));
            states.forEach((domain, state) -> sessions.put(domain, new EffectSession(program, EffectCheckpointTest.clock(), 1, state, request -> world(domain, request))));
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>();
            sessions.forEach((domain, session) -> bindings.put(domain, new ResourceSessionAuthority.Binding(program, session)));
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings);
            host.enroll(Map.of(OWNER, B), Map.of(OWNER, A, DISPATCHER, D), Map.of(OWNER, C, DISPATCHER, D));
        }
        RuleEngine.ActionResult world(String domain, RuleEngine.WorldRequest request) {
            if (request.command() instanceof ResourceRequests.Request) {
                if (request.command() instanceof ActionRoutes.Request) beforeRoute.accept(request);
                var receipt = host.execute(domain, request); assertSame(receipt, host.execute(domain, request));
                if (receipt instanceof ActionRoutes.Receipt route) { routes.add(route); return routeReply.apply(route); }
                if (receipt instanceof ResourceRoutes.Receipt route) resourceReplies.add(route); return receipt;
            }
            if (request.command() instanceof DamageCommand damage) return damage(request, damage);
            if (request.command() instanceof EntityAnchor.GuardedDamage guarded) return damage(request, guarded.damage());
            if (request.command() instanceof EntityAnchor.Query query) {
                var reference = new EntityAnchor.Reference(query.target(), "life", D);
                return new EntityAnchor.Result(query, EntityAnchor.Outcome.AVAILABLE,
                        Optional.of(new EntityAnchor.Observation(reference, new WorldPosition(D, 0, 0, 0), query.anchor(), true)));
            }
            if (request.command() instanceof StatusResult.Check check) return new StatusResult.Checked(check, StatusResult.Decision.ALLOWED);
            if (request.command() instanceof Action.CueCommand cue) {
                cues.add(cue.cue()); traces.add(new CueTrace(domain, cue.cue(), sessions.get(domain).state().engine().frames().getFirst().event().signal()));
                return RuleEngine.Empty.INSTANCE;
            }
            throw new AssertionError(request.command());
        }
        DamageReceipt damage(RuleEngine.WorldRequest request, DamageCommand command) {
            damages.add(command);
            // This fixture checks preparation only; cancellation authorizes no remote Buff consumption.
            return damageResult.apply(request, command);
        }
        EffectState state(String domain) { return sessions.get(domain).state().engine().domain(); }
        void start() { sessions.get(D).start(0, new RuleEngine.Signal("test:go", new EffectEvent(OWNER, VICTIM, ORIGIN, Set.of("test:inherited"), Map.of()))); }
        void due() { sessions.get(D).observe(100_000, List.of()); assertFalse(host.drain(100).pending()); }
        void replaceReactor() {
            if (state(A).sources().containsKey("old")) sessions.get(A).start(0, SourceChange.remove("old"));
            sessions.get(A).start(0, SourceChange.bind(reactor("new")));
        }
        DamageSnapshot snapshot() { return routes.stream().filter(ActionRoutes.Completed.class::isInstance).map(ActionRoutes.Completed.class::cast)
                .map(ActionRoutes.Completed::value).filter(DamageSnapshot.class::isInstance).map(DamageSnapshot.class::cast).findFirst().orElseThrow(); }
        void assertPrepared(DamageCommand command, String source) {
            assertEquals(10, command.amount()); assertEquals(List.of(reactor(source)), command.reactions().orElseThrow().sources());
            assertEquals(1, command.consumptions().size());
            assertEquals(state(C).buffs().instances().keySet().stream().filter(key -> key.definition().equals("test:next_hit")).findFirst().orElseThrow(),
                    command.consumptions().getFirst().key());
            assertTrue(state(D).sources().values().stream().noneMatch(value -> value.holder().equals(OWNER)));
            assertEquals(.75, state(B).resources().get(ENERGY).value());
        }
    }

    @Test void ordinaryDamageCapturesCharacterReactionsAndBuffEligibilityBeforeTheNativeBoundary() throws Exception {
        var f = new Fixture(steps(damage())); f.start();
        assertEquals(1, f.damages.size()); f.assertPrepared(f.damages.getFirst(), "old");
        var ready = f.routes.stream().filter(ActionRoutes.Ready.class::isInstance).map(ActionRoutes.Ready.class::cast)
                .filter(value -> value.command() instanceof DamageCommand).findFirst().orElseThrow();
        assertEquals(ready.command(), f.damages.getFirst());
    }

    @Test void pairedViewsRequireTheSameCallerSnapshotAndTheLegacyOverloadRemainsLocal() throws Exception {
        var f = new Fixture(steps(damage())); var locations = f.host.participants();
        var catalogues = Map.of(A, f.program.program(), B, f.program.program(), C, f.program.program(), D, f.program.program());
        int[] checked = {0};
        f.beforeRoute = request -> {
            if (!(request.command() instanceof ActionRoutes.Begin)) return;
            var ordinary = f.host.readView(D, request); var state = f.sessions.get(D).state().engine();
            var combat = ordinary.combatParticipants(locations, catalogues);
            var old = (RuleEngine.Await<EffectState>) f.program.evaluateActionRoute(ordinary, state, request);
            var current = (RuleEngine.Await<EffectState>) f.program.evaluateActionRoute(ordinary, combat, state, request);
            var local = (DamageCommand) old.command(); var remote = (DamageCommand) current.command();
            assertEquals(0, local.amount()); assertTrue(local.reactions().orElseThrow().sources().isEmpty());
            assertEquals(10, remote.amount()); assertEquals(List.of(reactor("old")), remote.reactions().orElseThrow().sources());
            assertThrows(IllegalArgumentException.class, () -> f.program.evaluateActionRoute(ordinary, ordinary, state, request));
            assertThrows(IllegalArgumentException.class, () -> f.program.evaluateActionRoute(ordinary, combat.characterView(OWNER), state, request));
            var changed = combat.withLocalState(combat.state().withMode(EffectState.Mode.PVP));
            assertThrows(IllegalArgumentException.class, () -> f.program.evaluateActionRoute(ordinary, changed, state, request));
            checked[0]++;
        };
        f.start(); assertEquals(1, checked[0]);
    }

    @Test void deferredConditionalDamagePreparesTheCurrentRemoteSourceWhenItsBodyRuns() throws Exception {
        var f = new Fixture(steps(delayed(steps(damage())))); f.start(); assertTrue(f.damages.isEmpty());
        f.replaceReactor(); f.due(); assertEquals(1, f.damages.size()); f.assertPrepared(f.damages.getFirst(), "new");
    }

    @Test void capturedDamageKeepsHistoricalReactionsThroughDeferredConditionalReplay() throws Exception {
        var f = new Fixture(capture(false, false)); f.start(); var snapshot = f.snapshot();
        assertEquals(List.of(reactor("old")), snapshot.attack().reactions().orElseThrow().sources());
        assertEquals(1, snapshot.contributions().size());
        f.replaceReactor(); f.due(); var command = f.damages.getFirst(); f.assertPrepared(command, "old");
        assertEquals(snapshot, command.snapshot().orElseThrow()); snapshot.validate(command);
        assertEquals(snapshot.attack().reactions(), command.reactions());
    }

    @Test void emptyHistoricalSelectionCannotAcquireTheNewRemoteSourceAtImpact() throws Exception {
        var f = new Fixture(capture(false, false), false); f.start(); var snapshot = f.snapshot();
        assertTrue(snapshot.attack().reactions().orElseThrow().sources().isEmpty());
        f.replaceReactor(); f.due(); var command = f.damages.getFirst();
        assertTrue(command.reactions().orElseThrow().sources().isEmpty()); snapshot.validate(command);
        assertEquals(1, command.consumptions().size());
    }

    @Test void taggedCaptureAndEntityGuardedReplayPropagateDamagePreparation() throws Exception {
        var f = new Fixture(capture(true, true)); f.start(); var snapshot = f.snapshot();
        assertTrue(snapshot.attack().tags().contains("test:inherited"));
        f.replaceReactor(); f.due(); f.assertPrepared(f.damages.getFirst(), "old");
        assertTrue(f.routes.stream().filter(ActionRoutes.Ready.class::isInstance).map(ActionRoutes.Ready.class::cast)
                .anyMatch(value -> value.command() instanceof EntityAnchor.GuardedDamage));
    }

    @Test void ordinaryQueriesBranchesAndStatusCompletionKeepTheirLocalScope() throws Exception {
        var capture = object("{\"type\":\"chorus:capture_value\"}"); capture.add("value", count());
        var branch = object("{\"if\":{\"type\":\"chorus:has_buff\",\"buff\":\"test:next_hit\",\"target\":\"event_actor\"},"
                + "\"then\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:wrong\"}],\"else\":[{\"type\":\"chorus:play_cue\",\"cue\":\"test:local\"}]}");
        var status = object("{\"type\":\"chorus:apply_status\",\"buff\":\"test:marker\",\"target\":\"self\"}");
        var f = new Fixture(steps(bind(capture, "local_count"), branch, status, damage())); f.start();
        var value = f.resourceReplies.stream().map(ResourceRoutes.Receipt::value)
                .filter(Action.CapturedValue.class::isInstance).map(Action.CapturedValue.class::cast).findFirst().orElseThrow();
        assertEquals(0, value.value().value()); assertEquals(List.of("test:local"), f.cues);
        assertTrue(f.state(D).buffs().instances().keySet().stream().anyMatch(key -> key.holder().equals(DISPATCHER) && key.definition().equals("test:marker")));
        assertTrue(f.state(C).buffs().instances().keySet().stream().noneMatch(key -> key.definition().equals("test:marker")));
        f.assertPrepared(f.damages.getFirst(), "old");
    }
}
