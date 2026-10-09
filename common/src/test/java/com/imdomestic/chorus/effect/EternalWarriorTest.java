package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class EternalWarriorTest {
    static final String SHIELD = "chorus_d2:eternal_warrior_shield";
    private static ShieldRecoveryTest.Harness harness(EffectState.Mode mode) throws Exception {
        var h = new ShieldRecoveryTest.Harness(load("eternal_warrior"), "chorus_d2:eternal_warrior", mode);
        h.send(0, "test:fists_start", Map.of()); return h;
    }
    private static Optional<BuffInstance> pool(ShieldRecoveryTest.Harness h) { return h.state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(SHIELD)).findFirst(); }
    private static double capacity(ShieldRecoveryTest.Harness h) { return pool(h).orElseThrow().components().numbers().get("capacity"); }
    private static void hit(ShieldRecoveryTest.Harness h, long time, double amount) {
        h.advance(time); var command = new DamageCommand("player", h.source.origin(), amount, "minecraft:generic", Set.of(), Set.of(), false);
        var plan = h.program.shields(h.state(), command, amount);
        var receipt = new DamageReceipt("damage/" + time, DamageReceipt.Outcome.APPLIED, plan.budget().shieldLoss(), 0, plan.budget().toVanilla(), Optional.empty(), false, Optional.empty(), plan.layers());
        h.session.observe(time, DamageFacts.from(command, receipt), plan.commit());
    }
    @Test void bothModesUseFiveSecondsWithoutDamageAndSevenSecondsForAFullPool() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = harness(mode); var before = pool(h).orElseThrow(); assertEquals(7.5, capacity(h));
            hit(h, 0, 3.75); h.advance(5_000_000); assertEquals(3.75, capacity(h)); assertTrue(h.restored().isEmpty());
            h.advance(5_020_001); assertEquals(3.75 + 7.5 / 7 * .020001, capacity(h), 1e-12);
            h.advance(8_500_001); assertEquals(7.5, capacity(h), 1e-10); assertEquals(before.generation(), pool(h).orElseThrow().generation());
            h.advance(12_000_000); assertEquals(7.5, capacity(h));
        }
    }
    @Test void laterDamageResetsDelayAndAnIntactLayerDoesNotBankSuppressedTime() throws Exception {
        var h = harness(EffectState.Mode.PVE); hit(h, 0, 1); hit(h, 4_900_000, 1);
        h.advance(9_900_000); assertEquals(5.5, capacity(h)); assertTrue(h.restored().isEmpty());
        h.advance(9_950_000); assertEquals(5.5 + 7.5 / 7 * .05, capacity(h), 1e-12);
        hit(h, 9_950_000, .5); h.advance(14_950_000); assertEquals(5 + 7.5 / 7 * .05, capacity(h), 1e-12);
    }
    @Test void breakEndAndDeathCancelRecoveryAndARecastStartsANewLayer() throws Exception {
        var h = harness(EffectState.Mode.PVE); var first = pool(h).orElseThrow(); hit(h, 0, 8);
        assertTrue(pool(h).isEmpty()); assertTrue(h.state().timers().isEmpty()); h.advance(6_000_000); assertTrue(pool(h).isEmpty());
        h.send(6_000_000, "test:fists_start", Map.of()); assertNotEquals(first.generation(), pool(h).orElseThrow().generation()); assertEquals(7.5, capacity(h));
        h.session.start(6_000_000, SourceChange.remove(h.source.instance())); h.send(6_000_000, "test:fists_end", Map.of()); assertTrue(pool(h).isEmpty());
        h.session.start(6_000_000, SourceChange.bind(h.source)); h.send(6_000_000, "test:fists_start", Map.of());
        h.session.start(6_000_000, new RuleEngine.Signal("chorus:death", new EffectEvent("attacker", "player", h.source.origin(), Set.of(), Map.of())));
        assertTrue(pool(h).isEmpty()); h.advance(20_000_000); assertTrue(pool(h).isEmpty());
    }
}
