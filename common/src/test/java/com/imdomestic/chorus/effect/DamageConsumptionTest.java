package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DamageConsumptionTest {
    static final EffectSource SOURCE = source("test:control");
    static final class Harness {
        final CompiledEffects program; final EffectSession session; final List<Double> amounts = new ArrayList<>();
        final List<DamageCommand> commands = new ArrayList<>();
        DamageReceipt.Outcome first = DamageReceipt.Outcome.APPLIED; boolean unknown;
        Harness() throws Exception { this(load("damage_consumption")); }
        Harness(CompiledEffects p) {
            program = p; session = new EffectSession(engine(p), EffectState.empty().withSource(SOURCE), request -> {
                var command = (DamageCommand) request.command(); commands.add(command);
                double amount = p.outgoing(state(), command, command.amount()).orElseThrow().output().value(); amounts.add(amount);
                if (unknown) throw new IllegalStateException("damage applied but receipt unknown");
                var outcome = amounts.size() == 1 ? first : DamageReceipt.Outcome.APPLIED;
                return new DamageReceipt(request.id().toString(), outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? amount : 0, Optional.empty(), false);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void run(String event) { session.start(0, new RuleEngine.Signal("test:" + event, event(SOURCE))); }
    }
    @Test void consumptionPrecedesTheNextInstructionEvenWhileHitFactsWaitInTheBreadthFirstQueue() throws Exception {
        for (String event : List.of("double", "snapshot")) {
            var h = new Harness(); h.run("arm"); h.run(event);
            assertEquals(List.of(25.0, 10.0), h.amounts); assertEquals(1, h.commands.getFirst().consumptions().size());
            assertTrue(h.commands.getLast().consumptions().isEmpty()); assertTrue(h.state().buffs().instances().isEmpty());
        }
    }
    @Test void cancelledFailedAndEffectiveOnlyPolicyRetainTheBuffForTheNextEligibleReceipt() throws Exception {
        for (var outcome : List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED)) {
            var h = new Harness(); h.first = outcome; h.run("arm"); h.run("double"); assertEquals(List.of(25.0, 25.0), h.amounts);
        }
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED)) {
            var h = new Harness(); h.first = outcome; h.run("arm"); h.run("double"); assertEquals(List.of(25.0, 10.0), h.amounts);
            var data = json("damage_consumption"); data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("consume_on_damage").addProperty("when", "effective_damage");
            var effective = new Harness(compile(data)); effective.first = outcome; effective.run("arm"); effective.run("double"); assertEquals(List.of(25.0, 25.0), effective.amounts);
        }
    }
    @Test void unknownWorldOutcomeKeepsEligibilityAndPendingOperationWithoutConsumingOrReplaying() throws Exception {
        var h = new Harness(); h.run("arm"); h.unknown = true; assertThrows(IllegalStateException.class, () -> h.run("double"));
        assertEquals(List.of(25.0), h.amounts); assertEquals(1, h.state().buffs().instances().size()); assertEquals(1, h.commands.getFirst().consumptions().size());
        assertTrue(h.session.state().engine().pending().isPresent()); assertThrows(IllegalStateException.class, () -> h.run("double")); assertEquals(1, h.commands.size());
    }
    @Test void oldPreparedGenerationCannotConsumeANewApplicationAndQueriesNeverConsume() throws Exception {
        var h = new Harness(); h.run("arm");
        var command = new DamageCommand("target", SOURCE.origin(), 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false, Optional.of("test:melee"));
        var prepared = h.program.prepareDamage(h.state(), command); var old = h.state();
        h.program.outgoing(old, prepared, 10); h.program.outgoing(old, prepared, 10); assertEquals(old, h.state());
        var receipt = new DamageReceipt("old", DamageReceipt.Outcome.APPLIED, 0, 0, 25, Optional.empty(), false);
        var consumed = BuffConsumption.finish(old, prepared, receipt).state();
        var temporary = new EffectSession(engine(h.program), consumed, _ -> { throw new AssertionError(); });
        temporary.start(0, new RuleEngine.Signal("test:arm", event(SOURCE))); var reapplied = temporary.state().engine().domain();
        assertNotEquals(prepared.consumptions().getFirst().generation(), reapplied.buffs().instances().values().iterator().next().generation());
        assertEquals(reapplied, BuffConsumption.finish(reapplied, prepared, receipt).state());
        assertTrue(h.program.prepareDamage(old, new DamageCommand("target", SOURCE.origin(), 10, "minecraft:generic", Set.of(), Set.of(), false)).consumptions().isEmpty());
    }
    @Test void codecsRequireLiveModifiersAndRejectInvalidPolicies() throws Exception {
        var p = load("damage_consumption").program(); assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        var frozen = json("damage_consumption"); frozen.getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().remove("evaluate");
        assertThrows(RuntimeException.class, () -> compile(frozen));
        var zero = json("damage_consumption"); zero.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("consume_on_damage").addProperty("stacks", 0);
        assertThrows(RuntimeException.class, () -> compile(zero));
    }
}
