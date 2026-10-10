package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class EnergyGainGameTest {
    static void prepare(JsonObject data) {
        ThreadedSpikeGameTest.prepare(data);
        ThreadedSpikeGameTest.json("spike_energy_inputs").getAsJsonArray("bundles").forEach(b -> data.getAsJsonArray("bundles").add(b));
    }
    static void start(ProjectileGameTest.Harness t) {
        ThreadedSpikeGameTest.cast(t); t.projectiles.getFirst().discard();
        String owner = ThreadedSpikeGameTest.owner(t);
        t.runtime.bind(new EffectSource("energy-input", "test:spike_energy", owner, new BuffInstance.Origin(owner, "energy-input", "", ""), Set.of()));
    }
    static void send(ProjectileGameTest.Harness t, String type, String field, double value, Unit unit) {
        String owner = ThreadedSpikeGameTest.owner(t);
        t.runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent(owner, owner,
                new BuffInstance.Origin(owner, "energy-input", "", ""), Set.of(), Map.of(field, new Measure(value, unit)))));
    }
    static void stat(ProjectileGameTest.Harness t, double value) { send(t, "stat", "stat", value, new Unit("chorus:stat_point")); }
    @GameCase public void baseReferenceAndFixedGainsCreditTheActualAbilityAccountExactlyOnce(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "threaded_spike", EnergyGainGameTest::prepare, true)) {
            start(t); stat(t, 100);
            send(t, "base", "amount", .04, Unit.CHARGE); near(h, ThreadedSpikeGameTest.energy(t), .072, "zero-stat basis plus recipient and current stat");
            send(t, "reference", "amount", .072, Unit.CHARGE); near(h, ThreadedSpikeGameTest.energy(t), .144, "reference factors removed and applied once");
            send(t, "fixed", "amount", .20, Unit.CHARGE); near(h, ThreadedSpikeGameTest.energy(t), .344, "fixed return ignores both factors");
            h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "gain runtime settled");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:energy_stat_change", maxTicks = 45)
    public void realTickStatChangesSettleTheOldPassiveRateBeforeUsingTheNewCurve(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "threaded_spike", EnergyGainGameTest::prepare, true);
        try {
            start(t); long start = t.runtime.state().engine().domain().buffs().timeMicros(); long[] changed = {-1};
            h.runAfterDelay(10, () -> { try { stat(t, 100); changed[0] = t.runtime.state().engine().domain().buffs().timeMicros(); } catch (Throwable e) { t.close(); throw e; } });
            t.finish(30, () -> {
                long end = t.runtime.state().engine().domain().buffs().timeMicros();
                h.assertTrue(changed[0] > start && changed[0] < end, "stat changed during integration");
                near(h, ThreadedSpikeGameTest.energy(t), ((changed[0] - start) + (end - changed[0]) * 2.75) / 1_000_000.0 / 145.2, "old and new rates integrated separately");
                double before = ThreadedSpikeGameTest.energy(t); send(t, "base", "amount", .04, Unit.CHARGE);
                near(h, ThreadedSpikeGameTest.energy(t) - before, .072, "stat change does not alter the independent 0.8 scalar");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void unknownLaterWorldResultKeepsTheAlreadyCreditedGain(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "threaded_spike", data -> {
            prepare(data);
            var rules = data.getAsJsonArray("bundles").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(b -> b.get("id").getAsString().equals("test:spike_energy")).findFirst().orElseThrow().getAsJsonArray("rules");
            rules.get(1).getAsJsonObject().getAsJsonArray("do").add(JsonParser.parseString("""
                {"type":"chorus:heal","target":"self","amount":{"type":"chorus:constant","value":1,"unit":"damage"}}
                """));
        }, true)) {
            start(t); t.failAfterHealing = true;
            try { send(t, "base", "amount", .04, Unit.CHARGE); throw new AssertionError("Expected an unknown world outcome"); }
            catch (IllegalStateException expected) { h.assertTrue(expected.getMessage().contains("Injected unknown projectile healing outcome"), "unexpected failure: " + expected); }
            h.assertTrue(t.runtime.failure().isPresent(), "later unknown world result stops the sequence");
            near(h, ThreadedSpikeGameTest.energy(t), .032, "confirmed energy remains committed");
            near(h, t.owner.getHealth(), 11, "actual world heal also remains");
            h.assertValueEqual(t.cues.size(), 1, "one sequence, no replay");
        }
        h.succeed();
    }
}
