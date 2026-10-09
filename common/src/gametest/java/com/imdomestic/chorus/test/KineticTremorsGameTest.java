package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
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

/** Weapon/archetype/rank identity is supplied explicitly by this test host; all perk behavior is JSON. */
public class KineticTremorsGameTest {
    private static final String PERK = "chorus_d2:kinetic_tremors";
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper helper; final List<LivingEntity> entities = new ArrayList<>();
        final LivingEntity owner; final EffectSource normal, enhanced;
        final List<TargetQuery.Result> queries = new ArrayList<>(); final List<Long> queryTimes = new ArrayList<>();
        final List<DamageCommand> waves = new ArrayList<>(); final List<DamageReceipt> receipts = new ArrayList<>();
        EffectSource current; String rank = "minor"; MinecraftEffectRuntime runtime;
        Harness(GameTestHelper helper, EffectState.Mode mode) throws Exception {
            this.helper = helper; owner = cow(2, 2);
            normal = weapon("normal", false); enhanced = weapon("enhanced", true); current = normal;
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(KineticTremorsGameTest.class.getResourceAsStream("/effects/kinetic_tremors.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var type = helper.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(helper.getLevel(), ref -> helper.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null,
                    _ -> new DamageSource(type, owner), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(helper.getLevel(), program, EffectState.empty().withMode(mode).withSource(normal).withSource(enhanced),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request);
                        if (result instanceof TargetQuery.Result selected) { queries.add(selected); queryTimes.add(runtime.state().engine().timeMicros()); }
                        if (result instanceof DamageReceipt receipt) { waves.add((DamageCommand) request.command()); receipts.add(receipt); }
                        return result;
                    }, (target, nativeSource, amount) -> new DamageCommand(id(target), current.origin(), amount, "minecraft:mob_attack",
                            Set.of("chorus:direct_weapon_hit", "chorus:target_rank/" + rank), Set.of("chorus:weapon_kill"), false));
        }
        EffectSource weapon(String name, boolean enhanced) {
            var tags = new HashSet<>(Set.of("chorus_d2:bow")); if (enhanced) tags.add("chorus:enhanced");
            return new EffectSource(name, PERK, id(owner), new BuffInstance.Origin(id(owner), name, name, ""), tags);
        }
        LivingEntity cow(int x, int z) {
            var entity = helper.spawnWithNoFreeWill(EntityTypes.COW, x, 40, z); entity.setNoGravity(true);
            entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
            entity.setHealth(1000); entities.add(entity); return entity;
        }
        void hit(LivingEntity target, EffectSource source) {
            current = source; target.damageCooldownTime = 0;
            helper.assertTrue(target.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(owner), 1), "direct input must really apply");
            helper.assertTrue(runtime.failure().isEmpty(), "Native hit failed: " + runtime.failure());
        }
        Optional<BuffInstance> buff(String suffix, LivingEntity target, EffectSource source) {
            return runtime.state().engine().domain().buffs().active(new BuffInstance.Key(id(target), PERK + suffix, source.origin().weapon()));
        }
        double count(LivingEntity target, EffectSource source) { return buff("_hits", target, source).map(b -> b.components().numbers().get("hits")).orElse(0.0); }
        void stow(EffectSource source) { runtime.start(new RuleEngine.Signal("chorus:weapon_stowed", new EffectEvent(id(owner), id(owner), source.origin(), Set.of(), Map.of()))); }
        @Override public void close() { if (runtime != null) runtime.close(); entities.forEach(LivingEntity::discard); }
    }
    @GameCase(environment = "chorus_gametest:tremors_pve", maxTicks = 65)
    public void realHitsTriggerThreeFrozenRankWavesAfterStowDetachAndOriginRemoval(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVE);
        try {
            var origin = test.cow(4, 4); var nearby = test.cow(7, 4); var outside = test.cow(11, 4); test.rank = "boss";
            test.hit(origin, test.normal); test.hit(origin, test.normal); test.stow(test.normal);
            h.assertValueEqual(test.count(origin, test.normal), 2.0, "stow retains hits"); h.assertTrue(test.queries.isEmpty(), "early activation");
            test.hit(origin, test.normal); h.assertValueEqual(test.count(origin, test.normal), 0.0, "trigger clears counter");
            h.assertValueEqual(test.buff("_cooldown", origin, test.normal).orElseThrow().deadline(), 4_250_000L, "cooldown includes three scheduled waves");
            test.runtime.unbind(test.normal.instance()); origin.discard(); test.rank = "minor";
            var replacement = test.cow(4, 4);
            h.runAfterDelay(4, () -> {
                try { h.assertTrue(test.waves.isEmpty(), "first wave preceded 250 ms"); }
                catch (Throwable failure) { test.close(); throw failure; }
            });
            h.runAfterDelay(50, () -> {
                try (test) {
                    h.assertTrue(test.runtime.failure().isEmpty() && test.runtime.state().idle(), "Tremor execution failed: " + test.runtime.failure());
                    h.assertValueEqual(test.queryTimes, List.of(250_000L, 1_250_000L, 2_250_000L), "three independent wave times");
                    h.assertValueEqual(test.receipts.size(), 6, "two current targets per wave");
                    double wave = 9 * 1.333;
                    near(h, replacement.getHealth(), 1000 - 3 * wave, "frozen initial boss factor on replacement");
                    near(h, nearby.getHealth(), 1000 - 3 * wave, "frozen factor on another target");
                    near(h, outside.getHealth(), 1000, "outside six metres"); near(h, test.owner.getHealth(), 1000, "owner excluded");
                    h.assertTrue(test.waves.stream().allMatch(d -> d.snapshot().isPresent() && d.source().weapon().equals("normal")
                            && d.tags().contains("chorus:weapon_damage") && !d.tags().contains("chorus:direct_weapon_hit")), "wave identity or direct-hit exclusion lost");
                    h.assertTrue(test.runtime.state().engine().domain().timers().isEmpty(), "wave continuation leaked"); h.succeed();
                }
            });
        } catch (Throwable failure) { test.close(); throw failure; }
    }
    @GameCase(environment = "chorus_gametest:tremors_pvp", maxTicks = 110)
    public void enhancedAndNormalWeaponsKeepSeparateCountersAndPvpCooldownReopensAfterFinalWave(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVP);
        try {
            var a = test.cow(4, 4); var b = test.cow(24, 4); test.rank = "boss";
            test.hit(a, test.normal); test.hit(a, test.normal); test.stow(test.normal);
            test.hit(a, test.enhanced); test.hit(b, test.enhanced); test.hit(b, test.enhanced);
            h.assertTrue(test.buff("_cooldown", b, test.enhanced).isPresent(), "enhanced bow needs two hits");
            h.assertTrue(test.buff("_cooldown", a, test.normal).isEmpty(), "normal bow should still require its third hit");
            test.hit(a, test.normal);
            h.runAfterDelay(84, () -> {
                try {
                    h.assertTrue(test.runtime.failure().isEmpty(), "Tremor runtime failed: " + test.runtime.failure());
                    h.assertValueEqual(test.receipts.size(), 6, "both independent three-wave sequences");
                    h.assertValueEqual(test.queryTimes, List.of(250_000L, 250_000L, 1_250_000L, 1_250_000L, 2_250_000L, 2_250_000L), "independent schedules");
                    near(h, a.getHealth(), 990, "four direct hits plus three PvP waves"); near(h, b.getHealth(), 992, "two direct hits plus three PvP waves");
                    test.hit(a, test.normal); h.assertValueEqual(test.count(a, test.normal), 0.0, "cooldown still excludes direct input");
                    h.assertValueEqual(test.buff("_cooldown", a, test.normal).orElseThrow().deadline(), 4_250_000L, "blocked hit did not extend cooldown");
                } catch (Throwable failure) { test.close(); throw failure; }
            });
            h.runAfterDelay(88, () -> {
                try (test) {
                    test.hit(a, test.normal); h.assertValueEqual(test.count(a, test.normal), 1.0, "first post-cooldown hit starts a new counter");
                    h.assertTrue(test.buff("_cooldown", a, test.normal).isEmpty(), "cooldown expired");
                    h.assertValueEqual(test.receipts.size(), 6, "one new direct hit cannot create a new wave"); h.succeed();
                }
            });
        } catch (Throwable failure) { test.close(); throw failure; }
    }
}
