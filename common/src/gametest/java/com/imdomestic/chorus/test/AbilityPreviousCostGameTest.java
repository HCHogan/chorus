package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;

/** Generic transformations with distinct base, predecessor and result; not a complete Heat Rises implementation. */
public class AbilityPreviousCostGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "ability_cost_previous", data -> {
            for (String id : List.of("test:projectile", "test:power")) { var bundle = new com.google.gson.JsonObject(); bundle.addProperty("id", id); data.getAsJsonArray("bundles").add(bundle); }
        }, true);
        t.owner.setGameMode(GameType.SURVIVAL); t.owner.setHealth(20); return t;
    }
    static void choose(ProjectileGameTest.Harness t, String id) { AscensionGameTest.choose(t, "test:grenade", "test:" + id); }
    static void bind(ProjectileGameTest.Harness t, String bundle) {
        String owner = t.owner.getUUID().toString(); t.runtime.bind(new EffectSource(bundle, "test:" + bundle, owner, new BuffInstance.Origin(owner, bundle, "", ""), Set.of()));
    }
    static AbilityUse.Receipt use(ProjectileGameTest.Harness t) { return t.runtime.useAbility(t.owner, "test:grenade"); }
    static double energy(ProjectileGameTest.Harness t, String name) { return AscensionGameTest.energy(t, "test:" + name); }
    @GameCase(environment = "chorus_gametest:previous_cost_chain", maxTicks = 60)
    public void actualWorldEffectsConsumeOnlyTheIntermediateAbilityWithFinalCredit(GameTestHelper h) {
        ready(h, () -> { try (var t = open(h)) {
            choose(t, "a"); bind(t, "chain"); bind(t, "identity"); var r = use(t);
            h.assertValueEqual(r.base(), "test:a", "base selection"); h.assertValueEqual(r.resolved(), "test:converted", "final ability"); near(h, r.cost().orElseThrow().receipt().paid(), .2, "intermediate declared amount");
            near(h, energy(t, "energy"), 2, "base pool untouched"); near(h, energy(t, "converted_energy"), 1.8, "intermediate payment"); near(h, t.owner.getHealth(), 28, "predecessor metadata chose native benefit");
            t.runtime.unbind("chain"); t.runtime.unbind("identity"); bind(t, "convert"); use(t);
            near(h, energy(t, "energy"), 1, "direct conversion now pays original ability"); near(h, t.owner.getHealth(), 32, "ordinary predecessor benefit"); ShieldThrowGameTest.settled(t); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:previous_cost_reject", maxTicks = 60)
    public void missingOrExhaustedPreviousCostNeverFallsBackToAnotherLivePool(GameTestHelper h) {
        ready(h, () -> { try (var t = open(h)) {
            choose(t, "converted"); h.assertValueEqual(use(t).outcome(), AbilityUse.Outcome.NO_BASE_COST, "direct conversion invented predecessor"); near(h, t.owner.getHealth(), 20, "rejected action healed");
            choose(t, "b"); bind(t, "convert"); for (int i = 0; i < 4; i++) h.assertValueEqual(use(t).outcome(), AbilityUse.Outcome.ACCEPTED, "paid conversion");
            h.assertValueEqual(use(t).outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "empty donor borrowed another pool"); near(h, energy(t, "energy"), 2, "unrelated pool spent"); near(h, t.owner.getHealth(), 36, "rejected cast executed world action"); ShieldThrowGameTest.settled(t); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:previous_cost_refund", maxTicks = 60)
    public void delayedNativeRefundKeepsItsPaidPoolAfterSelectionsAndOverridesChange(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h);
            try {
                choose(t, "a"); bind(t, "middle_only"); bind(t, "refund_override"); use(t); near(h, energy(t, "converted_energy"), 1.8, "payment committed");
                choose(t, "b"); t.runtime.unbind("middle_only"); t.runtime.unbind("refund_override");
                t.finish(4, () -> { near(h, energy(t, "converted_energy"), 2, "refund follows paid cost"); near(h, energy(t, "other"), 2, "refund redirected to new selection"); near(h, t.owner.getHealth(), 24, "refund replayed benefit"); });
            } catch (Exception | Error e) { t.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:previous_cost_unknown", maxTicks = 60)
    public void unknownNativeBenefitKeepsIntermediatePaymentAndActualHealthWithoutReplay(GameTestHelper h) {
        ready(h, () -> { try (var t = open(h)) {
            choose(t, "a"); bind(t, "chain"); t.failAfterHealing = true; boolean failed = false;
            try { use(t); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown native result did not stop"); near(h, energy(t, "converted_energy"), 1.8, "unknown result refunded cost"); near(h, energy(t, "energy"), 2, "wrong payment account");
            near(h, t.owner.getHealth(), 28, "native write lost"); int operations = t.operations.size(); t.runtime.prepare(); h.assertValueEqual(t.operations.size(), operations, "unknown action replayed"); near(h, t.owner.getHealth(), 28, "health write repeated"); h.succeed();
        } });
    }
}
