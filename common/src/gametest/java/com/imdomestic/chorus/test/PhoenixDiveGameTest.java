package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.platform.minecraft.PlayerActor;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

/** Native paid motion, accepted player landings, shared Solar effects and confirmed lifecycle cancellation. */
public class PhoenixDiveGameTest {
    static final String D = "chorus_d2:", ABILITY = D + "phoenix_dive", ENERGY = D + "phoenix_dive_energy";
    static void prepare(JsonObject data) {
        BenevolenceGameTest.prepareBenevolence(data);
        data.getAsJsonArray("buffs").asList().removeIf(x -> HealClipGameTest.identity(x, "buffs").equals(D + "restoration"));
        data.getAsJsonArray("bundles").asList().removeIf(x -> HealClipGameTest.identity(x, "bundles").equals(D + "restoration_active"));
        for (String name : List.of("phoenix_dive", "restoration_effect", "restoration_test_calibration", "solar", "solar_test_calibration", "ember_of_char")) {
            var part = ThreadedSpikeGameTest.json(name); if (name.equals("phoenix_dive")) AscensionGameTest.calibrate(name, part);
            GamblersDodgeGameTest.merge(data, part);
        }
        GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json("phoenix_dive_test_calibration").getAsJsonObject("program"));
        AscensionGameTest.version(data);
    }
    static class Scenario implements AutoCloseable {
        final GameTestHelper h; final ProjectileGameTest.Harness t; final PlayerTeam team;
        final List<HealingCommand> heals = new ArrayList<>(); final List<Impulse.Command> impulses = new ArrayList<>();
        Scenario(GameTestHelper h) throws Exception {
            this(h, PhoenixDiveGameTest::prepare);
        }
        Scenario(GameTestHelper h, java.util.function.Consumer<JsonObject> prepare) throws Exception {
            this.h = h; t = new ProjectileGameTest.Harness(h, "ascension", prepare, true);
            var board = h.getLevel().getScoreboard(); team = board.addPlayerTeam("phoenix-" + UUID.randomUUID());
            try {
                t.owner.setGameMode(GameType.SURVIVAL); t.owner.setHealth(20); t.owner.setDeltaMovement(0, 0, 0);
                GroundContactGameTest.floor(t); GroundContactGameTest.placePlayer(t); board.addPlayerToTeam(t.owner.getScoreboardName(), team);
                AscensionGameTest.choose(t, D + "class", ABILITY);
                for (String bundle : List.of("test:phoenix_inputs", D + "ember_of_benevolence")) bind(bundle);
                var actor = PlayerActor.get(t.owner);
                actor.configure(t.owner, Optional.of(new com.imdomestic.chorus.effect.actor.ActorBinding(D + "solar_scaling")), actor.revision());
                t.beforeWorld = r -> { if (r.command() instanceof HealingCommand c) heals.add(c); if (r.command() instanceof Impulse.Command c) impulses.add(c); };
            } catch (Exception | Error e) { close(); throw e; }
        }
        String owner() { return t.owner.getUUID().toString(); }
        void bind(String bundle) { t.runtime.bind(BenevolenceGameTest.source(t.owner, bundle, false)); }
        Optional<BuffInstance> buff(String name, LivingEntity who) {
            return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals(D + name) && b.key().holder().equals(who.getUUID().toString())).findFirst();
        }
        Optional<BuffInstance> pending() { return buff("phoenix_dive_pending", t.owner); }
        double energy() { return AscensionGameTest.energy(t, ENERGY); }
        List<HealingCommand> cure() { return heals.stream().filter(c -> c.tags().contains(D + "cure")).toList(); }
        List<DamageCommand> blasts() { return t.hits.stream().filter(c -> c.tags().contains(D + "phoenix_dive_damage")).toList(); }
        LivingEntity target(double distance, boolean allied) {
            var e = t.cow(5.5 + distance, 40, 3.5); e.setHealth(allied ? 20 : 100);
            if (allied) h.getLevel().getScoreboard().addPlayerToTeam(e.getScoreboardName(), team);
            h.assertTrue(t.resolve(e.getUUID().toString()) == e, "target must be indexed"); return e;
        }
        void input(String name) { t.runtime.start(new RuleEngine.Signal("test:phoenix_" + name, new EffectEvent(owner(), owner(), new BuffInstance.Origin(owner(), "input", "", ""), Set.of(), Map.of()))); ShieldThrowGameTest.settled(t); }
        void cast() { h.assertValueEqual(t.runtime.useAbility(t.owner, D + "class").outcome(), AbilityUse.Outcome.ACCEPTED, "Phoenix payment accepted"); ShieldThrowGameTest.settled(t); }
        void land() { GroundContactGameTest.packet(t, 40, true); GroundContactGameTest.settled(t); }
        void later(int ticks, Runnable action) { h.runAfterDelay(ticks, () -> { try { action.run(); } catch (RuntimeException | Error e) { close(); throw e; } }); }
        void finish(int ticks, Runnable checks) { later(ticks, () -> { try { ShieldThrowGameTest.settled(t); checks.run(); h.succeed(); } finally { close(); } }); }
        @Override public void close() { h.getLevel().getScoreboard().removePlayerTeam(team); t.close(); }
    }
    @GameCase(environment = "chorus_gametest:phoenix_landing", maxTicks = 100)
    public void paidInputWaitsForAcceptedPhysicalLandingBeforeSharedCureAndBenevolence(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h);
            try {
                var ally = s.target(9, true); var outside = s.target(9.001, true); var enemy = s.target(2, false);
                AcrobatDodgeGameTest.input(s.t, new Input(false, false, true, false, false, false, false)); s.cast();
                near(h, s.t.owner.getDeltaMovement().x(), .15, "native horizontal impulse"); near(h, s.t.owner.getDeltaMovement().y(), -.6, "native downward impulse"); near(h, s.energy(), 0, "one payment");
                s.later(3, () -> {
                    h.assertTrue(s.pending().isPresent() && s.heals.isEmpty(), "timer cannot impersonate landing");
                    GroundContactGameTest.packet(s.t, 39.5, true); GroundContactGameTest.settled(s.t);
                    h.assertTrue(s.pending().isPresent() && s.heals.isEmpty(), "rejected movement consumed pending");
                    try { NativeMotionGameTest.acknowledge(s.t.owner); } catch (Exception e) { throw new IllegalStateException(e); }
                    s.land(); h.assertTrue(s.pending().isEmpty(), "accepted contact consumed pending"); s.land();
                    s.finish(4, () -> {
                        near(h, s.t.owner.getHealth(), 32, "self Cure x2"); near(h, ally.getHealth(), 32, "9 m allied Cure x2"); near(h, outside.getHealth(), 20, "outside Cure radius"); near(h, enemy.getHealth(), 100, "nonheated landing cannot damage");
                        h.assertValueEqual(s.cure().size(), 4, "repeated grounded packet cannot repeat Cure"); h.assertTrue(BenevolenceGameTest.benefit(s.t, s.t.owner).isPresent(), "actual allied Cure triggers shared fragment");
                        h.assertTrue(s.cure().stream().allMatch(c -> c.source().owner().equals(s.owner()) && c.source().ability().equals(ABILITY)), "Cure keeps cast attribution");
                    });
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:phoenix_heat", maxTicks = 100)
    public void immediateRestorationAndHeatedLandingRetainClassCreditThroughSharedScorch(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h);
            try {
                var enemy = s.target(2, false); var edge = s.target(5, false); var outside = s.target(5.001, false); s.target(9, true);
                s.bind(D + "ember_of_solace"); s.bind(D + "ember_of_ashes"); s.input("heat"); long start = BenevolenceGameTest.now(s.t); s.cast();
                h.assertValueEqual(s.impulses.size(), 1, "neutral input adds only downward motion"); near(h, s.t.owner.getDeltaMovement().y(), -.6, "neutral downward velocity");
                var restoration = s.buff("restoration", s.t.owner).orElseThrow(); h.assertValueEqual(restoration.tier(), 2, "Restoration x2"); near(h, restoration.deadline() - start, 4_500_000, "Solace on immediate Restoration");
                h.assertTrue(s.cure().isEmpty() && s.blasts().isEmpty(), "landing effects before contact"); s.input("unheat"); s.t.deniedStatusTargets.add(edge.getUUID().toString()); s.land();
                near(h, enemy.getHealth(), 90, "native calibrated explosion"); near(h, edge.getHealth(), 90, "inclusive 5 m blast independent of Scorch denial"); near(h, outside.getHealth(), 100, "outside explicit blast radius");
                h.assertValueEqual(s.buff("scorch", enemy).orElseThrow().count(), 60, "Ashes adds twenty stacks"); h.assertTrue(s.buff("scorch", edge).isEmpty(), "target-specific authorization");
                s.finish(12, () -> {
                    var dot = s.t.hits.stream().filter(c -> c.tags().contains(D + "scorch_damage")).findFirst().orElseThrow();
                    h.assertTrue(dot.tags().contains("chorus:class_damage") && dot.killTags().contains("chorus:class_kill"), "Scorch dropped class identity");
                    h.assertValueEqual(dot.source().ability(), ABILITY, "physical landing origin replaced original cast"); h.assertTrue(enemy.getHealth() < 90, "shared Scorch actually damaged the target");
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:phoenix_unequip", maxTicks = 100)
    public void unequippingAfterPaymentRetainsOneLandingAndItsOriginalAbilityCredit(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h);
            try {
                var enemy = s.target(2, false); s.input("heat"); s.cast(); var origin = s.pending().orElseThrow().origin();
                AscensionGameTest.choose(s.t, D + "class", null); s.land(); s.finish(4, () -> {
                    h.assertTrue(s.pending().isEmpty() && !s.blasts().isEmpty(), "unselection erased accepted landing work");
                    h.assertTrue(s.blasts().stream().allMatch(c -> c.source().equals(origin)), "detached completion changed origin");
                    h.assertValueEqual(s.buff("scorch", enemy).orElseThrow().count(), 40, "base heated stacks"); near(h, s.energy(), 0, "unselected account recharged");
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    static void lifecycle(GameTestHelper h, boolean death) {
        ready(h, () -> { try (var s = new Scenario(h)) {
            s.cast(); h.assertTrue(s.pending().isPresent(), "pending before lifecycle");
            if (death) s.t.owner.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000); else s.t.owner.discard();
            GroundContactGameTest.settled(s.t); h.assertTrue(s.pending().isEmpty(), "confirmed lifecycle must cancel old pending");
            h.assertTrue(s.cure().isEmpty() && s.blasts().isEmpty(), "cancellation granted landing effects"); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:phoenix_death", maxTicks = 100)
    public void confirmedPlayerDeathCancelsThePendingDive(GameTestHelper h) { lifecycle(h, true); }
    @GameCase(environment = "chorus_gametest:phoenix_removal", maxTicks = 100)
    public void confirmedPlayerRemovalCancelsThePendingDive(GameTestHelper h) { lifecycle(h, false); }
    @GameCase(environment = "chorus_gametest:phoenix_unknown_blast", maxTicks = 100)
    public void unknownBlastReceiptRetainsNativeDamageAndConsumedLandingWithoutReplay(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h);
            try {
                var enemy = s.target(2, false); s.input("heat"); s.cast(); s.t.failAfterDamage = true;
                GroundContactGameTest.packet(s.t, 40, true); s.t.runtime.prepare();
                h.assertTrue(s.t.runtime.failure().isPresent() && s.pending().isEmpty(), "unknown blast must stop after consuming pending"); near(h, enemy.getHealth(), 90, "native damage remains committed");
                s.later(4, () -> { try {
                    MinecraftEffectRuntime.tick(h.getLevel()); h.assertValueEqual(s.blasts().size(), 1, "unknown blast replayed"); near(h, enemy.getHealth(), 90, "replayed damage");
                    h.assertTrue(s.buff("scorch", enemy).isEmpty() && s.cure().isEmpty(), "unknown damage continued queued rewards"); h.succeed();
                } finally { s.close(); } });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:phoenix_unknown_cure", maxTicks = 100)
    public void unknownCureReceiptRetainsTheFirstActualPulseWithoutInventingFragmentCredit(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h);
            try {
                s.target(9, true); s.cast(); s.t.failAfterHealing = true; s.land();
                s.later(4, () -> { try {
                    h.assertTrue(s.t.runtime.failure().isPresent() && s.pending().isEmpty(), "unknown Cure must stop consumed landing");
                    h.assertValueEqual(s.cure().size(), 1, "unknown Cure replay or continued pulse"); near(h, s.t.resolve(s.cure().getFirst().target()).getHealth(), 26, "first native pulse remains committed");
                    h.assertTrue(BenevolenceGameTest.benefit(s.t, s.t.owner).isEmpty(), "unconfirmed Cure invented benefit"); h.succeed();
                } finally { s.close(); } });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:phoenix_repeat", maxTicks = 100)
    public void aSubsequentPaidDiveCanLandAgainAfterTheFirstPendingIsConsumed(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h);
            try {
                s.cast(); s.input("full"); h.assertValueEqual(s.t.runtime.useAbility(s.t.owner, D + "class").outcome(), AbilityUse.Outcome.CONDITION, "pending cannot be overwritten"); near(h, s.energy(), 1, "rejected overlapping cast paid"); s.land();
                s.later(22, () -> {
                    try { GroundContactGameTest.placePlayer(s.t); } catch (Exception e) { throw new IllegalStateException(e); }
                    s.cast(); s.land(); s.finish(4, () -> { h.assertValueEqual(s.cure().size(), 4, "two accepted dives each heal twice"); near(h, s.t.owner.getHealth(), 44, "second legitimate cast completed"); });
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
}
