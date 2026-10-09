package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

public class RecoveryGameTest {
    private static CompiledEffects program() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(RecoveryGameTest.class.getResourceAsStream("/effects/restoration.json")), StandardCharsets.UTF_8)) {
            return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }
    private static final class Harness {
        final EffectSource source;
        final EffectSession session;
        final List<HealingReceipt> receipts = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        Harness(GameTestHelper helper, LivingEntity target, EffectState.Mode mode) throws Exception {
            String id = target.getUUID().toString(); source = new EffectSource("recovery", "test:recovery_inputs", id, new BuffInstance.Origin(id, "test:recovery", "", ""), Set.of());
            var world = new MinecraftWorldActions(helper.getLevel(), value -> value.equals(id) ? target : null,
                    _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            session = new EffectSession(program().engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1),
                    EffectState.empty().withSource(source).withMode(mode), request -> {
                        times.add(time()); var receipt = (HealingReceipt) world.apply(request); receipts.add(receipt); return receipt;
                    });
        }
        long time() { return session.state().engine().timeMicros(); }
        void send(long time, String type, int tier, double duration) {
            session.start(time, new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(),
                    Map.of("tier", new Measure(tier, Unit.COUNT), "duration", new Measure(duration, Unit.SECOND)))));
        }
    }
    @GameCase public void continuousRecoveryIncludesTheFinalFractionOfATick(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(1);
        var test = new Harness(helper, target, EffectState.Mode.PVE);
        test.send(0, "test:restoration", 1, .070001); test.send(200_000, "test:noop", 1, 1);
        near(helper, target.getHealth(), 1 + .070001 * 3.5, "integrated native health");
        helper.assertValueEqual(test.times, List.of(50_000L, 70_001L), "including residual interval");
        near(helper, test.receipts.stream().mapToDouble(HealingReceipt::effective).sum(), .070001 * 3.5, "actual healing receipts");
        helper.succeed();
    }
    @GameCase public void restorationAndRiftShareAChannelWithoutAddingTheirRates(GameTestHelper helper) throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var target = helper.spawnWithNoFreeWill(EntityTypes.COW, mode == EffectState.Mode.PVE ? 2 : 4, 2, 2); target.setHealth(1);
            var test = new Harness(helper, target, mode);
            test.send(0, "test:restoration", 1, .2); test.send(0, "test:rift", 1, .070001); test.send(200_000, "test:noop", 1, 1);
            double rift = mode == EffectState.Mode.PVE ? 4 : 3.5; double restoration = mode == EffectState.Mode.PVE ? 3.5 : 1.75;
            near(helper, target.getHealth(), 1 + .070001 * rift + .129999 * restoration, "mutually exclusive " + mode + " recovery");
            helper.assertTrue(test.receipts.get(1).command().tags().contains("chorus_d2:healing_rift"), "Rift did not own the final interval");
            helper.assertTrue(test.receipts.get(2).command().tags().contains("chorus_d2:restoration"), "Suppressed restoration did not resume");
        }
        helper.succeed();
    }
    @GameCase public void cappedRecoveryReportsOverhealAndNeverStoresItForLater(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(target.getMaxHealth() - .1f);
        var test = new Harness(helper, target, EffectState.Mode.PVE);
        test.send(0, "test:restoration", 2, .07); test.send(70_000, "test:noop", 1, 1);
        near(helper, target.getHealth(), target.getMaxHealth(), "capped health");
        near(helper, test.receipts.stream().mapToDouble(HealingReceipt::requested).sum(), .35, "allocated recovery");
        near(helper, test.receipts.stream().mapToDouble(HealingReceipt::effective).sum(), .1, "effective recovery");
        near(helper, test.receipts.stream().mapToDouble(HealingReceipt::overheal).sum(), .25, "overheal");
        target.setHealth(1); test.send(1_000_000, "test:noop", 1, 1);
        near(helper, target.getHealth(), 1, "expired recovery retained no credit"); helper.succeed();
    }
}
