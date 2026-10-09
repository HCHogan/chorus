package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContinuationTest {
    private static final EffectSource SOURCE = source("test:launcher");
    private static EffectState initial(CompiledEffects program) {
        var state = EffectState.empty().withSource(SOURCE).withSource(new EffectSource("controls", "test:controls", "player", SOURCE.origin(), Set.of()));
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:empower"), "player", "player", SOURCE.origin(), 1, 1, 50_000).store());
    }
    private static final class Harness {
        final CompiledEffects program;
        final EffectSession session;
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        final List<Double> losses = new ArrayList<>();
        List<String> targets = List.of("a", "b");
        String missing = "";
        Harness() throws Exception { this(load("delayed_snapshot")); }
        Harness(CompiledEffects program) {
            this.program = program; session = new EffectSession(engine(program), initial(program), request -> {
                requests.add(request); times.add(state().buffs().timeMicros());
                return switch (request.command()) {
                    case DamageCommand damage -> {
                        double loss = damage.target().equals(missing) ? 0 : program.outgoing(state(), damage, damage.amount()).orElseThrow().output().value();
                        if (loss > 0) loss = program.defense(state(), damage, loss).orElseThrow().output().value();
                        losses.add(loss);
                        yield new DamageReceipt(request.id().toString(), loss == 0 ? DamageReceipt.Outcome.FAILED : DamageReceipt.Outcome.APPLIED, 0, 0, loss, Optional.empty(), false);
                    }
                    case HealingCommand heal -> new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
                    case TargetQuery query -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE, targets.stream().map(id -> new TargetQuery.Target(id, 0)).toList());
                    case Action.CueCommand ignored -> RuleEngine.Empty.INSTANCE;
                    default -> throw new AssertionError(request.command());
                };
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void fire(String event, String victim) { session.start(state().buffs().timeMicros(), new RuleEngine.Signal("test:" + event, new EffectEvent("player", victim, SOURCE.origin(), Set.of(), Map.of()))); }
        void advance(long time) { session.observe(time, List.of()); assertTrue(session.state().idle()); }
        void detach() { session.start(state().buffs().timeMicros(), SourceChange.remove(SOURCE.instance())); }
    }
    @Test void detachedBodyRunsAtExactTimeAfterSourceRemovalAndBuffExpiryWithRealResultDependencies() throws Exception {
        var h = new Harness(); h.fire("fire", "target");
        assertEquals(1, h.state().timers().size()); assertInstanceOf(Action.CueCommand.class, h.requests.getFirst().command());
        h.detach(); h.fire("weaken", "target"); h.advance(99_999); assertTrue(h.losses.isEmpty());
        h.advance(100_000); assertEquals(List.of(18.0), h.losses); assertTrue(h.state().timers().isEmpty());
        assertEquals(List.of(0L, 100_000L, 100_000L), h.times);
        var damage = (DamageCommand) h.requests.get(1).command(); var heal = (HealingCommand) h.requests.get(2).command();
        assertEquals(SOURCE.origin(), damage.source()); assertTrue(damage.snapshot().isPresent()); assertEquals(Set.of("test:ability_kill"), damage.killTags());
        assertEquals(9, heal.amount()); assertEquals("player", heal.target());
    }
    @Test void defaultSourceLifetimeCancelsOnRemovalButIndependentCastsDoNotReplaceEachOther() throws Exception {
        var h = new Harness(); h.fire("attached", "discarded"); h.detach(); h.advance(100_000); assertTrue(h.requests.isEmpty());
        var independent = new Harness(); independent.fire("fire", "first"); independent.fire("fire", "second");
        assertEquals(2, independent.state().timers().size()); independent.detach(); independent.advance(100_000);
        assertEquals(List.of("first", "second"), independent.requests.stream().map(RuleEngine.WorldRequest::command).filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).map(DamageCommand::target).toList());
        assertEquals(2, independent.requests.stream().filter(r -> r.command() instanceof DamageCommand).map(RuleEngine.WorldRequest::id).distinct().count());
    }
    @Test void eachLoopIterationCapturesItsOwnTargetAndLocalsWhileLateQueriesSeeNewMembership() throws Exception {
        var h = new Harness(); h.fire("split", "center"); assertEquals(2, h.state().timers().size());
        h.targets = List.of("c"); h.missing = "a"; h.detach(); h.advance(100_000);
        assertEquals(List.of("a", "b"), h.requests.stream().map(RuleEngine.WorldRequest::command).filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).map(DamageCommand::target).toList());
        assertEquals(List.of(0.0, 12.0), h.losses); assertEquals(1, h.requests.stream().filter(r -> r.command() instanceof HealingCommand).count());
        var late = new Harness(); late.fire("area", "center"); assertTrue(late.requests.isEmpty()); late.targets = List.of("c"); late.detach(); late.advance(100_000);
        assertEquals("c", ((DamageCommand) late.requests.get(1).command()).target());
    }
    @Test void nestedAfterRetainsSnapshotAndContextButSchedulesRelativeToItsOwnExecution() throws Exception {
        var h = new Harness(); h.fire("burst", "target"); h.detach(); h.advance(149_999);
        assertEquals(List.of(12.0), h.losses); assertEquals(150_000, h.state().timers().values().iterator().next().dueAt());
        h.advance(150_000); assertEquals(List.of(12.0, 12.0), h.losses); assertEquals(List.of(100_000L, 150_000L), h.times);
        assertEquals(((DamageCommand) h.requests.get(0).command()).snapshot(), ((DamageCommand) h.requests.get(1).command()).snapshot());
    }
    @Test void buffOwnedContinuationCancelsAtExpiryWhileDetachedOneRetainsTheCapturedAttack() throws Exception {
        var p = load("delayed_snapshot"); var state = initial(p);
        state = state.withBuffs(Buffs.grant(state.buffs(), p.buff("test:emitter"), "player", "player", SOURCE.origin(), 1, 1, 100_000).store());
        var requests = new ArrayList<RuleEngine.WorldRequest>();
        var session = new EffectSession(engine(p), state, request -> { requests.add(request); return new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false); });
        session.start(0, new RuleEngine.Signal("test:buff_attached", event(SOURCE))); session.start(0, new RuleEngine.Signal("test:buff_detached", event(SOURCE)));
        assertEquals(2, session.state().engine().domain().timers().size()); session.observe(100_000, List.of());
        assertEquals(1, requests.size()); assertEquals(SOURCE.origin(), ((DamageCommand) requests.getFirst().command()).source());
    }
    @Test void oldWorldReceiptCannotReplayOrAdvanceADifferentDelayedFrame() throws Exception {
        var p = load("delayed_snapshot"); var engine = engine(p);
        var scheduled = send(engine, engine.initial(initial(p)), 0, "test:burst", event(SOURCE));
        var first = pump(engine, engine.transition(scheduled.state(), new RuleEngine.Start(100_000, List.of())));
        var receipt = new DamageReceipt("first", DamageReceipt.Outcome.APPLIED, 0, 0, 2, Optional.empty(), false);
        var done = complete(engine, first, receipt); assertTrue(done.state().idle());
        var second = pump(engine, engine.transition(done.state(), new RuleEngine.Start(150_000, List.of())));
        assertNotEquals(first.actions().getFirst().id(), second.actions().getFirst().id());
        // The old boundary has retired. It cannot be accepted as the second pending operation.
        assertThrows(IllegalArgumentException.class, () -> engine.transition(second.state(), new RuleEngine.Completed(first.actions().getFirst().id(), receipt)));
    }
    @Test void sourceBuffPauseFreezesTheRemainingDelayAndDetachedDelayContinues() throws Exception {
        var p = load("delayed_snapshot"); var state = initial(p);
        state = state.withBuffs(Buffs.grant(state.buffs(), p.buff("test:emitter"), "player", "player", SOURCE.origin(), 1, 1, 500_000).store());
        var times = new ArrayList<Long>();
        var engine = engine(p); var holder = new EffectSession[1];
        holder[0] = new EffectSession(engine, state, request -> {
            times.add(holder[0].state().engine().timeMicros());
            return new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false);
        });
        var session = holder[0]; session.start(0, new RuleEngine.Signal("test:buff_attached", event(SOURCE)));
        session.start(0, new RuleEngine.Signal("test:buff_detached", event(SOURCE)));
        session.start(50_000, new RuleEngine.Signal("chorus:weapon_stowed", event(SOURCE)));
        session.observe(200_000, List.of()); assertEquals(List.of(100_000L), times);
        var paused = session.state().engine().domain().timers().values().iterator().next(); assertEquals(50_000, paused.pausedRemaining().orElseThrow());
        session.start(200_000, new RuleEngine.Signal("chorus:weapon_drawn", event(SOURCE)));
        session.observe(249_999, List.of()); assertEquals(1, times.size()); session.observe(250_000, List.of());
        assertEquals(List.of(100_000L, 250_000L), times);
    }
    @Test void manySameTimeCastsKeepSchedulingOrderBeyondSingleDigitFrameNumbers() throws Exception {
        var h = new Harness(); var expected = new ArrayList<String>();
        for (int i = 0; i < 14; i++) { expected.add("target-" + i); h.fire("attached", "target-" + i); }
        h.advance(100_000);
        assertEquals(expected, h.requests.stream().map(RuleEngine.WorldRequest::command).filter(DamageCommand.class::isInstance).map(DamageCommand.class::cast).map(DamageCommand::target).toList());
    }
    @Test void explicitCapturedValueRetainsItsUnitAndBuffOperandAfterTheBuffExpires() throws Exception {
        var data = json("delayed_snapshot"); var rules = data.getAsJsonArray("bundles").get(4).getAsJsonObject().getAsJsonArray("rules");
        rules.add(JsonParser.parseString("""
                {"id":"pulse","on":"test:pulse","do":[
                  {"action":{"type":"chorus:capture_value","value":{"type":"chorus:by_buff_tier","values":[3,7],"unit":"damage"}},"as":"saved"},
                  {"after":{"type":"chorus:constant","value":0.1,"unit":"second"},"lifetime":"detached","do":[
                    {"type":"chorus:heal","amount":{"type":"chorus:result","binding":"saved","field":"value"}}
                  ]}
                ]}
                """));
        var p = compile(data); var state = initial(p);
        state = state.withBuffs(Buffs.grant(state.buffs(), p.buff("test:emitter"), "player", "player", SOURCE.origin(), 1, 2, 50_000).store());
        var commands = new ArrayList<HealingCommand>();
        var session = new EffectSession(engine(p), state, request -> {
            var heal = (HealingCommand) request.command(); commands.add(heal);
            return new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
        });
        session.start(0, new RuleEngine.Signal("test:pulse", RuleEngine.Empty.INSTANCE)); session.observe(100_000, List.of());
        assertEquals(7, commands.getFirst().amount()); assertTrue(session.state().engine().domain().buffs().instances().isEmpty());
        var wrong = data.deepCopy(); wrong.getAsJsonArray("bundles").get(4).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject()
                .getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("value").addProperty("unit", "count");
        assertThrows(RuntimeException.class, () -> compile(wrong));
    }
    @Test void decodedProgramsRoundTripAndDelayUnitsLifetimeAndSnapshotTypesAreValidated() throws Exception {
        var p = load("delayed_snapshot");
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow().program());
        for (String invalid : List.of(
                "{\"after\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"second\"},\"do\":[]}",
                "{\"after\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"damage\"},\"do\":[]}",
                "{\"after\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"},\"lifetime\":\"guess\",\"do\":[]}",
                "{\"type\":\"chorus:damage_snapshot\",\"snapshot\":\"future\"}",
                "{\"type\":\"chorus:heal\",\"target\":{\"binding\":\"shot\"},\"amount\":{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"damage\"}}")) {
            var data = json("delayed_snapshot"); actions(data).set(1, JsonParser.parseString(invalid)); assertThrows(RuntimeException.class, () -> compile(data), invalid);
        }
    }
    private static JsonArray actions(JsonObject data) { return data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do"); }
    @Test void delayedBindingsCannotEscapeOrShadowAndPaidCostsCannotBeCopiedIntoAnotherFrame() throws Exception {
        var data = json("delayed_snapshot"); actions(data).add(JsonParser.parseString("""
                {"type":"chorus:heal","amount":{"type":"chorus:result","binding":"hit","field":"effective"}}
                """)); assertThrows(RuntimeException.class, () -> compile(data));
        var shadow = json("delayed_snapshot"); actions(shadow).get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().addProperty("as", "shot");
        assertThrows(RuntimeException.class, () -> compile(shadow));
        var cost = json("delayed_snapshot"); cost.add("resources", JsonParser.parseString("[{\"id\":\"test:energy\",\"capacity\":1,\"initial\":1}]"));
        actions(cost).set(0, JsonParser.parseString("""
                {"action":{"type":"chorus:spend_resource","resource":"test:energy","payment":"cast","amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"}},"as":"cost"}
                """));
        var body = actions(cost).get(1).getAsJsonObject().getAsJsonArray("do"); body.asList().clear(); body.add(JsonParser.parseString("""
                {"type":"chorus:refund_cost","cost":"cost","fraction":{"type":"chorus:constant","value":1,"unit":"multiplier"}}
                """)); assertThrows(RuntimeException.class, () -> compile(cost));
    }
}
