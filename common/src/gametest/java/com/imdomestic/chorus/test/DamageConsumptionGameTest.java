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

public class DamageConsumptionGameTest {
    private static final class Harness implements AutoCloseable {
        final MinecraftEffectRuntime runtime; final LivingEntity target; final EffectSource source;
        final List<DamageReceipt> hits = new ArrayList<>(); boolean unknown, missingFirst;
        Harness(GameTestHelper h) throws Exception {
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 3, 3); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(100);
            source = new EffectSource("test-controller", "test:control", "owner", new BuffInstance.Origin("owner", "test-controller", "", ""), Set.of());
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/damage_consumption.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> target.getUUID().toString().equals(ref) && !(missingFirst && hits.isEmpty()) ? target : null,
                    _ -> new DamageSource(type), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof DamageReceipt hit) { hits.add(hit); if (unknown) throw new IllegalStateException("Injected unknown damage consumption result"); }
                return receipt;
            }, MinecraftEffectRuntime::nativeSource);
        }
        void run(String type) { runtime.start(new RuleEngine.Signal("test:" + type, new EffectEvent("owner", target.getUUID().toString(), source.origin(), Set.of(), Map.of()))); }
        EffectState state() { return runtime.state().engine().domain(); }
        @Override public void close() { runtime.close(); target.discard(); }
    }
    @GameCase
    public void nextManagedDamageConsumesBeforeSecondRawOrSnapshotWorldCommand(GameTestHelper h) throws Exception {
        for (String type : List.of("double", "snapshot")) try (var t = new Harness(h)) {
            t.run("arm"); t.run(type); near(h, t.target.getHealth(), 65, "only first command boosted");
            h.assertValueEqual(t.hits.size(), 2, "two real receipts"); near(h, t.hits.getFirst().healthLoss(), 25, "first boosted hit"); near(h, t.hits.getLast().healthLoss(), 10, "second base hit");
            h.assertTrue(t.state().buffs().instances().isEmpty() && t.runtime.failure().isEmpty(), "consumption settled before next instruction");
        }
        h.succeed();
    }
    @GameCase
    public void knownMissingTargetPreservesBuffUntilNextSuccessfulWorldHit(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.missingFirst = true; t.run("arm"); t.run("double");
            h.assertValueEqual(t.hits.getFirst().outcome(), DamageReceipt.Outcome.FAILED, "explicit missing target");
            near(h, t.target.getHealth(), 75, "second command retains boost"); h.assertTrue(t.state().buffs().instances().isEmpty(), "successful second hit consumes"); h.succeed();
        }
    }
    @GameCase
    public void unknownWorldReceiptPreservesPendingDamageAndDoesNotExecuteSecondCommand(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.run("arm"); t.unknown = true; boolean failed = false; try { t.run("double"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "lost receipt did not stop runtime");
            near(h, t.target.getHealth(), 75, "one real damage before lost receipt"); h.assertValueEqual(t.hits.size(), 1, "second damage must not run");
            h.assertValueEqual(t.state().buffs().instances().size(), 1, "unknown outcome cannot infer consumption"); h.succeed();
        }
    }
}
