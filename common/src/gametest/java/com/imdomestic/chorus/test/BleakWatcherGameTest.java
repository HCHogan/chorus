package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Real thrown deployment, independently damageable turrets and each physical projectile's hit. */
public class BleakWatcherGameTest {
    static final String ABILITY = "chorus_d2:bleak_watcher", BEHAVIOR = ABILITY + "_behavior", SLOT = "chorus_d2:grenade", ENERGY = ABILITY + "_energy";
    static CompiledEffects program() {
        var data = ThreadedSpikeGameTest.json("bleak_watcher");
        ThreadedSpikeGameTest.json("bleak_watcher_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e ->
                data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
        var parts = new ArrayList<EffectProgram>(); parts.add(DuranceGameTest.program().program()); parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        for (String name : List.of("bleak_watcher_energy", "bleak_watcher_inputs")) parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json(name)).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static String id(Entity e) { return e.getUUID().toString(); }
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final MinecraftWorldActions world; final MinecraftEffectRuntime runtime; final ServerPlayer owner;
        final List<LivingEntity> entities = new ArrayList<>(); final List<EffectConstruct> turrets = new ArrayList<>(); final List<EffectProjectile> flights = new ArrayList<>();
        final List<ProjectileFlight.Launch> launches = new ArrayList<>(); final List<DamageCommand> damage = new ArrayList<>(); final List<DamageReceipt> receipts = new ArrayList<>(); final List<StatusResult.Check> checks = new ArrayList<>();
        final Map<BlockPos, BlockState> blocks = new HashMap<>(); boolean failDamage;
        Harness(GameTestHelper h) {
            this.h = h; owner = player(2.5); floor(2);
            world = new MinecraftWorldActions(h.getLevel(), this::resolve, d -> new DamageSource(h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                    .getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(d.damageType()))), null, resolve(d.source().owner())), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program(), EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof StatusResult.Check check) checks.add(check);
                var result = world.apply(request);
                if (result instanceof WorldConstruct.Receipt r && r.entity().isPresent()) turrets.add((EffectConstruct) h.getLevel().getEntity(UUID.fromString(r.entity().orElseThrow())));
                if (result instanceof ProjectileFlight.Receipt r && r.entity().isPresent()) { launches.add(r.launch()); flights.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(r.entity().orElseThrow()))); }
                if (result instanceof DamageReceipt r && request.command() instanceof DamageCommand d && d.tags().contains("chorus_d2:bleak_watcher_bolt")) {
                    damage.add(d); receipts.add(r); if (failDamage) throw new IllegalStateException("Unknown physical turret damage outcome");
                }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity resolve(String key) { try { return h.getLevel().getEntity(UUID.fromString(key)) instanceof LivingEntity e ? e : null; } catch (IllegalArgumentException invalid) { return null; } }
        EffectState state() { return runtime.state().engine().domain(); }
        ServerPlayer player(double x) { var p = NativeMeleeGameTest.player(h); p.setPos(h.absoluteVec(new Vec3(x, 40, 2.5))); p.setNoGravity(true); p.setXRot(90); p.setYRot(0); p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); p.setHealth(1000); entities.add(p); return p; }
        LivingEntity mob(double x) { var e = h.spawnWithNoFreeWill(EntityTypes.COW, new Vec3(x, 40, 2.5)); e.setNoGravity(true); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(1000); e.addTag("chorus_d2:elite"); entities.add(e); return e; }
        void floor(int x) { var p = h.absolutePos(new BlockPos(x, 39, 2)); blocks.putIfAbsent(p, h.getLevel().getBlockState(p)); h.getLevel().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState()); }
        void cast(ServerPlayer player) {
            runtime.abilities(new AbilityChange(id(player), state().abilities().getOrDefault(id(player), AbilityLoadout.EMPTY), new AbilityLoadout(Map.of(SLOT, ABILITY))));
            h.assertValueEqual(runtime.useAbility(player, SLOT).outcome(), AbilityUse.Outcome.ACCEPTED, "turret cast accepted");
            player.setPos(player.getX(), player.getY(), h.absoluteVec(new Vec3(0, 0, 6)).z); // Leave the captured deployment column.
        }
        Optional<BuffInstance> buff(String name, Entity entity) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(name) && b.key().holder().equals(id(entity))).findFirst(); }
        List<ProjectileFlight.Launch> bolts() { return launches.stream().filter(l -> !l.owner().equals(l.emitter())).toList(); }
        EffectConstruct dummy(LivingEntity owner, double x, double z) {
            var p = h.absoluteVec(new Vec3(x, 40, z)); var command = new WorldConstruct.Spawn(Optional.of(new WorldPosition(h.getLevel().dimension().identifier().toString(), p.x, p.y, p.z)),
                    "test:friendly_construct", new BuffInstance.Origin(id(owner), "dummy", "", ""), new WorldConstruct.Parameters(150, .5, .75, 30_000_000), Set.of(), runtime.program().program().version());
            var r = (WorldConstruct.Receipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), command)); return (EffectConstruct) h.getLevel().getEntity(UUID.fromString(r.entity().orElseThrow()));
        }
        Optional<Boolean> relation(Entity left, Entity right) { var q = new RelationQuery(id(left), id(right)); return ((RelationQuery.Result) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), q))).allied(); }
        void healthy() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Bleak Watcher failed: " + runtime.failure()); }
        void at(int ticks, Runnable check) { h.runAfterDelay(ticks, () -> { try { healthy(); check.run(); } catch (RuntimeException | Error e) { close(); throw e; } }); }
        void finish(int ticks, Runnable check) { at(ticks, () -> { check.run(); close(); h.succeed(); }); }
        @Override public void close() { runtime.close(); flights.forEach(Entity::discard); entities.forEach(Entity::discard); blocks.forEach((p, s) -> h.getLevel().setBlockAndUpdate(p, s)); }
    }
    @GameCase(environment="chorus_gametest:bleak_burst", maxTicks=50)
    public void realFirstBurstRemovesInitialResistanceHitsFiveTimesAndFreezesACombatant(GameTestHelper h) {
        var t = new Harness(h);
        try {
            var target = t.mob(6.5); var friendly = t.dummy(t.owner, 5.5, 3.5); t.cast(t.owner);
            t.at(4, () -> { h.assertValueEqual(t.turrets.size(), 1, "physical deployment"); var turret = t.turrets.getFirst(); near(h, turret.getHealth(), 150, "construct health");
                h.assertTrue(turret.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(target), 100), "initial damage rejected"); near(h, turret.getHealth(), 117, "67 percent initial resistance"); });
            t.at(16, () -> { var turret = t.turrets.getFirst(); h.assertTrue(!t.bolts().isEmpty(), "first shot did not launch"); // Native damageCooldownTime from the first probe has elapsed.
                h.assertTrue(turret.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(target), 10), "post-launch damage rejected"); near(h, turret.getHealth(), 107, "initial resistance remained after firing"); });
            t.finish(35, () -> { h.assertValueEqual(t.bolts().size(), 5, "first burst projectile count"); h.assertValueEqual(t.damage.size(), 5, "physical hit count");
                near(h, target.getHealth(), 995, "native hit cooldown suppressed rapid projectiles"); near(h, friendly.getHealth(), 150, "own construct was selected as enemy");
                h.assertTrue(t.buff("chorus_d2:freeze", target).isPresent() && t.buff("chorus_d2:slow", target).isEmpty(), "five twenty-stack hits did not Freeze");
                h.assertValueEqual(target.getLastDamageSource().getEntity(), t.owner, "wrong native attacker"); h.assertTrue(target.getLastDamageSource().is(DamageTypeTags.BYPASSES_COOLDOWN), "wrong projectile damage registry type");
                h.assertTrue(t.damage.stream().allMatch(d -> d.source().ability().equals(ABILITY) && d.source().owner().equals(id(t.owner)) && d.tags().contains("chorus:grenade_damage")), "grenade credit lost"); });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:bleak_guardian", maxTicks=90)
    public void twoPhysicalBurstsApplyTenStacksPerGuardianHitAndReachFreeze(GameTestHelper h) {
        var t = new Harness(h); try { var target = t.player(6.5); t.cast(t.owner);
            t.at(35, () -> { h.assertValueEqual(t.buff("chorus_d2:slow", target).orElseThrow().count(), 50, "Guardian first burst Slow"); h.assertTrue(t.buff("chorus_d2:freeze", target).isEmpty(), "Guardian froze after only five hits"); });
            t.finish(75, () -> { h.assertValueEqual(t.damage.size(), 10, "two physical bursts"); near(h, target.getHealth(), 990, "ten native damage commits"); h.assertTrue(t.buff("chorus_d2:freeze", target).isPresent(), "Guardian did not Freeze at 100 Slow"); });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:bleak_destroyed", maxTicks=45)
    public void destroyingTheTurretStopsFutureShotsButItsFirstProjectileStillAppliesSlow(GameTestHelper h) {
        var t = new Harness(h); try { var target = t.mob(6.5); t.cast(t.owner);
            t.at(8, () -> { h.assertValueEqual(t.bolts().size(), 1, "expected one launched projectile"); var turret = t.turrets.getFirst();
                h.assertTrue(turret.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1000), "turret was not destroyed"); h.assertTrue(t.buff(BEHAVIOR, turret).isEmpty(), "death did not cancel behavior"); });
            t.finish(35, () -> { h.assertValueEqual(t.bolts().size(), 1, "unfired burst members survived destruction"); h.assertValueEqual(t.damage.size(), 1, "already flying shot was lost or repeated");
                near(h, target.getHealth(), 999, "detached real damage"); h.assertValueEqual(t.buff("chorus_d2:slow", target).orElseThrow().count(), 20, "destroyed behavior lost impact parameters"); });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:bleak_lifetime", maxTicks=640)
    public void alliedTurretsDoNotTargetEachOtherAndKeepIndependentDuranceLifetimes(GameTestHelper h) {
        var t = new Harness(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("bleak-" + UUID.randomUUID());
        try { var other = t.player(6.5); t.floor(6); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(other.getScoreboardName(), team);
            t.runtime.bind(DuranceGameTest.fragment("own", t.owner)); t.cast(t.owner); t.cast(other);
            t.at(5, () -> { h.assertValueEqual(t.turrets.size(), 2, "two deployments"); t.runtime.unbind("own"); });
            t.at(505, () -> { var extended = t.turrets.stream().filter(e -> e.construct().orElseThrow().origin().owner().equals(id(t.owner))).findFirst().orElseThrow();
                var ordinary = t.turrets.stream().filter(e -> e.construct().orElseThrow().origin().owner().equals(id(other))).findFirst().orElseThrow();
                h.assertTrue(extended.isAlive() && ordinary.isRemoved(), "25 and 30 second lifetimes did not separate"); h.assertTrue(t.bolts().isEmpty(), "allied construct became a target"); });
            t.at(610, () -> { h.assertTrue(t.turrets.stream().allMatch(Entity::isRemoved), "Durance turret exceeded its captured lifetime"); h.assertTrue(t.state().timers().isEmpty(), "expired turret kept timers"); scoreboard.removePlayerTeam(team); t.close(); h.succeed(); });
        } catch (RuntimeException | Error e) { scoreboard.removePlayerTeam(team); t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:bleak_alliance", maxTicks=45)
    public void allianceChangesBeforeImpactPreventFriendlyDamageAndUnknownOwnersAreNotEnemies(GameTestHelper h) {
        var t = new Harness(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("bleak-impact-" + UUID.randomUUID());
        try { var target = t.mob(6.5); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); t.cast(t.owner);
            t.at(8, () -> { h.assertValueEqual(t.bolts().size(), 1, "expected projectile in flight"); scoreboard.addPlayerToTeam(target.getScoreboardName(), team); });
            t.at(30, () -> { h.assertTrue(t.damage.isEmpty(), "new ally received projectile damage"); near(h, target.getHealth(), 1000, "friendly health changed"); h.assertTrue(t.buff("chorus_d2:slow", target).isEmpty(), "new ally received Slow");
                var first = t.turrets.getFirst(); var second = t.dummy(t.owner, 5.5, 3.5); h.assertValueEqual(t.relation(first, second), Optional.of(true), "same owner allegiance");
                t.owner.discard(); h.assertValueEqual(t.relation(first, second), Optional.of(true), "stable ownership identity lost"); h.assertTrue(t.relation(target, first).isEmpty(), "unknown owner invented hostility");
                scoreboard.removePlayerTeam(team); t.close(); h.succeed(); });
        } catch (RuntimeException | Error e) { scoreboard.removePlayerTeam(team); t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:bleak_fault", maxTicks=40)
    public void unknownPhysicalHitKeepsDamageAndSpentEnergyWithoutReplayingOrApplyingSlow(GameTestHelper h) {
        var t = new Harness(h); try { var target = t.mob(6.5); t.failDamage = true; t.cast(t.owner);
            h.runAfterDelay(25, () -> { try (t) { h.assertTrue(t.runtime.failure().isPresent(), "unknown damage did not stop runtime"); h.assertValueEqual(t.damage.size(), 1, "unknown damage replayed");
                near(h, target.getHealth(), 999, "committed health loss was changed"); h.assertTrue(t.checks.isEmpty(), "unknown hit applied Slow");
                h.assertTrue(t.state().resources().get(new com.imdomestic.chorus.effect.resource.ResourceState.Key(id(t.owner), ENERGY)).value() < .01, "charge was refunded"); h.succeed(); } });
        } catch (RuntimeException | Error e) { t.close(); throw e; }
    }
}
