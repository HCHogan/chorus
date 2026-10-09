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

class LayerProfileTest {
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("player", "gun", "weapon", "");
    static CompiledEffects program() throws Exception { return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("under_over")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("shield_scaling")).getOrThrow())); }
    static EffectState state(boolean enhanced) { return EffectState.empty().withSource(new EffectSource("perk", "chorus_d2:under_over", "player", ORIGIN, enhanced ? Set.of("chorus:enhanced") : Set.of())); }
    static EffectState grant(CompiledEffects program, EffectState state, String id) {
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:" + id), "target", "target", ORIGIN, 1, 1, BuffDefinition.FOREVER).store());
    }
    static DamageCommand command(BuffInstance.Origin origin, Set<String> tags) {
        return new DamageCommand("target", origin, 20, "minecraft:generic", tags, Set.of("test:weapon_kill"), false,
                Optional.of("test:weapon_damage"), Optional.empty(), ImpactData.EMPTY, Optional.of("test:shield_attack"));
    }
    static DamageCommand command() { return command(ORIGIN, Set.of("chorus:weapon_direct")); }
    @Test void layerAttackBonusesMultiplyLocalResistanceAndNeverMultiplyVanillaOverflow() throws Exception {
        var p = program();
        for (boolean enhanced : List.of(false, true)) {
            var state = grant(p, grant(p, state(enhanced), "elemental"), "overshield"); var attack = command();
            assertEquals(20, p.outgoing(state, attack, 20).orElseThrow().output().value());
            var plan = p.shields(state, attack, 20); double first = enhanced ? 1.55 : 1.5, second = enhanced ? 2.4 : 2.25;
            assertEquals(7.5, plan.budget().shieldLoss()); assertEquals(20 - 3 / first - 4.5 / (.3 * second), plan.budget().toVanilla(), 1e-12);
            assertEquals(first, plan.layers().getFirst().attackScaling().orElseThrow().output().value());
            assertEquals(.3 * second, plan.layers().getLast().trace().multiplier());
            assertEquals(List.of("test:elemental", "test:overshield"), plan.layers().stream().map(h -> h.before().definition().id()).toList());
            assertTrue(plan.layers().stream().allMatch(h -> h.attackScaling().orElseThrow().trace().contributions().size() == 1));
        }
    }
    @Test void targetLayerTagsRatherThanActivityModeChooseGuardianCombatantAndBarrierBonuses() throws Exception {
        var p = program();
        for (var mode : EffectState.Mode.values()) for (boolean enhanced : List.of(false, true)) {
            for (var entry : Map.of("elemental", enhanced ? 1.55 : 1.5, "barrier", enhanced ? 1.55 : 1.5,
                    "overshield", enhanced ? 2.4 : 2.25, "guardian", enhanced ? 1.22 : 1.2, "plain", 1.0).entrySet()) {
                var state = grant(p, state(enhanced).withMode(mode), entry.getKey());
                assertEquals(entry.getValue(), p.shields(state, command(), 1).layers().getFirst().attackScaling().orElseThrow().output().value());
            }
        }
    }
    @Test void wrongWeaponExplosivePerkAndOtherOwnersCannotSupplyLayerBonus() throws Exception {
        var p = program(); var state = grant(p, state(false), "elemental");
        for (var attack : List.of(command(ORIGIN, Set.of()), command(ORIGIN, Set.of("chorus:weapon_direct", "chorus:explosive_perk")),
                command(new BuffInstance.Origin("player", "other", "other-weapon", ""), Set.of("chorus:weapon_direct")),
                command(new BuffInstance.Origin("other", "gun", "weapon", ""), Set.of("chorus:weapon_direct")))) {
            assertEquals(1, p.shields(state, attack, 1).layers().getFirst().trace().multiplier());
        }
        var targetPerk = state(false).withoutSource("perk").withSource(new EffectSource("target-perk", "chorus_d2:under_over", "target", ORIGIN, Set.of()));
        assertEquals(1, p.shields(grant(p, targetPerk, "elemental"), command(), 1).layers().getFirst().trace().multiplier());
    }
    @Test void wovenMailBodyshotBranchUsesGlobalDamageWhileLayerTagsNeverBecomeAttackTags() throws Exception {
        var p = program(); var state = grant(p, state(false), "woven");
        var body = command(ORIGIN, Set.of("chorus:weapon_direct", "chorus:guardian_target", "chorus:bodyshot"));
        assertEquals(24, p.outgoing(state, body, 20).orElseThrow().output().value());
        assertEquals(20, p.outgoing(state, command(ORIGIN, Set.of("chorus:weapon_direct", "chorus:guardian_target", "chorus:precision")), 20).orElseThrow().output().value());
        assertEquals(20, p.outgoing(state, command(ORIGIN, Set.of("chorus:weapon_direct", "chorus:bodyshot")), 20).orElseThrow().output().value());
        assertEquals(20, p.outgoing(state(false), body, 20).orElseThrow().output().value());
        var protectedState = grant(p, state, "guardian"); var plan = p.shields(protectedState, body, 24);
        var receipt = new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, plan.budget().shieldLoss(), 0, plan.budget().toVanilla(), Optional.empty(), false, Optional.empty(), plan.layers());
        var hit = (EffectEvent) DamageFacts.from(body, receipt).getFirst().payload(); assertEquals(body.tags(), hit.tags());
        assertFalse(hit.tags().contains("chorus:guardian_overshield"));
    }
    @Test void snapshotFreezesEnhancementAndWeaponSourceButDefersLayerSelectionAndTargetState() throws Exception {
        var p = program(); var initial = state(true); var snapshot = p.captureDamage(initial, command());
        assertEquals(1, snapshot.shieldScaling().orElseThrow().contributions().size());
        var after = initial.withoutSource("perk"); after = after.withBuffs(Buffs.advanceStep(after.buffs(), 100_000).store());
        for (var entry : Map.of("elemental", 1.55, "guardian", 1.22, "overshield", 2.4).entrySet()) {
            var target = grant(p, after, entry.getKey());
            assertEquals(entry.getValue(), p.shields(target, snapshot.command("target"), 1).layers().getFirst().attackScaling().orElseThrow().output().value());
            assertEquals(1, p.shields(target, command(), 1).layers().getFirst().attackScaling().orElseThrow().output().value());
        }
        var body = command(ORIGIN, Set.of("chorus:weapon_direct", "chorus:guardian_target", "chorus:bodyshot"));
        var bodySnapshot = p.captureDamage(initial, body); assertEquals(20, p.outgoing(after, bodySnapshot.command("target"), 20).orElseThrow().output().value());
        assertEquals(24.4, p.outgoing(grant(p, after, "woven"), bodySnapshot.command("target"), 20).orElseThrow().output().value(), 1e-12);
    }
    @Test void liveContributionsShareTheCapturedGroupInsteadOfMultiplyingAnAlreadyFoldedResult() throws Exception {
        var p = withLive(program(), .9, "test-1", false); var initial = state(false); var snapshot = p.captureDamage(initial, command());
        var state = grant(p, initial.withoutSource("perk").withSource(new EffectSource("live", "test:live_layer", "player", ORIGIN, Set.of())), "elemental");
        var result = p.shields(state, snapshot.command("target"), 1).layers().getFirst().attackScaling().orElseThrow();
        assertEquals(1.9, result.output().value()); assertEquals(2, result.trace().contributions().size());
        assertEquals(1, result.trace().contributions().stream().filter(CalculationTrace.ContributionTrace::selected).count());
        var replaced = withLive(p, 1.0, "test-2", false); var newState = grant(replaced, state(false).withoutSource("perk").withSource(new EffectSource("live", "test:live_layer", "player", ORIGIN, Set.of())), "elemental");
        var changed = replaced.shields(newState, snapshot.command("target"), 1).layers().getFirst().attackScaling().orElseThrow();
        assertEquals(2, changed.output().value()); assertEquals("test-1", changed.trace().version());
        assertEquals(Set.of("test-1", "test-2"), changed.trace().contributions().stream().map(c -> c.contribution().source().version()).collect(java.util.stream.Collectors.toSet()));
        var incompatible = withLive(p, 1.0, "test-2", true); var badState = grant(incompatible, state(false).withSource(new EffectSource("live", "test:live_layer", "player", ORIGIN, Set.of())), "elemental");
        assertThrows(IllegalArgumentException.class, () -> incompatible.shields(badState, snapshot.command("target"), 1));
    }
    @Test void snapshotVictimHasShieldQueriesUseTheCurrentCatalogue() throws Exception {
        var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program().program()).getOrThrow().getAsJsonObject();
        var modifiers = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers");
        modifiers.get(1).getAsJsonObject().add("if", JsonParser.parseString("{\"type\":\"chorus:has_shield\",\"target\":\"victim\"}"));
        var p = compile(data); var snapshot = p.captureDamage(state(false), command());
        assertEquals(20, p.outgoing(EffectState.empty(), snapshot.command("target"), 20).orElseThrow().output().value());
        assertEquals(24, p.outgoing(grant(p, EffectState.empty(), "plain"), snapshot.command("target"), 20).orElseThrow().output().value());
    }
    @Test void unreachedAndEmptyLayersDoNotEvaluateTheirProfilesOrMissingMeasurements() throws Exception {
        var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program().program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("buffs").get(1).getAsJsonObject().getAsJsonObject("shield").add("taken_multiplier", JsonParser.parseString("{\"type\":\"chorus:impact_number\",\"name\":\"only_later\",\"unit\":\"multiplier\"}"));
        var p = compile(data); var state = grant(p, grant(p, state(false), "elemental"), "overshield");
        assertEquals(1, p.shields(state, command(), 1).layers().size()); assertTrue(p.shields(state, command(), 0).layers().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> p.shields(state, command(), 20));
        var layer = state.buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:overshield")).findFirst().orElseThrow();
        var empty = state.withBuffs(Buffs.components(state.buffs(), layer.key(), layer.components().number("capacity", BuffComponents.Update.SET, 0)));
        assertEquals(1, p.shields(empty, command(), 20).layers().size());
    }
    @Test void declarationsRoundTripAndRejectWrongProfileUnitsAndAlteredSnapshotProfile() throws Exception {
        var p = program(); var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var bad = encoded.deepCopy().getAsJsonObject(); bad.getAsJsonArray("profiles").get(1).getAsJsonObject().addProperty("input_unit", "damage");
        assertThrows(RuntimeException.class, () -> compile(bad));
        var snapshot = p.captureDamage(state(false), command()); var command = snapshot.command("target");
        assertThrows(IllegalArgumentException.class, () -> new DamageCommand(command.target(), command.source(), command.amount(), command.damageType(), command.tags(), command.killTags(), false,
                command.scalingProfile(), command.snapshot(), command.impact(), Optional.empty()));
        var negative = withLive(p, -2, "test-1", false); var state = grant(negative, EffectState.empty().withSource(new EffectSource("live", "test:live_layer", "player", ORIGIN, Set.of())), "elemental");
        assertThrows(IllegalArgumentException.class, () -> negative.shields(state, command(), 1));
    }
    private static CompiledEffects withLive(CompiledEffects p, double amount, String version, boolean changedLayout) {
        var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow().getAsJsonObject();
        for (int i = data.getAsJsonArray("bundles").size() - 1; i >= 0; i--) if (data.getAsJsonArray("bundles").get(i).getAsJsonObject().get("id").getAsString().equals("test:live_layer")) data.getAsJsonArray("bundles").remove(i);
        data.getAsJsonArray("bundles").add(JsonParser.parseString("""
          {"id":"test:live_layer","modifiers":[{"id":"live","profile":"test:shield_attack","stage":"perk","group":"perk","op":"multiply","stacking_key":"test:live",
          "evaluate":"on_hit","value":{"type":"chorus:constant","value":%s,"unit":"delta"},"reference":"Synthetic live group test","confidence":"assumed"}]}
          """.formatted(amount)));
        if (changedLayout) data.getAsJsonArray("profiles").get(1).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("group").addProperty("reduction", "sum");
        return compile(JsonParser.parseString(data.toString().replace("test-1", version)).getAsJsonObject());
    }
}
