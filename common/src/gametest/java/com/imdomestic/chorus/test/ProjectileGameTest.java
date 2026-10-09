package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ProjectileGameTest {
    private static String id(Entity entity) { return entity.getUUID().toString(); }
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final MinecraftWorldActions world; final MinecraftEffectRuntime runtime;
        final List<LivingEntity> entities = new ArrayList<>(); final List<EffectProjectile> projectiles = new ArrayList<>();
        final List<DamageCommand> hits = new ArrayList<>(); final List<DamageReceipt> receipts = new ArrayList<>(); final List<TargetQuery> queries = new ArrayList<>();
        final List<Action.CueCommand> cues = new ArrayList<>(); final List<RuleEngine.OperationId> operations = new ArrayList<>();
        final Map<BlockPos, BlockState> blocks = new HashMap<>(); boolean failAfterDamage;
        Harness(GameTestHelper h) throws Exception { this(h, _ -> {}); }
        Harness(GameTestHelper h, Consumer<JsonObject> edit) throws Exception { this(h, "projectile", edit); }
        Harness(GameTestHelper h, String fixture, Consumer<JsonObject> edit) throws Exception {
            this.h = h; owner = h.makeMockServerPlayerInLevel(); owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(-90);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); owner.setHealth(10);
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
                var data = JsonParser.parseReader(reader).getAsJsonObject(); edit.accept(data); program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
            }
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            world = new MinecraftWorldActions(h.getLevel(), this::resolve, _ -> new DamageSource(type, null, owner), (_, _) -> true, cues::add);
            var source = new EffectSource("launch", "test:projectile", id(owner), new BuffInstance.Origin(id(owner), "source", "", "test:bolt"), Set.of());
            var boost = new EffectSource("boost", "test:power", id(owner), source.origin(), Set.of());
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source).withSource(boost), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                operations.add(request.id()); var result = world.apply(request);
                if (result instanceof ProjectileFlight.Receipt receipt && receipt.entity().isPresent()) projectiles.add((EffectProjectile) h.getLevel().getEntity(UUID.fromString(receipt.entity().orElseThrow())));
                if (result instanceof DamageReceipt receipt) { hits.add((DamageCommand) request.command()); receipts.add(receipt); if (failAfterDamage) throw new IllegalStateException("Injected unknown projectile damage outcome"); }
                if (request.command() instanceof TargetQuery q) queries.add(q);
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity resolve(String ref) { return h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null; }
        LivingEntity cow(double x, double y, double z) {
            var e = h.spawnWithNoFreeWill(EntityTypes.COW, (int) x, (int) y, (int) z); e.setPos(h.absoluteVec(new Vec3(x, y, z))); e.setNoGravity(true);
            e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(100); entities.add(e); return e;
        }
        void fire() { runtime.start(new RuleEngine.Signal("test:launch", new EffectEvent(id(owner), id(owner), new BuffInstance.Origin(id(owner), "source", "", "test:bolt"), Set.of(), Map.of()))); }
        void ability() {
            runtime.abilities(new AbilityChange(id(owner), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:grenade", "test:bolt"))));
            h.assertValueEqual(runtime.useAbility(owner, "test:grenade").outcome(), AbilityUse.Outcome.ACCEPTED, "server ability accepted");
        }
        void wall() { for (int y = 40; y <= 43; y++) { var p = h.absolutePos(new BlockPos(4, y, 3)); blocks.put(p, h.getLevel().getBlockState(p)); h.getLevel().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState()); } }
        void finish(int ticks, Runnable checks) {
            h.runAfterDelay(ticks, () -> { try { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Projectile runtime failed: " + runtime.failure()); checks.run(); h.succeed(); } finally { close(); } });
        }
        @Override public void close() { runtime.close(); projectiles.forEach(Entity::discard); owner.discard(); entities.forEach(Entity::discard); blocks.forEach((p, b) -> h.getLevel().setBlockAndUpdate(p, b)); }
    }
    @GameCase(environment = "chorus_gametest:projectile_entity", maxTicks = 60)
    public void paidAbilityLaunchesPhysicalProjectileAndRetainsCapturedPowerAfterOwnerMovesAndSourceDetaches(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var victim = t.cow(2.5, 46, 3.5); t.ability(); h.assertTrue(t.hits.isEmpty() && t.projectiles.size() == 1, "launch is not hitscan");
            near(h, t.runtime.state().engine().domain().resources().get(new ResourceState.Key(id(t.owner), "test:energy")).value(), 0, "paid before physical spawn");
            var projectile = t.projectiles.getFirst(); t.runtime.unbind("boost"); t.runtime.unbind("launch"); t.owner.setPos(h.absoluteVec(new Vec3(5, 40, 3.5)));
            t.finish(10, () -> {
                near(h, victim.getHealth(), 80, "frozen doubled attack"); near(h, t.owner.getHealth(), 30, "retained self and actual hit receipt");
                h.assertValueEqual(t.hits.size(), 1, "one terminal impact"); h.assertTrue(projectile.isRemoved(), "physical entity consumed");
                h.assertValueEqual(t.hits.getFirst().source().ability(), "test:bolt", "selected ability credit"); h.assertTrue(t.hits.getFirst().snapshot().isPresent(), "physical impact lost snapshot");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:projectile_block", maxTicks = 60)
    public void sweptBlockCollisionStopsDirectFlightAndUsesExactContactForAreaActions(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.owner.setYRot(-90); t.owner.setXRot(0); var target = t.cow(5.5, 40, 3.5); t.wall(); t.fire();
            t.finish(8, () -> {
                h.assertValueEqual(t.queries.size(), 1, "block body queried its contact point");
                var point = ((TargetQuery.PositionCenter) t.queries.getFirst().center()).position().orElseThrow();
                near(h, point.x(), h.absoluteVec(new Vec3(4, 40, 3.5)).x, "wall face contact");
                near(h, target.getHealth(), 80, "area damage beyond wall by explicit no-sight policy"); near(h, t.owner.getHealth(), 10, "direct-hit healing branch did not run");
                h.assertValueEqual(t.hits.size(), 1, "no direct plus explosion double hit"); h.assertTrue(t.projectiles.getFirst().isRemoved(), "wall consumed entity");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:projectile_expiry", maxTicks = 60)
    public void gravityAndDragAdvanceActualEntityAndExpiryRunsOnceWithoutInventingAnEntityHit(GameTestHelper h) throws Exception {
        var t = new Harness(h, data -> {
            var spec = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do").get(3).getAsJsonObject().getAsJsonObject("projectile");
            spec.getAsJsonObject("speed").addProperty("value", 0); spec.getAsJsonObject("gravity").addProperty("value", 20); spec.getAsJsonObject("drag").addProperty("value", 0.5); spec.getAsJsonObject("lifetime").addProperty("value", 0.1);
        });
        try {
            t.fire(); var projectile = t.projectiles.getFirst(); double start = projectile.getY();
            t.finish(6, () -> {
                near(h, projectile.getY(), start - 0.0625, "two semi-implicit 50ms gravity/drag steps");
                near(h, t.owner.getHealth(), 11, "expiry body once"); h.assertTrue(t.hits.isEmpty() && t.queries.isEmpty() && projectile.isRemoved(), "expiry fabricated a collision");
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
    @GameCase public void absentGeometryAndUnknownChunksRejectLaunchWhileStoppedRuntimeCannotExecuteOldFlight(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.fire(); var original = t.projectiles.getFirst().launch().orElseThrow();
            var far = new WorldPosition(h.getLevel().dimension().identifier().toString(), 1_000_001, 80, 1_000_001);
            var cases = List.of(
                    new ProjectileFlight.Launch(Optional.empty(), original.direction(), original.parameters(), original.owner(), original.continuation(), original.impactSlot()),
                    new ProjectileFlight.Launch(original.position(), Optional.empty(), original.parameters(), original.owner(), original.continuation(), original.impactSlot()),
                    new ProjectileFlight.Launch(original.position(), Optional.of(new WorldDirection("other:world", 1, 0, 0)), original.parameters(), original.owner(), original.continuation(), original.impactSlot()),
                    new ProjectileFlight.Launch(Optional.of(far), original.direction(), original.parameters(), original.owner(), original.continuation(), original.impactSlot()));
            var outcomes = List.of(ProjectileFlight.Outcome.MISSING_POSITION, ProjectileFlight.Outcome.MISSING_DIRECTION, ProjectileFlight.Outcome.WRONG_DIMENSION, ProjectileFlight.Outcome.UNLOADED);
            for (int i = 0; i < cases.size(); i++) {
                var receipt = (ProjectileFlight.Receipt) t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(9, i, 0), cases.get(i)));
                h.assertValueEqual(receipt.outcome(), outcomes.get(i), "explicit rejected spawn reason"); h.assertTrue(receipt.entity().isEmpty(), "rejection created identity");
            }
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(1_000_001 >> 4, 1_000_001 >> 4) == null, "launch loaded unknown chunk");
            t.projectiles.getFirst().setPos(far.x(), far.y(), far.z()); t.projectiles.getFirst().tick();
            h.assertTrue(t.projectiles.getFirst().isRemoved() && t.runtime.failure().isEmpty() && t.hits.isEmpty(), "unknown terrain must terminate without impact damage");
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(1_000_001 >> 4, 1_000_001 >> 4) == null, "flight loaded unknown chunk");
            t.fire(); t.runtime.close(); t.projectiles.getLast().tick(); h.assertTrue(t.projectiles.getLast().isRemoved() && t.hits.isEmpty(), "stopped runtime retained executable flight");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:projectile_failure", maxTicks = 60)
    public void unknownImpactOutcomeRetainsCommittedDamageAndConsumesProjectileWithoutReplay(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            var victim = t.cow(2.5, 46, 3.5); t.failAfterDamage = true; t.fire();
            h.runAfterDelay(10, () -> {
                try {
                    h.assertTrue(t.runtime.failure().isPresent(), "unknown world outcome did not stop runtime");
                    near(h, victim.getHealth(), 80, "committed damage retained exactly once"); near(h, t.owner.getHealth(), 10, "later healing did not execute");
                    h.assertTrue(t.projectiles.getFirst().isRemoved(), "failed terminal projectile was retained");
                    t.projectiles.getFirst().tick(); h.assertValueEqual(t.hits.size(), 1, "unknown damage must not replay"); h.succeed();
                } finally { t.close(); }
            });
        } catch (Throwable error) { t.close(); throw error; }
    }
}
