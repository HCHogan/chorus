package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FractionalHealingTest {
    private static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("giver", "source", "weapon", "");
    private static HealingCommand fraction(double value) { return new HealingCommand("target", ORIGIN, value, Set.of(), HealingCommand.Basis.MAX_HEALTH); }
    private static Recovery.Offer offer(String id, double rate, HealingCommand.Basis basis, int priority) {
        return new Recovery.Offer(id, "target", "test:shared", priority, rate, new BuffInstance.Origin(id, id, "", ""), Set.of("test:" + id), basis);
    }
    @Test void requestedHealthRequiresEvidenceAndRemainsIndependentOfOfferedActualAndOverflow() {
        var r = new HealingReceipt("h", fraction(.2), HealingReceipt.Outcome.APPLIED, 30, 5, 25, OptionalDouble.of(100));
        assertEquals(20, r.requested()); assertEquals(30, r.offered()); assertEquals(5, r.effective());
        assertEquals(20, ResultShape.HEALING.read("requested", r).value()); assertEquals(100, ResultShape.HEALING.read("maximum_health", r).value());
        var facts = HealingFacts.from(r); assertEquals(3, facts.size());
        assertEquals(20, ((EffectEvent) facts.getFirst().payload()).numbers().get("requested").value());
        var missing = HealingReceipt.unapplied("missing", fraction(.2), HealingReceipt.Outcome.MISSING);
        assertFalse(missing.amountResolved()); assertThrows(IllegalArgumentException.class, missing::requested);
        assertFalse(ResultShape.HEALING.flag("observed_maximum", missing)); assertTrue(HealingFacts.from(missing).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HealingReceipt("bad", fraction(.2), HealingReceipt.Outcome.APPLIED, 2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> fraction(.2).basis().resolve(.2, -1));
        assertThrows(IllegalArgumentException.class, () -> fraction(.2).basis().resolve(Double.MAX_VALUE, 100));
    }
    @Test void jsonActionsAndRecoveryHaveExplicitIndependentUnitsAndRoundTrip() throws Exception {
        var p = load("fractional_healing"); var source = source("test:inputs"); var engine = engine(p);
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:heal",
                new EffectEvent(source.holder(), "target", source.origin(), Set.of(), Map.of("fraction", new Measure(.2, Unit.HEALTH_FRACTION))));
        var command = (HealingCommand) waiting.actions().getFirst().command();
        assertEquals(.2, command.amount()); assertEquals(HealingCommand.Basis.MAX_HEALTH, command.basis());
        var receipt = new HealingReceipt("fraction", command, HealingReceipt.Outcome.APPLIED, 20, 20, 0, OptionalDouble.of(100));
        var done = complete(engine, waiting, receipt);
        assertEquals(done.state(), engine.transition(done.state(), new RuleEngine.Completed(waiting.actions().getFirst().id(), receipt)).state());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var wrongAction = json("fractional_healing");
        wrongAction.getAsJsonArray("bundles").asList().getLast().getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("action").addProperty("basis", "absolute");
        assertThrows(IllegalStateException.class, () -> compile(wrongAction));
        var wrong = json("fractional_healing"); wrong.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("health_recovery").get(0).getAsJsonObject().addProperty("basis", "absolute");
        assertThrows(IllegalStateException.class, () -> compile(wrong));
        var wrongProfile = json("fractional_healing"); wrongProfile.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("input_unit", "damage_per_second");
        assertThrows(IllegalStateException.class, () -> compile(wrongProfile));
    }
    @Test void sameUnitRecoveryIsSelectedPurelyButMixedUnitsWaitForCurrentMaximumHealth() {
        var fixed = offer("fixed", 10, HealingCommand.Basis.ABSOLUTE, 0);
        var fractional = offer("fractional", .2, HealingCommand.Basis.MAX_HEALTH, 0);
        var group = Recovery.integrate(List.of(fixed, fractional), 0, 50_000, List.of());
        var choice = (Recovery.Selection) group.allocations().getFirst().command();
        assertEquals(fixed, choice.winner(40)); assertEquals(fractional, choice.winner(100));
        assertEquals(fixed, choice.winner(50)); // Equal resolved rates use stable source identity.
        assertTrue(group.suppressed().isEmpty());
        var higher = offer("higher", .3, HealingCommand.Basis.MAX_HEALTH, 0);
        var same = Recovery.integrate(List.of(fractional, higher), 0, 70_001, List.of());
        assertEquals(List.of(fractional), same.suppressed());
        var command = (HealingCommand) same.allocations().getFirst().command();
        assertEquals(.3 * .070001, command.amount(), 1e-12); assertEquals(HealingCommand.Basis.MAX_HEALTH, command.basis());
        assertEquals(higher.source(), command.source());
    }
    @Test void priorityOverridesIncomparableRatesAndOtherTargetsOrChannelsStayIndependent() {
        var high = offer("high", .01, HealingCommand.Basis.MAX_HEALTH, 1);
        var low = offer("low", 100, HealingCommand.Basis.ABSOLUTE, 0);
        var otherTarget = new Recovery.Offer("other", "other", "test:shared", 0, .2, ORIGIN, Set.of(), HealingCommand.Basis.MAX_HEALTH);
        var otherChannel = new Recovery.Offer("independent", "target", "test:independent", 0, 1, ORIGIN, Set.of());
        var group = Recovery.integrate(List.of(high, low, otherTarget, otherChannel), 0, 50_000, List.of());
        assertEquals(3, group.allocations().size()); assertEquals(List.of(low), group.suppressed());
        assertTrue(group.allocations().stream().noneMatch(a -> a.command() instanceof Recovery.Selection));
    }
    @Test void mixedSelectionReceiptCannotChangeTheChosenSourceOrObservedMaximum() {
        var fixed = offer("fixed", 10, HealingCommand.Basis.ABSOLUTE, 0); var fractional = offer("fractional", .2, HealingCommand.Basis.MAX_HEALTH, 0);
        var choice = (Recovery.Selection) Recovery.integrate(List.of(fixed, fractional), 0, 50_000, List.of()).allocations().getFirst().command();
        var receipt = new HealingReceipt("h", choice.command(fractional), HealingReceipt.Outcome.APPLIED, 1, 1, 0, OptionalDouble.of(100));
        assertDoesNotThrow(() -> new Recovery.SelectionReceipt(choice, receipt.outcome(), Optional.of(new Recovery.Selected(fractional, 100, receipt))));
        assertThrows(IllegalArgumentException.class, () -> new Recovery.SelectionReceipt(choice, receipt.outcome(), Optional.of(new Recovery.Selected(fixed, 100, receipt))));
        assertThrows(IllegalArgumentException.class, () -> new Recovery.SelectionReceipt(choice, receipt.outcome(), Optional.of(new Recovery.Selected(fractional, 101, receipt))));
        assertThrows(IllegalArgumentException.class, () -> new Recovery.SelectionReceipt(choice, receipt.outcome(), Optional.empty()));
        assertDoesNotThrow(() -> new Recovery.SelectionReceipt(choice, HealingReceipt.Outcome.MISSING, Optional.empty()));
    }
    @Test void rateProfileIsEvaluatedInFractionUnitsBeforeIntegration() throws Exception {
        var data = json("fractional_healing");
        data.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").add(com.google.gson.JsonParser.parseString("""
                {"type":"chorus:curve","id":"double","output_unit":"health_fraction_per_second","curve":{"type":"chorus:polynomial","coefficients":[0,2],"minimum":0,"maximum":1,"boundary":"clamp"}}
                """));
        var p = compile(data); var store = Buffs.grant(BuffStore.empty(), p.buff("test:fraction"), "target", "target", ORIGIN, 1, 1, 70_001).store();
        var offers = p.recoveryOffers(EffectState.empty().withBuffs(store));
        assertEquals(.4, offers.getFirst().perSecond());
        var command = (HealingCommand) Recovery.integrate(offers, 0, 70_001, List.of()).allocations().getFirst().command();
        assertEquals(.4 * .070001, command.amount(), 1e-12);
    }
}
