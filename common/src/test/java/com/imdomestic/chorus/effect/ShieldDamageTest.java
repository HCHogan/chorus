package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShieldDamageTest {
    private static DamageCommand damage(double amount) {
        return new DamageCommand("target", new BuffInstance.Origin("player", "perk", "weapon", ""), amount, "minecraft:generic", Set.of(), Set.of(), false);
    }
    private static EffectState armed(CompiledEffects program) {
        var source = source("test:shield_actions"); var engine = engine(program);
        return send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:arm", event(source)).state().engine().domain();
    }
    private static DamageReceipt receipt(String id, ShieldDamage.Planned plan) {
        return new DamageReceipt(id, DamageReceipt.Outcome.APPLIED, plan.budget().shieldLoss(), 0, 0, Optional.empty(), false, Optional.empty(), plan.layers());
    }
    @Test void jsonShieldsUseFifoAndModeValuesAndRetainTheirBudgetTrace() throws Exception {
        var compiled = load("shields"); var state = armed(compiled); var plan = compiled.shields(state, damage(200), 200);
        assertEquals(List.of("test:z_first", "test:a_second"), plan.layers().stream().map(layer -> layer.before().definition().id()).toList());
        assertEquals(95, plan.budget().shieldLoss(), 1e-10); assertEquals(150, plan.layers().getFirst().trace().spentInput(), 1e-10);
        assertEquals(50, plan.layers().getLast().after(), 1e-10);
        assertEquals(145, compiled.shields(state.withMode(EffectState.Mode.PVP), damage(200), 200).budget().shieldLoss(), 1e-10);
        assertEquals(45, state.buffs().instances().values().stream().filter(v -> v.definition().id().equals("test:z_first")).findFirst().orElseThrow().components().numbers().get("capacity"));
        assertEquals(List.of("chorus:hit", "chorus:damage_taken", "chorus:shield_damaged", "chorus:shield_broken", "chorus:shield_damaged"),
                DamageFacts.from(damage(200), receipt("first", plan)).stream().map(Signal::type).toList());
    }
    @Test void commitRejectsStaleOrRecreatedInstancesAndNeverPartiallyChangesTheInput() throws Exception {
        var compiled = load("shields"); var state = armed(compiled); var commit = compiled.shields(state, damage(200), 200).commit();
        var changed = commit.apply(state);
        assertThrows(IllegalStateException.class, () -> commit.apply(changed));
        var before = commit.writes().getFirst().before();
        var removed = Buffs.remove(state.buffs(), before.key(), Buffs.Reason.REMOVED).store();
        var again = Buffs.grant(removed, before.definition(), "target", "target", before.origin(), 1, 1, BuffDefinition.FOREVER).store();
        assertThrows(IllegalStateException.class, () -> commit.apply(state.withBuffs(again)));
        assertEquals(45, before.components().numbers().get("capacity"));
    }
    @Test void completedWorldWritesAreVisibleToTheNextActionAndDuplicateEnvelopeIsIdempotent() throws Exception {
        var compiled = load("shields"); var initial = armed(compiled); var engine = engine(compiled);
        var waiting = send(engine, engine.initial(initial), 0, "test:attack", event(source("test:shield_actions")));
        var plan = compiled.shields(initial, damage(200), 200); var op = waiting.actions().getFirst().id();
        var envelope = new WorldReceipt(receipt("hit", plan), List.of(), plan.commit());
        var next = complete(engine, waiting, envelope);
        assertEquals(50, ((DamageCommand) next.actions().getFirst().command()).amount(), 1e-10);
        assertEquals(next.state(), engine.transition(next.state(), new Completed(op, envelope)).state());
        assertThrows(IllegalArgumentException.class, () -> engine.transition(next.state(), new Completed(op, new WorldReceipt(receipt("hit", plan), List.of()))));
    }
    @Test void committedWritesSurviveACompletionFailure() throws Exception {
        var compiled = load("shields"); var initial = armed(compiled); var engine = engine(compiled);
        var waiting = send(engine, engine.initial(initial), 0, "test:attack", event(source("test:shield_actions")));
        var plan = compiled.shields(initial, damage(200), 200);
        var envelope = new WorldReceipt(Empty.INSTANCE, List.of(), plan.commit()); // Actual world commit, deliberately incompatible action result.
        var failed = engine.transition(waiting.state(), new Completed(waiting.actions().getFirst().id(), envelope));
        assertTrue(failed.state().engine().failure().isPresent());
        assertEquals(plan.commit().apply(initial), failed.state().engine().domain());
        assertEquals(envelope, failed.state().engine().receipts().values().iterator().next());
    }
    @Test void damageFactsKeepIssuedImpactInputsWhenTheSameReceiptConsumesTheirShieldSource() throws Exception {
        var data = json("shields"); var rules = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules");
        var action = rules.get(1).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject();
        action.add("tags", JsonParser.parseString("[\"test:measured\"]"));
        action.add("impact", JsonParser.parseString("""
                {"before_shield":{"type":"chorus:component","buff":"test:z_first","target":"victim","component":"capacity"}}
                """));
        rules.add(JsonParser.parseString("""
                {"id":"observe_measurement","on":"chorus:hit","if":{"type":"chorus:event_tag","tag":"test:measured"},"do":[
                  {"type":"chorus:damage","amount":{"type":"chorus:impact_number","name":"before_shield","unit":"damage"},"damage_type":"minecraft:generic"}
                ]}
                """));
        var compiled = compile(data); var initial = armed(compiled); var engine = engine(compiled);
        var first = send(engine, engine.initial(initial), 0, "test:attack", event(source("test:shield_actions")));
        var command = (DamageCommand) first.actions().getFirst().command();
        assertEquals(45, command.impact().number("before_shield", Unit.DAMAGE).value());
        var plan = compiled.shields(initial, command, 200);
        var second = complete(engine, first, new WorldReceipt(receipt("first", plan), List.of(), plan.commit()));
        assertEquals(0, buff(second.state(), "test:z_first", "target").components().numbers().get("capacity"));
        assertEquals(50, ((DamageCommand) second.actions().getFirst().command()).amount());
        var reaction = complete(engine, second, new DamageReceipt("second", DamageReceipt.Outcome.APPLIED, 0, 0, 50, Optional.empty(), false));
        assertEquals(45, ((DamageCommand) reaction.actions().getFirst().command()).amount(), "Facts must carry pre-commit inputs, not the now-empty shield");
        assertTrue(complete(engine, reaction, new DamageReceipt("reaction", DamageReceipt.Outcome.APPLIED, 0, 0, 45, Optional.empty(), false)).state().idle());
    }
    @Test void externalObservationReconcilesBeforeRunningDamageReactions() throws Exception {
        var compiled = load("shields"); var initial = armed(compiled); var engine = engine(compiled);
        var plan = compiled.shields(initial, damage(200), 200);
        var result = pump(engine, engine.transition(engine.initial(initial), new Start(0, DamageFacts.from(damage(200), receipt("native", plan)), plan.commit())));
        assertEquals(plan.commit().apply(initial), result.state().engine().domain());
        assertTrue(result.state().idle());
    }
    @Test void shieldSchemaAndExpressionUnitsAreValidatedAndRoundTrip() throws Exception {
        var valid = json("shields"); var compiled = compile(valid);
        assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, compiled).getOrThrow()).getOrThrow().program());
        var bad = valid.deepCopy(); bad.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("shield").addProperty("capacity", "missing");
        assertThrows(IllegalStateException.class, () -> compile(bad));
        var original = compiled.program(); var first = original.buffs().getFirst();
        var wrong = new EffectProgram.Buff(first.definition(), first.bundle(), Optional.of(new EffectProgram.Shield("capacity", new Value.Constant(1, Unit.DAMAGE), 0)));
        assertThrows(IllegalArgumentException.class, () -> new CompiledEffects(new EffectProgram(original.version(), List.of(wrong), List.of(), List.of())));
    }
    @Test void breakRulesCanSelectShieldTagsAndDefinitionAndRepairAfterTheAttackSequence() throws Exception {
        var data = json("shields");
        data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").add("tags", JsonParser.parseString("[\"chorus:elemental_shield\"]"));
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").add(JsonParser.parseString("""
                {"id":"repair","on":"chorus:shield_broken","if":{"type":"chorus:all","of":[
                   {"type":"chorus:event_tag","tag":"chorus:elemental_shield"},
                   {"type":"chorus:event_reference","name":"shield_definition","is":"test:z_first"}
                ]},"do":[{"type":"chorus:update_component","buff":"test:z_first","target":"victim","component":"capacity","op":"set",
                          "value":{"type":"chorus:constant","value":7,"unit":"damage"}}]}
                """));
        var compiled = compile(data); var initial = armed(compiled); var engine = engine(compiled);
        var waiting = send(engine, engine.initial(initial), 0, "test:attack", event(source("test:shield_actions")));
        var first = compiled.shields(initial, damage(200), 200);
        var next = complete(engine, waiting, new WorldReceipt(receipt("first", first), List.of(), first.commit()));
        assertEquals(0, buff(next.state(), "test:z_first", "target").components().numbers().get("capacity"));
        var second = compiled.shields(next.state().engine().domain(), damage(50), 50);
        var done = complete(engine, next, new WorldReceipt(receipt("second", second), List.of(), second.commit()));
        assertTrue(done.state().idle());
        assertEquals(7, buff(done.state(), "test:z_first", "target").components().numbers().get("capacity"));
        assertEquals(0, buff(done.state(), "test:a_second", "target").components().numbers().get("capacity"));
    }
}
