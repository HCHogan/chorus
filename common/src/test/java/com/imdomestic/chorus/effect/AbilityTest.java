package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbilityTest {
    static final class Harness {
        final CompiledEffects program = load("abilities");
        final List<HealingCommand> heals = new ArrayList<>(); final List<Double> balancesAtWorld = new ArrayList<>();
        final EffectSession session; int sequence;
        Harness() throws Exception {
            session = new EffectSession(engine(program), EffectState.empty(), request -> {
                var heal = (HealingCommand) request.command(); heals.add(heal); balancesAtWorld.add(energy());
                return new HealingReceipt("heal/" + heals.size(), heal, HealingReceipt.Outcome.APPLIED, heal.amount(), heal.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        double energy() { return state().resources().get(new ResourceState.Key("player", "test:energy")).value(); }
        void choose(String id) { session.start(state().buffs().timeMicros(), new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY),
                id.isEmpty() ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of("test:grenade", "test:" + id))).signal()); }
        void source(String id, String bundle) { session.start(state().buffs().timeMicros(), SourceChange.bind(new EffectSource(id, bundle, "player", new BuffInstance.Origin("player", id, "", ""), Set.of()))); }
        AbilityUse.Request request(boolean sprint) {
            return new AbilityUse.Request("player", "test:grenade", Integer.toString(++sequence), new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of(), Map.of("sprinting", sprint), Map.of()));
        }
        AbilityUse.Receipt use(boolean sprint) {
            var request = request(sprint); var planned = program.useAbility(state(), request); session.start(state().buffs().timeMicros(), request.signal());
            assertTrue(session.state().idle()); return (AbilityUse.Receipt) planned.result();
        }
    }
    @Test void acceptancePaysBeforeWorldActionsAndInsufficientEnergyDoesNotEmitUse() throws Exception {
        var h = new Harness(); assertEquals(AbilityUse.Outcome.EMPTY_SLOT, h.use(false).outcome()); h.choose("pulse");
        assertEquals(AbilityUse.Outcome.ACCEPTED, h.use(false).outcome()); h.use(false); var before = h.state();
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use(false).outcome()); assertEquals(before, h.state());
        assertEquals(List.of(1.0, 0.0), h.balancesAtWorld); assertEquals(2, h.heals.size());
        assertEquals("test:pulse", h.heals.getFirst().source().ability()); assertEquals("player", h.heals.getFirst().source().owner());
        assertTrue(h.heals.getFirst().source().tags().contains("test:grenade")); assertNotEquals(h.heals.get(0).source().source(), h.heals.get(1).source().source());
    }
    @Test void selectionDoesNotRefillResourcesAndSurvivesClockChanges() throws Exception {
        var h = new Harness(); h.choose("pulse"); h.use(false); h.choose("strong"); assertEquals(1, h.energy());
        h.choose(""); h.choose("pulse"); assertEquals(1, h.energy()); h.session.observe(500_000, List.of());
        assertEquals(1.5, h.energy()); assertEquals("test:pulse", h.state().abilities().get("player").slots().get("test:grenade"));
        assertThrows(IllegalArgumentException.class, () -> new AbilityChange("player", h.state().abilities().get("player"), new AbilityLoadout(Map.of("test:wrong", "test:pulse"))).apply(h.state(), h.program));
        assertThrows(IllegalStateException.class, () -> new AbilityChange("player", AbilityLoadout.EMPTY, AbilityLoadout.EMPTY).apply(h.state(), h.program));
    }
    @Test void conditionalAndBuffReplacementsResolveInPriorityOrderWithoutOverwritingBaseSelection() throws Exception {
        var h = new Harness(); h.choose("pulse"); h.source("replace", "test:replacement"); h.source("control", "test:control");
        assertEquals("test:pulse", h.use(false).resolved()); assertEquals("test:strong", h.use(true).resolved());
        h.session.start(0, new RuleEngine.Signal("test:transform", new EffectEvent("player", "player", new BuffInstance.Origin("player", "", "", ""), Set.of(), Map.of())));
        var free = h.use(true); assertEquals("test:free", free.resolved()); assertEquals(0, free.cost().orElseThrow().receipt().paid());
        assertEquals("test:pulse", h.state().abilities().get("player").slots().get("test:grenade"));
        h.session.observe(200_000, List.of()); assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.use(true).outcome());
        assertEquals(List.of(4.0, 8.0, 2.0), h.heals.stream().map(HealingCommand::amount).toList());
    }
    @Test void samePriorityConflictAndFailedConditionsHaveNoPaymentOrEffects() throws Exception {
        var h = new Harness(); h.choose("pulse"); h.source("a", "test:replacement"); h.source("b", "test:conflict"); var before = h.state();
        assertEquals(AbilityUse.Outcome.CONFLICT, h.use(true).outcome()); assertEquals(before, h.state());
        h.choose("blocked"); before = h.state(); assertEquals(AbilityUse.Outcome.CONDITION, h.use(false).outcome()); assertEquals(before, h.state()); assertTrue(h.heals.isEmpty());
    }
    @Test void parametersAndCostUseProfilesAndDelayedActionsKeepTheAcceptedSnapshot() throws Exception {
        var h = new Harness(); h.choose("echo"); h.source("boost", "test:boost"); h.source("discount", "test:discount");
        var receipt = h.use(false); assertEquals(.5, receipt.cost().orElseThrow().receipt().paid()); assertEquals(1.5, h.energy());
        h.choose("strong"); h.session.start(0, SourceChange.remove("boost")); h.session.start(0, SourceChange.remove("discount"));
        h.session.observe(100_000, List.of()); assertEquals(8, h.heals.getFirst().amount()); assertEquals("test:echo", h.heals.getFirst().source().ability());
    }
    @Test void refundsShareThePaidBudgetWithinTheCastAndFreeCastsCannotMintEnergy() throws Exception {
        var h = new Harness(); h.choose("refund"); h.use(false); assertEquals(2, h.energy());
        h.source("discount", "test:discount"); h.use(false); assertEquals(2, h.energy());
        var free = new Harness(); free.choose("pulse"); free.use(false); free.use(false); free.choose("free_refund");
        var receipt = free.use(false); assertEquals(0, receipt.cost().orElseThrow().receipt().paid()); assertEquals(0, free.energy());
    }
    @Test void codecLinkingAndValidationCoverAbilityReferencesAndContinuationLifetimes() throws Exception {
        var p = load("abilities"); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
        var data = json("abilities"); var abilities = data.remove("abilities"); var fragment = new com.google.gson.JsonObject(); fragment.addProperty("version", "test-1"); fragment.add("abilities", abilities);
        var a = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow(); var b = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, fragment).getOrThrow();
        assertEquals(p.program().abilities(), CompiledEffects.link(List.of(a, b)).program().abilities()); assertThrows(IllegalArgumentException.class, () -> new CompiledEffects(a));
        data = json("abilities"); data.getAsJsonArray("abilities").get(4).getAsJsonObject().getAsJsonArray("on_use").get(0).getAsJsonObject().addProperty("lifetime", "source");
        var invalid = data; assertThrows(RuntimeException.class, () -> compile(invalid));
        data = json("abilities"); data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("cost").addProperty("profile", "test:potency");
        var wrongUnit = data; assertThrows(RuntimeException.class, () -> compile(wrongUnit));
    }
}
