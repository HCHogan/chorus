package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.StatusResult;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

/** Real inputs, native velocity/damage, same-life delayed rewards and selected-dodge composition. */
public class AcrobatDodgeGameTest {
    static final String D = "chorus_d2:", ABILITY = D + "acrobat_dodge", ENERGY = D + "acrobat_dodge_energy";
    @FunctionalInterface interface Scenario { void run() throws Exception; }
    static void ready(GameTestHelper h, Scenario scenario) {
        var min = ChunkPos.containing(h.absolutePos(new BlockPos(-16, 40, 0))); var max = ChunkPos.containing(h.absolutePos(new BlockPos(22, 40, 8)));
        var chunks = new ArrayList<ChunkPos>();
        for (int x = min.x(); x <= max.x(); x++) for (int z = min.z(); z <= max.z(); z++) { h.getLevel().getChunk(x, z); chunks.add(new ChunkPos(x, z)); }
        h.startSequence().thenWaitUntil(() -> { for (var chunk : chunks) h.assertTrue(h.getLevel().areEntitiesLoaded(chunk.pack()), "Acrobat fixture entity index must finish loading"); })
                .thenExecute(() -> { try { scenario.run(); } catch (RuntimeException | Error e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); } });
    }
    static void prepare(JsonObject data) {
        AscensionGameTest.prepare(data);
        for (String name : List.of("acrobat_dodge", "radiant", "solar_effect_duration", "ember_of_solace", "empowering_damage")) {
            var part = ThreadedSpikeGameTest.json(name); if (name.equals("acrobat_dodge")) AscensionGameTest.calibrate(name, part); GamblersDodgeGameTest.merge(data, part);
        }
        GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json("acrobat_dodge_test_calibration").getAsJsonObject("program"));
    }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "ascension", AcrobatDodgeGameTest::prepare, true);
        try {
            t.owner.setGameMode(GameType.SURVIVAL); t.owner.setOnGround(false); t.owner.setYRot(0); t.owner.setXRot(-90); t.owner.setDeltaMovement(0, 0, 0);
            AscensionGameTest.choose(t, D + "class", ABILITY); AscensionGameTest.choose(t, D + "air_move", D + "ascension");
            t.runtime.bind(AmplifiedGameTest.calibration(id(t))); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static String id(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static void input(ProjectileGameTest.Harness t, Input keys) { t.owner.connection.handlePlayerInput(new ServerboundPlayerInputPacket(keys)); }
    static void cast(ProjectileGameTest.Harness t) { t.h.assertValueEqual(t.runtime.useAbility(t.owner, D + "class").outcome(), AbilityUse.Outcome.ACCEPTED, "Acrobat rejected"); ShieldThrowGameTest.settled(t); }
    static double energy(ProjectileGameTest.Harness t) { return AscensionGameTest.energy(t, ENERGY); }
    static Optional<BuffInstance> radiant(ProjectileGameTest.Harness t, LivingEntity who) { return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.key().holder().equals(who.getUUID().toString()) && b.definition().id().equals(D + "radiant")).findFirst(); }
    static LivingEntity target(ProjectileGameTest.Harness t, double dx) {
        var entity = t.h.spawnWithNoFreeWill(EntityTypes.COW, t.h.relativeVec(t.owner.position().add(dx, 0, 0))); entity.setNoGravity(true); entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); entity.setHealth(100); entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); t.entities.add(entity);
        t.h.assertTrue(t.resolve(entity.getUUID().toString()) == entity, "target entity index missing"); return entity;
    }
    static void solace(ProjectileGameTest.Harness t, LivingEntity who) { var id = who.getUUID().toString(); t.runtime.bind(new EffectSource("solace/" + id, D + "ember_of_solace", id, new BuffInstance.Origin(id, "solace", "", ""), Set.of())); }
    @GameCase(environment="chorus_gametest:acrobat_input", maxTicks=18)
    public void actualInputSetsHorizontalAndVerticalVelocityBeforeDelayedSameLifeBenefits(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h);
            try {
                var impulses = new ArrayList<Impulse.Command>(); t.beforeWorld = r -> { if (r.command() instanceof Impulse.Command c) impulses.add(c); };
                input(t, new Input(false, false, true, false, false, false, false)); cast(t);
                near(h, t.owner.getDeltaMovement().x(), .3, "left input native velocity"); near(h, t.owner.getDeltaMovement().y(), .2, "leap native vertical velocity"); near(h, energy(t), 0, "cost before motion"); h.assertTrue(radiant(t, t.owner).isEmpty(), "benefit before calibrated completion");
                t.owner.setYRot(180); input(t, new Input(true, false, false, false, false, false, false)); AscensionGameTest.choose(t, D + "class", null);
                t.finish(6, () -> { h.assertValueEqual(impulses.size(), 2, "completion replayed leap"); near(h, impulses.getFirst().direction().orElseThrow().x(), 1, "changed input affected captured motion"); h.assertValueEqual(radiant(t, t.owner).orElseThrow().origin().ability(), ABILITY, "original cast credit lost"); near(h, energy(t), 0, "unselected account changed"); });
            } catch (Exception | Error e) { t.close(); throw e; }
        });
    }
    @GameCase(environment="chorus_gametest:acrobat_radius", maxTicks=18)
    public void completionUsesCurrentCasterPositionAndRecipientSolaceWithRealEnemyDamage(GameTestHelper h) {
        ready(h, () -> {
            var t = open(h); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("acrobat-" + UUID.randomUUID());
            try {
                var ally = target(t, 12); var outside = target(t, 12.001); var enemy = target(t, 5.25); var edge = target(t, 8.5); var past = target(t, 8.501);
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team); scoreboard.addPlayerToTeam(outside.getScoreboardName(), team); solace(t, ally);
                cast(t); double now = t.runtime.state().engine().domain().buffs().timeMicros(); t.owner.setPos(t.owner.position().add(2, 0, 0));
                h.runAfterDelay(6, () -> { try {
                    ShieldThrowGameTest.settled(t); h.assertTrue(radiant(t, t.owner).isPresent() && radiant(t, ally).isPresent() && radiant(t, outside).isEmpty(), "10 m allied boundary at completion");
                    near(h, radiant(t, ally).orElseThrow().deadline() - now, 15_200_000, "recipient Solace"); near(h, radiant(t, t.owner).orElseThrow().deadline() - now, 10_200_000, "caster inherited ally Solace");
                    near(h, enemy.getHealth(), 98, "actual calibrated halfway-falloff damage"); near(h, edge.getHealth(), 100, "zero boundary falloff"); near(h, past.getHealth(), 100, "outside blast");
                    h.assertTrue(t.hits.stream().allMatch(c -> c.source().ability().equals(ABILITY)), "damage cast credit"); h.assertTrue(t.queries.stream().anyMatch(q -> q.radius() == 6.5 && q.relation() == com.imdomestic.chorus.effect.target.TargetQuery.Relation.NOT_ALLIED), "enemy query missing"); h.succeed();
                } finally { scoreboard.removePlayerTeam(team); t.close(); } });
            } catch (Exception | Error e) { scoreboard.removePlayerTeam(team); t.close(); throw e; }
        });
    }
    @GameCase(environment="chorus_gametest:acrobat_neutral", maxTicks=18)
    public void airborneNeutralUsesTheExplicitCalibratedBackwardFallback(GameTestHelper h) {
        ready(h, () -> { var t = open(h); try {
            input(t, Input.EMPTY); cast(t); near(h, t.owner.getDeltaMovement().z(), -.3, "explicit neutral fallback"); near(h, t.owner.getDeltaMovement().y(), .2, "airborne leap");
            t.finish(6, () -> h.assertTrue(radiant(t, t.owner).isPresent(), "airborne calibrated completion missing"));
        } catch (Exception | Error e) { t.close(); throw e; } });
    }
    @GameCase(environment="chorus_gametest:acrobat_ascension", maxTicks=18)
    public void actualAscensionPaysAcrobatAccountAndRetainsOnlyConfiguredSharedBenefits(GameTestHelper h) {
        ready(h, () -> { var t = open(h); try {
            int[] impulses = {0}; t.beforeWorld = r -> { if (r.command() instanceof Impulse.Command) impulses[0]++; }; double y = t.owner.getY();
            AscensionGameTest.cast(t); near(h, t.owner.getY(), y + 3, "real Ascension movement"); near(h, energy(t), 0, "current Acrobat payment");
            AscensionGameTest.choose(t, D + "class", D + "marksman_dodge"); AscensionGameTest.choose(t, D + "air_move", null);
            t.finish(6, () -> { h.assertValueEqual(impulses[0], 0, "shared benefit replayed Acrobat leap"); h.assertTrue(t.hits.stream().noneMatch(c -> c.tags().contains(D + "acrobat_dodge_damage")), "synthetic Radiant-only policy ignored"); h.assertValueEqual(radiant(t, t.owner).orElseThrow().origin().ability(), D + "ascension", "original Ascension credit lost"); near(h, energy(t), 0, "second payment or retained recharge"); });
        } catch (Exception | Error e) { t.close(); throw e; } });
    }
    @GameCase(environment="chorus_gametest:acrobat_dead", maxTicks=18)
    public void actualDeathBeforeCompletionPreventsRadiantAndBlast(GameTestHelper h) {
        ready(h, () -> { var t = open(h); try {
            var enemy = target(t, 2); cast(t); t.owner.setHealth(0);
            t.finish(6, () -> { h.assertTrue(radiant(t, t.owner).isEmpty(), "dead caster received completion"); near(h, enemy.getHealth(), 100, "dead caster produced blast"); h.assertTrue(t.hits.isEmpty(), "completion damage after death"); });
        } catch (Exception | Error e) { t.close(); throw e; } });
    }
    @GameCase(environment="chorus_gametest:acrobat_unknown", maxTicks=18)
    public void unknownCompletionAuthorizationRetainsPaymentAndNativeVelocityWithoutReplay(GameTestHelper h) {
        ready(h, () -> { var t = open(h); try {
            int[] queries = {0}, impulses = {0}; t.beforeWorld = r -> { if (r.command() instanceof Impulse.Command) impulses[0]++; if (r.command() instanceof StatusResult.Check q && q.definition().id().equals(D + "radiant")) { queries[0]++; throw new IllegalStateException("Unknown Acrobat completion authorization"); } };
            cast(t); h.runAfterDelay(6, () -> { try {
                h.assertTrue(t.runtime.failure().isPresent(), "unknown result reported success"); h.assertTrue(energy(t) < .01, "failed completion refunded energy"); MinecraftEffectRuntime.tick(h.getLevel()); h.assertValueEqual(impulses[0], 2, "failed completion replayed movement"); h.assertValueEqual(queries[0], 1, "failed completion retried authorization"); h.assertTrue(t.hits.isEmpty(), "unknown authorization continued to blast"); h.succeed();
            } finally { t.close(); } });
        } catch (Exception | Error e) { t.close(); throw e; } });
    }
}
