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

public class ProcPolicyGameTest {
    private static String id(Entity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final LivingEntity target; final MinecraftEffectRuntime runtime;
        final BuffInstance.Origin origin; final DamageSource nativeDamage;
        final List<HealingCommand> heals = new ArrayList<>(); final List<DamageCommand> hits = new ArrayList<>(); final List<EffectProjectile> projectiles = new ArrayList<>();
        ProcPolicy nativeProc = new ProcPolicy(Set.of("test:blocked"));
        Harness(GameTestHelper h) throws Exception {
            this.h = h; owner = h.makeMockServerPlayerInLevel(); owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); owner.setHealth(10);
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(h.absoluteVec(new Vec3(2.5, 46, 3.5))); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(100);
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/proc_policy.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            origin = new BuffInstance.Origin(id(owner), "weapon", "weapon", "");
            var state = EffectState.empty().withSource(new EffectSource("actor", "test:actor", id(owner), origin, Set.of()))
                    .withSource(new EffectSource("observer", "test:observer", id(owner), origin, Set.of()));
            state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:listener"), id(owner), id(owner), origin, 1, 1, 10_000_000).store());
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            nativeDamage = new DamageSource(type, null, owner);
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> nativeDamage, (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, state, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof ProjectileFlight.Receipt flight && flight.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(flight.entity().orElseThrow())));
                if (request.command() instanceof DamageCommand command) hits.add(command);
                if (request.command() instanceof HealingCommand command) heals.add(command);
                return receipt;
            }, (victim, _, amount) -> new DamageCommand(id(victim), origin, amount, "chorus_gametest:delayed", Set.of("test:child"), Set.of("chorus:weapon_kill"), false).withProc(nativeProc));
        }
        void input(String type) { runtime.start(new RuleEngine.Signal(type, new EffectEvent(id(owner), id(target), origin, Set.of(), Map.of()))); }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Proc runtime failed: " + runtime.failure()); }
        List<Double> amounts() { return heals.stream().map(HealingCommand::amount).toList(); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); target.discard(); owner.discard(); }
    }
    @GameCase
    public void managedLethalDamageStillHealsThroughUnrelatedRulesButDeniesKeyedKillReward(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.target.setHealth(1); t.input("test:fire"); t.settled();
            h.assertTrue(!t.target.isAlive(), "proc denial cancelled actual damage"); near(h, t.owner.getHealth(), 14, "only plain and unrelated heals");
            h.assertValueEqual(t.amounts(), List.of(1.0, 3.0), "source, captured and buff proc exclusions");
            h.assertTrue(t.runtime.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:reward")), "denied kill proc granted reward");
            h.assertTrue(t.hits.getFirst().killTags().contains("chorus:weapon_kill"), "proc policy erased weapon credit");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:proc_projectile", maxTicks = 25)
    public void projectileKeepsItsExclusionAfterLaunchSourceIsRemoved(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.input("test:launch"); t.runtime.unbind("actor");
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); near(h, t.target.getHealth(), 98, "actual projectile damage"); near(h, t.owner.getHealth(), 14, "allowed actual heals");
                    h.assertValueEqual(t.amounts(), List.of(1.0, 3.0), "snapshot proc policy lost");
                    h.assertValueEqual(t.hits.getFirst().proc().deny(), Set.of("test:blocked"), "frozen exclusion"); h.succeed();
                }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
    @GameCase
    public void nativeAdapterPreservesExplicitPolicyAndDefaultAllowsAllThreeRuleSources(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.target.hurtServer(h.getLevel(), t.nativeDamage, 1); t.settled(); near(h, t.owner.getHealth(), 14, "native exclusion");
            t.nativeProc = ProcPolicy.ALLOW; t.target.hurtServer(h.getLevel(), t.nativeDamage, 1); t.settled();
            near(h, t.target.getHealth(), 98, "both native damages applied"); near(h, t.owner.getHealth(), 29, "current, captured and buff rules restored");
            h.assertValueEqual(t.amounts(), List.of(1.0, 3.0, 1.0, 2.0, 3.0, 4.0, 5.0), "policy-only change affected wrong rules"); h.assertTrue(t.hits.isEmpty(), "native hit replayed as a managed action");
        }
        h.succeed();
    }
}
