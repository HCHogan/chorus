package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class DamageSnapshotComponentGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final LivingEntity owner, victim; final BuffInstance.Origin origin;
        final CompiledEffects program; final MinecraftEffectRuntime runtime;
        final List<DamageCommand> commands = new ArrayList<>(); final List<DamageReceipt> receipts = new ArrayList<>();
        boolean failAfterDamage;
        Harness(GameTestHelper h) throws Exception {
            this.h = h; owner = h.spawnWithNoFreeWill(EntityTypes.COW, 1, 40, 1); victim = h.spawnWithNoFreeWill(EntityTypes.COW, 3, 40, 1);
            owner.setNoGravity(true); victim.setNoGravity(true); victim.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); victim.setHealth(100);
            victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            origin = new BuffInstance.Origin(owner.getUUID().toString(), "original", "original-weapon", "");
            program = CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("damage_snapshot")).getOrThrow(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("stored_damage_snapshot")).getOrThrow()));
            String holder = origin.owner();
            var state = EffectState.empty().withSource(new EffectSource("storage", "test:storage", holder, origin, Set.of()))
                    .withSource(new EffectSource("attack", "test:attack", holder, origin, Set.of()))
                    .withResource(new ResourceState(new ResourceState.Key(holder, "test:power"), .3, 1, 0));
            state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:empower"), holder, holder, origin, 1, 1, 100_000).store());
            var world = new MinecraftWorldActions(h.getLevel(), id -> h.getLevel().getEntity(UUID.fromString(id)) instanceof LivingEntity entity ? entity : null,
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (result instanceof DamageReceipt receipt) {
                    commands.add((DamageCommand) request.command()); receipts.add(receipt);
                    if (failAfterDamage) throw new IllegalStateException("unknown after actual stored-snapshot damage");
                }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        void signal(String name) { runtime.start(new RuleEngine.Signal(name, new EffectEvent(origin.owner(), victim.getUUID().toString(), origin, Set.of(), Map.of()))); }
        void control(String name) { signal("test:snapshot_" + name); }
        BuffInstance instance() { return runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("test:stored_attack")).findFirst().orElseThrow(); }
        DamageSnapshot saved() { return instance().components().damageSnapshots().get("attack").orElseThrow(); }
        void detach() { runtime.unbind("attack"); runtime.unbind("storage"); }
        @Override public void close() { runtime.close(); owner.discard(); victim.discard(); }
    }
    @GameCase(environment = "chorus_gametest:stored_snapshot", maxTicks = 30)
    public void independentBuffEventAfterSourceExpiryAndDetachUsesStoredAttackAndCurrentTargetDefense(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.control("init"); t.control("save"); var saved = t.saved(); t.control("mark"); t.detach();
            h.runAfterDelay(4, () -> {
                try (t) {
                    t.runtime.prepare();
                    h.assertTrue(t.runtime.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:empower")), "source bonus really expired");
                    t.signal("test:stored_fire"); h.assertTrue(t.runtime.failure().isEmpty(), "stored attack failed: " + t.runtime.failure());
                    near(h, t.victim.getHealth(), 79.75, "13.5 frozen-source/current-victim attack times 1.5 live defense");
                    h.assertValueEqual(t.commands.getFirst().snapshot().orElseThrow(), saved, "component preserved full snapshot");
                    h.assertValueEqual(t.commands.getFirst().source(), t.origin, "initial weapon source retained after detach");
                    h.assertValueEqual(t.receipts.getFirst().outgoing().orElseThrow().trace().version(), "test-snapshot-v1", "profile remains pinned"); h.succeed();
                }
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void endedGenerationUsesItsSnapshotAndTheReplacementStartsEmpty(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.control("init"); t.control("save"); var saved = t.saved(); long generation = t.instance().generation();
            t.control("mark"); t.control("replace");
            h.assertTrue(t.runtime.failure().isEmpty(), "ended snapshot execution failed");
            h.assertTrue(t.instance().generation() != generation && t.instance().components().damageSnapshots().get("attack").isEmpty(), "replacement inherited retired attack");
            near(h, t.victim.getHealth(), 79.75, "ended rule dealt actual captured damage");
            h.assertValueEqual(t.commands.getFirst().snapshot().orElseThrow(), saved, "ended rule read wrong generation");
            t.control("fire"); h.assertValueEqual(t.commands.size(), 1, "empty replacement invented damage");
        }
        h.succeed();
    }
    @GameCase public void unknownDamageReceiptKeepsCommittedHealthAndSnapshotWithoutRepeatingTheWorldAction(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.control("init"); t.control("save"); var saved = t.saved(); t.failAfterDamage = true;
            boolean failed = false;
            try { t.control("fire"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown result must stop the pending action");
            near(h, t.victim.getHealth(), 88, "actual world damage remains committed");
            h.assertValueEqual(t.saved(), saved, "unknown receipt modified stored snapshot");
            t.failAfterDamage = false; t.runtime.prepare(); h.assertValueEqual(t.commands.size(), 1, "unknown world action replayed");
        }
        h.succeed();
    }
}
