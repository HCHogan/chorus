package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class SelectedAbilityResourceGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "selected_ability_resource", _ -> {}, true);
        try { t.runtime.bind(new EffectSource("control", "test:control", owner(t), new BuffInstance.Origin(owner(t), "control", "", ""), Set.of())); choose(t, "a"); SelectedAbilityGameTest.choose(t, "test:grenade", "test:grenade"); return t; }
        catch (Exception | Error e) { t.close(); throw e; }
    }
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static double seconds(ProjectileGameTest.Harness t) { return state(t).buffs().timeMicros() / 1e6; }
    static void choose(ProjectileGameTest.Harness t, String ability) { SelectedAbilityGameTest.choose(t, "test:class", ability.isEmpty() ? "" : "test:" + ability); }
    static double energy(ProjectileGameTest.Harness t, String name) { return state(t).resources().get(new ResourceState.Key(owner(t), "test:" + name + "_energy")).value(); }
    static void signal(ProjectileGameTest.Harness t, String kind, String resource) { t.runtime.start(new RuleEngine.Signal("test:" + kind, new EffectEvent(owner(t), owner(t), new BuffInstance.Origin(owner(t), "input", "", "test:a"), Set.of(), Map.of(), Map.of(), resource.isEmpty() ? Map.of() : Map.of("resource", "test:" + resource + "_energy")))); }
    @GameCase(environment="chorus_gametest:selected_resource_rates", maxTicks=80)
    public void serverTicksApplyTheTemporaryBonusOnlyToTheCurrentClassAccountAcrossSwitchAndClear(GameTestHelper h) throws Exception {
        var t = open(h); signal(t, "boost", ""); double start = seconds(t); double[] cuts = new double[2];
        at(t, 20, () -> { t.runtime.prepare(); cuts[0] = seconds(t) - start; near(h, energy(t, "a"), .4 * cuts[0], "selected rate"); near(h, energy(t, "b"), .2 * cuts[0], "retained account baseline"); choose(t, "b"); });
        at(t, 40, () -> { t.runtime.prepare(); cuts[1] = seconds(t) - start; near(h, energy(t, "a"), .4 * cuts[0] + .1 * (cuts[1] - cuts[0]), "old account lost only bonus"); near(h, energy(t, "b"), .2 * cuts[0] + .8 * (cuts[1] - cuts[0]), "new account got bonus"); choose(t, ""); });
        t.finish(65, () -> { double elapsed = seconds(t) - start; near(h, energy(t, "a"), .4 * cuts[0] + .1 * (elapsed - cuts[0]), "clear kept ordinary baseline"); near(h, energy(t, "b"), .2 * cuts[0] + .8 * (cuts[1] - cuts[0]) + .2 * (elapsed - cuts[1]), "clear removed bonus"); near(h, energy(t, "grenade"), .3 * elapsed, "unrelated slot got class bonus"); h.assertTrue(state(t).buffs().instances().isEmpty(), "three-second boost did not expire"); });
    }
    @GameCase(environment="chorus_gametest:selected_resource_recharge", maxTicks=35)
    public void explicitRechargeResourceReceivesTheBonusIndependentlyOfItsPaymentAccount(GameTestHelper h) throws Exception {
        var t = open(h); choose(t, "recharge"); signal(t, "boost", ""); double start = seconds(t);
        t.runtime.bind(new EffectSource("stat", "test:stat", owner(t), new BuffInstance.Origin(owner(t), "stat", "", ""), Set.of()));
        t.finish(20, () -> { double elapsed = seconds(t) - start; near(h, energy(t, "a"), .2 * elapsed, "payment account got receiving bonus"); near(h, energy(t, "b"), 1 * elapsed, "additional base rate multiplied final stat rate"); near(h, energy(t, "grenade"), .6 * elapsed, "unrelated stat baseline"); });
    }
    @GameCase(environment="chorus_gametest:selected_resource_delay", maxTicks=16)
    public void detachedConditionalHealingReadsTheNewSelectedAccountOnRealTicks(GameTestHelper h) throws Exception {
        var t = open(h); signal(t, "delay", "b"); choose(t, "b");
        at(t, 3, () -> { t.runtime.prepare(); near(h, t.owner.getHealth(), 14, "delayed matching account did not heal"); signal(t, "delay", "b"); t.runtime.unbind("control"); choose(t, "a"); });
        t.finish(7, () -> near(h, t.owner.getHealth(), 14, "detached query retained obsolete selection"));
    }
}
