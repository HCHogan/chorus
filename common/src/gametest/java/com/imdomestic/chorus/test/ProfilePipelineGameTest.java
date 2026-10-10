package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProfilePipelineGameTest {
    static void prepare(JsonObject data) {
        SurplusGameTest.prepare(data); var fragment = ThreadedSpikeGameTest.json("pipeline_inputs");
        for (String field : List.of("profiles", "bundles")) fragment.getAsJsonArray(field).forEach(x -> data.getAsJsonArray(field).add(x));
    }
    static EffectState state(ProjectileGameTest.Harness t) { return SurplusGameTest.state(t); }
    static String owner(ProjectileGameTest.Harness t) { return SurplusGameTest.owner(t); }
    static void signal(ProjectileGameTest.Harness t, String type, String victim) {
        t.runtime.start(new RuleEngine.Signal(type, new EffectEvent(owner(t), victim, new BuffInstance.Origin(owner(t), "pipeline", "a", ""), Set.of(), Map.of())));
    }
    @GameCase(environment = "chorus_gametest:pipeline_archetypes", maxTicks = 50)
    public void twoWeaponsShareSurplusStatsButUseDifferentCurvesAndPhysicalReloads(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "surplus", ProfilePipelineGameTest::prepare, true);
        try {
            SurplusGameTest.start(t, false, 1, 1, 1, true); SurplusGameTest.spend(t); SurplusGameTest.reload(t);
            var rifle = state(t).reloads().get(owner(t)); near(h, rifle.duration().value(), 1.3, "rifle curve");
            PugilistGameTest.draw(t, "secondary"); signal(t, "test:spend_ammo", "b"); SurplusGameTest.reload(t);
            var shotgun = state(t).reloads().get(owner(t)); near(h, shotgun.duration().value(), 1.6, "shotgun curve");
            for (var plan : List.of(rifle, shotgun)) {
                var steps = plan.calculation().orElseThrow().steps(); h.assertValueEqual(steps.size(), 3, "three traces retained");
                h.assertValueEqual(steps.getFirst().trace().profile(), "chorus_d2:weapon_reload", "shared stat profile"); near(h, steps.getFirst().output().value(), 70, "same Surplus bonus");
                h.assertTrue(steps.getFirst().trace().contributions().stream().filter(c -> c.selected()).count() == 1, "only the queried weapon's Surplus contributes");
            }
            t.finish(34, () -> {
                h.assertValueEqual(state(t).ammunition().get("b").magazine(), 5, "shotgun completed at its own curve duration");
                h.assertValueEqual(state(t).ammunition().get("b").reserve().orElseThrow().rounds(), 29, "actual reserve transfer");
                h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "stowed rifle plan was cancelled");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:pipeline_query", maxTicks = 30)
    public void calculatedPipelineSurvivesSourceRemovalAndAbilityChangeBeforeDelayedHealing(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "surplus", ProfilePipelineGameTest::prepare, true);
        try {
            SurplusGameTest.start(t, false, 1, 1, 1);
            t.runtime.bind(new EffectSource("pipeline", "test:pipeline_inputs", owner(t), new BuffInstance.Origin(owner(t), "pipeline", "a", ""), Set.of()));
            signal(t, "test:pipeline_query", owner(t)); near(h, t.owner.getHealth(), 10, "query itself has no world effect");
            SurplusGameTest.use(t, "grenade"); t.runtime.unbind("pipeline"); near(h, SurplusGameTest.points(t, "reload", "a"), 35, "current live stat changed");
            t.finish(4, () -> near(h, t.owner.getHealth(), 23, "captured 70 stat to 1.3 seconds to 13 healing retained"));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:pipeline_animation", maxTicks = 35)
    public void animationStageRunsAfterCurveAndAcceptedPlanRetainsAllTraces(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "surplus", ProfilePipelineGameTest::prepare, true);
        try {
            SurplusGameTest.start(t, false, 1, 1, 1);
            t.runtime.bind(new EffectSource("fast", "test:fast_animation", owner(t), new BuffInstance.Origin(owner(t), "fast", "a", ""), Set.of()));
            SurplusGameTest.spend(t); SurplusGameTest.reload(t); var plan = state(t).reloads().get(owner(t)); var result = plan.calculation().orElseThrow();
            near(h, result.steps().getFirst().output().value(), 70, "points stage"); near(h, result.steps().get(1).output().value(), 1.3, "seconds before animation");
            near(h, result.output().value(), .975, "animation multiplies seconds"); t.runtime.unbind("fast"); SurplusGameTest.use(t, "melee");
            near(h, result.withoutFactors(Set.of("chorus_d2:reload_animation")).output().value(), 1.3, "offline counterfactual retains original ability state");
            h.assertTrue(plan.equals(state(t).reloads().get(owner(t))), "accepted plan and full traces unchanged");
            t.finish(22, () -> h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "reload completed at accepted animation duration"));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
