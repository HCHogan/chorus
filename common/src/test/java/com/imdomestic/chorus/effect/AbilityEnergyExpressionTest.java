package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.ability.AbilityLoadout;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityEnergyExpressionTest {
    static final String SLOT = PugilistTest.SLOT;
    static final Validation VALIDATION = new Validation(Map.of(), Map.of(), false);
    static Value.AbilityEnergy value(Evaluation.Target target, String field) { return new Value.AbilityEnergy(SLOT, target, field); }
    static Condition.AbilityEnergyFlag flag(Evaluation.Target target, String field) { return new Condition.AbilityEnergyFlag(SLOT, target, field, true); }
    static EffectState balance(EffectState state, String holder, double value) {
        return state.withAbilities(holder, new AbilityLoadout(Map.of(SLOT, "test:alternate")))
                .withResource(new ResourceState(new ResourceState.Key(holder, "test:alternate_energy"), value, 2, 0));
    }
    @Test void liveNumericReadsShareTheObservationContractAndNeverMutateState() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle"); var state = balance(h.state(), "player", 1.5);
        var e = AbilityEnergyTest.evaluation(h, state, "player"); var action = new EnergyActions.ObserveAbility(SLOT, Evaluation.Target.SELF);
        var observed = (com.imdomestic.chorus.rule.RuleEngine.Local<EffectState>) action.execute(e);
        for (String field : List.of("value", "capacity", "missing", "full_charges")) {
            var v = value(Evaluation.Target.SELF, field); assertEquals(action.validate(VALIDATION).read(field, observed.result()), v.evaluate(e));
            assertEquals(field.equals("full_charges") ? Unit.COUNT : Unit.CHARGE, v.unit(VALIDATION));
        }
        assertTrue(flag(Evaluation.Target.SELF, "available").test(e)); assertFalse(flag(Evaluation.Target.SELF, "full").test(e));
        assertEquals(state, e.state()); assertSame(state, observed.state()); assertTrue(observed.emitted().isEmpty());
    }
    @Test void availabilityGuardCanExplicitlyCountMissingAsZeroButRawReadsStillFail() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle");
        var guarded = new Value.Choose(flag(Evaluation.Target.SELF, "available"), value(Evaluation.Target.SELF, "full_charges"), new Value.Constant(0, Unit.COUNT));
        assertEquals(Unit.COUNT, guarded.unit(VALIDATION));
        for (String id : Arrays.asList(null, "test:no_resource")) {
            h.select(id); var e = AbilityEnergyTest.evaluation(h, h.state(), "player");
            assertTrue(flag(Evaluation.Target.SELF, id == null ? "no_selection" : "no_resource").test(e));
            assertEquals(0, guarded.evaluate(e).value()); assertEquals(new Value.Constant(0, Unit.COUNT), guarded.snapshot(e));
            assertThrows(IllegalArgumentException.class, () -> value(Evaluation.Target.SELF, "value").evaluate(e));
        }
    }
    @Test void sourceOperandsFreezeWhileVictimEnergyAndAvailabilityRemainLive() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle");
        var before = balance(balance(h.state(), "player", 2), "ally", 1.5); var e = AbilityEnergyTest.evaluation(h, before, "ally");
        var source = value(Evaluation.Target.SELF, "full_charges").snapshot(e); var victim = value(Evaluation.Target.VICTIM, "full_charges").snapshot(e);
        var sourceFull = flag(Evaluation.Target.SELF, "full").snapshot(e); var victimAvailable = flag(Evaluation.Target.VICTIM, "available").snapshot(e);
        var after = balance(balance(before, "player", 0), "ally", .5); var impact = AbilityEnergyTest.evaluation(h, after, "ally");
        assertEquals(2, source.evaluate(impact).value()); assertEquals(0, victim.evaluate(impact).value()); assertTrue(sourceFull.test(impact));
        assertTrue(victimAvailable.test(impact));
        impact = AbilityEnergyTest.evaluation(h, after.withAbilities("ally", AbilityLoadout.EMPTY), "ally"); assertFalse(victimAvailable.test(impact));
        assertEquals(0, value(Evaluation.Target.SELF, "full_charges").evaluate(impact).value(), "ordinary query remains live");
    }
    @Test void brokenSelectionCannotMasqueradeAsAbsentAndFlagsCanBeNegated() throws Exception {
        var h = new PugilistTest.Harness(false, "auto_rifle");
        var bad = h.state().withAbilities("player", new AbilityLoadout(Map.of(SLOT, "test:unknown"))); var e = AbilityEnergyTest.evaluation(h, bad, "player");
        assertThrows(IllegalArgumentException.class, () -> flag(Evaluation.Target.SELF, "available").test(e));
        var full = AbilityEnergyTest.evaluation(h, balance(h.state(), "player", 2), "player");
        assertFalse(new Condition.AbilityEnergyFlag(SLOT, Evaluation.Target.SELF, "full", false).test(full));
        assertThrows(IllegalArgumentException.class, () -> value(new Evaluation.BoundTarget("loop"), "value").snapshot(full));
        assertThrows(IllegalArgumentException.class, () -> flag(new Evaluation.BoundTarget("loop"), "available").snapshot(full));
    }
    @Test void damageSnapshotsResolveVictimAbilitiesThroughTheActualCompiledProgram() throws Exception {
        var data = EffectTestSupport.json("ability_energy_targets");
        data.getAsJsonArray("bundles").remove(0); // unrelated override refers to Threaded Spike
        data.getAsJsonArray("profiles").add(JsonParser.parseString("""
                {"id":"test:damage","version":"compendium-2026-10-05","input_unit":"damage","steps":[
                  {"type":"chorus:apply","id":"perks","operation":"add","group":{"name":"energy","reduction":"sum"}}]}
                """));
        var bundle = JsonParser.parseString("{\"id\":\"test:read\",\"modifiers\":[]}").getAsJsonObject();
        for (String target : List.of("self", "victim")) bundle.getAsJsonArray("modifiers").add(JsonParser.parseString("""
                {"id":"%s","profile":"test:damage","stage":"perks","group":"energy","op":"add","stacking_key":"test:%s",
                 "if":{"type":"chorus:ability_energy_flag","slot":"chorus_d2:melee","target":"%s","field":"available"},
                 "value":{"type":"chorus:scale","factor":1,"from":"charge_fraction","to":"damage",
                   "of":{"type":"chorus:ability_energy","slot":"chorus_d2:melee","target":"%s","field":"value"}},
                 "reference":"synthetic dependency test","confidence":"assumed"}
                """.formatted(target, target, target, target)));
        data.getAsJsonArray("bundles").add(bundle); var program = EffectTestSupport.compile(data);
        var source = new EffectSource("read", "test:read", "player", new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("player", "read", "", ""), Set.of());
        var before = balance(balance(EffectState.empty().withSource(source), "player", 2), "ally", 1.5);
        var attack = new com.imdomestic.chorus.effect.combat.DamageCommand("ally", source.origin(), 10, "minecraft:generic", Set.of(), Set.of(), false, Optional.of("test:damage"));
        var snapshot = program.captureDamage(before, attack); var after = balance(balance(before, "player", 0), "ally", .5);
        assertEquals(12.5, program.outgoing(after, snapshot.command("ally"), 10).orElseThrow().output().value());
        assertEquals(10.5, program.outgoing(after, attack, 10).orElseThrow().output().value());
        after = after.withAbilities("ally", AbilityLoadout.EMPTY);
        assertEquals(12, program.outgoing(after, snapshot.command("ally"), 10).orElseThrow().output().value());
    }
    @Test void codecsDefaultToSelfAndValidationRejectsUnknownOrWrongKindOfField() {
        var v = JsonParser.parseString("{\"type\":\"chorus:ability_energy\",\"slot\":\"chorus_d2:melee\",\"field\":\"value\"}");
        assertEquals(value(Evaluation.Target.SELF, "value"), EffectCodecs.VALUE.parse(JsonOps.INSTANCE, v).getOrThrow());
        var f = JsonParser.parseString("{\"type\":\"chorus:ability_energy_flag\",\"slot\":\"chorus_d2:melee\",\"field\":\"available\"}");
        assertEquals(flag(Evaluation.Target.SELF, "available"), EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, f).getOrThrow());
        var value = value(Evaluation.Target.VICTIM, "capacity"); var flag = new Condition.AbilityEnergyFlag(SLOT, Evaluation.Target.VICTIM, "full", false);
        assertEquals(value, EffectCodecs.VALUE.parse(JsonOps.INSTANCE, EffectCodecs.VALUE.encodeStart(JsonOps.INSTANCE, value).getOrThrow()).getOrThrow());
        assertEquals(flag, EffectCodecs.CONDITION.parse(JsonOps.INSTANCE, EffectCodecs.CONDITION.encodeStart(JsonOps.INSTANCE, flag).getOrThrow()).getOrThrow());
        assertThrows(IllegalArgumentException.class, () -> value(Evaluation.Target.SELF, "available").unit(VALIDATION));
        assertThrows(IllegalArgumentException.class, () -> flag(Evaluation.Target.SELF, "value").validate(VALIDATION));
        assertThrows(IllegalArgumentException.class, () -> value(Evaluation.Target.SELF, "typo").unit(VALIDATION));
        assertThrows(IllegalArgumentException.class, () -> new Value.AbilityEnergy("bad slot", Evaluation.Target.SELF, "value"));
    }
}
