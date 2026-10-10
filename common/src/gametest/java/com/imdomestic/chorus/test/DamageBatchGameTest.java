package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

public class DamageBatchGameTest {
    private static String id(Entity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final LivingEntity target; final MinecraftEffectRuntime runtime;
        final BuffInstance.Origin origin; final DamageSource nativeDamage;
        final List<DamageCommand> hits = new ArrayList<>(); final List<EffectProjectile> projectiles = new ArrayList<>();
        DamageBatch nativeBatch;
        Harness(GameTestHelper h) throws Exception {
            this.h = h; owner = h.makeMockServerPlayerInLevel(); owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(h.absoluteVec(new Vec3(2.5, 46, 3.5))); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(100);
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/damage_batch.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            origin = new BuffInstance.Origin(id(owner), "weapon", "weapon", "");
            var state = EffectState.empty().withSource(new EffectSource("actor", "test:actor", id(owner), origin, Set.of()))
                    .withSource(new EffectSource("observer", "test:observer", id(owner), origin, Set.of()));
            state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:counter"), id(owner), id(owner), origin, 1, 1, 10_000_000).store());
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            nativeDamage = new DamageSource(type, null, owner);
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> nativeDamage, (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof ProjectileFlight.Receipt flight && flight.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(flight.entity().orElseThrow())));
                if (request.command() instanceof DamageCommand command) hits.add(command);
                return receipt;
            }, (victim, _, amount) -> {
                var command = new DamageCommand(id(victim), origin, amount, "chorus_gametest:delayed", Set.of(), Set.of(), false);
                return nativeBatch == null ? command : command.withBatch(nativeBatch);
            });
        }
        void input(String event) { runtime.start(new RuleEngine.Signal("test:" + event, new EffectEvent(id(owner), id(target), origin, Set.of(), Map.of()))); }
        void counts(int instances, int batches) {
            runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Batch runtime failed: " + runtime.failure());
            var values = runtime.state().engine().domain().buffs().instances().values().iterator().next().components().numbers();
            near(h, values.get("instances"), instances, "actual damage instance count"); near(h, values.get("batches"), batches, "logical batch count");
        }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); target.discard(); owner.discard(); }
    }
    @GameCase
    public void realManagedComponentsCountOncePerDeclaredBatchAndDoNotMergeTheNextAttack(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.input("pair"); t.counts(3, 2); near(h, t.target.getHealth(), 94, "all three components execute");
            t.input("separate"); t.counts(5, 4); near(h, t.target.getHealth(), 90, "two same-tick batches remain independent");
            h.assertValueEqual(t.hits.get(0).batch(), t.hits.get(1).batch(), "declared pair"); h.assertTrue(t.hits.get(2).batch().isEmpty(), "standalone hit inherited batch");
        }
        h.succeed();
    }
    @GameCase
    public void nativeAdapterCanDeclareMembershipAndUngroupedNativeHitsStayIndependent(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.nativeBatch = new DamageBatch("native-composite", id(t.owner));
            t.target.hurtServer(h.getLevel(), t.nativeDamage, 2); t.target.hurtServer(h.getLevel(), t.nativeDamage, 2); t.counts(2, 1);
            t.nativeBatch = null;
            t.target.hurtServer(h.getLevel(), t.nativeDamage, 2); t.target.hurtServer(h.getLevel(), t.nativeDamage, 2); t.counts(4, 3);
            near(h, t.target.getHealth(), 92, "native components"); h.assertTrue(t.hits.isEmpty(), "observations replayed as managed damage");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:batch_projectile", maxTicks = 25)
    public void actualProjectileGroupsTwoImpactComponentsAfterItsSourceIsRemoved(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.input("launch"); t.runtime.unbind("actor");
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.counts(2, 1); near(h, t.target.getHealth(), 96, "actual projectile components");
                    h.assertValueEqual(t.hits.get(0).batch(), t.hits.get(1).batch(), "impact-created batch lost");
                    h.assertTrue(t.hits.get(0).snapshot().orElseThrow().attack().batch().isEmpty(), "release snapshot owns an impact batch"); h.succeed();
                }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:batch_delayed", maxTicks = 20)
    public void explicitlySharedLogicalBatchSurvivesDelayWhileUnboundSnapshotGetsAnotherBatch(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.input("later"); t.counts(1, 1); t.runtime.unbind("actor");
            h.runAfterDelay(5, () -> {
                try (t) { t.counts(3, 2); near(h, t.target.getHealth(), 94, "delayed components"); h.succeed(); }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
}
