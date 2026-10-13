package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityCrossSlotCostTest {
    static class Harness {
        final CompiledEffects p = load("ability_cross_slot_cost"); EffectSession session;
        final List<HealingCommand> heals = new ArrayList<>(); boolean failWorld; int cast; List<RuleEngine.Signal> facts = List.of();
        Harness() throws Exception { session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty(), this::world); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            var heal = (HealingCommand) request.command(); heals.add(heal);
            if (failWorld) throw new IllegalStateException("Unknown cross-slot healing result");
            return new HealingReceipt(request.id().toString(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void send(RuleEngine.Signal signal) { session.start(now(), signal); }
        void choose(String holder, String slot, String id) {
            var before = state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots());
            if (id == null) slots.remove("test:" + slot); else slots.put("test:" + slot, "test:" + id);
            send(new AbilityChange(holder, before, new AbilityLoadout(slots)).signal());
        }
        void choose(String slot, String id) { choose("player", slot, id); }
        void bind(String name) { send(SourceChange.bind(new EffectSource(name, "test:" + name, "player", new BuffInstance.Origin("player", name, "", ""), Set.of()))); }
        AbilityUse.Receipt use(boolean grounded) {
            var input = new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of(), Map.of("on_ground", grounded), Map.of("cost_slot", "test:forged", "cost_ability", "test:forged"));
            var request = new AbilityUse.Request("player", "test:air_move", "cross/" + ++cast, input);
            var plan = p.useAbility(state(), request); facts = plan.emitted(); send(request.signal()); return (AbilityUse.Receipt) plan.result();
        }
        double energy(String name) { return state().resources().get(new ResourceState.Key("player", "test:" + name)).value(); }
        void until(long time) { session.observe(time, List.of()); }
        void restore() { session = EffectSession.restore(p, EffectCheckpointTest.clock(), 1, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    @Test void separateInputSlotConsumesTheCurrentOtherSelectionWithoutCreatingAnotherPool() throws Exception {
        var h = new Harness(); h.choose("air_move", "air_plain"); assertTrue(h.state().resources().isEmpty()); h.choose("grenade", "a");
        var beforeKeys = h.state().resources().keySet(); var r = h.use(false);
        assertEquals(AbilityUse.Outcome.ACCEPTED, r.outcome()); assertEquals("test:air_plain", r.base()); assertEquals("test:air_move", r.slot());
        assertEquals(1, h.energy("energy")); assertEquals(1, r.cost().orElseThrow().receipt().paid()); assertEquals(beforeKeys, h.state().resources().keySet());
        var fact = (AbilityUse.Started) h.facts.stream().filter(f -> f.type().equals(AbilityUse.STARTED)).findFirst().orElseThrow().payload();
        assertEquals("test:grenade", fact.event().references().get("cost_slot")); assertEquals("test:a", fact.event().references().get("cost_ability"));
        assertEquals("test:air_plain", h.heals.getFirst().source().ability());
        h.choose("grenade", "b"); h.use(false); assertEquals(1.5, h.energy("other")); assertEquals(1, h.energy("energy"));
    }
    @Test void targetSlotReplacementIsIgnoredButTheCallingAbilitysFinalCostPolicyWins() throws Exception {
        var h = new Harness(); h.choose("grenade", "a"); h.choose("air_move", "air_plain"); h.bind("chain"); h.use(false);
        assertEquals(1, h.energy("energy")); assertEquals(2, h.energy("converted_energy"));
        h.choose("alternate", "alternate"); h.bind("air_override"); var r = h.use(false);
        assertEquals("test:air_other", r.resolved()); assertEquals(.2, r.cost().orElseThrow().receipt().paid()); assertEquals(1.8, h.energy("converted_energy"), 1e-12); assertEquals(1, h.energy("energy"));
    }
    @Test void inheritedCostProfileReadsTheCallingCastAndNeverChangesItsDamageCredit() throws Exception {
        var h = new Harness(); h.choose("grenade", "a"); h.choose("air_move", "air_plain"); h.bind("discount");
        assertEquals(.5, h.use(false).cost().orElseThrow().receipt().paid()); assertEquals(1.5, h.energy("energy"));
        assertEquals("test:air_plain", h.heals.getFirst().source().ability()); assertTrue(h.heals.getFirst().source().tags().contains("test:converted"));
    }
    @Test void missingOrCostlessSelectionDoesNotBecomeFreeAndPoliciesAreNeverFollowedRecursively() throws Exception {
        var h = new Harness(); h.choose("air_move", "air_missing"); var before = h.state();
        assertEquals(AbilityUse.Outcome.NO_BASE_COST, h.use(false).outcome()); assertEquals(before, h.state()); assertTrue(h.facts.isEmpty());
        for (String selection : List.of("free", "relay")) {
            h.choose("grenade", selection); before = h.state(); assertEquals(AbilityUse.Outcome.NO_BASE_COST, h.use(false).outcome()); assertEquals(before, h.state());
        }
        assertTrue(h.heals.isEmpty());
    }
    @Test void groundedAndInsufficientRequestsCannotSpendEmitStartOrRunEffects() throws Exception {
        var h = new Harness(); h.choose("grenade", "a"); h.choose("air_move", "air_plain"); var before = h.state();
        assertEquals(AbilityUse.Outcome.CONDITION, h.use(true).outcome()); assertEquals(before, h.state()); assertTrue(h.facts.isEmpty());
        h.use(false); h.use(false); before = h.state(); assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use(false).outcome());
        assertEquals(before, h.state()); assertTrue(h.facts.isEmpty()); assertEquals(2, h.heals.size());
    }
    @Test void retainedRefundKeepsThePaidAccountAfterBothSlotsChangeAndDomainRestore() throws Exception {
        var h = new Harness(); h.choose("grenade", "a"); h.choose("air_move", "air"); h.use(false); assertEquals(1, h.energy("energy"));
        h.choose("grenade", "b"); h.choose("air_move", null); h.restore(); h.until(100_000);
        assertEquals(2, h.energy("energy")); assertEquals(2, h.energy("other")); h.until(1_000_000); assertEquals(2, h.energy("energy"));
    }
    @Test void zeroDeclaredCostIsExplicitlyFreeAndCannotManufactureARefund() throws Exception {
        var h = new Harness(); h.choose("grenade", "a"); h.choose("air_move", "air_plain"); h.use(false); h.use(false);
        h.choose("grenade", "zero"); h.choose("air_move", "air"); var r = h.use(false); assertEquals(0, r.cost().orElseThrow().receipt().paid());
        h.until(100_000); assertEquals(0, h.energy("energy"));
    }
    @Test void otherHoldersSelectionAndUnknownWorldResultsCannotRedirectOrUndoThePayment() throws Exception {
        var h = new Harness(); h.choose("air_move", "air_plain"); h.choose("other", "grenade", "a"); assertEquals(AbilityUse.Outcome.NO_BASE_COST, h.use(false).outcome());
        h.choose("grenade", "b"); h.failWorld = true; assertThrows(IllegalStateException.class, () -> h.use(false));
        assertEquals(1.5, h.energy("other")); assertEquals(2, h.state().resources().get(new ResourceState.Key("other", "test:energy")).value());
        assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(1, h.heals.size());
    }
    @Test void codecAndLegacyDeclarationMigrationPreserveAllPriorFields() throws Exception {
        var h = new Harness(); var program = h.p.program();
        assertEquals(program, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program).getOrThrow()).getOrThrow());
        assertEquals(program, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(program), EffectProgram.class));
        var original = program.abilities().stream().filter(a -> a.id().equals("test:a")).findFirst().orElseThrow();
        var old = new com.imdomestic.chorus.effect.persistence.LegacyAbilityDefinitionV1(original.id(), original.slot(), original.cost(), original.condition(), original.tags(), original.parameters(), original.onUse(), original.effects(), original.costFrom(), original.rechargeResource(), original.rechargeYieldProfile());
        assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.encode(old));
        var encoded = EffectCheckpointCodec.DATA.encode(original).getAsJsonObject();
        encoded.addProperty("$", "chorus:effect/ability/ability_definition");
        encoded.getAsJsonObject("v").remove("costSlot");
        assertEquals(original, EffectCheckpointCodec.DATA.decode(encoded, AbilityDefinition.class));
        assertTrue(original.costSlot().isEmpty());
    }
    @Test void ambiguousSelfReferencingAndMalformedCostDeclarationsAreRejected() throws Exception {
        for (String fault : List.of("cost", "base_selection", "self", "id", "field")) {
            var d = json("ability_cross_slot_cost"); var a = d.getAsJsonArray("abilities").asList().stream().map(v -> v.getAsJsonObject()).filter(v -> v.get("id").getAsString().equals("test:air")).findFirst().orElseThrow();
            switch (fault) {
                case "cost" -> a.add("cost", d.getAsJsonArray("abilities").get(0).getAsJsonObject().get("cost"));
                case "base_selection" -> a.addProperty("cost_from", "base_selection");
                case "self" -> a.addProperty("cost_slot", "test:air_move");
                case "id" -> a.addProperty("cost_slot", "bad id");
                case "field" -> a.addProperty("cost_player", "other");
            }
            assertThrows(RuntimeException.class, () -> compile(d), fault);
        }
    }
}
