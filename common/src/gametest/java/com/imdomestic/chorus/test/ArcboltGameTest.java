package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Impact is supplied by the host; flight and ground contact are not implemented by this fixture. */
public class ArcboltGameTest {
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final List<LivingEntity> entities = new ArrayList<>();
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final List<DamageCommand> hits = new ArrayList<>();
        final List<DamageReceipt> receipts = new ArrayList<>();
        final List<TargetQuery.Result> queries = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        final LivingEntity owner, impact;
        final EffectSource source;
        final BuffInstance.Origin cast;
        final MinecraftEffectRuntime runtime;
        String discardAfterDamage = "";
        Harness(GameTestHelper h, EffectState.Mode mode) throws Exception {
            this.h = h; owner = cow(1, 220, 1); impact = cow(2.5, 40, 3.5);
            source = new EffectSource("arcbolt", "chorus_d2:arcbolt", id(owner), new BuffInstance.Origin(id(owner), "selection", "", "chorus_d2:arcbolt"), Set.of());
            cast = new BuffInstance.Origin(id(owner), "cast-" + UUID.randomUUID(), "", "chorus_d2:arcbolt");
            var fragments = new ArrayList<EffectProgram>();
            for (String name : List.of("arcbolt", "combat_damage", "character_stats", "armor_stats", "armor_stat_inputs", "ability_stat_damage"))
                fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json(name)).getOrThrow());
            var program = CompiledEffects.link(fragments);
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), this::resolve, d -> new DamageSource(type, null, resolve(d.source().owner())), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withMode(mode).withSource(source),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request);
                        if (result instanceof TargetQuery.Result q) queries.add(q);
                        if (result instanceof DamageReceipt receipt) {
                            var d = (DamageCommand) request.command(); hits.add(d); receipts.add(receipt); times.add(time());
                            if (d.target().equals(discardAfterDamage)) resolve(d.target()).discard();
                        }
                        return result;
                    }, MinecraftEffectRuntime::nativeSource);
        }
        long time() { return runtime.state().engine().timeMicros(); }
        LivingEntity resolve(String ref) { return h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null; }
        LivingEntity cow(double x, double y, double z) {
            var e = h.spawnWithNoFreeWill(EntityTypes.COW, (int) x, (int) y, (int) z); e.setPos(h.absoluteVec(new Vec3(x, y, z))); e.setNoGravity(true);
            e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(100); entities.add(e); return e;
        }
        void wall(int from, int through) {
            for (int y = from; y <= through; y++) { var p = h.absolutePos(new BlockPos(3, y, 3)); blocks.putIfAbsent(p, h.getLevel().getBlockState(p)); h.getLevel().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState()); }
        }
        void fire() { runtime.start(new RuleEngine.Signal("test:arcbolt_impact", new EffectEvent(id(owner), id(impact), cast, Set.of(), Map.of()))); }
        void finish(Runnable assertions) {
            h.runAfterDelay(25, () -> {
                try {
                    h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Arcbolt failed: " + runtime.failure());
                    h.assertTrue(runtime.state().engine().domain().timers().isEmpty(), "Arcbolt delay was retained");
                    assertions.run(); h.succeed();
                } finally { close(); }
            });
        }
        @Override public void close() { runtime.close(); entities.forEach(LivingEntity::discard); blocks.forEach((p, state) -> h.getLevel().setBlockAndUpdate(p, state)); }
    }
    @GameCase(environment = "chorus_gametest:arcbolt_stat_snapshot", maxTicks = 60)
    public void actualDelayedBoltsRetainEnhancedGrenadeStatAfterArmorRemoval(GameTestHelper h) throws Exception {
        var t = new Harness(h, EffectState.Mode.PVE);
        try {
            var first = t.cow(2.5, 44, 3.5); var second = t.cow(2.5, 54, 3.5);
            var values = new TreeMap<String, com.imdomestic.chorus.stat.Measure>();
            for (String stat : List.of("health", "class", "grenade", "melee", "super", "weapons"))
                values.put(stat, new com.imdomestic.chorus.stat.Measure(stat.equals("grenade") ? 200 : 0, com.imdomestic.chorus.stat.Unit.STAT_POINT));
            var gear = new com.imdomestic.chorus.effect.equipment.Loadout(Map.of("chorus_d2:arms",
                    new com.imdomestic.chorus.effect.equipment.Loadout.Gear("armor", "test:armor_arms", Map.of(), values)), Optional.empty());
            t.runtime.bind(new EffectSource("scaling", "chorus_d2:ability_stat_damage", id(t.owner), new BuffInstance.Origin(id(t.owner), "scaling", "", ""), Set.of()));
            t.runtime.equip(new com.imdomestic.chorus.effect.equipment.EquipmentChange(id(t.owner), com.imdomestic.chorus.effect.equipment.Loadout.EMPTY, gear));
            t.fire();
            t.runtime.equip(new com.imdomestic.chorus.effect.equipment.EquipmentChange(id(t.owner), gear, com.imdomestic.chorus.effect.equipment.Loadout.EMPTY));
            t.runtime.unbind("scaling");
            t.finish(() -> {
                h.assertValueEqual(t.hits.size(), 2, "two actual delayed bolts");
                for (var enemy : List.of(first, second)) near(h, enemy.getHealth(), 100 - 52.1 * 1.65, "captured grenade multiplier survives armor and scaling-source removal");
                h.assertTrue(t.hits.getFirst().snapshot().isPresent() && t.hits.getFirst().snapshot().equals(t.hits.getLast().snapshot()), "chain reuses immutable attack snapshot");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:arcbolt_chain", maxTicks = 60)
    public void realTickLocksVisibleTargetAndChainsFromMovedLethalHitThroughFourDistinctEnemies(GameTestHelper h) throws Exception {
        var t = new Harness(h, EffectState.Mode.PVE);
        try {
            var hidden = t.cow(4.5, 40, 3.5); var first = t.cow(2.5, 44, 3.5); first.setHealth(1);
            var second = t.cow(2.5, 154, 3.5); var third = t.cow(2.5, 164, 3.5); var fourth = t.cow(2.5, 174, 3.5); var fifth = t.cow(2.5, 184, 3.5);
            t.wall(40, 41); t.fire();
            h.assertValueEqual(t.queries.getFirst().targets().stream().map(TargetQuery.Target::entity).toList(), List.of(id(first)), "occluded nearer enemy must not consume first selection");
            h.assertTrue(t.hits.isEmpty(), "scan must not immediately damage");
            first.setPos(first.getX(), h.absoluteVec(new Vec3(2.5, 144, 3.5)).y, first.getZ());
            t.runtime.unbind(t.source.instance()); t.impact.discard(); hidden.discard();
            var late = t.cow(2.5, 41, 3.5); t.discardAfterDamage = id(first);
            t.finish(() -> {
                h.assertValueEqual(t.hits.stream().map(DamageCommand::target).toList(), List.of(id(first), id(second), id(third), id(fourth)), "chain identity and cap");
                h.assertTrue(!first.isAlive() && t.receipts.getFirst().lethal(), "first real death");
                for (var e : List.of(second, third, fourth)) near(h, e.getHealth(), 47.9, "PvE bolt actual health loss");
                near(h, fifth.getHealth(), 100, "fourth target must end chain"); near(h, late.getHealth(), 100, "initial scan cannot retarget a late arrival");
                h.assertValueEqual(t.times, List.of(1_000_000L, 1_000_000L, 1_000_000L, 1_000_000L), "one second activation and explicit immediate hops");
                h.assertTrue(t.hits.stream().allMatch(d -> d.source().equals(t.cast) && d.killTags().contains("chorus:grenade_kill")), "cast ownership and credit");
                h.assertValueEqual(t.queries.size(), 4, "first scan and three fresh chain scans");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:arcbolt_cancel", maxTicks = 60)
    public void cancelledSecondHitStopsChainWithoutRetargetingOrHurtingTheThirdEnemy(GameTestHelper h) throws Exception {
        String denied = "arcbolt-denied-" + UUID.randomUUID(); TestDamageHooks.ALLOW_DAMAGE.register((target, _, _) -> !target.entityTags().contains(denied));
        var t = new Harness(h, EffectState.Mode.PVP);
        try {
            var first = t.cow(2.5, 44, 3.5); var second = t.cow(2.5, 54, 3.5); var third = t.cow(2.5, 64, 3.5); second.addTag(denied); t.fire();
            t.finish(() -> {
                h.assertValueEqual(t.hits.stream().map(DamageCommand::target).toList(), List.of(id(first), id(second)), "rejected selected target ends chain");
                near(h, first.getHealth(), 91.5, "PvP damage"); near(h, second.getHealth(), 100, "cancelled damage"); near(h, third.getHealth(), 100, "no fallback hop");
                h.assertValueEqual(t.receipts.getLast().outcome(), DamageReceipt.Outcome.CANCELLED, "real loader cancellation"); h.assertValueEqual(t.queries.size(), 2, "no scan after rejection");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:arcbolt_missing", maxTicks = 60)
    public void removedFirstTargetProducesNoDamageAndCannotBeReplacedByALateEnemy(GameTestHelper h) throws Exception {
        var t = new Harness(h, EffectState.Mode.PVE);
        try {
            var first = t.cow(2.5, 44, 3.5); t.fire(); first.discard(); var replacement = t.cow(2.5, 44, 3.5);
            t.finish(() -> {
                h.assertValueEqual(t.hits.stream().map(DamageCommand::target).toList(), List.of(id(first)), "target identity remains frozen");
                h.assertValueEqual(t.receipts.getFirst().outcome(), DamageReceipt.Outcome.FAILED, "missing target observation");
                near(h, replacement.getHealth(), 100, "late replacement remains untouched"); h.assertValueEqual(t.queries.size(), 1, "no new first scan");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:arcbolt_visibility", maxTicks = 60)
    public void lateWallDoesNotRewriteFirstScanAndSubsequentHopsUseExplicitNoSightPolicy(GameTestHelper h) throws Exception {
        var t = new Harness(h, EffectState.Mode.PVP);
        try {
            var first = t.cow(4.5, 40, 3.5); var second = t.cow(2.5, 44, 3.5); t.fire(); t.wall(40, 45);
            t.finish(() -> {
                h.assertValueEqual(t.hits.stream().map(DamageCommand::target).toList(), List.of(id(first), id(second)), "late wall and chain sight policy");
                near(h, first.getHealth(), 91.5, "retained first target"); near(h, second.getHealth(), 91.5, "chain after late wall");
                h.assertTrue(t.queries.getFirst().query().lineOfSight() && t.queries.stream().skip(1).noneMatch(q -> q.query().lineOfSight()), "only original scan uses sight");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
}
