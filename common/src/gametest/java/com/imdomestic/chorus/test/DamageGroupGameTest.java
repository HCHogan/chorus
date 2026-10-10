package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class DamageGroupGameTest {
    private static final class Harness implements AutoCloseable {
        final MinecraftEffectRuntime runtime; final LivingEntity target; final EffectSource source;
        final List<DamageReceipt> hits = new ArrayList<>(); boolean missingFirst; int unknownAt = -1;
        Harness(GameTestHelper h) throws Exception {
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 3, 3); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(100);
            source = new EffectSource("test-controller", "test:control", "owner", new BuffInstance.Origin("owner", "test-controller", "", ""), Set.of());
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/damage_group.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> target.getUUID().toString().equals(ref) && !(missingFirst && hits.isEmpty()) ? target : null,
                    _ -> new DamageSource(type), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof DamageReceipt hit) { hits.add(hit); if (hits.size() == unknownAt) throw new IllegalStateException("Injected unknown damage consumption result"); }
                return receipt;
            }, MinecraftEffectRuntime::nativeSource);
        }
        void run(String type) { runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent("owner", target.getUUID().toString(), source.origin(), Set.of(), Map.of()))); }
        EffectState state() { return runtime.state().engine().domain(); }
        @Override public void close() { runtime.close(); target.discard(); }
    }
    @GameCase
    public void rawAndSnapshotComponentsShareOneConsumedBuffButIndependentDamageUsesCurrentState(GameTestHelper h) throws Exception {
        for (String event : List.of("group", "group_snapshot")) try (var t = new Harness(h)) {
            t.run("arm"); t.run(event); near(h, t.target.getHealth(), 40, "two shared boosted components and one independent base hit");
            h.assertValueEqual(t.hits.size(), 3, "each component has its own actual receipt");
            near(h, t.hits.getFirst().healthLoss(), 25, "first component"); near(h, t.hits.get(1).healthLoss(), 25, "shared component"); near(h, t.hits.getLast().healthLoss(), 10, "independent hit");
            h.assertTrue(t.state().buffs().instances().isEmpty() && t.state().damageGroups().isEmpty() && t.runtime.failure().isEmpty(), "live buff and closed attack group leaked");
        }
        h.succeed();
    }
    @GameCase
    public void failedFirstWorldHitDoesNotReserveEligibilityAwayFromTheNextIndependentHit(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.missingFirst = true; t.run("arm"); t.run("release");
            h.assertValueEqual(t.hits.getFirst().outcome(), DamageReceipt.Outcome.FAILED, "missing target");
            near(h, t.hits.get(1).healthLoss(), 25, "independent successful hit consumes"); near(h, t.hits.getLast().healthLoss(), 10, "old group has no grant");
            near(h, t.target.getHealth(), 65, "confirmed actual damage"); h.succeed();
        }
    }
    @GameCase
    public void lostSecondReceiptKeepsFirstConsumptionAndNeverReplaysEitherActualDamage(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.run("arm"); t.unknownAt = 2; boolean failed = false; try { t.run("group"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "lost second receipt must stop runtime");
            near(h, t.target.getHealth(), 50, "two real boosted components before second receipt lost"); h.assertValueEqual(t.hits.size(), 2, "no independent third hit");
            h.assertTrue(t.state().buffs().instances().isEmpty() && t.state().damageGroups().values().iterator().next().grants().size() == 1, "first receipt consumption was rolled back");
            boolean rejected = false; try { t.run("group"); } catch (IllegalStateException expected) { rejected = true; }
            h.assertTrue(rejected && t.hits.size() == 2, "unknown operation replayed"); h.succeed();
        }
    }
    @GameCase(environment = "chorus_gametest:damage_group_delayed", maxTicks = 30)
    public void delayedMemberRetainsOwnBuffEligibilityAfterConsumptionAndClosesOnRealTick(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.run("arm"); t.run("delayed"); near(h, t.target.getHealth(), 75, "first component");
            h.assertTrue(t.state().buffs().instances().isEmpty(), "consumed buff must disappear immediately");
            h.runAfterDelay(8, () -> {
                try (t) {
                    t.runtime.prepare(); near(h, t.target.getHealth(), 50, "retained delayed component");
                    h.assertTrue(t.state().damageGroups().isEmpty() && t.runtime.failure().isEmpty(), "delayed close failed"); h.succeed();
                }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
