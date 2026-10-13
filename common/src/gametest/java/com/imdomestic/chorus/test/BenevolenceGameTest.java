package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;

/** Real recipients, healing/overhealing, relation queries, delayed Acrobat and passive account integration. */
public class BenevolenceGameTest {
    static void prepareBenevolence(JsonObject data) {
        AcrobatDodgeGameTest.prepare(data);
        for (String name : List.of("ember_of_benevolence", "grenade_energy", "solar_melee_energy", "cure", "restoration", "healing_rift", "benevolence_test_inputs")) GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json(name));
        AscensionGameTest.version(data);
    }
    static EffectSource source(LivingEntity who, String bundle, boolean excluded) {
        String id = who.getUUID().toString(); var tags = excluded ? Set.of(D + "benevolence_excluded") : Set.<String>of();
        return new EffectSource(id + "/" + bundle, bundle, id, new BuffInstance.Origin(id, id + "/" + bundle, "", "", tags), tags);
    }
    static ProjectileGameTest.Harness openBenevolence(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "ascension", BenevolenceGameTest::prepareBenevolence, true);
        try {
            t.owner.setGameMode(GameType.SURVIVAL); t.owner.setOnGround(false); t.owner.setYRot(0); t.owner.setDeltaMovement(0, 0, 0);
            AscensionGameTest.choose(t, D + "class", ABILITY); AscensionGameTest.choose(t, D + "grenade", "test:benevolence_grenade"); AscensionGameTest.choose(t, D + "melee", "test:benevolence_melee");
            t.runtime.bind(source(t.owner, D + "ember_of_benevolence", false)); t.runtime.bind(source(t.owner, "test:benevolence_inputs", false));
            return t;
        } catch (Exception | Error failure) { t.close(); throw failure; }
    }
    static Optional<BuffInstance> benefit(ProjectileGameTest.Harness t, LivingEntity who) {
        return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.key().definition().equals(D + "benevolence_regeneration") && b.key().holder().equals(who.getUUID().toString())).findFirst();
    }
    static long now(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain().buffs().timeMicros(); }
    static void give(ProjectileGameTest.Harness t, String name, LivingEntity who, LivingEntity target) {
        var source = source(who, "test:benevolence_inputs", false);
        t.runtime.start(new RuleEngine.Signal("test:" + name, new EffectEvent(source.holder(), target.getUUID().toString(), source.origin(), Set.of(), Map.of()))); ShieldThrowGameTest.settled(t);
    }
    @GameCase(environment = "chorus_gametest:benevolence_acrobat", maxTicks = 100)
    public void actualAcrobatCompletionAcceleratesAllThreeSelectedReceivingAccounts(GameTestHelper h) {
        ready(h, () -> {
            var t = openBenevolence(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("benevolence-" + UUID.randomUUID());
            try {
                var ally = target(t, 3); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
                t.runtime.bind(new EffectSource("stats", "test:benevolence_stats", id(t), new BuffInstance.Origin(id(t), "stats", "", ""), Set.of(), Map.of("grenade", new Measure(110, Unit.STAT_POINT), "melee", new Measure(100, Unit.STAT_POINT), "class_delta", new Measure(1, Unit.DELTA))));
                long start = now(t); for (String slot : List.of("grenade", "melee", "class")) h.assertValueEqual(t.runtime.useAbility(t.owner, D + slot).outcome(), AbilityUse.Outcome.ACCEPTED, "selected ability payment");
                h.assertTrue(benefit(t, t.owner).isEmpty(), "benefit before the calibrated completion");
                h.runAfterDelay(6, () -> { try {
                    ShieldThrowGameTest.settled(t); var buff = benefit(t, t.owner).orElseThrow(); double elapsed = (now(t) - start) / 1e6, active = (now(t) - (buff.deadline() - 7_000_000)) / 1e6;
                    h.assertTrue(active > 0 && radiant(t, ally).isPresent(), "actual allied Radiant gives recovery");
                    near(h, AscensionGameTest.energy(t, D + "grenade_energy"), .0275 * elapsed + .03 * active, "grenade additional base");
                    near(h, AscensionGameTest.energy(t, D + "solar_melee_progress"), .0275 * elapsed + .03 * active, "melee receiving progress rather than use count");
                    near(h, energy(t), (2 * elapsed + 3 * active) / 56, "class pre-stat baseline"); h.succeed();
                } finally { scoreboard.removePlayerTeam(team); t.close(); } });
            } catch (Exception | Error failure) { scoreboard.removePlayerTeam(team); t.close(); throw failure; }
        });
    }
    @GameCase(environment = "chorus_gametest:benevolence_cure", maxTicks = 100)
    public void actualCureOverhealingQualifiesButOrdinaryHealingAndSelfGrantsDoNot(GameTestHelper h) {
        ready(h, () -> {
            var t = openBenevolence(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("benevolence-" + UUID.randomUUID());
            try {
                var ally = target(t, 3); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
                give(t, "ordinary_heal", t.owner, ally); give(t, "radiant_grant_buff", t.owner, t.owner); h.assertTrue(benefit(t, t.owner).isEmpty(), "nonqualifying health/self benefit");
                long start = now(t); give(t, "cure_bestow", t.owner, ally); h.assertTrue(benefit(t, t.owner).isEmpty(), "cooldown grant alone is not confirmed Cure");
                h.runAfterDelay(4, () -> { try {
                    ShieldThrowGameTest.settled(t); near(h, ally.getHealth(), 100, "actual overheal remains capped"); near(h, benefit(t, t.owner).orElseThrow().deadline() - start, 7_100_000, "second accepted Cure pulse refreshes current policy"); h.succeed();
                } finally { scoreboard.removePlayerTeam(team); t.close(); } });
            } catch (Exception | Error failure) { scoreboard.removePlayerTeam(team); t.close(); throw failure; }
        });
    }
    @GameCase(environment = "chorus_gametest:benevolence_refresh", maxTicks = 100)
    public void sharedRestorationKeepsItsFirstOriginWhileEachActualGiverGetsCredit(GameTestHelper h) {
        ready(h, () -> {
            var t = openBenevolence(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("benevolence-" + UUID.randomUUID());
            try {
                var ally = target(t, 3); var other = target(t, 6);
                for (var who : List.of(t.owner, ally, other)) scoreboard.addPlayerToTeam(who.getScoreboardName(), team);
                t.runtime.bind(source(other, D + "ember_of_benevolence", false)); t.runtime.bind(source(other, "test:benevolence_inputs", false));
                give(t, "restoration_grant_buff", t.owner, ally); long first = benefit(t, t.owner).orElseThrow().deadline(); give(t, "restoration_apply_status", other, ally);
                h.assertTrue(benefit(t, other).isPresent(), "second giver got its own benefit");
                h.runAfterDelay(5, () -> { try {
                    ShieldThrowGameTest.settled(t); var restoration = t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.key().definition().equals(D + "restoration")).findFirst().orElseThrow();
                    h.assertValueEqual(restoration.origin().owner(), id(t), "restoration retained first origin"); h.assertValueEqual(benefit(t, t.owner).orElseThrow().deadline(), first, "continuous Restoration healing did not refresh Benevolence"); h.succeed();
                } finally { scoreboard.removePlayerTeam(team); t.close(); } });
            } catch (Exception | Error failure) { scoreboard.removePlayerTeam(team); t.close(); throw failure; }
        });
    }
    @GameCase(environment = "chorus_gametest:benevolence_rift", maxTicks = 100)
    public void actualHealingRiftRefreshesFromItsConfirmedContinuousHealing(GameTestHelper h) {
        ready(h, () -> {
            var t = openBenevolence(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("benevolence-" + UUID.randomUUID());
            try {
                var ally = target(t, 3); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
                long start = now(t); t.runtime.bind(source(t.owner, D + "healing_rift", false)); h.assertTrue(benefit(t, t.owner).isPresent(), "confirmed allied Rift presence");
                h.runAfterDelay(5, () -> { try {
                    ShieldThrowGameTest.settled(t); h.assertTrue(benefit(t, t.owner).orElseThrow().deadline() > start + 7_000_000, "native continuous Rift healing refreshed the benefit"); near(h, ally.getHealth(), 100, "full ally still has actual overheal receipts"); h.succeed();
                } finally { scoreboard.removePlayerTeam(team); t.close(); } });
            } catch (Exception | Error failure) { scoreboard.removePlayerTeam(team); t.close(); throw failure; }
        });
    }
    @GameCase(environment = "chorus_gametest:benevolence_unknown", maxTicks = 100)
    public void unknownNativeCureReceiptKeepsTheActualHealWithoutInventingFragmentCredit(GameTestHelper h) {
        ready(h, () -> {
            var t = openBenevolence(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("benevolence-" + UUID.randomUUID());
            try {
                var ally = target(t, 3); ally.setHealth(20); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
                int[] heals = {0}; t.beforeWorld = r -> { if (r.command() instanceof HealingCommand c && c.tags().contains(D + "cure")) heals[0]++; }; t.failAfterHealing = true;
                give(t, "cure_bestow", t.owner, ally);
                h.runAfterDelay(4, () -> { try {
                    h.assertTrue(t.runtime.failure().isPresent(), "unknown real heal stops the runtime"); near(h, ally.getHealth(), 23, "first Cure pulse actually changed health"); h.assertValueEqual(heals[0], 1, "no replay or second pulse"); h.assertTrue(benefit(t, t.owner).isEmpty(), "no confirmed receipt means no fragment credit"); h.succeed();
                } finally { scoreboard.removePlayerTeam(team); t.close(); } });
            } catch (Exception | Error failure) { scoreboard.removePlayerTeam(team); t.close(); throw failure; }
        });
    }
}
