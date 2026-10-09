package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceProgramTest {
    private static final ResourceState.Key ENERGY = new ResourceState.Key("player", "test:energy");
    private record Heal(long time, HealingCommand command) {}
    private static final class Harness {
        final EffectSource source = source("test:resources");
        final List<Heal> heals = new ArrayList<>();
        final EffectSession session;
        Harness(CompiledEffects program, EffectState.Mode mode) {
            session = new EffectSession(engine(program), EffectState.empty().withMode(mode), request -> {
                var command = (HealingCommand) request.command(); heals.add(new Heal(time(), command));
                return new HealingReceipt("resource/" + heals.size(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
            });
            session.start(0, SourceChange.bind(source));
        }
        long time() { return session.state().engine().timeMicros(); }
        EffectState state() { return session.state().engine().domain(); }
        double value() { return state().resources().get(ENERGY).value(); }
        void send(long at, String event, double amount) { session.start(at, new RuleEngine.Signal(event, new EffectEvent("player", "player", source.origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.CHARGE))))); }
    }
    private static JsonObject resource(JsonObject data) { return data.getAsJsonArray("resources").get(0).getAsJsonObject(); }
    @Test void initializationIsIdempotentAndProfileSplitsAtBoostAndExpiryBeforeChargeThresholds() throws Exception {
        var program = load("resource_regeneration"); var test = new Harness(program, EffectState.Mode.PVE);
        assertEquals(0, test.value()); test.send(100_000, "test:boost", 0); assertEquals(.05, test.value());
        var trace = program.resourceCalculation(test.state(), test.state().resources().get(ENERGY)).orElseThrow(); assertEquals(1, trace.output().value());
        assertEquals(Set.of(com.imdomestic.chorus.stat.NumericContribution.Confidence.ASSUMED), trace.contributionConfidence());
        test.send(600_000, "test:initialize", 0); assertEquals(.55, test.value()); assertTrue(test.state().buffs().instances().isEmpty());
        test.send(4_000_000, "test:noop", 0); assertEquals(2, test.value());
        assertEquals(List.of(1_500_000L, 3_500_000L), test.heals.stream().map(Heal::time).toList());
        assertEquals(List.of(1.0, 2.0), test.heals.stream().map(value -> value.command().amount()).toList());
        test.send(10_000_000, "test:initialize", 0); assertEquals(2, test.heals.size()); assertEquals(2, test.value());
    }
    @Test void pvpRateProfileAndChunkCrossingAreSeparateFromTotalCapacity() throws Exception {
        var test = new Harness(load("resource_regeneration"), EffectState.Mode.PVP);
        test.send(0, "test:boost", 0); test.send(500_000, "test:noop", 0); assertEquals(.375, test.value());
        test.send(500_000, "test:grant", .1); assertEquals(.475, test.value());
        test.send(500_000, "test:grant", .525); assertEquals(1, test.value()); assertEquals(1, test.heals.size());
        assertTrue(test.heals.getFirst().command().tags().contains("test:one")); assertEquals(500_000, test.heals.getFirst().time());
    }
    @Test void spendBranchesOnActualSuccessAndFullCapacityDoesNotBankRegeneration() throws Exception {
        var test = new Harness(load("resource_regeneration"), EffectState.Mode.PVE);
        test.send(0, "test:spend", 0); assertTrue(test.heals.isEmpty()); assertEquals(0, test.value());
        test.send(10_000_000, "test:noop", 0); assertEquals(2, test.value()); assertEquals(2, test.heals.size());
        test.send(10_000_000, "test:spend", 0); assertEquals(1, test.value());
        test.send(10_000_000, "test:spend", 0); assertEquals(0, test.value());
        test.send(10_000_000, "test:spend", 0); assertEquals(4, test.heals.size());
        test.send(10_100_000, "test:noop", 0); assertEquals(.05, test.value());
        assertEquals(2, test.heals.stream().filter(value -> value.command().tags().contains("test:cast")).count());
    }
    @Test void drainCrossesDownwardAndDistinctHoldersDoNotReactToEachOthersAccounts() throws Exception {
        var data = json("resource_regeneration"); resource(data).addProperty("initial", 2); resource(data).addProperty("base_rate", -.5);
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(5).getAsJsonObject().getAsJsonObject("if").addProperty("direction", "down");
        var test = new Harness(compile(data), EffectState.Mode.PVE);
        var other = new EffectSource("other", "test:resources", "other", new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("other", "other", "", ""), Set.of());
        test.session.start(0, SourceChange.bind(other)); test.send(5_000_000, "test:noop", 0);
        assertEquals(0, test.value()); assertEquals(2, test.heals.size());
        assertEquals(Set.of("player", "other"), test.heals.stream().map(value -> value.command().target()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(2_000_000L, 2_000_000L), test.heals.stream().map(Heal::time).toList());
    }
    @Test void decimalGrantAndDeadlineReachTheDeclaredBoundaryWithoutOneMicrosecondDelay() {
        var before = new ResourceState(ENERGY, .7, 1, 0);
        var grant = Resources.grant(before, .1, .1); assertEquals(.8, grant.after().value());
        assertEquals(.1, ResourceFacts.granted(grant, source("test:resources").origin()).numbers().get("delta").value());
        assertEquals(100_000, Resources.microsToThreshold(before, .8, 1));
        assertEquals(.8, Resources.integrate(before, 100_000, 1, List.of()).value());
        assertEquals(.7, Resources.spend(new ResourceState(ENERGY, .8, 1, 0), "cast", .1).after().value());
        assertEquals(333_334, Resources.microsToThreshold(new ResourceState(ENERGY, 0, 1, 0), 1, 3));
        assertEquals(Long.MAX_VALUE, Resources.microsToThreshold(before, .8, 0));
        assertEquals(Long.MAX_VALUE, Resources.microsToThreshold(before, .6, 1));
    }
    @Test void declarationsRejectMissingReferencesWrongUnitsCapacityAndUndeclaredThresholds() throws Exception {
        var missing = json("resource_regeneration"); missing.remove("resources"); assertThrows(IllegalStateException.class, () -> compile(missing));
        var profile = json("resource_regeneration"); resource(profile).addProperty("rate_profile", "test:missing"); assertThrows(IllegalStateException.class, () -> compile(profile));
        var units = json("resource_regeneration"); units.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("input_unit", "damage"); assertThrows(IllegalStateException.class, () -> compile(units));
        var threshold = json("resource_regeneration"); resource(threshold).remove("thresholds"); assertThrows(IllegalStateException.class, () -> compile(threshold));
        var bounds = json("resource_regeneration"); resource(bounds).addProperty("initial", 3); assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, bounds).error().isPresent());
        var program = load("resource_regeneration");
        assertThrows(IllegalArgumentException.class, () -> engine(program).initial(EffectState.empty().withResource(new ResourceState(ENERGY, 0, 1, 0))));
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
    @Test void paymentIdentityIsUniqueWithinEachRuleAndPaidAmountSurvivesWorldWait() throws Exception {
        var source = source("test:resources"); var engine = engine(load("resource_regeneration"));
        var initial = engine.initial(EffectState.empty().withSource(source).withResource(new ResourceState(ENERGY, 1, 2, 0)));
        var waiting = send(engine, initial, 0, "test:spend", event(source));
        var paid = (Resources.SpendResult) waiting.state().engine().frames().getFirst().bindings().get("cost");
        assertEquals(1, paid.receipt().paid()); assertTrue(paid.succeeded()); assertEquals(0, waiting.state().engine().domain().resources().get(ENERGY).value());
        var command = (HealingCommand) waiting.actions().getFirst().command();
        var done = complete(engine, waiting, new HealingReceipt("cast", command, HealingReceipt.Outcome.APPLIED, 1, 1, 0)); assertTrue(done.state().idle());
        var data = json("resource_regeneration"); var steps = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(4).getAsJsonObject().getAsJsonArray("do");
        var duplicate = steps.get(0).getAsJsonObject().getAsJsonObject("action").deepCopy(); steps.add(duplicate);
        assertThrows(IllegalStateException.class, () -> compile(data));
    }
}
