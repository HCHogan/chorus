package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.AscensionGameTest.*;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.StatusResult;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.imdomestic.chorus.stat.codec.StatCodecs;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

/** Shared Slow/Freeze, real class input, native motion/health and server resource integration. */
public class WintersShroudGameTest {
    static void prepare(JsonObject data) {
        for (String name : List.of("class_regeneration", "marksman_dodge", "gamblers_dodge", "ascension", "character_stats",
                "amplified", "amplified_inputs", "amplified_movement", "weapon_stats", "jolt", "freeze", "freeze_test_falloff",
                "slow", "stasis_duration", "durance", "combat_damage", "movement_attributes")) {
            var part = ThreadedSpikeGameTest.json(name); if (List.of("marksman_dodge", "gamblers_dodge", "ascension").contains(name)) calibrate(name, part); GamblersDodgeGameTest.merge(data, part);
        }
        for (String name : List.of("ascension", "gamblers_dodge", "winters_shroud")) { var part = ThreadedSpikeGameTest.json(name + "_test_calibration").getAsJsonObject("program"); if (name.equals("ascension")) part.remove("profiles"); GamblersDodgeGameTest.merge(data, part); }
        version(data);
    }
    static EffectSource aspect(String id, String holder, String variant) {
        var data = ThreadedSpikeGameTest.json("winters_shroud_test_calibration"); var values = data.getAsJsonObject("parameters").deepCopy(); var params = new HashMap<String, Measure>();
        if (variant.equals("stasis")) data.getAsJsonObject("stasis_parameters").entrySet().forEach(e -> values.add(e.getKey(), e.getValue()));
        values.entrySet().forEach(e -> params.put(e.getKey(), StatCodecs.MEASURE.parse(JsonOps.INSTANCE, e.getValue()).getOrThrow()));
        return new EffectSource(id, D + "winters_shroud_" + variant, holder, new BuffInstance.Origin(holder, id, "", ""), Set.of(), params);
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, String variant) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "winters_shroud", WintersShroudGameTest::prepare, true);
        try {
            t.owner.setGameMode(GameType.SURVIVAL); t.owner.setOnGround(false); t.owner.setHealth(100); t.owner.getFoodData().setFoodLevel(0);
            choose(t, D + "class", D + "marksman_dodge"); choose(t, SLOT, ABILITY); t.runtime.bind(aspect("aspect", owner(t), variant));
            t.runtime.bind(AmplifiedGameTest.calibration(owner(t))); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static BuffInstance buff(ProjectileGameTest.Harness t, String name, LivingEntity holder) { return state(t).buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + name) && b.key().holder().equals(holder.getUUID().toString())).findFirst().orElseThrow(); }
    static LivingEntity target(ProjectileGameTest.Harness t, double distance) { var point = t.owner.position().add(distance, 0, 0); t.h.getLevel().getChunk(net.minecraft.core.BlockPos.containing(point)); var entity = t.cow(2.5, 40, 3.5); entity.setPos(point); return entity; }
    static void dodge(ProjectileGameTest.Harness t) { t.h.assertValueEqual(t.runtime.useAbility(t.owner, D + "class").outcome(), AbilityUse.Outcome.ACCEPTED, "class input rejected"); ShieldThrowGameTest.settled(t); }
    static void slow(ProjectileGameTest.Harness t, LivingEntity target, int stacks) {
        var values = new HashMap<>(aspect("source", owner(t), "prismatic").parameters()); values.remove("resist_duration"); values.put("slow_duration", new Measure(2, Unit.SECOND)); values.put("slow_durance_extension", new Measure(0, Unit.SECOND));
        var source = new EffectSource("blade", D + "slow_application", owner(t), new BuffInstance.Origin(owner(t), "blade", "", "test:other_slow"), Set.of(), values); t.runtime.bind(source);
        t.runtime.start(new RuleEngine.Signal(D + "apply_slow", new EffectEvent(owner(t), target.getUUID().toString(), source.origin(), Set.of(), Map.of("stacks", new Measure(stacks, Unit.COUNT)), Map.of(), Map.of("source_instance", source.instance(), "bundle", source.bundle()))));
    }
    @GameCase public void acceptedDodgeUsesNineMeterEnemyBoundaryAndDuranceAndSingleAspectPulse(GameTestHelper h) throws Exception {
        try (var t = open(h, "stasis")) {
            t.runtime.bind(aspect("copy", owner(t), "stasis")); t.runtime.bind(AmplifiedGameTest.source("durance", D + "durance", owner(t)));
            var edge = target(t, 9); var outside = target(t, 9.001); var ally = target(t, 2); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("winter-" + UUID.randomUUID());
            try {
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team); double speed = edge.getAttributeValue(Attributes.MOVEMENT_SPEED); long now = state(t).buffs().timeMicros(); dodge(t);
                h.assertValueEqual(count(t, "slow", edge), 60, "duplicate pulse or missing inclusive boundary"); h.assertValueEqual(count(t, "slow", outside), 0, "outside boundary"); h.assertValueEqual(count(t, "slow", ally), 0, "allied target");
                near(h, edge.getAttributeValue(Attributes.MOVEMENT_SPEED), speed * .5, "shared native Slow projection"); h.assertValueEqual(buff(t, "slow", edge).deadline() - now, 12_000_000L, "explicit Stasis/Durance timing");
                near(h, energy(t, D + "marksman_dodge_energy"), 0, "one paid charge"); h.assertValueEqual(count(t, "winters_shroud_regeneration", t.owner), 1, "successful Slow regeneration");
            } finally { scoreboard.removePlayerTeam(team); }
        } h.succeed();
    }
    @GameCase public void ascensionTriggersPrismaticPulseBeforeMotionAndReusesAllSharedStatuses(GameTestHelper h) throws Exception {
        try (var t = open(h, "prismatic")) {
            var edge = target(t, 9); var enemy = target(t, 3); enemy.addTag(D + "elite"); var before = t.owner.position(); long now = state(t).buffs().timeMicros();
            cast(t); near(h, t.owner.getY(), before.y + 3, "Ascension vertical movement"); h.assertValueEqual(count(t, "slow", edge), 60, "pulse should sample the accepted cast position before ascent");
            h.assertValueEqual(buff(t, "slow", enemy).deadline() - now, 8_000_000L, "Prismatic duration"); h.assertValueEqual(buff(t, "slow", enemy).origin().ability(), ABILITY, "Slow lost Ascension credit");
            h.assertValueEqual(count(t, "jolt", enemy), 1, "shared Jolt absent"); h.assertValueEqual(count(t, "amplified", t.owner), 1, "Amplified absent"); near(h, energy(t, D + "marksman_dodge_energy"), 0, "cost was not one dodge charge");
            slow(t, enemy, 40); h.assertValueEqual(count(t, "slow", enemy), 0, "shared hundred-stack conversion retained Slow"); h.assertValueEqual(count(t, "freeze", enemy), 1, "shared Freeze absent");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:winters_shroud_regeneration", maxTicks=92)
    public void realTicksIntegrateAdditionalBaseRateThenExpireWhileDetachedAspectDoesNotEraseGrantedBenefits(GameTestHelper h) throws Exception {
        var t = open(h, "stasis");
        try {
            target(t, 3); t.runtime.bind(AmplifiedGameTest.source("stat", "test:winters_shroud_stat", owner(t))); long start = state(t).buffs().timeMicros(); dodge(t); t.runtime.unbind("aspect");
            t.finish(82, () -> { t.runtime.prepare(); double elapsed = (state(t).buffs().timeMicros() - start) / 1_000_000d;
                near(h, energy(t, D + "marksman_dodge_energy"), (2 * elapsed + 9) / 42, "additional base bonus multiplied by stat or persisted beyond three seconds");
                h.assertValueEqual(count(t, "winters_shroud_regeneration", t.owner), 0, "regeneration expiry"); h.assertValueEqual(count(t, "winters_shroud_resistance", t.owner), 0, "calibrated resistance expiry");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void deniedSlowCannotAwardRegenerationButClassUsageStillHalvesCombatantHealthLoss(GameTestHelper h) throws Exception {
        try (var t = open(h, "stasis")) {
            var enemy = target(t, 3); t.deniedStatusTargets.add(enemy.getUUID().toString()); dodge(t);
            h.assertValueEqual(count(t, "slow", enemy), 0, "denied status granted"); h.assertValueEqual(count(t, "winters_shroud_regeneration", t.owner), 0, "denied status generated bonus");
            AmplifiedGameTest.damage(t, Set.of("chorus:combatant")); near(h, t.owner.getHealth(), 95, "class-usage combatant resistance");
            for (var tags : List.of(Set.<String>of(), Set.of("chorus:combatant", "chorus:guardian"))) { t.owner.setHealth(100); AmplifiedGameTest.damage(t, tags); near(h, t.owner.getHealth(), 90, "unclassified or Guardian incorrectly resisted"); }
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:winters_shroud_switch", maxTicks=55)
    public void changingCurrentDodgeRoutesBonusToNewAccountAndClearingStopsItsRecovery(GameTestHelper h) throws Exception {
        var t = open(h, "stasis");
        try {
            target(t, 3); dodge(t); long start = state(t).buffs().timeMicros(); long[] change = {0};
            h.runAfterDelay(20, () -> { try { t.runtime.prepare(); change[0] = state(t).buffs().timeMicros(); choose(t, D + "class", D + "gamblers_dodge"); t.owner.setOnGround(true); dodge(t); choose(t, D + "class", null); } catch (RuntimeException | Error e) { t.close(); throw e; } });
            t.finish(42, () -> { t.runtime.prepare(); double first = (change[0] - start) / 1_000_000d, after = (state(t).buffs().timeMicros() - change[0]) / 1_000_000d;
                near(h, energy(t, D + "marksman_dodge_energy"), (4 * first + after) / 42, "retained account incorrectly kept bonus"); near(h, energy(t, D + "gamblers_dodge_energy"), 0, "cleared Gambler selection still recovered");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void unknownSlowAuthorizationKeepsPaidEnergyAndDefenseAndNeverRetries(GameTestHelper h) throws Exception {
        try (var t = open(h, "stasis")) {
            target(t, 3); int[] checks = {0}; t.beforeWorld = r -> { if (r.command() instanceof StatusResult.Check) { checks[0]++; throw new IllegalStateException("Unknown Slow authorization"); } };
            boolean failed = false; try { dodge(t); } catch (IllegalStateException expected) { failed = true; } h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown result not stopped");
            near(h, energy(t, D + "marksman_dodge_energy"), 0, "payment rolled back"); h.assertValueEqual(count(t, "winters_shroud_resistance", t.owner), 1, "prior pure grant lost");
            h.assertValueEqual(count(t, "winters_shroud_regeneration", t.owner), 0, "unknown result awarded bonus"); failed = false; try { dodge(t); } catch (IllegalStateException expected) { failed = true; } h.assertTrue(failed && checks[0] == 1, "authorization replayed");
        } h.succeed();
    }
}
