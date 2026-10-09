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

class ShieldRestorationTest {
    private static final String POOL = "test:z_first";
    private static final EffectSource SOURCE = source("test:shield_actions");
    private static EffectState armed(CompiledEffects program) {
        var engine = engine(program); return send(engine, engine.initial(EffectState.empty().withSource(SOURCE)), 0, "test:arm", event(SOURCE)).state().engine().domain();
    }
    private static BuffInstance pool(EffectState state) { return state.buffs().instances().values().stream().filter(b -> b.definition().id().equals(POOL)).findFirst().orElseThrow(); }
    private static EffectEvent input(double amount) { return new EffectEvent("player", "target", SOURCE.origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.DAMAGE))); }
    @Test void restoringClipsCapacityPreservesGenerationAndLifetimeAndDoesNotBankOverflow() throws Exception {
        var program = load("shield_restoration"); var before = armed(program); var instance = pool(before);
        var result = ShieldRestoration.restore(before.buffs(), instance.key(), "capacity", 12, program.shieldMaximum(before, instance));
        assertEquals(10, result.receipt().after()); assertEquals(10, result.receipt().effective()); assertEquals(2, result.receipt().overflow());
        var after = result.store().active(instance.key()).orElseThrow(); assertEquals(instance.generation(), after.generation()); assertEquals(instance.deadline(), after.deadline());
        assertEquals(instance.origin(), after.origin());
        var full = ShieldRestoration.restore(result.store(), instance.key(), "capacity", 9, 10); assertEquals(0, full.receipt().effective()); assertEquals(9, full.receipt().overflow());
        var state = before.withBuffs(full.store()); var command = new DamageCommand("target", SOURCE.origin(), 4, "minecraft:generic", Set.of(), Set.of(), false);
        state = program.shields(state, command, 4).commit().apply(state); assertEquals(6, pool(state).components().numbers().get("capacity"));
        var next = ShieldRestoration.restore(state.buffs(), instance.key(), "capacity", 1, 10); assertEquals(7, next.receipt().after(), "old overflow cannot refill later damage");
    }
    @Test void maximumUsesReceivingLayerScopeAndLowerLimitsDoNotDamageExistingShield() throws Exception {
        var program = load("shield_restoration"); var state = armed(program); var instance = pool(state);
        assertEquals(10, program.shieldMaximum(state, instance), "self in the maximum is the layer's target, not the restoring source holder");
        var filled = ShieldRestoration.restore(state.buffs(), instance.key(), "capacity", 8, 10);
        var changed = filled.store().active(instance.key()).orElseThrow();
        state = state.withBuffs(Buffs.components(filled.store(), instance.key(), changed.components().number("limit", BuffComponents.Update.SET, 3)));
        var lowered = ShieldRestoration.restore(state.buffs(), instance.key(), "capacity", 2, program.shieldMaximum(state, pool(state)));
        assertEquals(8, lowered.receipt().after()); assertEquals(0, lowered.receipt().effective()); assertEquals(2, lowered.receipt().overflow());
        var legacy = state.buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:a_second")).findFirst().orElseThrow();
        assertEquals(100, program.shieldMaximum(state, legacy), "legacy maximum defaults to declared initial capacity");
    }
    @Test void decimalIncrementsReachTheCapWithoutCreatingCapacityThroughRounding() throws Exception {
        var state = armed(load("shield_restoration")); var instance = pool(state); var store = state.buffs();
        for (int i = 0; i < 100; i++) store = ShieldRestoration.restore(store, instance.key(), "capacity", .015, 1.5).store();
        assertEquals(1.5, store.active(instance.key()).orElseThrow().components().numbers().get("capacity"));
        store = Buffs.components(store, instance.key(), instance.components().number("capacity", BuffComponents.Update.SET, 1e16));
        var tiny = ShieldRestoration.restore(store, instance.key(), "capacity", 1.1, 2e16);
        assertTrue(tiny.receipt().effective() <= 1.1); assertEquals(1e16, tiny.receipt().after());
        assertThrows(IllegalArgumentException.class, () -> new ShieldRestoration.Receipt(instance, 0, 10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ShieldRestoration.restore(state.buffs(), instance.key(), "capacity", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> ShieldRestoration.restore(BuffStore.empty(), instance.key(), "capacity", 1, 10));
    }
    @Test void actualRestorationFactsRetainLayerIdentityAndRestorerAttribution() throws Exception {
        var state = armed(load("shield_restoration")); var instance = pool(state);
        var receipt = ShieldRestoration.restore(state.buffs(), instance.key(), "capacity", 12, 10).receipt();
        var other = new BuffInstance.Origin("other", "repair", "", "repair"); var facts = ShieldRestoration.facts(receipt, other);
        assertEquals(List.of("chorus:shield_restored"), facts.stream().map(RuleEngine.Signal::type).toList());
        var event = (EffectEvent) facts.getFirst().payload(); assertEquals(other, event.source()); assertEquals("target", event.victim());
        assertEquals(POOL, event.references().get("shield_definition")); assertEquals(Long.toString(instance.generation()), event.references().get("shield_generation"));
        assertEquals(10, event.numbers().get("effective").value()); assertEquals(2, event.numbers().get("overflow").value());
        assertTrue(ShieldRestoration.facts(new ShieldRestoration.Receipt(instance, 0, 10, 0, 0), other).isEmpty());
    }
    @Test void restorationCommitsBeforeWorldWaitAndDuplicateReceiptsCannotRefillDamage() throws Exception {
        var program = load("shield_restoration"); var state = armed(program); var engine = engine(program);
        var waiting = send(engine, engine.initial(state), 0, "test:restore", input(12));
        var heal = (HealingCommand) waiting.actions().getFirst().command(); assertEquals(10, heal.amount());
        assertEquals(10, pool(waiting.state().engine().domain()).components().numbers().get("capacity"));
        var command = new DamageCommand("target", SOURCE.origin(), 4, "minecraft:generic", Set.of(), Set.of(), false);
        var plan = program.shields(waiting.state().engine().domain(), command, 4);
        var receipt = new RuleEngine.WorldReceipt(new HealingReceipt("heal", heal, HealingReceipt.Outcome.APPLIED, 10, 10, 0), List.of(), plan.commit());
        var completed = complete(engine, waiting, receipt); assertEquals(6, pool(completed.state().engine().domain()).components().numbers().get("capacity"));
        var duplicate = engine.transition(completed.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), receipt));
        assertEquals(completed.state(), duplicate.state());
    }
    @Test void anySourceAndTagPresenceRemainDistinctFromPositiveShieldCapacityAndPausedProtection() throws Exception {
        var program = load("shield_restoration"); var state = armed(program); var original = pool(state);
        var other = new EffectSource("other", SOURCE.bundle(), "player", new BuffInstance.Origin("player", "other", "other-weapon", ""), Set.of());
        state = state.withoutSource(SOURCE.instance()).withSource(other);
        var cues = new ArrayList<String>(); var session = new EffectSession(engine(program), state, request -> { cues.add(((Action.CueCommand) request.command()).cue()); return RuleEngine.Empty.INSTANCE; });
        session.start(0, new RuleEngine.Signal("test:probe", event(other))); assertEquals(List.of("test:any", "test:tag"), cues);
        state = state.withBuffs(ShieldRestoration.restore(state.buffs(), original.key(), "capacity", 1, 10).store());
        assertTrue(program.hasShield(state, "target", Optional.of("test:shield"))); assertFalse(program.hasShield(state, "other-target", Optional.empty()));
        var engine = engine(program); var paused = send(engine, engine.initial(state), 0, "chorus:weapon_stowed", event(SOURCE)).state().engine().domain();
        assertFalse(program.hasShield(paused, "target", Optional.of("test:shield")), "paused layers cannot protect");
    }
    @Test void newConditionsFreezeSourceQueriesButKeepVictimQueriesDeferred() throws Exception {
        var program = load("shield_restoration"); var state = armed(program); var layer = pool(state);
        state = state.withBuffs(ShieldRestoration.restore(state.buffs(), layer.key(), "capacity", 1, 10).store());
        var holder = new EffectSource("holder", SOURCE.bundle(), "target", SOURCE.origin(), Set.of());
        var context = new RuleEngine.Context(new RuleEngine.Event(1, 1, Optional.empty(), 0, new RuleEngine.Signal("test:probe", event(holder))), "test", holder, Map.of());
        var defs = new HashMap<String, BuffDefinition>(); program.program().buffs().forEach(b -> defs.put(b.definition().id(), b.definition()));
        var evaluation = new Evaluation(state, context, defs, Map.of(), Map.of(), Map.of(), Optional.of(program));
        for (Condition condition : List.of(new Condition.HasShield(Optional.of("test:shield"), Evaluation.Target.SELF), new Condition.HasBuffTag("test:shield", Evaluation.Target.SELF))) {
            assertEquals(new Condition.Constant(true), condition.snapshot(evaluation));
        }
        for (Condition condition : List.of(new Condition.HasShield(Optional.empty(), Evaluation.Target.VICTIM), new Condition.HasBuffTag("test:shield", Evaluation.Target.VICTIM))) assertEquals(condition, condition.snapshot(evaluation));
    }
    @Test void declarationsAndActionsRejectNonShieldBuffsWrongUnitsAndNegativeRequests() throws Exception {
        var program = load("shield_restoration"); assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow().program());
        for (String invalidValue : List.of("{\"type\":\"chorus:constant\",\"value\":-1,\"unit\":\"damage\"}", "{\"type\":\"chorus:constant\",\"value\":1,\"unit\":\"second\"}")) {
            var maximumData = json("shield_restoration"); maximumData.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("shield").add("maximum", JsonParser.parseString(invalidValue));
            assertThrows(RuntimeException.class, () -> compile(maximumData));
            var invalid = json("shield_restoration");
            invalid.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").add("amount", JsonParser.parseString(invalidValue));
            assertThrows(RuntimeException.class, () -> compile(invalid));
        }
        var noLayer = json("shield_restoration"); noLayer.getAsJsonArray("buffs").get(0).getAsJsonObject().remove("shield"); assertThrows(RuntimeException.class, () -> compile(noLayer));
    }
}
