package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Data-defined Volatile, native hit/death facts, synthetic application intent and explicit test damage-type policy. */
public class VolatileGameTest {
    private static final String VOLATILE = "chorus_d2:volatile", COOLDOWN = "chorus_d2:volatile_cooldown";
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final List<LivingEntity> entities = new ArrayList<>();
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        final List<TargetQuery> queries = new ArrayList<>();
        final List<Long> queryRoots = new ArrayList<>();
        final List<DamageReceipt> damage = new ArrayList<>();
        final CompiledEffects program;
        final LivingEntity owner, target, neighbor;
        final EffectSource source;
        final MinecraftWorldActions world;
        MinecraftEffectRuntime runtime;
        boolean applying, denied;
        Harness(GameTestHelper helper, EffectState.Mode mode) throws Exception {
            this.helper = helper; owner = cow(2, 2); target = cow(4, 4); neighbor = cow(5, 4);
            source = new EffectSource("volatile-source", "chorus_d2:volatile_application", id(owner),
                    new BuffInstance.Origin(id(owner), "volatile-source", "test-weapon", ""), Set.of());
            try (var reader = new InputStreamReader(Objects.requireNonNull(VolatileGameTest.class.getResourceAsStream("/effects/volatile.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var damageType = helper.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(
                    ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:volatile")));
            world = new MinecraftWorldActions(helper.getLevel(), ref -> {
                var e = helper.getLevel().getEntity(UUID.fromString(ref)); return e instanceof LivingEntity living ? living : null;
            }, _ -> new DamageSource(damageType, owner), (_, _) -> !denied, _ -> {});
            runtime = MinecraftEffectRuntime.install(helper.getLevel(), program, EffectState.empty().withMode(mode).withSource(source),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        requests.add(request);
                        if (request.command() instanceof TargetQuery query) { queries.add(query); queryRoots.add(runtime.state().engine().frames().getFirst().event().root()); }
                        var receipt = world.apply(request); if (receipt instanceof DamageReceipt hit) damage.add(hit); return receipt;
                    }, (victim, _, amount) -> new DamageCommand(id(victim), source.origin(), amount, "minecraft:mob_attack",
                            applying ? Set.of("test:apply_volatile", "chorus:weapon_damage") : Set.of("chorus:weapon_damage"), Set.of("chorus:weapon_kill"), false));
        }
        LivingEntity cow(int x, int z) {
            var e = helper.spawnWithNoFreeWill(EntityTypes.COW, x, 40, z); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.setHealth(100); entities.add(e); return e;
        }
        void hit(LivingEntity victim, float amount, boolean apply) {
            applying = apply; victim.damageCooldownTime = 0; // Independent native inputs at the same logical test time.
            helper.assertTrue(victim.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(owner), amount), "Native hit did not apply");
            helper.assertTrue(runtime.failure().isEmpty(), "Volatile runtime failed: " + runtime.failure());
            helper.assertTrue(runtime.state().idle(), "Native boundary did not settle");
        }
        Optional<BuffInstance> buff(String definition, LivingEntity holder) {
            return runtime.state().engine().domain().buffs().instances().values().stream()
                    .filter(b -> b.key().holder().equals(id(holder)) && b.definition().id().equals(definition)).findFirst();
        }
        StatusResult.Decision qualify(boolean allowDead) {
            var definition = program.program().buffs().stream().filter(b -> b.definition().id().equals(VOLATILE)).findFirst().orElseThrow().definition();
            var check = new StatusResult.Check(id(target), definition, source.origin(), 1, 1, 10_000_000, allowDead);
            return ((StatusResult.Checked) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), check))).decision();
        }
        @Override public void close() { if (runtime != null) runtime.close(); entities.forEach(LivingEntity::discard); }
    }
    @GameCase public void nativeThresholdDetonatesSelfAndNearbyTargetsWithModeSpecificDamageAndCooldown(GameTestHelper helper) throws Exception {
        for (var mode : EffectState.Mode.values()) try (var test = new Harness(helper, mode)) {
            test.hit(test.target, 30, true);
            near(helper, test.target.getHealth(), 70, "applying hit excluded from threshold");
            near(helper, test.buff(VOLATILE, test.target).orElseThrow().components().numbers().get("damage"), 0, "initial accumulation");
            helper.assertTrue(test.queries.isEmpty(), "Applying hit detonated prematurely");
            double threshold = mode == EffectState.Mode.PVP ? 20 : 19, maximum = mode == EffectState.Mode.PVP ? 8 : 14.5;
            test.hit(test.target, (float) threshold, false);
            near(helper, test.target.getHealth(), 70 - threshold - maximum, "center receives actual explosion damage despite native hit cooldown");
            near(helper, test.neighbor.getHealth(), 100 - maximum * .8, "test linear falloff at one block");
            helper.assertTrue(test.buff(VOLATILE, test.target).isEmpty(), "Detonated status still active");
            helper.assertValueEqual(test.buff(COOLDOWN, test.target).orElseThrow().deadline(), 1_000_000L, "cooldown starts at detonation");
            test.hit(test.target, 1, true);
            helper.assertTrue(test.buff(VOLATILE, test.target).isEmpty(), "Reapplication bypassed active cooldown");
            helper.assertValueEqual(test.queries.size(), 1, "one detonation"); near(helper, test.owner.getHealth(), 100, "owner excluded");
        }
        helper.succeed();
    }
    @GameCase public void lethalApplyingHitTriggersOneQualifiedExplosionWithoutApplyingToADeadEntity(GameTestHelper helper) throws Exception {
        for (boolean denied : List.of(false, true)) try (var test = new Harness(helper, EffectState.Mode.PVE)) {
            test.denied = denied; test.target.setHealth(1); test.hit(test.target, 2, true);
            helper.assertTrue(test.target.isDeadOrDying(), "Expected committed native death");
            helper.assertTrue(test.buff(VOLATILE, test.target).isEmpty(), "Postmortem branch applied a live status");
            near(helper, test.neighbor.getHealth(), denied ? 100 : 88.4, "postmortem eligibility controls explosion");
            helper.assertValueEqual(test.queries.size(), denied ? 0 : 1, "death and kill facts did not duplicate the reaction");
            helper.assertValueEqual(test.buff(COOLDOWN, test.target).isPresent(), !denied, "cooldown only after qualified detonation");
            var check = (StatusResult.Check) test.requests.getFirst().command(); helper.assertTrue(check.allowDead(), "Missing explicit postmortem qualification");
        }
        helper.succeed();
    }
    @GameCase public void lethalHitOnAnExistingStatusDoesNotDetonateTwiceOrChangeTheApplicationOrigin(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper, EffectState.Mode.PVE)) {
            test.target.setHealth(8); test.hit(test.target, 1, true); test.hit(test.target, 20, true);
            helper.assertTrue(test.target.isDeadOrDying(), "Expected native death below accumulated threshold");
            helper.assertValueEqual(test.queries.size(), 1, "exactly one explosion for hit plus death");
            near(helper, test.neighbor.getHealth(), 88.4, "one neighboring explosion component");
            helper.assertValueEqual(test.requests.stream().filter(r -> r.command() instanceof StatusResult.Check).count(), 1L, "no second apply to an already-active target");
            for (var request : test.requests) if (request.command() instanceof DamageCommand explosion) {
                helper.assertValueEqual(explosion.source(), test.source.origin(), "application origin retained");
                helper.assertValueEqual(explosion.killTags(), Set.of("chorus:ability_kill"), "explicit ability kill credit");
            }
        }
        helper.succeed();
    }
    @GameCase public void volatileExplosionsCanDetonateOtherVolatileTargetsInTheSameCausalRoot(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper, EffectState.Mode.PVE)) {
            test.hit(test.target, 1, true); test.hit(test.neighbor, 1, true); test.hit(test.neighbor, 8, false);
            test.hit(test.target, 19, false);
            helper.assertValueEqual(test.queries.size(), 2, "both targets detonate");
            helper.assertValueEqual(new HashSet<>(test.queries.stream().map(TargetQuery::center).toList()), Set.of(new TargetQuery.EntityCenter(id(test.target)), new TargetQuery.EntityCenter(id(test.neighbor))), "independent centers");
            helper.assertValueEqual(new HashSet<>(test.queryRoots).size(), 1, "legitimate cascade stays in one root");
            helper.assertTrue(test.buff(VOLATILE, test.target).isEmpty() && test.buff(VOLATILE, test.neighbor).isEmpty(), "consumed statuses terminate this cascade");
            helper.assertTrue(test.buff(COOLDOWN, test.target).isPresent() && test.buff(COOLDOWN, test.neighbor).isPresent(), "independent per-target cooldowns");
            near(helper, test.target.getHealth(), 99 - 19 - 14.5 - 11.6, "first target receives both blasts");
            near(helper, test.neighbor.getHealth(), 99 - 8 - 11.6 - 14.5, "second target threshold and own blast");
            var hits = test.requests.stream().filter(r -> r.command() instanceof DamageCommand).toList();
            helper.assertValueEqual(hits.size(), 4, "four actual explosion components");
            helper.assertValueEqual(hits.stream().map(RuleEngine.WorldRequest::id).distinct().count(), 4L, "no operation reuse across the cascade");
        }
        helper.succeed();
    }
    @GameCase public void readOnlyDeadQualificationStillHonorsStatusPolicyAndMissingEntities(GameTestHelper helper) throws Exception {
        try (var test = new Harness(helper, EffectState.Mode.PVE)) {
            test.target.setHealth(0);
            helper.assertValueEqual(test.qualify(false), StatusResult.Decision.DEAD, "ordinary live-only qualification");
            helper.assertValueEqual(test.qualify(true), StatusResult.Decision.ALLOWED, "explicit dead qualification");
            test.denied = true; helper.assertValueEqual(test.qualify(true), StatusResult.Decision.DENIED, "status policy still checked for dead entities");
            helper.assertTrue(test.runtime.state().engine().domain().buffs().instances().isEmpty(), "Read-only checks committed a buff");
            near(helper, test.target.getHealth(), 0, "qualification never revives");
            test.target.discard(); helper.assertValueEqual(test.qualify(true), StatusResult.Decision.MISSING, "allow_dead does not bypass missing target");
        }
        helper.succeed();
    }
}
