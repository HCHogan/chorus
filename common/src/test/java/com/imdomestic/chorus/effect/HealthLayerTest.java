package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.EntityObservation;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HealthLayerTest {
    private static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("attacker", "test-source", "weapon", "");
    private static DamageCommand hit() {
        return new DamageCommand("target", ORIGIN, 40, "minecraft:mob_attack", Set.of(), Set.of(), false, Optional.of("test:outgoing"));
    }
    private static EffectState grant(CompiledEffects p, EffectState state, String id, String holder) {
        return state.withBuffs(Buffs.grant(state.buffs(), p.buff(id), holder, holder, ORIGIN, 1, 1, BuffDefinition.FOREVER).store());
    }
    @Test void outgoingGlobalShieldsAndHealthUseSeparateBudgetsAndTraces() throws Exception {
        var p = load("health_layer");
        var state = EffectState.empty().withSource(new EffectSource("test-source", "test:attack", "attacker", ORIGIN, Set.of()));
        for (var id : List.of("test:global", "test:health", "test:shield")) state = grant(p, state, id, "target");
        var outgoing = p.outgoing(state, hit(), 40).orElseThrow();
        var global = p.defense(state, hit(), outgoing.output().value()).orElseThrow();
        var shield = p.shields(state, hit(), global.output().value());
        assertEquals(80, outgoing.output().value()); assertEquals(40, global.output().value());
        assertEquals(10, shield.budget().shieldLoss()); assertEquals(30, shield.budget().toVanilla());
        // The host next applies armor and absorption; only its resulting 20 HP budget is passed here.
        var health = p.health(state, hit(), 20).orElseThrow();
        assertEquals(5, health.output().value()); assertEquals(20, health.trace().stages().get("base").value());
        assertTrue(health.trace().contributions().stream().allMatch(c -> c.contribution().source().definition().equals("test:health_active")));
        assertEquals(10, state.buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:shield")).findFirst().orElseThrow().components().numbers().get("capacity"));
    }
    @Test void currentRecipientOwnsTheHealthModifiersEvenWithoutAnAttackProfile() throws Exception {
        var p = load("health_layer");
        var wrong = grant(p, EffectState.empty(), "test:health", "attacker");
        assertEquals(20, p.health(wrong, hit(), 20).orElseThrow().output().value());
        var right = grant(p, wrong, "test:health", "target");
        var nativeHit = new DamageCommand("target", ORIGIN, 40, "minecraft:mob_attack", Set.of(), Set.of(), false);
        assertEquals(5, p.health(right, nativeHit, 20).orElseThrow().output().value());
        var key = right.buffs().instances().keySet().stream().filter(k -> k.holder().equals("target")).findFirst().orElseThrow();
        right = right.withBuffs(Buffs.remove(right.buffs(), key, Buffs.Reason.REMOVED).store());
        assertEquals(20, p.health(right, nativeHit, 20).orElseThrow().output().value());
    }
    @Test void noBudgetMeansNoQueryEvenWithAFlatHealthProfile() throws Exception {
        var data = json("health_layer");
        data.getAsJsonArray("profiles").get(2).getAsJsonObject().getAsJsonArray("steps").add(JsonParser.parseString("""
                {"type":"chorus:curve","id":"flat","output_unit":"damage","curve":{"type":"chorus:polynomial","coefficients":[7],"minimum":0,"maximum":1000,"boundary":"clamp"}}
                """));
        var p = compile(data);
        assertTrue(p.health(EffectState.empty(), hit(), 0).isEmpty());
        assertEquals(7, p.health(EffectState.empty(), hit(), 1).orElseThrow().output().value());
        assertThrows(IllegalArgumentException.class, () -> p.health(EffectState.empty(), hit(), -1));
        assertThrows(IllegalArgumentException.class, () -> p.health(EffectState.empty(), hit(), Double.NaN));
        assertTrue(new CompiledEffects(new EffectProgram("test-1", List.of(), List.of(), List.of())).health(EffectState.empty(), hit(), 20).isEmpty());
    }
    @Test void fieldRoundTripsAndLinksAcrossFragmentsButRejectsDuplicateSelection() throws Exception {
        var data = json("health_layer");
        var selector = JsonParser.parseString("{\"version\":\"test-1\",\"health_profile\":\"test:health\"}");
        data.remove("health_profile");
        var definitions = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow();
        var selection = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, selector).getOrThrow();
        var p = CompiledEffects.link(List.of(definitions, selection));
        assertEquals(Optional.of("test:health"), p.program().healthProfile());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        assertThrows(IllegalArgumentException.class, () -> CompiledEffects.link(List.of(definitions, selection, selection)));
    }
    @Test void unknownProfilesWrongUnitsAndNegativeOutputsAreRejected() throws Exception {
        var missing = json("health_layer"); missing.addProperty("health_profile", "test:missing");
        assertThrows(IllegalStateException.class, () -> compile(missing));
        var wrong = json("health_layer"); wrong.getAsJsonArray("profiles").get(2).getAsJsonObject().addProperty("input_unit", "second");
        assertThrows(IllegalStateException.class, () -> compile(wrong));
        var negative = json("health_layer");
        negative.getAsJsonArray("profiles").get(2).getAsJsonObject().getAsJsonArray("steps").add(JsonParser.parseString("""
                {"type":"chorus:curve","id":"negative","output_unit":"damage","curve":{"type":"chorus:polynomial","coefficients":[-1],"minimum":0,"maximum":1000,"boundary":"clamp"}}
                """));
        var p = compile(negative);
        assertThrows(IllegalArgumentException.class, () -> p.health(EffectState.empty(), hit(), 1));
    }
    @Test void receiptTraceSurvivesAllObservationAndConsumptionCopies() throws Exception {
        var p = load("health_layer"); var trace = p.health(grant(p, EffectState.empty(), "test:health", "target"), hit(), 20);
        var receipt = new DamageReceipt("damage", DamageReceipt.Outcome.APPLIED, 0, 0, 5, Optional.empty(), false,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), trace);
        var copied = receipt.withObservedEntities(new EntityObservation(0, Map.of()))
                .withObservedBuffs(BuffObservation.capture(BuffStore.empty(), "target")).withConsumptionFacts(List.of());
        assertEquals(trace, copied.health()); assertEquals(5, copied.healthLoss());
        var invalid = new CalculationProfile("test:invalid", "test-1", Unit.SECOND, List.of()).calculate(new Measure(2, Unit.SECOND), List.of());
        assertThrows(IllegalArgumentException.class, () -> new DamageReceipt("damage", DamageReceipt.Outcome.APPLIED, 0, 0, 5, Optional.empty(), false,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(invalid)));
    }
}
