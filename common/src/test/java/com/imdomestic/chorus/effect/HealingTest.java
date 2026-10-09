package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HealingTest {
    private static HealingCommand request(double amount) {
        return new HealingCommand("player", source("test:healing").origin(), amount, Set.of("test:healing"));
    }
    private static EffectEvent input(EffectSource source, double amount) {
        return new EffectEvent(source.holder(), "target", source.origin(), Set.of(), Map.of("amount", new Measure(amount, Unit.DAMAGE)));
    }
    @Test void quantitiesDoNotConfuseLoaderReductionWithOverheal() {
        var reduced = new HealingReceipt("heal-1", request(8), HealingReceipt.Outcome.APPLIED, 2, 2, 0);
        assertEquals(8, ResultShape.HEALING.read("requested", reduced).value());
        assertEquals(2, ResultShape.HEALING.read("offered", reduced).value());
        assertEquals(List.of("chorus:heal", "chorus:health_restored"), HealingFacts.from(reduced).stream().map(Signal::type).toList());
        var capped = new HealingReceipt("heal-2", request(8), HealingReceipt.Outcome.APPLIED, 8, 2, 6);
        assertEquals(List.of("chorus:heal", "chorus:health_restored", "chorus:overheal"), HealingFacts.from(capped).stream().map(Signal::type).toList());
        var full = new HealingReceipt("heal-3", request(8), HealingReceipt.Outcome.APPLIED, 8, 0, 8);
        assertTrue(ResultShape.HEALING.flag("applied", full)); assertFalse(ResultShape.HEALING.flag("changed", full));
        assertEquals(List.of("chorus:heal", "chorus:overheal"), HealingFacts.from(full).stream().map(Signal::type).toList());
        for (var outcome : List.of(HealingReceipt.Outcome.REJECTED, HealingReceipt.Outcome.DEAD, HealingReceipt.Outcome.MISSING)) {
            assertTrue(HealingFacts.from(HealingReceipt.unapplied("rejected", request(8), outcome)).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> new HealingReceipt("bad", request(8), outcome, 8, 0, 8));
        }
    }
    @Test void jsonResultsAndFactsResumeOnceAfterWorldReceipt() throws Exception {
        var program = load("healing"); var engine = engine(program); var source = source("test:healing");
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:heal", input(source, 8));
        var command = (HealingCommand) waiting.actions().getFirst().command();
        assertEquals(request(8), command);
        var result = new HealingReceipt("test-heal", command, HealingReceipt.Outcome.APPLIED, 8, 2, 6);
        var done = complete(engine, waiting, result); var meter = buff(done.state(), "test:healing_meter", "player");
        assertEquals(2, meter.components().numbers().get("effective")); assertEquals(6, meter.components().numbers().get("overheal"));
        assertEquals(2, meter.components().numbers().get("observed"));
        assertEquals(done.state(), engine.transition(done.state(), new Completed(waiting.actions().getFirst().id(), result)).state());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
    @Test void damageResultFeedsHealingInsteadOfRequestedDamage() throws Exception {
        var program = load("healing"); var engine = engine(program); var source = source("test:healing");
        var damage = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:drain", input(source, 0));
        assertEquals(10, ((DamageCommand) damage.actions().getFirst().command()).amount());
        var healing = complete(engine, damage, new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, 5, 3, 1, Optional.empty(), false));
        assertEquals(1, ((HealingCommand) healing.actions().getFirst().command()).amount());
        var done = complete(engine, healing, new HealingReceipt("heal", (HealingCommand) healing.actions().getFirst().command(), HealingReceipt.Outcome.APPLIED, 1, 1, 0));
        assertEquals(1, buff(done.state(), "test:healing_meter", "player").components().numbers().get("effective"));
    }
    @Test void wrongTargetAndInvalidHealingUnitsAreRejected() throws Exception {
        var program = load("healing"); var engine = engine(program); var source = source("test:healing");
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:heal", input(source, 8));
        var wrong = new HealingReceipt("wrong", new HealingCommand("other", source.origin(), 8, Set.of("test:healing")), HealingReceipt.Outcome.APPLIED, 8, 2, 6);
        var result = engine.transition(waiting.state(), new Completed(waiting.actions().getFirst().id(), wrong));
        assertTrue(result.state().engine().failure().isPresent());
        var json = json("healing");
        json.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do")
                .get(1).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("amount").addProperty("unit", "second");
        assertThrows(IllegalStateException.class, () -> compile(json));
        assertThrows(IllegalArgumentException.class, () -> request(-1));
        assertThrows(IllegalArgumentException.class, () -> new HealingReceipt("bad", request(8), HealingReceipt.Outcome.APPLIED, 2, 0, 8));
    }
    @Test void completedHealingDoesNotReevaluateAmountAfterCommittedShieldChanges() throws Exception {
        var data = json("healing");
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do")
                .get(1).getAsJsonObject().getAsJsonObject("action").add("amount", com.google.gson.JsonParser.parseString("""
                {"type":"chorus:component","buff":"test:healing_meter","component":"effective"}
                """));
        var program = compile(data); var source = source("test:healing"); var engine = engine(program);
        var store = Buffs.grant(BuffStore.empty(), program.buff("test:healing_meter"), "player", "player", source.origin(), 1, 1, BuffDefinition.FOREVER).store();
        var before = store.instances().values().iterator().next();
        store = Buffs.components(store, before.key(), before.components().number("effective", BuffComponents.Update.SET, 8));
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source).withBuffs(store)), 0, "test:heal", input(source, 0));
        before = buff(waiting.state(), "test:healing_meter", "player");
        var command = (HealingCommand) waiting.actions().getFirst().command(); assertEquals(8, command.amount());
        var writes = new ShieldDamage.Commit(List.of(new ShieldDamage.Write(before, "effective", 1)));
        var done = complete(engine, waiting, new WorldReceipt(new HealingReceipt("captured", command, HealingReceipt.Outcome.APPLIED, 8, 2, 6), List.of(), writes));
        assertEquals(2, buff(done.state(), "test:healing_meter", "player").components().numbers().get("effective"));
    }
    @Test void healingReceiptMustMatchIssuedAmountButMayReportADifferentOfferedAmount() throws Exception {
        var program = load("healing"); var engine = engine(program); var source = source("test:healing");
        var waiting = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "test:heal", input(source, 8));
        var op = waiting.actions().getFirst().id();
        var mismatched = new HealingReceipt("wrong", request(2), HealingReceipt.Outcome.APPLIED, 2, 2, 0);
        var failed = engine.transition(waiting.state(), new Completed(op, mismatched));
        assertTrue(failed.state().engine().failure().isPresent());
        var actual = new HealingReceipt("reduced", request(8), HealingReceipt.Outcome.APPLIED, 2, 2, 0);
        var done = complete(engine, waiting, actual);
        assertTrue(done.state().idle()); assertEquals(2, buff(done.state(), "test:healing_meter", "player").components().numbers().get("observed"));
    }
}
