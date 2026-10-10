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

public class FixedPositionGameTest {
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static LivingEntity cow(GameTestHelper h, int x, int z) {
        var e = h.spawnWithNoFreeWill(EntityTypes.COW, x, 40, z); e.setNoGravity(true);
        e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        e.setHealth(100); return e;
    }
    private static LivingEntity resolve(GameTestHelper h, String reference) {
        return h.getLevel().getEntity(UUID.fromString(reference)) instanceof LivingEntity living ? living : null;
    }
    private static RuleEngine.ActionResult execute(MinecraftWorldActions world, RuleEngine.WorldCommand command) {
        return world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), command));
    }
    private static MinecraftWorldActions world(GameTestHelper h) {
        return new MinecraftWorldActions(h.getLevel(), ref -> resolve(h, ref), _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
    }
    @GameCase public void positionCaptureUsesFeetAcceptsDeadAndReportsMissingEntities(GameTestHelper h) {
        var target = cow(h, 4, 4);
        try {
            var world = world(h); var request = new PositionQuery(id(target));
            target.setPos(target.getX() + .25, target.getY() + .75, target.getZ() - .5);
            var live = (PositionQuery.Result) execute(world, request); var point = live.position().orElseThrow();
            near(h, point.x(), target.getX(), "feet x"); near(h, point.y(), target.getY(), "feet y"); near(h, point.z(), target.getZ(), "feet z");
            h.assertValueEqual(point.dimension(), h.getLevel().dimension().identifier().toString(), "capture dimension");
            target.setHealth(0);
            var dead = (PositionQuery.Result) execute(world, request);
            h.assertValueEqual(dead.position(), live.position(), "dead present entity still has a position");
            target.setPos(target.getX() + 20, target.getY(), target.getZ());
            h.assertTrue(!((PositionQuery.Result) execute(world, request)).position().equals(live.position()), "new reads should see movement");
            near(h, live.position().orElseThrow().x(), point.x(), "previous capture stays immutable");
            target.discard();
            h.assertTrue(((PositionQuery.Result) execute(world, request)).position().isEmpty(), "removed target is missing");
            h.assertTrue(((PositionQuery.Result) execute(world, new PositionQuery(UUID.randomUUID().toString()))).position().isEmpty(), "unknown target is missing");
        } finally { target.discard(); }
        h.succeed();
    }
    @GameCase public void fixedPointSelectionUsesItsWorldAndFiltersWithoutAnOriginEntity(GameTestHelper h) {
        var origin = cow(h, 4, 4); var owner = cow(h, 5, 4); var edge = cow(h, 9, 4); var outside = cow(h, 10, 4);
        var replacement = cow(h, 4, 4);
        try {
            // The empty test structure does not own the adjacent chunk. Keep this geometry-only
            // fixture in the center's loaded chunk, independent of the test grid's placement.
            double left = origin.chunkPosition().getMinBlockX(), y = origin.getY(), z = origin.getZ();
            origin.setPos(left + 4.5, y, z); owner.setPos(left + 5.5, y, z);
            edge.setPos(left + 9.5, y, z); outside.setPos(left + 10.5, y, z); replacement.setPos(left + 4.5, y, z);
            h.assertTrue(resolve(h, id(edge)) == edge, "boundary fixture must be a loaded entity");
            var world = world(h);
            var captured = (PositionQuery.Result) execute(world, new PositionQuery(id(origin)));
            origin.discard(); var center = new TargetQuery.PositionCenter(captured.position());
            var query = new TargetQuery(center, 5, TargetQuery.Relation.ANY, id(origin), false, Set.of(id(owner)), TargetQuery.Order.NEAREST, OptionalInt.empty());
            var selected = (TargetQuery.Result) execute(world, query);
            h.assertValueEqual(selected.targets().stream().map(TargetQuery.Target::entity).toList(), List.of(id(replacement), id(edge)), "point includes zero and exact boundary, excludes owner and outside");
            h.assertValueEqual(selected.targets().stream().map(TargetQuery.Target::distance).toList(), List.of(0.0, 5.0), "distances from fixed point");
            var limited = (TargetQuery.Result) execute(world, new TargetQuery(center, 5, query.relation(), query.relativeTo(), false, query.exclude(), query.order(), OptionalInt.of(1)));
            h.assertValueEqual(limited.targets(), selected.targets().subList(0, 1), "fixed-point nearest limit");
            var zero = (TargetQuery.Result) execute(world, new TargetQuery(center, 0, TargetQuery.Relation.ANY, id(origin), false));
            h.assertValueEqual(zero.targets(), selected.targets().subList(0, 1), "zero radius does not implicitly exclude the entity at the point");
            var missingRelative = (TargetQuery.Result) execute(world, new TargetQuery(center, 5, TargetQuery.Relation.ALLIED, id(origin), false));
            h.assertValueEqual(missingRelative.outcome(), TargetQuery.Outcome.MISSING_RELATIVE, "relationship needs its actual reference");
            var allied = (TargetQuery.Result) execute(world, new TargetQuery(center, 5, TargetQuery.Relation.ALLIED, id(owner), false));
            h.assertValueEqual(allied.targets().stream().map(TargetQuery.Target::entity).toList(), List.of(id(owner)), "live relationship reference is separate from point");
            var point = captured.position().orElseThrow();
            var foreign = new TargetQuery.PositionCenter(new WorldPosition("minecraft:the_nether", point.x(), point.y(), point.z()));
            var wrongWorld = (TargetQuery.Result) execute(world, new TargetQuery(foreign, 5, TargetQuery.Relation.ANY, id(owner), false));
            h.assertValueEqual(wrongWorld.outcome(), TargetQuery.Outcome.WRONG_DIMENSION, "coordinates cannot cross worlds"); h.assertTrue(wrongWorld.targets().isEmpty(), "wrong-world query selected targets");
            var absent = (TargetQuery.Result) execute(world, new TargetQuery(new TargetQuery.PositionCenter(Optional.empty()), 5, TargetQuery.Relation.ANY, id(owner), false));
            h.assertValueEqual(absent.outcome(), TargetQuery.Outcome.MISSING_CENTER, "absent capture must not become origin");
        } finally { for (var e : List.of(origin, owner, edge, outside, replacement)) e.discard(); }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:fixed_position")
    public void jsonRepeatedBlastsKeepActivationPointAfterOriginRemovalAndRefreshMembership(GameTestHelper h) throws Exception {
        var entities = new ArrayList<LivingEntity>();
        var owner = cow(h, 2, 2); var origin = cow(h, 4, 4); var old = cow(h, 6, 4); var edge = cow(h, 9, 4); var outside = cow(h, 10, 4);
        entities.addAll(List.of(owner, origin, old, edge, outside));
        var captured = new ArrayList<PositionQuery.Result>(); var queries = new ArrayList<TargetQuery.Result>(); var times = new ArrayList<Long>();
        var damages = new ArrayList<DamageReceipt>(); var operations = new ArrayList<RuleEngine.OperationId>();
        var runtime = new MinecraftEffectRuntime[1]; var newcomer = new LivingEntity[1];
        try {
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(FixedPositionGameTest.class.getResourceAsStream("/effects/fixed_position.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var source = new EffectSource("burst", "test:fixed_burst", id(owner), new BuffInstance.Origin(id(owner), "test:burst", "test-weapon", ""), Set.of());
            var type = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), ref -> resolve(h, ref), _ -> new DamageSource(type, owner), (_, _) -> true, _ -> {});
            runtime[0] = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withSource(source), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (result instanceof PositionQuery.Result position) captured.add(position);
                if (result instanceof TargetQuery.Result targets) {
                    queries.add(targets); times.add(runtime[0].state().engine().timeMicros());
                    if (queries.size() == 1) {
                        old.setPos(old.getX(), old.getY() + 30, old.getZ()); // This wave retains its selected targets.
                        newcomer[0] = cow(h, 4, 6); entities.add(newcomer[0]); // Following waves observe this arrival.
                    }
                }
                if (result instanceof DamageReceipt receipt) {
                    var damage = (DamageCommand) request.command();
                    h.assertValueEqual(damage.source(), source.origin(), "source retained after detach");
                    h.assertTrue(damage.snapshot().isPresent(), "snapshot lost in delayed wave");
                    damages.add(receipt); operations.add(request.id());
                }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
            runtime[0].start(new RuleEngine.Signal("test:burst", new EffectEvent(id(owner), id(origin), source.origin(), Set.of(), Map.of())));
            runtime[0].unbind(source.instance()); origin.discard();
            var replacement = cow(h, 4, 4); entities.add(replacement);
            h.runAfterDelay(6, () -> {
                try {
                    h.assertTrue(runtime[0].failure().isEmpty(), "Fixed blast failed: " + runtime[0].failure());
                    h.assertTrue(runtime[0].state().idle() && runtime[0].state().engine().domain().timers().isEmpty(), "Fixed blast did not settle");
                    h.assertValueEqual(captured.size(), 1, "position read only at activation");
                    h.assertValueEqual(times, List.of(100_000L, 150_000L, 200_000L), "wave times");
                    h.assertTrue(queries.stream().allMatch(q -> q.query().center().equals(new TargetQuery.PositionCenter(captured.getFirst().position()))), "blast point moved");
                    h.assertTrue(queries.getFirst().targets().stream().anyMatch(t -> t.entity().equals(id(old))), "first wave lost its captured member");
                    h.assertTrue(queries.get(1).targets().stream().noneMatch(t -> t.entity().equals(id(old))), "next wave followed departed target");
                    h.assertTrue(queries.get(1).targets().stream().anyMatch(t -> t.entity().equals(id(newcomer[0]))), "next wave missed arrival");
                    near(h, old.getHealth(), 90, "first selected wave only"); near(h, newcomer[0].getHealth(), 80, "two later waves");
                    near(h, replacement.getHealth(), 70, "three waves at original point"); near(h, edge.getHealth(), 70, "three exact-boundary hits");
                    near(h, outside.getHealth(), 100, "outside untouched"); near(h, owner.getHealth(), 100, "owner excluded");
                    h.assertValueEqual(damages.size(), 9, "three targets per wave"); h.assertValueEqual(operations.stream().distinct().count(), 9L, "unique world operations");
                    h.succeed();
                } finally { runtime[0].close(); entities.forEach(LivingEntity::discard); }
            });
        } catch (Throwable error) {
            if (runtime[0] != null) runtime[0].close(); entities.forEach(LivingEntity::discard); throw error;
        }
    }
}
