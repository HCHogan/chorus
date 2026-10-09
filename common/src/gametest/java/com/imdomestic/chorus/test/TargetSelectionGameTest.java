package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class TargetSelectionGameTest {
    private static String id(LivingEntity e) { return e.getUUID().toString(); }
    private static LivingEntity cow(GameTestHelper h, int x, int y, int z) {
        var e = h.spawnWithNoFreeWill(EntityTypes.COW, x, y, z); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); e.setHealth(40); return e;
    }
    private static LivingEntity resolve(GameTestHelper h, String value) {
        var e = h.getLevel().getEntity(UUID.fromString(value)); return e instanceof LivingEntity living ? living : null;
    }
    private static TargetQuery.Result select(MinecraftWorldActions world, TargetQuery query) {
        return (TargetQuery.Result) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), query));
    }
    @GameCase public void exclusionsRunBeforeNearestLimitAndDistanceTiesUseStableIdentity(GameTestHelper helper) {
        var center = cow(helper, 4, 40, 4); var excluded = cow(helper, 5, 40, 4);
        var a = cow(helper, 4, 40, 6); var b = cow(helper, 4, 40, 2); var far = cow(helper, 4, 45, 4);
        try {
            var world = new MinecraftWorldActions(helper.getLevel(), ref -> ref.equals("excluded-alias") ? excluded : resolve(helper, ref),
                    _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var query = new TargetQuery(id(center), 7, TargetQuery.Relation.ANY, id(center), true,
                    Set.of(id(center), "excluded-alias"), TargetQuery.Order.NEAREST, OptionalInt.of(1));
            var result = select(world, query); var ties = List.of(id(a), id(b)).stream().sorted().toList();
            helper.assertValueEqual(result.targets().size(), 1, "explicit result limit");
            helper.assertValueEqual(result.targets().getFirst().entity(), ties.getFirst(), "equal distances sorted by identity after exclusions");
            near(helper, result.targets().getFirst().distance(), 2, "captured feet distance");
            var both = select(world, new TargetQuery(query.center(), query.radius(), query.relation(), query.relativeTo(), query.includeCenter(), query.exclude(), query.order(), OptionalInt.of(2)));
            helper.assertValueEqual(both.targets().stream().map(TargetQuery.Target::entity).toList(), ties, "limit applied after sorting");
            var zero = select(world, new TargetQuery(query.center(), query.radius(), query.relation(), query.relativeTo(), query.includeCenter(), query.exclude(), query.order(), OptionalInt.of(0)));
            helper.assertTrue(zero.targets().isEmpty() && zero.outcome() == TargetQuery.Outcome.AVAILABLE, "zero limit is a successful empty query");
            var all = select(world, new TargetQuery(query.center(), query.radius(), query.relation(), query.relativeTo(), query.includeCenter(), query.exclude(), TargetQuery.Order.IDENTITY, OptionalInt.empty()));
            helper.assertValueEqual(all.targets().stream().map(TargetQuery.Target::entity).toList(), List.of(id(a), id(b), id(far)).stream().sorted().toList(), "unbounded identity order");
        } finally { for (var e : List.of(center, excluded, a, b, far)) e.discard(); }
        helper.succeed();
    }
    @GameCase public void radialCurveChangesActualDamageUsingCapturedDistancesAfterTargetsMove(GameTestHelper helper) throws Exception {
        var center = cow(helper, 4, 40, 4); center.setHealth(0);
        var owner = cow(helper, 5, 40, 4); var close = cow(helper, 4, 43, 4); var middle = cow(helper, 4, 45, 4); var edge = cow(helper, 4, 47, 4);
        try (var reader = new InputStreamReader(Objects.requireNonNull(TargetSelectionGameTest.class.getResourceAsStream("/effects/radial_falloff.json")), StandardCharsets.UTF_8)) {
            var compiled = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var source = new EffectSource("radial", "test:radial", id(owner), new BuffInstance.Origin(id(owner), "test:radial", "", ""), Set.of());
            var world = new MinecraftWorldActions(helper.getLevel(), ref -> resolve(helper, ref), _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var receipts = new ArrayList<DamageReceipt>(); var queries = new ArrayList<TargetQuery.Result>();
            var session = new EffectSession(compiled.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), EffectState.empty(), request -> {
                var result = world.apply(request);
                if (result instanceof TargetQuery.Result snapshot) {
                    queries.add(snapshot);
                    helper.assertValueEqual(snapshot.targets().stream().map(TargetQuery.Target::distance).toList(), List.of(3.0, 5.0, 7.0), "captured radial distances");
                    close.setPos(close.getX(), close.getY() + 16, close.getZ()); // Outside the radius after observation; retained distance remains 3.
                    middle.setPos(center.getX(), center.getY(), center.getZ()); // Now at center; retained distance remains 5.
                }
                if (result instanceof DamageReceipt damage) receipts.add(damage);
                return result;
            });
            session.start(0, SourceChange.bind(source));
            session.start(0, new RuleEngine.Signal("test:blast", new EffectEvent(id(owner), id(center), source.origin(), Set.of(), Map.of("targets", new Measure(3, Unit.COUNT)))));
            near(helper, close.getHealth(), 30, "plateau damage at captured distance three");
            near(helper, middle.getHealth(), 35, "half damage at captured distance five");
            near(helper, edge.getHealth(), 40, "zero damage at radius seven"); near(helper, owner.getHealth(), 40, "explicitly excluded owner");
            helper.assertValueEqual(queries.size(), 1, "no implicit live-distance query on resume");
            helper.assertValueEqual(receipts.size(), 3, "one attempted component per selected target");
            near(helper, receipts.get(0).healthLoss(), 10, "first world receipt"); near(helper, receipts.get(1).healthLoss(), 5, "second world receipt");
            near(helper, receipts.get(2).healthLoss(), 0, "edge world receipt"); helper.assertTrue(session.state().idle(), "Radial sequence did not settle");
        } finally { for (var e : List.of(center, owner, close, middle, edge)) e.discard(); }
        helper.succeed();
    }
}
