package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.TimelineEngine;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EffectProgramTest {
    private static final String CLIP = "chorus_d2:kill_clip";
    private static final ResourceState.Key ENERGY = new ResourceState.Key("player", "test:energy");
    private static JsonObject json(String fixture) throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(EffectProgramTest.class.getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static CompiledEffects load(String fixture) throws Exception { return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, json(fixture)).getOrThrow(); }
    private static EffectSource source(String id, String bundle, String weapon, boolean enhanced) {
        return new EffectSource(id, bundle, "player", new BuffInstance.Origin("player", id, weapon, ""), enhanced ? Set.of("chorus:enhanced") : Set.of());
    }
    private static EffectEvent event(EffectSource source, Set<String> tags, Map<String, Measure> numbers) {
        return new EffectEvent("player", "target", source.origin(), tags, numbers);
    }
    private static TimelineEngine<EffectState> engine(CompiledEffects compiled) {
        return compiled.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 2);
    }
    private static TimelineEngine.Transition<EffectState> pump(TimelineEngine<EffectState> engine, TimelineEngine.Transition<EffectState> result) {
        for (int i = 0; i < 2000 && result.needsPump(); i++) result = engine.transition(result.state(), Pump.INSTANCE);
        assertFalse(result.needsPump()); assertTrue(result.state().engine().failure().isEmpty(), result.state().engine().failure().toString()); return result;
    }
    private static TimelineEngine.Transition<EffectState> send(TimelineEngine<EffectState> engine, TimelineEngine.State<EffectState> state,
            long time, String type, EffectSource source, Set<String> tags, Map<String, Measure> numbers) {
        return pump(engine, engine.transition(state, new Start(time, new Signal(type, event(source, tags, numbers)))));
    }
    private static TimelineEngine.Transition<EffectState> send(TimelineEngine<EffectState> engine, TimelineEngine.State<EffectState> state,
            long time, String type, EffectSource source, String... tags) { return send(engine, state, time, type, source, Set.of(tags), Map.of()); }
    private static double weaponDamage(CompiledEffects compiled, TimelineEngine.State<EffectState> state, EffectSource source, String... tags) {
        return compiled.calculate(state.engine().domain(), "player", event(source, Set.of(tags), Map.of()), "test:weapon_damage", new Measure(100, Unit.DAMAGE), List.of()).output().value();
    }

    @Test void killClipJsonHandlesWeaponIsolationRefreshStowAndDamageEligibility() throws Exception {
        var compiled = load("kill_clip"); var engine = engine(compiled);
        var a = source("perk-a", CLIP, "weapon-a", false); var b = source("perk-b", CLIP, "weapon-b", false);
        var initial = engine.initial(EffectState.empty().withSource(a).withSource(b));
        var killed = send(engine, initial, 0, "chorus:kill", a, "chorus:weapon_kill");
        var wrongReload = send(engine, killed.state(), 1_000_000, "chorus:reload_finished", b);
        assertEquals(100, weaponDamage(compiled, wrongReload.state(), a));
        assertEquals(100, weaponDamage(compiled, wrongReload.state(), b));
        var active = send(engine, wrongReload.state(), 1_500_000, "chorus:reload_finished", a);
        assertEquals(125, weaponDamage(compiled, active.state(), a));
        assertEquals(100, weaponDamage(compiled, active.state(), b));
        assertEquals(100, weaponDamage(compiled, active.state(), a, "chorus:explosive_perk_damage"));
        var killAgain = send(engine, active.state(), 2_000_000, "chorus:kill", a, "chorus:weapon_kill");
        var refreshed = send(engine, killAgain.state(), 2_500_000, "chorus:reload_finished", a);
        assertEquals(7_500_000, refreshed.state().engine().domain().buffs().nextDeadline());
        var stowed = send(engine, refreshed.state(), 3_000_000, "chorus:weapon_stowed", a);
        assertEquals(100, weaponDamage(compiled, stowed.state(), a));
        assertTrue(stowed.state().engine().domain().buffs().instances().isEmpty());
        assertEquals(2, stowed.state().engine().domain().sources().size());
    }

    @Test void killWindowUsesHalfOpenDeadlineAndEnhancedDurationIsDataDriven() throws Exception {
        var compiled = load("kill_clip"); var engine = engine(compiled);
        var a = source("perk", CLIP, "weapon", true);
        var initial = engine.initial(EffectState.empty().withSource(a));
        var killed = send(engine, initial, 0, "chorus:kill", a, "chorus:weapon_kill");
        var late = send(engine, killed.state(), 3_600_000, "chorus:reload_finished", a);
        assertEquals(100, weaponDamage(compiled, late.state(), a));
        var justInTime = send(engine, killed.state(), 3_599_999, "chorus:reload_finished", a);
        assertEquals(125, weaponDamage(compiled, justInTime.state(), a));
        assertEquals(9_099_999, justInTime.state().engine().domain().buffs().nextDeadline());
        assertTrue(justInTime.state().engine().domain().buffs().instances().values().iterator().next().origin().tags().contains("chorus:enhanced"));
        var expired = send(engine, justInTime.state(), 9_099_999, "test:idle", a);
        assertEquals(100, weaponDamage(compiled, expired.state(), a));
        var uncredited = send(engine, initial, 0, "chorus:kill", a);
        assertTrue(uncredited.state().engine().domain().buffs().instances().isEmpty());
    }

    @Test void independentVulnerabilityStacksMultiplyAndExpireSeparately() throws Exception {
        var compiled = load("disruption_break"); var engine = engine(compiled);
        var a = source("a", "chorus_d2:disruption_break", "weapon-a", false);
        var b = source("b", "chorus_d2:disruption_break", "weapon-b", false);
        var initial = engine.initial(EffectState.empty().withSource(a).withSource(b));
        var first = send(engine, initial, 0, "chorus:shield_broken", a, "chorus:elemental_shield", "chorus:player");
        assertEquals(5_500_000, first.state().engine().domain().buffs().nextDeadline()); // Target tag does not select PvP mode.
        var second = send(engine, first.state(), 1_000_000, "chorus:shield_broken", b, "chorus:barrier_shield");
        var result = compiled.calculate(second.state().engine().domain(), "target", event(a, Set.of("chorus:kinetic_damage"), Map.of()),
                "test:incoming_damage", new Measure(100, Unit.DAMAGE), List.of());
        assertEquals(225, result.output().value());
        assertEquals(2, result.trace().contributions().size());
        assertEquals(Set.of("a", "b"), result.trace().contributions().stream().map(c -> c.contribution().source().instance()).collect(java.util.stream.Collectors.toSet()));
        var excluded = compiled.calculate(second.state().engine().domain(), "target", event(a, Set.of("chorus:kinetic_damage", "chorus:strand_damage"), Map.of()),
                "test:incoming_damage", new Measure(100, Unit.DAMAGE), List.of());
        assertEquals(100, excluded.output().value());
        var oneLeft = send(engine, second.state(), 5_500_000, "test:idle", a);
        assertEquals(150, compiled.calculate(oneLeft.state().engine().domain(), "target", event(a, Set.of("chorus:kinetic_damage"), Map.of()),
                "test:incoming_damage", new Measure(100, Unit.DAMAGE), List.of()).output().value());
        assertTrue(send(engine, oneLeft.state(), 6_500_000, "test:idle", a).state().engine().domain().buffs().instances().isEmpty());
        var pvpInitial = engine.initial(EffectState.empty().withSource(a).withMode(EffectState.Mode.PVP));
        assertEquals(5_000_000, send(engine, pvpInitial, 0, "chorus:shield_broken", a, "chorus:guardian_shield").state().engine().domain().buffs().nextDeadline());
    }

    @Test void resultBindingsSurviveWorldWaitAndUseCreditedInsteadOfStoredDelta() throws Exception {
        var compiled = load("credited_resource"); var engine = engine(compiled);
        var source = source("perk", "test:credited_resource", "weapon", false);
        var initial = engine.initial(EffectState.empty().withSource(source).withResource(new ResourceState(ENERGY, 0, 2, 0)));
        var nine = send(engine, initial, 0, "test:gain", source, Set.of(), Map.of("stacks", new Measure(9, Unit.COUNT)));
        assertInstanceOf(com.imdomestic.chorus.effect.data.Action.CueCommand.class, nine.actions().getFirst().command());
        assertEquals(0, nine.state().engine().domain().resources().get(ENERGY).value());
        var afterNine = pump(engine, engine.transition(nine.state(), new Completed(nine.actions().getFirst().id(), Empty.INSTANCE)));
        assertEquals(.9, afterNine.state().engine().domain().resources().get(ENERGY).value(), 1e-12);
        var four = send(engine, afterNine.state(), 1_000_000, "test:gain", source, Set.of(), Map.of("stacks", new Measure(4, Unit.COUNT)));
        assertEquals(10, four.state().engine().domain().buffs().instances().values().iterator().next().count());
        var receipt = (Buffs.Receipt) four.state().engine().frames().getFirst().bindings().get("gain");
        assertEquals(4, receipt.credited()); assertEquals(1, receipt.storedDelta());
        var afterFour = pump(engine, engine.transition(four.state(), new Completed(four.actions().getFirst().id(), Empty.INSTANCE)));
        assertEquals(1.3, afterFour.state().engine().domain().resources().get(ENERGY).value(), 1e-12);
        assertEquals(afterFour.state(), engine.transition(afterFour.state(), new Completed(four.actions().getFirst().id(), Empty.INSTANCE)).state());
    }

    @Test void compiledExpiryRuleReadsFinalStacksAndCompletesBeforeRequestedTime() throws Exception {
        var compiled = load("expiration_reaction"); var engine = engine(compiled);
        var source = source("perk", "test:start", "weapon", false);
        var initial = engine.initial(EffectState.empty().withSource(source).withResource(new ResourceState(ENERGY, 0, 1, 0)));
        var active = send(engine, initial, 0, "test:start", source);
        var expiring = send(engine, active.state(), 1_000_000, "test:idle", source);
        assertEquals(500_000, expiring.state().engine().timeMicros());
        assertTrue(expiring.state().engine().domain().buffs().instances().isEmpty());
        assertEquals(.25, expiring.state().engine().domain().resources().get(ENERGY).value());
        assertEquals(1_000_000, expiring.state().requested().orElseThrow().timeMicros());
        var finished = pump(engine, engine.transition(expiring.state(), new Completed(expiring.actions().getFirst().id(), Empty.INSTANCE)));
        assertTrue(finished.state().idle()); assertEquals(1_000_000, finished.state().engine().timeMicros());
    }

    @Test void missingOrWrongUnitEventMeasurementsFailInsteadOfGrantingZero() throws Exception {
        var compiled = load("credited_resource"); var engine = engine(compiled);
        var source = source("perk", "test:credited_resource", "weapon", false);
        var domain = EffectState.empty().withSource(source).withResource(new ResourceState(ENERGY, 0, 2, 0));
        for (Map<String, Measure> numbers : List.of(Map.<String, Measure>of(), Map.of("stacks", new Measure(4, Unit.SECOND)))) {
            var failed = engine.transition(engine.initial(domain), new Start(0, new Signal("test:gain", event(source, Set.of(), numbers))));
            for (int i = 0; i < 100 && failed.needsPump(); i++) failed = engine.transition(failed.state(), Pump.INSTANCE);
            assertTrue(failed.state().engine().failure().isPresent());
            assertTrue(failed.actions().isEmpty());
            assertEquals(domain, failed.state().engine().domain());
        }
    }

    @Test void completeProgramsRoundTripWithTypeDispatch() throws Exception {
        for (String fixture : List.of("kill_clip", "disruption_break", "credited_resource", "expiration_reaction")) {
            var compiled = load(fixture);
            var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, compiled).getOrThrow();
            assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        }
    }

    @Test void jsonFeedbackLoopCanTriggerTheSameRuleFortyTimes() throws Exception {
        var data = json("credited_resource");
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("max_stacks", 40);
        var rule = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
        rule.addProperty("on", "test:repeat");
        rule.add("if", JsonParser.parseString("""
                {"type":"chorus:compare","op":"lt",
                 "left":{"type":"chorus:buff_count","buff":"test:counter"},
                 "right":{"type":"chorus:constant","value":40,"unit":"count"}}
                """));
        rule.add("do", JsonParser.parseString("""
                [{"type":"chorus:grant_buff","buff":"test:counter"},
                 {"type":"chorus:emit","event":"test:repeat"}]
                """));
        var compiled = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
        var engine = compiled.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1);
        var source = source("perk", "test:credited_resource", "weapon", false);
        var result = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:repeat", source);
        assertTrue(result.state().idle());
        assertEquals(40, result.state().engine().domain().buffs().instances().values().iterator().next().count());
        assertTrue(result.state().engine().nextFrame() > 40);
        assertEquals(0, result.state().engine().timeMicros());
    }

    @Test void unknownReferencesUnitsFieldsAndResultOrderFailAtLoadTime() throws Exception {
        List<Consumer<JsonObject>> badClip = List.of(
                root -> firstAction(root).addProperty("buff", "test:missing"),
                root -> firstAction(root).addProperty("stakc", 2),
                root -> firstAction(root).add("duration", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":5,\"unit\":\"count\"}")),
                root -> firstAction(root).add("stacks", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"count\"}")),
                root -> firstAction(root).add("stacks", JsonParser.parseString("{\"type\":\"chorus:by_stacks\",\"values\":[1],\"unit\":\"count\"}")),
                root -> root.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("group", "typo"),
                root -> root.addProperty("version", "other-version"),
                root -> root.add("bundless", new JsonArray()));
        for (var change : badClip) {
            var invalid = json("kill_clip"); change.accept(invalid);
            assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, invalid).error().isPresent(), invalid.toString());
        }
        for (String field : List.of("binding", "field")) {
            var invalid = json("credited_resource");
            var result = invalid.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject()
                    .getAsJsonArray("do").get(2).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("requested").getAsJsonObject("of");
            result.addProperty(field, "missing");
            assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, invalid).error().isPresent());
        }
        var duplicate = json("credited_resource");
        var actions = duplicate.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
        actions.get(2).getAsJsonObject().addProperty("as", "gain");
        assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, duplicate).error().isPresent());
    }
    private static JsonObject firstAction(JsonObject root) { return root.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject(); }

    private record Five() implements Value {
        @Override public Unit unit(Validation validation) { return Unit.COUNT; }
        @Override public Measure evaluate(Evaluation evaluation) { return new Measure(5, Unit.COUNT); }
    }
    @Test void customValueTypeCompilesAndExecutesWithoutChangingCoreDispatch() throws Exception {
        Codec<Value> values = Codec.recursive("ExtendedValues", self -> EffectCodecs.valueTypes(self).register("test:five", Five.class, MapCodec.unit(new Five())).build());
        var conditions = EffectCodecs.conditions(values); var actions = EffectCodecs.actionTypes(values).build();
        var codec = EffectCodecs.compiled(EffectCodecs.program(values, conditions, actions, StatCodecs.PROFILE));
        var json = json("credited_resource");
        firstAction(json).getAsJsonObject("action").add("stacks", JsonParser.parseString("{\"type\":\"test:five\"}"));
        assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, json).error().isPresent());
        var compiled = codec.parse(JsonOps.INSTANCE, json).getOrThrow(); var engine = engine(compiled);
        var source = source("perk", "test:credited_resource", "weapon", false);
        var initial = engine.initial(EffectState.empty().withSource(source).withResource(new ResourceState(ENERGY, 0, 2, 0)));
        var waiting = send(engine, initial, 0, "test:gain", source);
        var result = pump(engine, engine.transition(waiting.state(), new Completed(waiting.actions().getFirst().id(), Empty.INSTANCE)));
        assertEquals(.5, result.state().engine().domain().resources().get(ENERGY).value());
    }
}
