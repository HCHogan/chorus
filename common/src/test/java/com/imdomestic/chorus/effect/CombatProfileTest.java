package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class CombatProfileTest {
    private static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("attacker", "perk", "weapon", "");
    private static DamageCommand command(Optional<String> profile, Set<String> tags) {
        return new DamageCommand("target", ORIGIN, 16, "minecraft:mob_attack", tags, Set.of(), false, profile);
    }
    private static EffectState grant(CompiledEffects program, EffectState state, String id, String holder) {
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff(id), holder, holder, ORIGIN, 1, 1, BuffDefinition.FOREVER).store());
    }
    private static EffectState armed(CompiledEffects program) {
        var state = grant(program, EffectState.empty(), "chorus_d2:kill_clip", "attacker");
        state = grant(program, state, "chorus_d2:disruption_break", "target");
        state = grant(program, state, "test:resistance", "target");
        return grant(program, state, "test:shield", "target");
    }
    @Test void compendiumModifiersFeedOutgoingDefenseAndShieldInOrderWithoutMutatingState() throws Exception {
        var program = load("combat_profiles"); var state = armed(program);
        var command = command(Optional.of("test:weapon_damage"), Set.of("chorus:kinetic_damage"));
        var outgoing = program.outgoing(state, command, 16).orElseThrow();
        var defense = program.defense(state, command, outgoing.output().value()).orElseThrow();
        assertEquals(20, outgoing.output().value()); assertEquals(Unit.DAMAGE, outgoing.output().unit());
        assertEquals(30, defense.trace().stages().get("vulnerability").value());
        assertEquals(15, defense.output().value());
        var shield = program.shields(state, command, defense.output().value());
        assertEquals(5, shield.budget().shieldLoss()); assertEquals(10, shield.budget().toVanilla());
        assertEquals(5, state.buffs().instances().values().stream().filter(v -> v.definition().id().equals("test:shield"))
                .findFirst().orElseThrow().components().numbers().get("capacity"));
        assertTrue(outgoing.trace().contributions().stream().allMatch(c -> c.contribution().source().definition().equals("chorus_d2:kill_clip_active")));
    }
    @Test void attackerAndVictimModifiersCannotLeakAcrossTheTwoQueries() throws Exception {
        var program = load("combat_profiles"); var state = armed(program);
        state = grant(program, state, "chorus_d2:kill_clip", "target");
        state = grant(program, state, "test:resistance", "attacker");
        state = grant(program, state, "chorus_d2:disruption_break", "attacker");
        var command = command(Optional.of("test:weapon_damage"), Set.of("chorus:kinetic_damage"));
        assertEquals(20, program.outgoing(state, command, 16).orElseThrow().output().value());
        assertEquals(15, program.defense(state, command, 20).orElseThrow().output().value());
        var unrelatedWeapon = new DamageCommand("target", new BuffInstance.Origin("attacker", "other", "other_weapon", ""),
                16, "minecraft:mob_attack", Set.of(), Set.of(), false, Optional.of("test:weapon_damage"));
        assertEquals(16, program.outgoing(state, unrelatedWeapon, 16).orElseThrow().output().value());
        // No kinetic qualification: Disruption Break contributes nothing; target resistance still applies.
        assertEquals(8, program.defense(state, unrelatedWeapon, 16).orElseThrow().output().value());
    }
    @Test void missingAttackProfileDoesNotSkipTargetDefenseOrGuessScalingFromCredit() throws Exception {
        var program = load("combat_profiles"); var state = armed(program);
        var command = command(Optional.empty(), Set.of("chorus:kinetic_damage", "chorus:weapon_kill"));
        assertTrue(program.outgoing(state, command, 16).isEmpty());
        assertEquals(12, program.defense(state, command, 16).orElseThrow().output().value());
        var plain = new CompiledEffects(new EffectProgram("test-1", List.of(), List.of(), List.of()));
        assertTrue(plain.defense(EffectState.empty(), command, 16).isEmpty());
    }
    @Test void jsonDamageCommandCarriesTheExplicitProfileAndRoundTrips() throws Exception {
        var program = load("combat_profiles"); var engine = engine(program);
        var source = new EffectSource("attack", "test:attack", "attacker", ORIGIN, Set.of());
        var waiting = send(engine, engine.initial(armed(program).withSource(source)), 0, "test:attack",
                new EffectEvent("attacker", "target", ORIGIN, Set.of(), Map.of()));
        var command = (DamageCommand) waiting.actions().getFirst().command();
        assertEquals(Optional.of("test:weapon_damage"), command.scalingProfile()); assertEquals(16, command.amount());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
    @Test void unknownAndWrongUnitProfilesFailDuringCompilation() throws Exception {
        for (boolean outgoing : List.of(false, true)) {
            var data = json("combat_profiles");
            if (outgoing) data.getAsJsonArray("bundles").asList().getLast().getAsJsonObject().getAsJsonArray("rules")
                    .get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("scaling_profile", "test:missing");
            else data.addProperty("defense_profile", "test:missing");
            assertThrows(IllegalStateException.class, () -> compile(data));
        }
        var data = json("combat_profiles");
        data.getAsJsonArray("profiles").get(1).getAsJsonObject().addProperty("input_unit", "second");
        assertThrows(IllegalStateException.class, () -> compile(data));
        var bad = json("combat_profiles");
        bad.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").add(com.google.gson.JsonParser.parseString("""
                {"type":"chorus:curve","id":"wrong_output","output_unit":"second","curve":{"type":"chorus:polynomial","coefficients":[0,1],"minimum":0,"maximum":1000,"boundary":"clamp"}}
                """));
        var error = assertThrows(IllegalStateException.class, () -> compile(bad));
        assertTrue(error.getMessage().contains("Expected " + Unit.DAMAGE.id() + ", got " + Unit.SECOND.id()), error.getMessage());
    }
}
