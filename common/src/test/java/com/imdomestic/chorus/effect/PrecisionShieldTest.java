package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PrecisionShieldTest {
    static final String SHIELD = "chorus_d2:eternal_warrior_shield";
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("attacker", "gun", "weapon", "");
    static CompiledEffects program() throws Exception { return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("eternal_warrior")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("precision_damage")).getOrThrow())); }
    static EffectState state(boolean enhanced, boolean flat) { return EffectState.empty().withSource(new EffectSource("weapon", "test:precision_weapon", "attacker", ORIGIN,
            enhanced ? flat ? Set.of("chorus:enhanced", "test:flat") : Set.of("chorus:enhanced") : flat ? Set.of("test:flat") : Set.of())); }
    static EffectState shield(CompiledEffects p, EffectState state, String id) { return state.withBuffs(Buffs.grant(state.buffs(), p.buff(id), "target", "target", new BuffInstance.Origin("target", "armor", "", ""), 1, 1, BuffDefinition.FOREVER).store()); }
    static EffectState defense(EffectState state) { return state.withSource(new EffectSource("defense", "test:flat_defense", "target", new BuffInstance.Origin("target", "armor", "", ""), Set.of())); }
    static DamageCommand command(double amount, boolean precision, double delta) {
        return new DamageCommand("target", ORIGIN, amount, "minecraft:generic", precision ? Set.of("chorus:precision", "chorus:weapon_direct") : Set.of("chorus:weapon_direct"),
                Set.of("chorus:weapon", "chorus:precision"), false, Optional.of("test:precision_damage"), Optional.empty(),
                precision ? new ImpactData(Map.of("precision_delta", new Measure(delta, Unit.DELTA))) : ImpactData.EMPTY);
    }
    static DamageCommand captureCommand(double amount) { var c = command(amount, true, 1); return new DamageCommand(c.target(), c.source(), c.amount(), c.damageType(), c.tags(), c.killTags(), false, c.scalingProfile()); }
    static DamageBasis basis(CompiledEffects p, EffectState state, DamageCommand c) {
        var outgoing = p.outgoing(state, c, c.amount()); return new DamageBasis(outgoing, p.defense(state, c, outgoing.orElseThrow().output().value()));
    }
    static ShieldDamage.Planned hit(CompiledEffects p, EffectState state, DamageCommand c) { var b = basis(p, state, c); return p.shields(state, c, b.defense().orElseThrow().output().value(), b); }
    @Test void eternalWarriorAcceptsBodyshotDamageForPrecisionHitsInBothModesWithoutChangingHitTags() throws Exception {
        var p = program();
        for (var mode : EffectState.Mode.values()) for (boolean enhanced : List.of(false, true)) {
            var state = shield(p, state(enhanced, false).withMode(mode), SHIELD); var precision = command(1, true, 1);
            var plan = hit(p, state, precision); double body = enhanced ? 1.5 : 1.25;
            assertEquals(body, plan.budget().shieldLoss()); assertEquals(body, hit(p, state, command(1, false, 0)).budget().shieldLoss());
            assertEquals(.5, plan.layers().getFirst().factorSuppression().orElseThrow().multiplier());
            var receipt = new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, body, 0, 0, Optional.empty(), false, Optional.empty(), plan.layers());
            assertEquals(precision.tags(), ((EffectEvent) DamageFacts.from(precision, receipt).getFirst().payload()).tags());
        }
    }
    @Test void onlyTheMarkedLayerSuppressesPrecisionAndRemainingBudgetKeepsItsOriginalBasis() throws Exception {
        var p = program(); var state = shield(p, shield(p, state(false, false), SHIELD), "test:precision_plain");
        var plan = hit(p, state, command(10, true, 1));
        assertEquals(11.5, plan.budget().shieldLoss()); assertEquals(6, plan.budget().toVanilla());
        assertEquals(15, plan.layers().getFirst().trace().input() - plan.layers().getFirst().trace().remainingInput());
        assertTrue(plan.layers().getFirst().factorSuppression().isPresent()); assertTrue(plan.layers().getLast().factorSuppression().isEmpty());
        assertEquals(0, plan.commit().apply(state).buffs().instances().values().stream().filter(b -> b.definition().id().equals(SHIELD)).findFirst().orElseThrow().components().numbers().get("capacity"));
    }
    @Test void laterFlatAttackAndDefenseContributionsAreReplayedInTheirOriginalStages() throws Exception {
        var p = program(); var state = shield(p, defense(state(true, true)), SHIELD); var c = command(10, true, 1); var b = basis(p, state, c);
        assertEquals(32, b.outgoing().orElseThrow().output().value()); assertEquals(36, b.defense().orElseThrow().output().value());
        var plan = p.shields(state, c, 36, b); var suppression = plan.layers().getFirst().factorSuppression().orElseThrow();
        assertEquals(17, suppression.outgoing().orElseThrow().output().value()); assertEquals(21, suppression.defense().orElseThrow().output().value());
        assertEquals(21 / 36.0, suppression.multiplier()); assertEquals(36 - 7.5 / (21 / 36.0), plan.budget().toVanilla(), 1e-12);
    }
    @Test void suppressionCombinesWithUnderOverAndLocalResistanceInCapturedAttacks() throws Exception {
        var linked = CompiledEffects.link(List.of(program().program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("under_over")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("shield_scaling")).getOrThrow()));
        var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, linked.program()).getOrThrow().getAsJsonObject();
        for (var entry : data.getAsJsonArray("buffs")) if (entry.getAsJsonObject().getAsJsonObject("definition").get("id").getAsString().equals("test:overshield")) {
            entry.getAsJsonObject().getAsJsonObject("shield").add("excluded_attack_factors", JsonParser.parseString("[\"chorus:precision\"]"));
        }
        var p = compile(data); var initial = state(true, false).withSource(new EffectSource("under", "chorus_d2:under_over", "attacker", ORIGIN, Set.of()));
        var c = captureCommand(10);
        var capture = new DamageCommand(c.target(), c.source(), c.amount(), c.damageType(), c.tags(), c.killTags(), false, c.scalingProfile(), Optional.empty(), ImpactData.EMPTY, Optional.of("test:shield_attack"));
        var snapshot = p.captureDamage(initial, capture); var state = shield(p, initial.withoutSource("weapon").withoutSource("under"), "test:overshield");
        var command = snapshot.command("target", new ImpactData(Map.of("precision_delta", new Measure(1, Unit.DELTA)))); var plan = hit(p, state, command);
        assertEquals(.3 * 2.25 * .5, plan.layers().getFirst().trace().multiplier());
        assertEquals(2.25, plan.layers().getFirst().attackScaling().orElseThrow().output().value());
        assertEquals(15, plan.layers().getFirst().factorSuppression().orElseThrow().outgoing().orElseThrow().output().value());
        assertEquals(30 - 4.5 / (.3 * 2.25 * .5), plan.budget().toVanilla(), 1e-12);
    }
    @Test void snapshotPreservesSourceFactorsButResolvesImpactAndNewShieldAtHit() throws Exception {
        var p = program(); var initial = state(true, true); var snapshot = p.captureDamage(initial, captureCommand(10));
        var state = shield(p, defense(initial.withoutSource("weapon")), SHIELD);
        state = state.withBuffs(Buffs.advanceStep(state.buffs(), 100_000).store());
        var c = snapshot.command("target", new ImpactData(Map.of("precision_delta", new Measure(3, Unit.DELTA)))); var b = basis(p, state, c);
        assertEquals(62, b.outgoing().orElseThrow().output().value());
        var suppression = hit(p, state, c).layers().getFirst().factorSuppression().orElseThrow();
        assertEquals(17, suppression.outgoing().orElseThrow().output().value()); assertEquals(21, suppression.defense().orElseThrow().output().value());
        assertEquals(4, b.outgoing().orElseThrow().trace().factors().getFirst().multiplier());
    }
    @Test void unmarkedPrecisionNamesAndNativeTagsDoNotInventAnAppliedFactor() throws Exception {
        var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program().program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().remove("factor");
        var p = compile(data); var state = shield(p, state(false, false), SHIELD);
        assertEquals(2.5, hit(p, state, command(1, true, 1)).budget().shieldLoss());
        var c = new DamageCommand("target", ORIGIN, 2.5, "minecraft:generic", Set.of("chorus:precision"), Set.of(), false);
        assertEquals(2.5, p.shields(state, c, 2.5).budget().shieldLoss());
    }
    @Test void reachedSuppressionRequiresResolvedProfilesButZeroAndUnreachedLayersDoNot() throws Exception {
        var p = program(); var state = shield(p, state(false, false), SHIELD); var c = command(1, true, 1);
        assertThrows(IllegalArgumentException.class, () -> p.shields(state, c, 2.5));
        var wrong = new CalculationProfile("test:other", "test-1", Unit.DAMAGE, List.of()).calculate(new Measure(2.5, Unit.DAMAGE), List.of());
        assertThrows(IllegalArgumentException.class, () -> p.shields(state, c, 2.5, new DamageBasis(Optional.of(wrong), Optional.empty())));
        assertTrue(p.shields(state, c, 0).layers().isEmpty());
        var plain = shield(p, state(false, false), "test:precision_plain"); assertEquals(2.5, p.shields(plain, c, 2.5).budget().shieldLoss());
    }
    @Test void retainedBasisNeverRereadsSourceOrTargetExpressionsDuringSuppression() throws Exception {
        var p = program(); var before = shield(p, defense(state(true, true)), SHIELD); var c = command(10, true, 1); var b = basis(p, before, c);
        var after = before.withoutSource("weapon").withoutSource("defense");
        var plan = p.shields(after, c, 36, b); assertEquals(21, plan.layers().getFirst().factorSuppression().orElseThrow().defense().orElseThrow().output().value());
        assertEquals(10, basis(p, after, c).outgoing().orElseThrow().output().value(), "a fresh query would observe source removal");
    }
    @Test void shieldPolicyRoundTripsAndRejectsMisspelledFieldsAndMalformedFactorIds() throws Exception {
        var p = program(); var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow();
        assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        var shield = data.getAsJsonObject().getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("shield");
        shield.addProperty("excluded_attack_factor", "chorus:precision"); assertTrue(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).error().isPresent());
        shield.remove("excluded_attack_factor"); shield.add("excluded_attack_factors", JsonParser.parseString("[\"precision\"]"));
        assertThrows(RuntimeException.class, () -> compile(data.getAsJsonObject()));
    }
}
