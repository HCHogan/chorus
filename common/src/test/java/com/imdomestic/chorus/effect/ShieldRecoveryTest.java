package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ShieldRecoveryTest {
    static final String SHIELD = "test:recharging";
    record Fact(RuleEngine.Event event) implements RuleEngine.WorldCommand {}
    record Observe() implements Action {
        public ResultShape validate(Validation v) { return ResultShape.EMPTY; }
        public RuleEngine.Outcome<EffectState> execute(Evaluation e) { return new RuleEngine.Await<>(new Fact(e.context().event())); }
    }
    static CompiledEffects observing(CompiledEffects program) {
        var p = program.program(); var bundles = new ArrayList<>(p.bundles());
        bundles.add(new EffectProgram.Bundle("test:observer", EffectProgram.Scope.SOURCE, List.of("shield_restored", "buff_ended", "shield_damaged", "shield_broken").stream()
                .map(type -> new EffectProgram.Rule(type, "chorus:" + type, new Condition.Constant(true), List.<EffectProgram.Step>of(new EffectProgram.Instruction(new Observe(), "")))).toList(), List.of()));
        return new CompiledEffects(new EffectProgram(p.version(), p.buffs(), bundles, p.profiles(), p.defenseProfile(), p.resources()));
    }
    static final class Harness {
        final CompiledEffects program;
        final EffectSource source;
        final EffectSession session;
        final List<RuleEngine.Event> facts = new ArrayList<>();
        int damageId;
        Harness(CompiledEffects input, String bundle, EffectState.Mode mode) {
            program = observing(input); source = source(bundle);
            var initial = EffectState.empty().withSource(source).withSource(new EffectSource("observer", "test:observer", "player", source.origin(), Set.of())).withMode(mode);
            session = new EffectSession(engine(program), initial, request -> {
                if (request.command() instanceof Fact fact) { facts.add(fact.event()); return RuleEngine.Empty.INSTANCE; }
                var command = (DamageCommand) request.command(); var plan = program.shields(state(), command, command.amount());
                var receipt = new DamageReceipt("hit/" + ++damageId, DamageReceipt.Outcome.APPLIED,
                        plan.layers().stream().mapToDouble(h -> h.trace().capacityLoss()).sum(), 0, plan.budget().toVanilla(), Optional.empty(), false, Optional.empty(), plan.layers());
                return new RuleEngine.WorldReceipt(receipt, List.of(), plan.commit());
            });
        }
        Harness(CompiledEffects program) { this(program, "test:shield_recovery", EffectState.Mode.PVE); }
        EffectState state() { return session.state().engine().domain(); }
        void send(long time, String type, Map<String, Measure> numbers) {
            session.start(time, new RuleEngine.Signal(type, new EffectEvent("player", "target", source.origin(), Set.of(), numbers)));
            assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty(), session.state().engine().failure().toString());
        }
        void advance(long time) { send(time, "test:noop", Map.of()); }
        void grant(double duration) { send(0, "test:grant", Map.of("duration", new Measure(duration, Unit.SECOND))); }
        BuffInstance pool() { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(SHIELD)).findFirst().orElseThrow(); }
        double capacity() { return pool().components().numbers().get("capacity"); }
        List<EffectEvent> restored() { return facts.stream().filter(e -> e.signal().type().equals("chorus:shield_restored")).map(e -> (EffectEvent) e.signal().payload()).toList(); }
    }
    @Test void fractionalExpiryCommitsCapacityIntoEndedSnapshotAndPublishesIntervalsOnce() throws Exception {
        var h = new Harness(load("shield_recovery")); h.grant(.070001); h.advance(200_000);
        assertEquals(List.of(50_000L, 70_001L), h.facts.stream().filter(e -> e.signal().type().equals("chorus:shield_restored")).map(RuleEngine.Event::timeMicros).toList());
        assertEquals(.1750025, h.restored().stream().mapToDouble(e -> e.numbers().get("effective").value()).sum(), 1e-12);
        var ended = (Buffs.Change) h.facts.getLast().signal().payload(); assertEquals(.1750025, ended.instance().components().numbers().get("capacity"), 1e-12);
        var last = h.restored().getLast(); assertEquals(.05, last.numbers().get("interval_start").value()); assertEquals(.070001, last.numbers().get("interval_end").value());
        assertEquals(Unit.DAMAGE_PER_SECOND, last.numbers().get("rate").unit()); assertEquals(h.source.origin(), last.source());
        assertTrue(last.tags().contains("chorus:continuous_shield_recovery")); assertTrue(h.state().buffs().instances().isEmpty());
        h.advance(1_000_000); assertEquals(2, h.restored().size());
    }
    @Test void rateChangesAndPauseIntegrateOnlyTheirActiveIntervals() throws Exception {
        var h = new Harness(load("shield_recovery")); h.grant(1);
        h.send(25_000, "test:rate", Map.of("rate", new Measure(5, Unit.DAMAGE_PER_SECOND))); h.send(70_001, "test:remove", Map.of());
        assertEquals(.025 * 2.5 + .045001 * 5, h.restored().stream().mapToDouble(e -> e.numbers().get("effective").value()).sum(), 1e-12);
        var paused = new Harness(load("shield_recovery")); paused.grant(.070001);
        paused.send(25_000, "chorus:weapon_stowed", Map.of()); paused.advance(75_000); assertEquals(.0625, paused.capacity());
        paused.send(75_000, "chorus:weapon_drawn", Map.of()); paused.advance(200_000);
        assertEquals(.1750025, paused.restored().stream().mapToDouble(e -> e.numbers().get("effective").value()).sum(), 1e-12);
        assertEquals(120_001, paused.facts.getLast().timeMicros());
    }
    @Test void capacityIsAnExactMicrosecondBoundaryAndFullTimeCannotBeBanked() throws Exception {
        var data = json("shield_recovery"); numbers(data).getAsJsonObject("maximum").addProperty("initial", .123456);
        var h = new Harness(compile(data)); h.grant(10); h.advance(1_000_000);
        assertEquals(.123456, h.capacity()); assertEquals(List.of(49_383L), h.facts.stream().map(RuleEngine.Event::timeMicros).toList());
        assertTrue(h.restored().getFirst().numbers().get("overflow").value() > 0);
        var clock = new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())).withShieldRecovery(h.program::shieldRecoveryOffers);
        assertEquals(10_000_000, clock.nextDeadline(h.state()));
        h.send(1_000_000, "test:damage", Map.of("amount", new Measure(.1, Unit.DAMAGE))); h.advance(1_070_001); assertEquals(.023456, h.capacity(), 1e-12);
        h.advance(1_080_001); assertEquals(.048456, h.capacity(), 1e-12);
    }
    @Test void anotherHitRestartsOnlyThisLayersDelayAndFirstResidualIntervalIsPreserved() throws Exception {
        var h = new Harness(load("shield_recovery")); h.grant(1); h.advance(50_000);
        h.send(50_000, "test:damage", Map.of("amount", new Measure(.1, Unit.DAMAGE)));
        h.send(100_000, "test:damage", Map.of("amount", new Measure(.005, Unit.DAMAGE)));
        h.advance(170_001); assertEquals(.02, h.capacity(), 1e-12); assertEquals(1, h.pool().components().numbers().get("enabled"));
        h.advance(200_000); assertEquals(.02 + .029999 * 2.5, h.capacity(), 1e-12);
        assertEquals(.170001, h.restored().getLast().numbers().get("interval_start").value());
    }
    @Test void oldGenerationAndOtherHoldersFactsCannotInterruptReplacementLayer() throws Exception {
        var h = new Harness(load("shield_recovery")); h.grant(1); var old = h.pool();
        h.send(0, "test:remove", Map.of()); h.grant(1); var current = h.pool(); assertNotEquals(old.generation(), current.generation());
        for (var pair : List.of(Map.entry("target", old.generation()), Map.entry("other", current.generation()))) {
            var event = new EffectEvent("attacker", pair.getKey(), h.source.origin(), Set.of(), Map.of(), Map.of(),
                    Map.of("shield_definition", SHIELD, "shield_generation", Long.toString(pair.getValue())));
            h.session.start(0, new RuleEngine.Signal("chorus:shield_damaged", event));
            assertEquals(1, h.pool().components().numbers().get("enabled")); assertTrue(h.state().timers().isEmpty());
        }
        var event = new EffectEvent("attacker", "target", h.source.origin(), Set.of(), Map.of(), Map.of(),
                Map.of("shield_definition", SHIELD, "shield_generation", Long.toString(current.generation())));
        var context = new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0, new RuleEngine.Signal("chorus:shield_damaged", event)), "test", new BuffRules.Scope(current, false), Map.of());
        var evaluation = new Evaluation(h.state(), context, Map.of(SHIELD, current.definition()), Map.of(), Map.of(), Map.of(), Optional.of(h.program));
        assertEquals(new Condition.Constant(true), new Condition.OwnShield().snapshot(evaluation));
    }
    @Test void recoveryCommitsBeforeHealthWorldWaitAndReplayedReceiptCannotUndoNestedDamage() throws Exception {
        var data = json("shield_recovery"); data.getAsJsonArray("bundles").get(0).getAsJsonObject().add("health_recovery", JsonParser.parseString("""
          [{"id":"health","channel":"test:health","rate":{"type":"chorus:constant","value":2,"unit":"damage_per_second"}}]
          """));
        var program = compile(data); var source = source("test:shield_recovery"); var engine = engine(program);
        var armed = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:grant",
                new EffectEvent("player", "target", source.origin(), Set.of(), Map.of("duration", new Measure(1, Unit.SECOND))));
        var waiting = send(engine, armed.state(), 50_000, "test:noop", event(source)); var domain = waiting.state().engine().domain();
        assertEquals(.125, domain.buffs().instances().values().iterator().next().components().numbers().get("capacity"));
        var command = new DamageCommand("target", source.origin(), .1, "minecraft:generic", Set.of(), Set.of(), false);
        var plan = program.shields(domain, command, .1); var heal = (HealingCommand) waiting.actions().getFirst().command();
        var receipt = new RuleEngine.WorldReceipt(new HealingReceipt("health", heal, HealingReceipt.Outcome.APPLIED, .1, .1, 0), List.of(), plan.commit());
        var done = complete(engine, waiting, receipt);
        assertEquals(.025, done.state().engine().domain().buffs().instances().values().iterator().next().components().numbers().get("capacity"), 1e-12);
        assertEquals(done.state(), engine.transition(done.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), receipt)).state());
    }
    @Test void independentLayersRetainTheirOriginsAndRejectStaleOrDuplicateAllocations() throws Exception {
        var h = new Harness(load("shield_recovery")); h.grant(1); var original = h.pool();
        var second = Buffs.grant(h.state().buffs(), original.definition(), "another", "another", new BuffInstance.Origin("another", "source-b", "weapon-b", ""), 1, 1, 1_000_000);
        var state = h.state().withBuffs(second.store()); var offers = h.program.shieldRecoveryOffers(state);
        var batch = ShieldRecovery.integrate(state.buffs(), offers, 25_000); assertEquals(2, batch.allocations().size());
        assertEquals(Set.of("target", "another"), batch.facts().stream().map(f -> ((EffectEvent) f.payload()).victim()).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalArgumentException.class, () -> ShieldRecovery.integrate(state.buffs(), List.of(offers.getFirst(), offers.getFirst()), 1));
        assertThrows(IllegalArgumentException.class, () -> ShieldRecovery.integrate(batch.store(), offers, 50_000));
        assertThrows(IllegalArgumentException.class, () -> new ShieldRecovery.Offer(original, "capacity", 10, -1));
    }
    @Test void negativeDynamicRatesFailWhileZeroDisabledAndLoweredLimitsDoNotSample() throws Exception {
        var h = new Harness(load("shield_recovery")); h.grant(1); h.advance(50_000); var initial = h.pool();
        var clock = new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())).withShieldRecovery(h.program::shieldRecoveryOffers);
        for (var change : List.of(Map.entry("rate", 0.0), Map.entry("enabled", 0.0), Map.entry("maximum", 0.0))) {
            var state = h.state().withBuffs(Buffs.components(h.state().buffs(), initial.key(), initial.components().number(change.getKey(), BuffComponents.Update.SET, change.getValue())));
            assertEquals(1_000_000, clock.nextDeadline(state)); assertEquals(.125, ShieldRecovery.integrate(state.buffs(), h.program.shieldRecoveryOffers(state), 100_000).store().active(initial.key()).orElseThrow().components().numbers().get("capacity"));
        }
        var bad = h.state().withBuffs(Buffs.components(h.state().buffs(), initial.key(), initial.components().number("rate", BuffComponents.Update.SET, -1)));
        assertThrows(IllegalArgumentException.class, () -> h.program.shieldRecoveryOffers(bad));
    }
    @Test void recoverySchemaRoundTripsAndRejectsWrongUnitsUnknownFieldsAndSourceScopedOwnShield() throws Exception {
        var program = load("shield_recovery"); assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow().program());
        for (String value : List.of("{\"type\":\"chorus:constant\",\"value\":-1,\"unit\":\"damage_per_second\"}", "{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"damage\"}")) {
            var data = json("shield_recovery"); recovery(data).add("rate", JsonParser.parseString(value)); assertThrows(RuntimeException.class, () -> compile(data));
        }
        var typo = json("shield_recovery"); recovery(typo).addProperty("interval", 1); assertThrows(RuntimeException.class, () -> compile(typo));
        var badScope = json("shield_recovery"); badScope.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().add("if", JsonParser.parseString("{\"type\":\"chorus:own_shield\"}"));
        assertThrows(RuntimeException.class, () -> compile(badScope));
    }
    private static JsonObject numbers(JsonObject data) { return data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").getAsJsonObject("components").getAsJsonObject("numbers"); }
    private static JsonObject recovery(JsonObject data) { return data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("shield").getAsJsonObject("recovery"); }
}
