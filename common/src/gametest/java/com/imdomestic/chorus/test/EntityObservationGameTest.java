package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.effect.target.TargetShape;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public class EntityObservationGameTest {
    @GameCase public void nativeMovementFlagsAreFrozenIndependentlyOfEntityAvailability(GameTestHelper h) {
        var player=h.makeMockServerPlayerInLevel();
        try {
            player.setOnGround(true);player.setSprinting(true);player.setPose(net.minecraft.world.entity.Pose.CROUCHING);player.setSwimming(true);
            var world=new MinecraftWorldActions(h.getLevel(),_->player,_->h.getLevel().damageSources().generic(),(_,_) -> true,_ -> {});
            var before=inspect(world,"player").available().observedMovement();
            h.assertTrue(before.onGround()&&before.sprinting()&&before.crouching()&&before.swimming(),"native movement flags not captured");
            h.assertTrue(!before.fallFlying()&&!before.passenger()&&!before.sleeping(),"unrelated flags fabricated");
            player.setOnGround(false);player.setSprinting(false);player.setPose(net.minecraft.world.entity.Pose.STANDING);player.setSwimming(false);
            var after=inspect(world,"player").available().observedMovement();
            h.assertTrue(!after.onGround()&&!after.sprinting()&&!after.crouching()&&!after.swimming(),"fresh state not observed");
            h.assertTrue(before.sprinting()&&before.crouching(),"past movement observation mutated");
            player.discard();h.assertTrue(inspect(world,"player").view().isEmpty(),"removed entity movement fabricated");
        } finally {player.discard();}
        h.succeed();
    }
    private static final String ELITE = "chorus_gametest:elite";
    private static EntityQuery.Result inspect(MinecraftWorldActions world, String target) {
        return (EntityQuery.Result) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), new EntityQuery(target)));
    }
    @GameCase public void observationsDistinguishPlayersDeadEntitiesMissingRemovedAndForeignDimensions(GameTestHelper h) {
        var cow = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 80, 2); var player = h.makeMockServerPlayerInLevel();
        try {
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); cow.setHealth(8);
            cow.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); cow.setAbsorptionAmount(3);
            var world = new MinecraftWorldActions(h.getLevel(), ref -> switch (ref) { case "cow" -> cow; case "player" -> player; default -> null; },
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var before = inspect(world, "cow"); near(h, before.available().health(), 8, "native health"); near(h, before.available().maximumHealth(), 40, "native max health");
            near(h, before.available().absorption(), 3, "native absorption"); h.assertTrue(before.available().alive() && !before.available().player(), "living nonplayer");
            h.assertTrue(inspect(world, "player").available().player(), "real player identity");
            h.assertTrue(inspect(world, "unknown").view().isEmpty(), "missing identity");
            var other = Objects.requireNonNull(h.getLevel().getServer().getLevel(Level.NETHER));
            var wrongDimension = new MinecraftWorldActions(other, _ -> cow, _ -> other.damageSources().generic(), (_, _) -> true, _ -> {});
            h.assertTrue(inspect(wrongDimension, "cow").view().isEmpty(), "foreign dimension must be unavailable");
            cow.setHealth(0); var dead = inspect(world, "cow"); h.assertTrue(dead.view().isPresent() && !dead.available().alive(), "present dead entity is observable");
            near(h, dead.available().health(), 0, "dead health"); near(h, before.available().health(), 8, "old observation remains immutable");
            cow.discard(); h.assertTrue(inspect(world, "cow").view().isEmpty(), "removed entity must be unavailable");
        } finally { cow.discard(); player.discard(); }
        h.succeed();
    }
    @GameCase public void dependentHealingUsesObservedDeficitEvenIfWorldChangesBeforeResume(GameTestHelper h) throws Exception {
        var cow = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 80, 2); cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20); cow.setHealth(8);
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/entity_observation.json")), StandardCharsets.UTF_8)) {
            var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var source = new EffectSource("observer", "test:observe", "cow", new BuffInstance.Origin("cow", "observer", "", ""), Set.of());
            var world = new MinecraftWorldActions(h.getLevel(), _ -> cow, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var heals = new ArrayList<HealingCommand>();
            var session = new EffectSession(program.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), EffectState.empty().withSource(source), request -> {
                var result = world.apply(request);
                if (result instanceof EntityQuery.Result) { cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); cow.setHealth(20); }
                if (request.command() instanceof HealingCommand heal) heals.add(heal);
                return result;
            });
            session.start(0, new RuleEngine.Signal("test:probe", new EffectEvent("cow", "cow", source.origin(), Set.of(), Map.of())));
            h.assertValueEqual(heals.size(), 1, "one dependent heal"); near(h, heals.getFirst().amount(), 12, "observed 20 minus 8 deficit");
            near(h, cow.getHealth(), 32, "world applied the fixed observed amount to current health"); h.assertTrue(session.state().idle(), "observation did not resume");
        } finally { cow.discard(); }
        h.succeed();
    }
    @GameCase public void nativeEntityTagsAndDatapackTypeTagsRemainSeparateAcrossMutationDeathAndRemoval(GameTestHelper h) {
        var cow = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 80, 2);
        try {
            cow.addTag("test:individual");
            var world = new MinecraftWorldActions(h.getLevel(), _ -> cow, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var old = inspect(world, "cow");
            h.assertTrue(old.available().hasTag(EntityQuery.TagSource.TYPE, ELITE), "datapack type tag was not loaded");
            h.assertTrue(!old.available().hasTag(EntityQuery.TagSource.ENTITY, ELITE), "type tag leaked into instance tags");
            h.assertTrue(old.available().hasTag(EntityQuery.TagSource.ENTITY, "test:individual") && !old.available().hasTag(EntityQuery.TagSource.TYPE, "test:individual"), "instance tag leaked into type tags");
            cow.removeTag("test:individual"); cow.addTag(ELITE); cow.setHealth(0);
            var fresh = inspect(world, "cow");
            h.assertTrue(!fresh.available().alive() && fresh.available().hasTag(EntityQuery.TagSource.ENTITY, ELITE), "dead entity should remain observable");
            h.assertTrue(!fresh.available().hasTag(EntityQuery.TagSource.ENTITY, "test:individual"), "fresh observation did not see removal");
            h.assertTrue(old.available().hasTag(EntityQuery.TagSource.ENTITY, "test:individual") && !old.available().hasTag(EntityQuery.TagSource.ENTITY, ELITE), "old observation mutated");
            cow.discard(); h.assertTrue(inspect(world, "cow").view().isEmpty(), "removed entity must be unavailable");
        } finally { cow.discard(); }
        h.succeed();
    }
    private static LivingEntity burstEntity(GameTestHelper h, EntityType<? extends net.minecraft.world.entity.Mob> type, List<LivingEntity> entities, double x, double y, double z) {
        var e = h.spawnWithNoFreeWill(type, 2, 40, 2); e.setPos(x, y, z); e.setNoGravity(true);
        e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(100); entities.add(e); return e;
    }
    @GameCase(environment = "chorus_gametest:classified_burst", maxTicks = 30)
    public void actualDeathsSelectObservedRadiiAfterTagChangesSourceDetachAndCorpseRemoval(GameTestHelper h) throws Exception {
        var entities = new ArrayList<LivingEntity>(); var runtime = new MinecraftEffectRuntime[1];
        try {
            var owner = h.spawnWithNoFreeWill(EntityTypes.PIG, 2, 16, 2); owner.setNoGravity(true); entities.add(owner);
            double x = owner.chunkPosition().getMinBlockX() + 4.5, z = owner.chunkPosition().getMinBlockZ() + 4.5, y = owner.getY(); owner.setPos(x, y, z);
            var centers = new ArrayList<LivingEntity>(); var near = new ArrayList<LivingEntity>(); var far = new ArrayList<LivingEntity>(); var outside = new ArrayList<LivingEntity>();
            for (int i = 0; i < 3; i++) {
                double height = y + 24 * (i + 1);
                var center = burstEntity(h, i == 2 ? EntityTypes.COW : EntityTypes.PIG, entities, x, height, z); centers.add(center); center.setHealth(1);
                if (i == 1) center.addTag(ELITE);
                near.add(burstEntity(h, EntityTypes.PIG, entities, x + 3, height, z));
                far.add(burstEntity(h, EntityTypes.PIG, entities, x + 6, height, z));
                outside.add(burstEntity(h, EntityTypes.PIG, entities, x + 9, height, z));
            }
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/classified_burst.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            String id = owner.getUUID().toString();
            var source = new EffectSource("burst", "test:classified_burst", id, new BuffInstance.Origin(id, "burst", "", ""), Set.of());
            var observations = new ArrayList<EntityQuery.Result>(); var queries = new ArrayList<TargetQuery.Result>(); var damage = new ArrayList<DamageReceipt>();
            var world = new MinecraftWorldActions(h.getLevel(), ref -> entities.stream().filter(e -> e.getUUID().toString().equals(ref)).findFirst().orElse(null),
                    _ -> h.getLevel().damageSources().mobAttack(owner), (_, _) -> true, _ -> {});
            runtime[0] = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request);
                        if (result instanceof EntityQuery.Result observed) {
                            observations.add(observed);
                            var target = centers.stream().filter(e -> e.getUUID().toString().equals(observed.query().target())).findFirst().orElseThrow();
                            if (target.entityTags().contains(ELITE)) target.removeTag(ELITE); else target.addTag(ELITE);
                        }
                        if (result instanceof TargetQuery.Result selected) queries.add(selected);
                        if (result instanceof DamageReceipt receipt) damage.add(receipt);
                        return result;
                    }, MinecraftEffectRuntime::nativeSource);
            for (var center : centers) {
                h.assertTrue(center.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(owner), 2) && !center.isAlive(), "native lethal input");
                center.discard();
            }
            runtime[0].unbind(source.instance());
            h.runAfterDelay(6, () -> {
                try {
                    h.assertTrue(runtime[0].failure().isEmpty() && runtime[0].state().idle(), "classified burst failed: " + runtime[0].failure());
                    h.assertValueEqual(observations.size(), 3, "one observation per real death");
                    h.assertTrue(observations.stream().allMatch(o -> !o.available().alive()), "classification should observe actual dead victims");
                    h.assertValueEqual(queries.stream().map(q -> ((TargetShape.Sphere) q.query().shape()).radius()).toList(), List.of(4.0, 8.0, 8.0), "frozen normal / instance / type classification");
                    h.assertValueEqual(damage.size(), 5, "one normal neighbor and two neighbors per enhanced radius");
                    for (int i = 0; i < 3; i++) {
                        near(h, near.get(i).getHealth(), 99, "near neighbor"); near(h, far.get(i).getHealth(), i == 0 ? 100 : 99, "far neighbor"); near(h, outside.get(i).getHealth(), 100, "outside neighbor");
                    }
                    h.succeed();
                } finally { runtime[0].close(); entities.forEach(LivingEntity::discard); }
            });
        } catch (Exception | Error e) { if (runtime[0] != null) runtime[0].close(); entities.forEach(LivingEntity::discard); throw e; }
    }
}
