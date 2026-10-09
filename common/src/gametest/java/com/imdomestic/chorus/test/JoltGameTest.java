package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Partial Jolt content driven by real native hits; application intent and damage type are explicit test host choices. */
public class JoltGameTest {
    private static final String JOLT = "chorus_d2:jolt", COOLDOWN = "chorus_d2:jolt_cooldown";
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final List<LivingEntity> entities = new ArrayList<>();
        final LivingEntity applier, trigger, target, neighbor;
        final BuffInstance.Origin application, triggering;
        final List<DamageCommand> commands = new ArrayList<>();
        final List<DamageReceipt> receipts = new ArrayList<>();
        final List<StatusResult.Checked> qualifications = new ArrayList<>();
        final List<Long> queryRoots = new ArrayList<>();
        final List<String> nativeOwners = new ArrayList<>();
        final String marker = "jolt-" + UUID.randomUUID();
        MinecraftEffectRuntime runtime;
        boolean applying, afterDamage;
        Harness(GameTestHelper helper, EffectState.Mode mode, boolean players) throws Exception {
            this.helper = helper; applier = cow(1); trigger = cow(4);
            // Stay inside the test's loaded horizontal area; keep the trigger outside the radius vertically.
            trigger.setPos(trigger.getX(), trigger.getY() + 32, trigger.getZ());
            target = players ? player(4) : cow(4); neighbor = players ? player(5) : cow(5);
            for (var entity : entities) helper.assertTrue(resolve(id(entity)) == entity, "test entity must be loaded before runtime installation");
            target.addTag(marker); neighbor.addTag(marker);
            application = new BuffInstance.Origin(id(applier), "ability-A", "", "ability-A");
            triggering = new BuffInstance.Origin(id(trigger), "weapon-B", "weapon-B", "");
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(JoltGameTest.class.getResourceAsStream("/effects/jolt.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var type = helper.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(helper.getLevel(), this::resolve, command -> new DamageSource(type, null, resolve(command.source().owner())), (_, _) -> true, _ -> {});
            TestDamageHooks.ALLOW_DAMAGE.register((entity, source, _) -> {
                if (entity.entityTags().contains(marker) && source.typeHolder().equals(type)) nativeOwners.add(source.getEntity() == null ? "" : id((LivingEntity) source.getEntity()));
                return true;
            });
            var source = new EffectSource("application", "chorus_d2:jolt_application", id(applier), application, Set.of());
            runtime = MinecraftEffectRuntime.install(helper.getLevel(), program, EffectState.empty().withMode(mode).withSource(source),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        if (request.command() instanceof TargetQuery) queryRoots.add(runtime.state().engine().frames().getFirst().event().root());
                        var result = world.apply(request);
                        if (result instanceof StatusResult.Checked checked) qualifications.add(checked);
                        if (request.command() instanceof DamageCommand command) { commands.add(command); receipts.add((DamageReceipt) result); }
                        return result;
                    }, (victim, sourceDamage, amount) -> {
                        var origin = sourceDamage.getEntity() == applier ? application : triggering;
                        var tags = new HashSet<String>(); if (applying) tags.add("test:apply_jolt"); if (afterDamage) tags.add("test:jolt_after_damage");
                        if (sourceDamage.getEntity() == trigger) tags.add("chorus:weapon_damage");
                        return new DamageCommand(id(victim), origin, amount, "minecraft:generic", tags, Set.of("chorus:weapon_kill"), false);
                    });
        }
        LivingEntity resolve(String ref) { return helper.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null; }
        LivingEntity cow(int x) { var e = helper.spawnWithNoFreeWill(EntityTypes.COW, x, 160, 4); prepare(e); return e; }
        LivingEntity player(int x) {
            var e = helper.makeMockServerPlayerInLevel(); e.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            e.getAbilities().invulnerable = false; e.setInvulnerableTime(0);
            var pos = helper.absolutePos(new net.minecraft.core.BlockPos(x, 160, 4)); e.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5); prepare(e); return e;
        }
        void prepare(LivingEntity e) {
            e.setNoGravity(true); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(100); entities.add(e);
        }
        void hit(LivingEntity victim, LivingEntity attacker, float amount, boolean apply) {
            applying = apply; victim.damageCooldownTime = 0;
            // The GameTest world is peaceful: mob_attack would reduce damage against players to zero.
            var input = new DamageSource(helper.getLevel().damageSources().generic().typeHolder(), null, attacker);
            helper.assertTrue(victim.hurtServer(helper.getLevel(), input, amount), "native input rejected"); settled();
            if (apply && victim.isAlive()) helper.assertTrue(buff(JOLT, victim).isPresent(), "application missing; qualifications=" + qualifications);
        }
        void settled() { helper.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Jolt runtime failed: " + runtime.failure()); }
        Optional<BuffInstance> buff(String definition, LivingEntity holder) { return runtime.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.definition().id().equals(definition) && b.key().holder().equals(id(holder))).findFirst(); }
        @Override public void close() { if (runtime != null) runtime.close(); entities.forEach(LivingEntity::discard); }
    }
    @GameCase public void nativeApplyingHitCountsAndAnotherAttackerOwnsModeSpecificChainDamage(GameTestHelper h) throws Exception {
        for (var mode : EffectState.Mode.values()) try (var test = new Harness(h, mode, false)) {
            double threshold = mode == EffectState.Mode.PVP ? 4.5 : 11.5, damage = mode == EffectState.Mode.PVP ? 5.1 : 11.9;
            test.hit(test.target, test.applier, 1, true);
            near(h, test.buff(JOLT, test.target).orElseThrow().components().numbers().get("damage"), 1, "applying hit counted");
            test.hit(test.target, test.trigger, (float) threshold - 1, false);
            h.assertValueEqual(test.commands.size(), 2, "neighbor and nonplayer center");
            h.assertTrue(test.commands.stream().allMatch(c -> c.source().equals(test.triggering) && c.killTags().isEmpty() && !c.tags().contains("chorus:weapon_damage")), "derived owner or credit incorrect");
            h.assertValueEqual(test.nativeOwners, List.of(id(test.trigger), id(test.trigger)), "native chain DamageSource owner");
            near(h, test.target.getHealth(), 100 - threshold - damage, "center actual health"); near(h, test.neighbor.getHealth(), 100 - damage, "neighbor actual health");
            h.assertValueEqual(test.buff(JOLT, test.target).orElseThrow().origin(), test.application, "status application owner unchanged");
            h.assertValueEqual(test.buff(COOLDOWN, test.target).orElseThrow().deadline(), 800_000L, "cooldown begins before own chain");
        }
        h.succeed();
    }
    @GameCase public void playerCenterRequiresActualOtherPlayerLossIncludingAbsorptionAndRejectsCancelledChains(GameTestHelper h) throws Exception {
        String cancelled = "cancel-jolt-" + UUID.randomUUID(); TestDamageHooks.ALLOW_DAMAGE.register((victim, _, _) -> !victim.entityTags().contains(cancelled));
        for (boolean cancel : List.of(true, false)) try (var test = new Harness(h, EffectState.Mode.PVP, true)) {
            var npc = test.cow(6);
            test.neighbor.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 3)); test.neighbor.setAbsorptionAmount(10);
            if (cancel) test.neighbor.addTag(cancelled);
            test.hit(test.target, test.applier, 4.5f, true);
            near(h, npc.getHealth(), 94.9, "nonplayer neighbor always receives its chain"); near(h, test.neighbor.getHealth(), 100, "guardian neighbor HP absorbed or cancelled");
            near(h, test.neighbor.getAbsorptionAmount(), cancel ? 10 : 4.9, "actual guardian absorption loss");
            near(h, test.target.getHealth(), cancel ? 95.5 : 90.4, "center only damaged after actual other-player loss");
            h.assertValueEqual(test.commands.stream().filter(c -> c.target().equals(id(test.target))).count(), cancel ? 0L : 1L, "center eligibility from actual receipt");
            h.assertValueEqual(test.queryRoots.size(), 1, "one chain activation");
        }
        h.succeed();
    }
    @GameCase public void fatalThresholdStillChainsBeforeCleanupAndFreshAfterDamageApplicationDoesNotCount(GameTestHelper h) throws Exception {
        try (var test = new Harness(h, EffectState.Mode.PVE, false)) {
            test.afterDamage = true; test.hit(test.target, test.applier, 20, true);
            near(h, test.buff(JOLT, test.target).orElseThrow().components().numbers().get("damage"), 0, "late application excludes first damage");
            h.assertTrue(test.commands.isEmpty(), "late application triggered immediately"); test.afterDamage = false;
            test.target.setHealth(11.5f); test.hit(test.target, test.trigger, 20, false);
            h.assertTrue(!test.target.isAlive() && test.buff(JOLT, test.target).isEmpty(), "death cleaned up status");
            h.assertValueEqual(test.commands.size(), 1, "dead center skipped but threshold chains to neighbor"); near(h, test.neighbor.getHealth(), 88.1, "one actual fatal-threshold chain");
        }
        h.succeed();
    }
    @GameCase public void adjacentJoltsMayCascadeWithinOneRootWithIndependentCooldowns(GameTestHelper h) throws Exception {
        try (var test = new Harness(h, EffectState.Mode.PVE, false)) {
            test.hit(test.target, test.applier, 1, true); test.hit(test.neighbor, test.applier, 1, true);
            test.hit(test.target, test.trigger, 10.5f, false);
            h.assertValueEqual(test.queryRoots.size(), 2, "both target statuses can activate"); h.assertValueEqual(new HashSet<>(test.queryRoots).size(), 1, "same causal root is permitted");
            h.assertValueEqual(test.commands.size(), 4, "four actual chain components"); h.assertTrue(test.commands.stream().allMatch(c -> c.source().equals(test.triggering)), "cascade preserves triggering owner");
            near(h, test.target.getHealth(), 99 - 10.5 - 23.8, "target receives two chains"); near(h, test.neighbor.getHealth(), 99 - 23.8, "neighbor receives two chains");
            h.assertTrue(test.buff(COOLDOWN, test.target).isPresent() && test.buff(COOLDOWN, test.neighbor).isPresent(), "per-target cooldowns");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:jolt_cooldown", maxTicks = 30)
    public void nativeCooldownBlocksEarlyHitsAndAllowsAnotherActivationAtExactDeadline(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVE, false);
        try {
            test.hit(test.target, test.applier, 11.5f, true);
            h.runAfterDelay(15, () -> {
                try {
                    h.assertValueEqual(test.runtime.state().engine().timeMicros(), 750_000L, "time before deadline");
                    test.hit(test.target, test.trigger, 11.5f, false); h.assertValueEqual(test.queryRoots.size(), 1, "hit during cooldown discarded");
                } catch (Throwable error) { test.close(); throw error; }
            });
            h.runAfterDelay(16, () -> {
                try (test) {
                    h.assertValueEqual(test.runtime.state().engine().timeMicros(), 800_000L, "exact cooldown deadline");
                    test.hit(test.target, test.trigger, 11.5f, false); h.assertValueEqual(test.queryRoots.size(), 2, "new hit at deadline activates");
                    near(h, test.neighbor.getHealth(), 76.2, "two actual chains"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
}
