package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** The cycle values and healing body are synthetic; actual server ability costs and native health are exercised. */
public class LinkedRechargeGameTest {
    static BleakWatcherGameTest.Harness open(GameTestHelper h) {
        var p = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("linked_recharge")).getOrThrow();
        var t = new BleakWatcherGameTest.Harness(h, p); t.owner.setHealth(10);
        String owner = BleakWatcherGameTest.id(t.owner);
        t.runtime.bind(new EffectSource("input", "test:linked_inputs", owner, new BuffInstance.Origin(owner, "input", "", ""), Set.of()));
        t.runtime.abilities(new AbilityChange(owner, AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("chorus_d2:melee", "test:linked_ability"))));
        return t;
    }
    static double value(BleakWatcherGameTest.Harness t, String resource) {
        return t.state().resources().get(new ResourceState.Key(BleakWatcherGameTest.id(t.owner), "test:" + resource)).value();
    }
    static void send(BleakWatcherGameTest.Harness t, String event, double amount) {
        String owner = BleakWatcherGameTest.id(t.owner);
        t.runtime.start(new RuleEngine.Signal("test:" + event, new EffectEvent(owner, owner,
                new BuffInstance.Origin(owner, "input", "", ""), Set.of(), Map.of("amount", new Measure(amount, Unit.CHARGE)))));
    }
    static void use(GameTestHelper h, BleakWatcherGameTest.Harness t, AbilityUse.Outcome outcome) {
        h.assertValueEqual(t.runtime.useAbility(t.owner, "chorus_d2:melee").outcome(), outcome, "linked ability outcome");
    }
    @GameCase public void actualAbilityCannotSpendPartialCycleAndAFullGrantRestoresBothUses(GameTestHelper h) {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED);
            send(t, "base", 1); near(h, value(t, "progress"), .5, "cycle CES applied incorrectly");
            use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY); near(h, t.owner.getHealth(), 12, "rejected cast reached native body");
            send(t, "grant", .5); near(h, value(t, "charges"), 2, "linked uses not restored"); near(h, value(t, "progress"), 0, "completed meter retained energy");
            near(h, t.owner.getHealth(), 14, "actual completion observation"); send(t, "complete", 0); near(h, t.owner.getHealth(), 14, "cycle repeated");
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY);
            near(h, t.owner.getHealth(), 16, "restored uses did not reach native bodies"); t.healthy();
        }
        h.succeed();
    }
    @GameCase(environment="chorus_gametest:linked_recharge_ticks", maxTicks=55)
    public void realTicksRestoreBothUsesAtOneCycleBoundaryDespiteASecondUseMidway(GameTestHelper h) {
        var t = open(h);
        try {
            use(h, t, AbilityUse.Outcome.ACCEPTED); long started = t.runtime.nowMicros();
            t.at(10, () -> {
                near(h, value(t, "progress"), (t.runtime.nowMicros() - started) / 2_000_000.0, "shared cycle before second cast");
                use(h, t, AbilityUse.Outcome.ACCEPTED);
            });
            t.at(20, () -> {
                near(h, value(t, "charges"), 0, "a use completed half a cycle early");
                near(h, value(t, "progress"), (t.runtime.nowMicros() - started) / 2_000_000.0, "second use restarted shared progress");
                use(h, t, AbilityUse.Outcome.INSUFFICIENT_ENERGY);
            });
            t.finish(42, () -> {
                near(h, value(t, "charges"), 2, "full cycle did not restore both uses");
                near(h, value(t, "progress"), 0, "full account kept regenerating"); near(h, t.owner.getHealth(), 14, "cycle healed twice or failed to reach native health");
            });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase public void unknownNativeObservationRetainsBothAccountWritesAndDoesNotReplay(GameTestHelper h) {
        try (var t = open(h)) {
            use(h, t, AbilityUse.Outcome.ACCEPTED); use(h, t, AbilityUse.Outcome.ACCEPTED); t.failGainHealing = true;
            try { send(t, "grant", 1); } catch (IllegalStateException expected) { /* both account writes precede world observation */ }
            h.assertTrue(t.runtime.failure().isPresent(), "unknown completion observation did not stop runtime");
            near(h, value(t, "charges"), 2, "restored uses rolled back"); near(h, value(t, "progress"), 0, "cycle consumption rolled back");
            near(h, t.owner.getHealth(), 14, "native completion heal lost"); t.runtime.prepare(); near(h, t.owner.getHealth(), 14, "native completion replayed");
        }
        h.succeed();
    }
}
