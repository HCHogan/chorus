package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingReceipt;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
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

/** Synthetic resource policies; no Destiny tuning values are claimed by this fixture. */
public class ResourceRefundGameTest {
    private static CompiledEffects program() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(ResourceRefundGameTest.class.getResourceAsStream("/effects/resource_refund.json")), StandardCharsets.UTF_8)) {
            return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }
    private static final class Harness {
        final EffectSource source;
        final EffectSession session;
        final ResourceState.Key key;
        final List<HealingReceipt> receipts = new ArrayList<>();
        Harness(GameTestHelper helper, LivingEntity target, double initial) throws Exception {
            String id = target.getUUID().toString(); key = new ResourceState.Key(id, "test:energy");
            source = new EffectSource("refund", "test:refund", id, new BuffInstance.Origin(id, "test:refund", "", ""), Set.of());
            var world = new MinecraftWorldActions(helper.getLevel(), value -> value.equals(id) ? target : null,
                    _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            session = new EffectSession(program().engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1),
                    EffectState.empty().withResource(new ResourceState(key, initial, 2, 0)), request -> {
                        var receipt = (HealingReceipt) world.apply(request); receipts.add(receipt); return receipt;
                    });
            session.start(0, SourceChange.bind(source));
        }
        double energy() { return session.state().engine().domain().resources().get(key).value(); }
        void cast(double cost, double drain, boolean topUp) {
            session.start(0, new RuleEngine.Signal("test:cast", new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(),
                    Map.of("cost", new Measure(cost, Unit.CHARGE), "drain", new Measure(drain, Unit.CHARGE)),
                    Map.of("top_up", topUp, "early_refund", true), Map.of())));
        }
        void full(int charges) {
            session.start(0, new RuleEngine.Signal("test:full", new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(),
                    Map.of("charges", new Measure(charges, Unit.COUNT)))));
        }
    }
    @GameCase public void overflowedRefundCannotBeReclaimedAfterWorldActionAndAnotherCost(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(1);
        var test = new Harness(helper, target, 2); test.cast(1, 2, true);
        near(helper, test.energy(), .3, "remaining refund after a 70 percent overflow");
        near(helper, target.getHealth(), 2.3, "only actual credited refund drives second healing");
        helper.assertValueEqual(test.receipts.size(), 2, "two world actions");
        near(helper, test.receipts.get(1).effective(), .3, "actual refund-dependent healing");
        helper.assertTrue(test.session.state().idle(), "Refund sequence did not settle"); helper.succeed();
    }
    @GameCase public void failedAndFreePaymentsCannotCreateRefundEnergyInWorldSequence(GameTestHelper helper) throws Exception {
        for (int cost : List.of(0, 1)) {
            var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2 + cost * 2, 2, 2); target.setHealth(1);
            var test = new Harness(helper, target, .2); test.cast(cost, 0, false);
            near(helper, test.energy(), .2, "zero paid means zero refund");
            near(helper, target.getHealth(), 2, "only the fixture's unconditional barrier heals");
            near(helper, test.receipts.get(1).requested(), 0, "refund-dependent request");
            near(helper, test.receipts.get(1).effective(), 0, "refund-dependent actual healing");
        }
        helper.succeed();
    }
    @GameCase public void fullChargePreservesPartialProgressAndUsesClippedGainForWorldAction(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(1);
        var test = new Harness(helper, target, .4); test.full(1);
        near(helper, test.energy(), 1.4, "one full unit added to partial progress"); near(helper, target.getHealth(), 2, "first credited unit");
        test.full(2); near(helper, test.energy(), 2, "capacity clip"); near(helper, target.getHealth(), 2.6, "second gain limited to actual room");
        near(helper, test.receipts.get(1).requested(), .6, "clipped gain feeds world action"); helper.succeed();
    }
}
