package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real paid flight and collisions, native damageable orbs, and confirmed consumption before Restoration.
 * Projectile physics, body geometry and the first-allied-batch collection policy remain explicit calibration. */
public class HealingGrenadeGameTest {
    static final String D = "chorus_d2:", ABILITY = D + "healing_grenade";
    static void prepare(JsonObject data) {
        HeatRisesGameTest.prepare(data);
        for (String name : List.of("healing_grenade", "healing_grenade_energy")) {
            var part = ThreadedSpikeGameTest.json(name);
            if (name.equals("healing_grenade")) AscensionGameTest.calibrate(name, part);
            GamblersDodgeGameTest.merge(data, part);
        }
        AscensionGameTest.version(data);
    }
    static final class Scenario extends PhoenixDiveGameTest.Scenario {
        final List<Long> claimTimes = new ArrayList<>();
        boolean closed;
        Scenario(GameTestHelper h) throws Exception {
            super(h, HealingGrenadeGameTest::prepare);
            t.owner.getFoodData().setFoodLevel(0); choose();
            var observe = t.beforeWorld;
            t.beforeWorld = r -> { observe.accept(r); if (r.command() instanceof WorldConstruct.Consume) claimTimes.add(BenevolenceGameTest.now(t)); };
        }
        @Override public void close() {
            if (closed) return; closed = true;
            super.close();
        }
        void choose() { AscensionGameTest.choose(t, D + "grenade", ABILITY); }
        @Override void cast() {
            h.assertValueEqual(t.runtime.useAbility(t.owner, D + "grenade").outcome(), AbilityUse.Outcome.ACCEPTED, "paid Healing Grenade");
            h.assertValueEqual(t.projectiles.size(), 1, "one actual projectile"); near(h, energy(), 0, "one shared grenade payment");
        }
        @Override double energy() { return AscensionGameTest.energy(t, D + "grenade_energy"); }
        void move(LivingEntity e, double x, double y, double z) { e.setPos(h.absoluteVec(new Vec3(x, y, z))); }
        void away() { move(t.owner, -10, 42, 3.5); }
        void block(int x, int y, int z) {
            var pos = h.absolutePos(new BlockPos(x, y, z)); t.blocks.putIfAbsent(pos, h.getLevel().getBlockState(pos)); h.getLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
        void touch() { t.runtime.bind(new EffectSource("touch", "test:heat_touch", owner(), new BuffInstance.Origin(owner(), "touch", "", ""), Set.of(D + "touch_of_flame"))); }
        void solace(LivingEntity e) { t.runtime.bind(BenevolenceGameTest.source(e, D + "ember_of_solace", false)); }
        EffectConstruct orb() { h.assertValueEqual(t.constructs.size(), 1, "one real Restoration Orb"); return t.constructs.getFirst(); }
        void hitSurface(String face) {
            t.owner.setYRot(face.equals("wall") ? -90 : 0); t.owner.setXRot(face.equals("floor") ? 90 : face.equals("ceiling") ? -90 : 0);
            if (face.equals("wall")) for (int y = 40; y <= 45; y++) block(8, y, 3);
            if (face.equals("ceiling")) block(5, 46, 3);
            cast(); var flight = t.projectiles.getFirst();
            // Advance actual swept native physics; no fabricated impact signal or fixture-only spawn.
            for (int i = 0; i < 12 && !flight.isRemoved(); i++) flight.tick();
            h.assertTrue(flight.isRemoved(), "physical contact ended flight");
            h.assertValueEqual(t.constructs.size(), 1, "contact created damageable orb: " + t.constructReceipts + "; flight=" + flight.position());
        }
        void consumed(LivingEntity recipient, int tier, long duration) {
            h.assertValueEqual(t.consumptions.size(), 1, "one confirmed native consumption: " + t.constructReceipts + "; flights=" + t.projectiles.stream().map(p -> p.position() + "/removed=" + p.isRemoved()).toList());
            h.assertValueEqual(t.consumptions.getFirst().outcome(), WorldConstruct.ConsumeOutcome.CONSUMED, "claim succeeded");
            h.assertTrue(orb().isRemoved(), "claimed orb remains in world"); near(h, orb().getHealth(), 10, "pickup is not damage");
            var restoration = buff("restoration", recipient).orElseThrow(); h.assertValueEqual(restoration.tier(), tier, "Restoration strength");
            near(h, restoration.deadline() - claimTimes.getFirst(), duration, "duration evaluated on collector at pickup");
            h.assertTrue(buff("healing_grenade_orb_behavior", orb()).isEmpty(), "claimed behavior retained");
        }
    }
    static void readyScenario(GameTestHelper h, java.util.function.Consumer<Scenario> run) {
        ready(h, () -> {
            // The dedicated structure includes the complete flight region. GameTest waits for its
            // simulated chunks before starting its clock, instead of racing extra async tickets.
            for (int x : new int[]{4, 9}) for (int z : new int[]{2, 8})
                h.assertTrue(h.getLevel().isPositionEntityTicking(h.absolutePos(new BlockPos(x, 40, z))), "Healing Grenade physical region must be entity-ticking");
            var s = new Scenario(h);
            try { run.accept(s); } catch (RuntimeException | Error e) { s.close(); throw e; }
        });
    }
    static void homing(GameTestHelper h, boolean touch) {
        readyScenario(h, s -> {
            try {
                var ally = s.target(3, true); s.move(ally, 8.5, 42, 7.5); s.solace(ally);
                var enemy = s.target(-2, false); s.move(enemy, 3.5, 42, 5.5);
                if (touch) s.touch(); s.t.owner.setYRot(0); s.t.owner.setXRot(0); s.cast();
                var flight = s.t.projectiles.getFirst(); flight.tick();
                h.assertTrue(flight.getDeltaMovement().x() > 0, "native allied homing did not turn toward off-axis teammate");
                if (touch) s.t.runtime.unbind("touch");
                // Selection and source changes cannot cancel the accepted flight or change captured strength.
                AscensionGameTest.choose(s.t, D + "grenade", D + "test_heat_grenade");
                s.finish(15, () -> {
                    h.assertTrue(flight.isRemoved(), "flight did not hit teammate"); s.consumed(ally, touch ? 2 : 1, 6_000_000);
                    h.assertTrue(s.cure().size() == 4 && s.cure().stream().allMatch(c -> c.amount() == (touch ? 6 : 3) && c.source().ability().equals(ABILITY)), "impact Cure must include nearby caster and ally with cast credit");
                    h.assertTrue(ally.getHealth() > 20 && s.t.owner.getHealth() > 20, "actual health did not increase"); near(h, enemy.getHealth(), 100, "enemy healed");
                    h.assertTrue(s.buff("restoration", enemy).isEmpty() && BenevolenceGameTest.benefit(s.t, s.t.owner).isPresent(), "allied-only rewards and confirmed Benevolence");
                    near(h, s.orb().construct().orElseThrow().parameters().lifetimeMicros(), 7_500_000, "source orb lifetime");
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_homing", maxTicks = 100)
    public void paidPhysicalFlightHomesToAllyAndCreatesConsumedRestorationOrb(GameTestHelper h) { homing(h, false); }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_touch", maxTicks = 100)
    public void capturedTouchStrengthSurvivesUnbindAndComposesWithCollectorSolace(GameTestHelper h) { homing(h, true); }

    static void surface(GameTestHelper h, String face) {
        readyScenario(h, s -> { try (s) {
            s.hitSurface(face); var orb = s.orb(); var bounds = orb.getBoundingBox();
            h.assertTrue(h.getLevel().noCollision(orb, bounds), "orb intersects the contacted surface");
            if (face.equals("floor")) near(h, bounds.minY, h.absoluteVec(new Vec3(0, 40.01, 0)).y, "floor clearance");
            if (face.equals("wall")) near(h, bounds.maxX, h.absoluteVec(new Vec3(7.99, 0, 0)).x, "wall clearance");
            if (face.equals("ceiling")) near(h, bounds.maxY, h.absoluteVec(new Vec3(0, 45.99, 0)).y, "ceiling clearance");
            h.assertTrue(s.t.consumptions.isEmpty() && s.buff("restoration", s.t.owner).isEmpty(), "impact bypassed pickup");
            near(h, orb.getHealth(), 10, "explicit health projection"); near(h, orb.getBbWidth(), .5, "explicit body width");
            ShieldThrowGameTest.settled(s.t); h.succeed();
        } });
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_floor", maxTicks = 60)
    public void floorImpactPlacesEntireOrbAboveTheContact(GameTestHelper h) { surface(h, "floor"); }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_wall", maxTicks = 60)
    public void wallImpactPlacesEntireOrbOutsideTheWall(GameTestHelper h) { surface(h, "wall"); }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_ceiling", maxTicks = 60)
    public void ceilingImpactPlacesEntireOrbBelowTheCeiling(GameTestHelper h) { surface(h, "ceiling"); }

    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_late", maxTicks = 100)
    public void unclaimedOrbWaitsForFirstAlliedBatchAndNeverRewardsSubsequentArrivals(GameTestHelper h) {
        readyScenario(h, s -> {
            try {
                s.hitSurface("floor"); s.away(); var first = s.target(15, true); var late = s.target(16, true);
                s.later(5, () -> {
                    h.assertTrue(s.orb().isAlive() && s.t.consumptions.isEmpty(), "unclaimed orb was lost or remotely picked up");
                    s.move(first, 6.5, 40, 3.5); s.solace(first);
                    s.later(3, () -> {
                        s.consumed(first, 1, 6_000_000); s.move(late, 6.5, 40, 3.5);
                        s.finish(3, () -> { h.assertTrue(s.buff("restoration", late).isEmpty(), "late arrival reused consumed orb"); h.assertValueEqual(s.t.consumptions.size(), 1, "one shared batch claim"); });
                    });
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_expiry", maxTicks = 190)
    public void nativeOrbExpiresAfterSevenPointFiveSecondsWithoutAnEligibleCollector(GameTestHelper h) {
        readyScenario(h, s -> {
            try {
                s.hitSurface("floor"); s.away();
                s.later(140, () -> h.assertTrue(s.orb().isAlive() && s.t.consumptions.isEmpty(), "orb expired before source lifetime"));
                s.finish(155, () -> h.assertTrue(s.orb().isRemoved() && s.t.consumptions.isEmpty() && s.buff("healing_grenade_orb_behavior", s.orb()).isEmpty(), "expired orb kept native body or behavior"));
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_destroyed", maxTicks = 60)
    public void nativeDestructionBeforeFirstScanCancelsRestoration(GameTestHelper h) {
        readyScenario(h, s -> {
            try {
                s.hitSurface("floor"); var orb = s.orb(); h.assertTrue(orb.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(s.t.owner), 20), "real orb rejected lethal damage");
                s.finish(5, () -> h.assertTrue(orb.isRemoved() && s.t.consumptions.isEmpty() && s.buff("restoration", s.t.owner).isEmpty() && s.buff("healing_grenade_orb_behavior", orb).isEmpty(), "destroyed orb rewarded or retained behavior"));
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_denied", maxTicks = 60)
    public void deniedStatusDoesNotRefundPaidThrowOrRecreateConsumedOrb(GameTestHelper h) {
        readyScenario(h, s -> {
            try {
                s.t.deniedStatusTargets.add(s.owner()); s.hitSurface("floor");
                s.finish(5, () -> {
                    h.assertTrue(s.orb().isRemoved() && s.t.consumptions.size() == 1 && s.cure().isEmpty() && s.buff("restoration", s.t.owner).isEmpty(), "denied recipient still rewarded or refunded claim");
                    near(h, s.t.owner.getHealth(), 20, "denied native health"); h.assertTrue(s.energy() < .01, "paid grenade refunded");
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_unknown", maxTicks = 60)
    public void unknownNativeClaimKeepsPhysicalConsumptionAndNeverReplaysOrGrantsRestoration(GameTestHelper h) {
        readyScenario(h, s -> {
            try {
                s.hitSurface("floor"); s.t.failAfterConstructConsume = true;
                s.later(5, () -> { try (s) {
                    h.assertTrue(s.t.runtime.failure().isPresent() && s.orb().isRemoved(), "unknown native claim was acknowledged or reversed");
                    h.assertValueEqual(s.t.consumptions.size(), 1, "unknown claim replayed"); near(h, s.orb().getHealth(), 10, "claim synthesized damage");
                    h.assertTrue(s.buff("restoration", s.t.owner).isEmpty() && s.energy() < .01, "unknown claim granted reward or refunded grenade");
                    MinecraftEffectRuntime.tick(h.getLevel()); s.orb().tick(); h.assertValueEqual(s.t.consumptions.size(), 1, "failed runtime repeated pickup"); h.succeed();
                } });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(structure = "chorus_gametest:healing_grenade", environment = "chorus_gametest:healing_grenade_consumed", maxTicks = 100)
    public void actualHeldHeatRisesInputConsumesRealHealingGrenadeWithoutThrowing(GameTestHelper h) {
        readyScenario(h, s -> {
            try {
                s.touch(); s.solace(s.t.owner);
                s.t.runtime.bind(new EffectSource("heat", D + "heat_rises_aspect", s.owner(), new BuffInstance.Origin(s.owner(), "heat", "", ""), Set.of(), Map.of("hold_time", new Measure(.3, Unit.SECOND), "receipt_seconds", new Measure(60, Unit.SECOND))));
                s.t.runtime.abilityInput(s.t.owner, D + "grenade", AbilityInput.Edge.PRESS, 1);
                s.later(7, () -> {
                    var release = s.t.runtime.abilityInput(s.t.owner, D + "grenade", AbilityInput.Edge.RELEASE, 1);
                    h.assertValueEqual(release.cast().orElseThrow().resolved(), D + "heat_rises_consume", "real held input conversion");
                    s.finish(3, () -> {
                        h.assertTrue(s.t.projectiles.isEmpty() && s.t.constructs.isEmpty() && s.buff("heat_rises", s.t.owner).isPresent(), "consumed grenade executed original projectile");
                        h.assertTrue(s.cure().size() == 2 && s.cure().stream().allMatch(c -> c.amount() == 9), "Healing Grenade consumption Cure x3");
                        h.assertValueEqual(s.buff("restoration", s.t.owner).orElseThrow().tier(), 1, "consumption uses Heat Rises Restoration x1, not thrown x2");
                        h.assertTrue(s.energy() < .01, "held input did not pay actual grenade account");
                    });
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
}
