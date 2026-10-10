package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
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

public class ReactionBindingGameTest {
    private static String id(Entity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final LivingEntity target; final MinecraftEffectRuntime runtime;
        final EffectSource source; final DamageSource nativeDamage;
        final List<HealingCommand> heals = new ArrayList<>(); final List<DamageCommand> hits = new ArrayList<>(); final List<EffectProjectile> projectiles = new ArrayList<>();
        boolean unknownHeal;
        Harness(GameTestHelper h) throws Exception {
            this.h = h; owner = h.makeMockServerPlayerInLevel(); owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); owner.setHealth(10);
            target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 46, 3); target.setPos(h.absoluteVec(new Vec3(2.5, 46, 3.5))); target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); target.setHealth(100);
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/reaction_binding.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            source = new EffectSource("perk", "test:weapon", id(owner), new BuffInstance.Origin(id(owner), "perk", "weapon", ""), Set.of("chorus:enhanced"));
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            nativeDamage = new DamageSource(type, null, owner);
            var world = new MinecraftWorldActions(h.getLevel(), ref -> h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> nativeDamage, (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var receipt = world.apply(request);
                if (receipt instanceof ProjectileFlight.Receipt flight && flight.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(flight.entity().orElseThrow())));
                if (request.command() instanceof DamageCommand command) hits.add(command);
                if (request.command() instanceof HealingCommand command) { heals.add(command); if (unknownHeal) throw new IllegalStateException("Injected unknown captured-reaction heal result"); }
                return receipt;
            }, (victim, _, amount) -> new DamageCommand(id(victim), source.origin(), amount, "chorus_gametest:delayed", Set.of("test:direct"), Set.of("chorus:weapon_kill"), false));
        }
        void launch() { runtime.start(new RuleEngine.Signal("test:launch", new EffectEvent(id(owner), id(target), source.origin(), Set.of(), Map.of()))); }
        void settled() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Reaction runtime failed: " + runtime.failure()); }
        List<Double> amounts() { return heals.stream().map(HealingCommand::amount).toList(); }
        boolean reward() { return runtime.state().engine().domain().buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("test:kill_reward") && b.key().holder().equals(id(owner)) && b.origin().weapon().equals("weapon")); }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); target.discard(); owner.discard(); }
    }
    @GameCase(environment = "chorus_gametest:reaction_unbound", maxTicks = 25)
    public void physicalProjectileKeepsOldHitAndKillRulesAfterSourceRemoval(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.target.setHealth(3); t.launch(); t.runtime.unbind("perk");
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); h.assertTrue(!t.target.isAlive(), "actual projectile did not kill");
                    near(h, t.owner.getHealth(), 12, "captured enhanced heal survived removal"); h.assertValueEqual(t.amounts(), List.of(2.0), "current rule must not survive unbind");
                    h.assertTrue(t.reward(), "captured weapon kill reward missing"); h.assertValueEqual(t.hits.size(), 1, "physical hit count");
                    h.assertValueEqual(t.hits.getFirst().reactions().orElseThrow().sources(), List.of(t.source), "retained source identity"); h.succeed();
                }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:reaction_replace", maxTicks = 25)
    public void replacementRunsCurrentRuleAndOriginalEnhancedRuleExactlyOnce(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.launch(); t.runtime.bind(new EffectSource("perk", "test:weapon", id(t.owner), t.source.origin(), Set.of()));
            h.runAfterDelay(10, () -> {
                try (t) {
                    t.settled(); near(h, t.target.getHealth(), 96, "one actual projectile"); near(h, t.owner.getHealth(), 22, "current plus original enhancement");
                    h.assertValueEqual(t.amounts(), List.of(10.0, 2.0), "duplicate or reclassified origin rule"); h.succeed();
                }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
    @GameCase
    public void nativeDamageAdapterCapturesImmediateOriginRulesWithoutHostPreprocessing(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.target.hurtServer(h.getLevel(), t.nativeDamage, 1); t.settled();
            near(h, t.target.getHealth(), 99, "native actual hit"); near(h, t.owner.getHealth(), 22, "native captured and current healing");
            h.assertValueEqual(t.amounts(), List.of(10.0, 2.0), "native adapter lost origin selection"); h.assertTrue(t.hits.isEmpty(), "native hit must not be reissued as managed damage");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:reaction_unknown", maxTicks = 25)
    public void unknownCapturedReactionReceiptPreservesActualHealWithoutReplay(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.launch(); t.runtime.unbind("perk"); t.unknownHeal = true;
            h.runAfterDelay(10, () -> {
                try (t) {
                    h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown reaction receipt not retained");
                    near(h, t.target.getHealth(), 96, "confirmed projectile damage"); near(h, t.owner.getHealth(), 12, "one committed heal");
                    h.assertValueEqual(t.amounts(), List.of(2.0), "reaction replayed"); h.succeed();
                }
            });
        } catch (RuntimeException | Error error) { t.close(); throw error; }
    }
}
