package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

/** Synthetic area scenario. The radius and damage are adapter acceptance values, not Destiny tuning. */
public class TargetIterationGameTest {
    private static String id(LivingEntity entity) { return entity.getUUID().toString(); }
    private static MinecraftWorldActions world(GameTestHelper helper) {
        return new MinecraftWorldActions(helper.getLevel(), value -> {
            var entity = helper.getLevel().getEntity(UUID.fromString(value)); return entity instanceof LivingEntity living ? living : null;
        }, _ -> helper.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
    }
    private static TargetQuery.Result select(MinecraftWorldActions world, LivingEntity center, double radius,
            TargetQuery.Relation relation, String relative, boolean includeCenter) {
        return (TargetQuery.Result) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0),
                new TargetQuery(id(center), radius, relation, relative, includeCenter)));
    }
    private static List<String> ids(TargetQuery.Result result) { return result.targets().stream().map(TargetQuery.Target::entity).toList(); }
    @GameCase public void sphereUsesFeetDistanceIncludesBoundaryAndFiltersDeadAndRemovedTargets(GameTestHelper helper) {
        var center = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 4);
        var boundary = helper.spawnWithNoFreeWill(EntityTypes.COW, 7, 2, 8);
        var inside = helper.spawnWithNoFreeWill(EntityTypes.COW, 5, 2, 4);
        var diagonal = helper.spawnWithNoFreeWill(EntityTypes.COW, 8, 2, 8);
        var above = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 8, 4);
        var dead = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 5); dead.setHealth(0);
        var removed = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 6); removed.discard();
        var executor = world(helper); var result = select(executor, center, 5, TargetQuery.Relation.ANY, id(center), false);
        helper.assertValueEqual(ids(result), List.of(id(inside), id(boundary)).stream().sorted().toList(), "sphere selection by sorted identity");
        helper.assertTrue(!ids(result).contains(id(diagonal)) && !ids(result).contains(id(above)), "Box corners or vertical distance included");
        helper.assertValueEqual(ids(select(executor, center, 0, TargetQuery.Relation.ANY, id(center), true)), List.of(id(center)), "zero radius including center");
        helper.assertTrue(select(executor, center, 0, TargetQuery.Relation.ANY, id(center), false).targets().isEmpty(), "Excluded center selected");
        center.setHealth(0); // Death-triggered effects can still query around a present dead center.
        helper.assertValueEqual(ids(select(executor, center, 5, TargetQuery.Relation.ANY, id(center), true)), ids(result), "present dead center is an origin but not a living target");
        center.discard();
        helper.assertValueEqual(select(executor, center, 5, TargetQuery.Relation.ANY, id(center), false).outcome(), TargetQuery.Outcome.MISSING_CENTER, "removed center");
        helper.succeed();
    }
    @GameCase public void relationUsesExplicitReferenceTeamsAndMissingReferenceIsNotAnEmptySuccess(GameTestHelper helper) {
        var center = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 4);
        var owner = helper.spawnWithNoFreeWill(EntityTypes.COW, 5, 2, 4);
        var ally = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 5);
        var neutral = helper.spawnWithNoFreeWill(EntityTypes.COW, 3, 2, 4);
        var scoreboard = helper.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("query-" + UUID.randomUUID());
        try {
            scoreboard.addPlayerToTeam(owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
            var executor = world(helper);
            helper.assertValueEqual(ids(select(executor, center, 2, TargetQuery.Relation.ALLIED, id(owner), false)),
                    List.of(id(owner), id(ally)).stream().sorted().toList(), "relation relative to owner, not query center");
            helper.assertValueEqual(ids(select(executor, center, 2, TargetQuery.Relation.NOT_ALLIED, id(owner), false)), List.of(id(neutral)), "neutral is non-allied; not a Destiny enemy classification");
            String absent = UUID.randomUUID().toString();
            helper.assertValueEqual(select(executor, center, 2, TargetQuery.Relation.ALLIED, absent, false).outcome(), TargetQuery.Outcome.MISSING_RELATIVE, "missing relation reference");
            helper.assertValueEqual(select(executor, center, 2, TargetQuery.Relation.ANY, absent, false).outcome(), TargetQuery.Outcome.AVAILABLE, "any relation needs no relative entity");
        } finally { scoreboard.removePlayerTeam(team); }
        helper.succeed();
    }
    private static CompiledEffects program() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(TargetIterationGameTest.class.getResourceAsStream("/effects/target_iteration.json")), StandardCharsets.UTF_8)) {
            return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
        }
    }
    private static void area(GameTestHelper helper, boolean mutateAfterSelection) throws Exception {
        var owner = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 4); owner.setHealth(4);
        var one = helper.spawnWithNoFreeWill(EntityTypes.COW, 5, 2, 4);
        var two = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 5);
        var ordered = List.of(one, two).stream().sorted(Comparator.comparing(TargetIterationGameTest::id)).toList();
        LivingEntity first = ordered.getFirst(), second = ordered.getLast();
        var source = new EffectSource("area", "test:area", id(owner), new BuffInstance.Origin(id(owner), "test:area", "", ""), Set.of());
        var executor = world(helper); var requests = new ArrayList<RuleEngine.WorldRequest>(); var receipts = new ArrayList<RuleEngine.ActionResult>();
        LivingEntity[] late = new LivingEntity[1];
        var session = new EffectSession(program().engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), EffectState.empty(), request -> {
            requests.add(request); var receipt = executor.apply(request); receipts.add(receipt);
            if (receipt instanceof TargetQuery.Result selected) {
                helper.assertValueEqual(ids(selected), ordered.stream().map(TargetIterationGameTest::id).toList(), "selected initial targets");
                if (mutateAfterSelection) {
                    first.setPos(first.getX(), first.getY() + 16, first.getZ()); second.discard();
                    late[0] = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 6);
                }
            }
            return receipt;
        });
        session.start(0, SourceChange.bind(source));
        session.start(0, new RuleEngine.Signal("test:area", new EffectEvent(id(owner), id(owner), source.origin(), Set.of(), Map.of())));
        near(helper, first.getHealth(), 8, "first selected target actual HP loss");
        near(helper, owner.getHealth(), mutateAfterSelection ? 5 : 6, "healing from each target's actual damage receipt");
        if (mutateAfterSelection) near(helper, late[0].getHealth(), 10, "new arrival was not in the captured target list");
        else near(helper, second.getHealth(), 8, "second selected target actual HP loss");
        var hits = requests.stream().filter(r -> r.command() instanceof DamageCommand).toList();
        helper.assertValueEqual(hits.size(), 2, "both captured identities attempted");
        helper.assertTrue(!hits.getFirst().id().equals(hits.getLast().id()), "Loop reused world operation ID");
        helper.assertValueEqual(requests.stream().filter(r -> r.command() instanceof TargetQuery).count(), 1L, "selection was not repeated on resume");
        helper.assertValueEqual(receipts.stream().filter(r -> r instanceof HealingReceipt).count(), mutateAfterSelection ? 1L : 2L, "per-target branch uses current receipt");
        helper.assertTrue(session.state().idle(), "Area sequence did not settle");
        first.discard(); second.discard(); owner.discard(); if (late[0] != null) late[0].discard();
        helper.succeed();
    }
    @GameCase public void areaDamageAndLeechRunEverySelectedTargetWithIndependentWorldReceipts(GameTestHelper helper) throws Exception { area(helper, false); }
    @GameCase public void selectionStaysFixedWhenTargetsMoveDisappearAndArriveDuringWorldWaits(GameTestHelper helper) throws Exception { area(helper, true); }
}
