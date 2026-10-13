package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageFacts;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

/** Real input duration, payment, health, kill observations, movement and Solar composition.
 * Grenade/Wisp producers, hold threshold and unit projection are explicit test calibration. */
public class HeatRisesGameTest {
    static final String D = "chorus_d2:", CONSUME = D + "heat_rises_consume";
    static void prepare(JsonObject data) {
        PhoenixDiveGameTest.prepare(data);
        for (String name : List.of("heat_rises", "heat_rises_test_inputs", "icarus_dash", "icarus_dash_inputs", "icarus_dash_cure"))
            GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json(name));
        AscensionGameTest.version(data);
    }
    static final class Scenario extends PhoenixDiveGameTest.Scenario {
        long gesture;
        Scenario(GameTestHelper h) throws Exception {
            super(h, HeatRisesGameTest::prepare);
            try {
                t.owner.getFoodData().setFoodLevel(0);
                choose("grenade", D + "test_heat_grenade"); choose("melee", "test:benevolence_melee"); choose("air_move", D + "icarus_dash");
                bind("test:heat_inputs"); aspect("heat-aspect");
            } catch (Exception | Error e) { close(); throw e; }
        }
        void choose(String slot, String ability) { AscensionGameTest.choose(t, D + slot, ability); }
        void aspect(String id) {
            t.runtime.bind(new EffectSource(id, D + "heat_rises_aspect", owner(), new BuffInstance.Origin(owner(), id, "", ""), Set.of(),
                    Map.of("hold_time", new Measure(.3, Unit.SECOND), "receipt_seconds", new Measure(60, Unit.SECOND))));
        }
        void touch() { t.runtime.bind(new EffectSource("touch", "test:heat_touch", owner(), new BuffInstance.Origin(owner(), "touch", "", ""), Set.of(D + "touch_of_flame"))); }
        void press() { h.assertValueEqual(t.runtime.abilityInput(t.owner, D + "grenade", AbilityInput.Edge.PRESS, ++gesture).outcome(), AbilityInput.Outcome.PRESSED, "held grenade press"); }
        AbilityInput.Receipt release() { var r = t.runtime.abilityInput(t.owner, D + "grenade", AbilityInput.Edge.RELEASE, gesture); ShieldThrowGameTest.settled(t); return r; }
        void consumed() {
            var r = release(); h.assertTrue(r.heldMicros() >= 300_000, "server did not measure calibrated hold duration");
            h.assertValueEqual(r.cast().orElseThrow().resolved(), CONSUME, "held consumption definition");
            h.assertValueEqual(r.cast().orElseThrow().outcome(), AbilityUse.Outcome.ACCEPTED, "consumption payment");
            near(h, heat().orElseThrow().deadline() - now(), 15_000_000, "consume resets fifteen seconds");
        }
        Optional<BuffInstance> heat() { return buff("heat_rises", t.owner); }
        long now() { return BenevolenceGameTest.now(t); }
        double account(String name) { return AscensionGameTest.energy(t, name); }
        double melee() { return account(D + "solar_melee_progress"); }
        double ae() {
            var event = new EffectEvent(owner(), owner(), new BuffInstance.Origin(owner(), "query", "", ""), Set.of(), Map.of());
            return t.runtime.program().calculate(t.runtime.state().engine().domain(), owner(), event, D + "weapon_airborne_effectiveness", new Measure(0, Unit.STAT_POINT), List.of()).output().value();
        }
        void full() { heatEvent("full", t.owner); }
        void heatEvent(String name, LivingEntity target) {
            t.runtime.start(new RuleEngine.Signal("test:heat_" + name, new EffectEvent(owner(), target.getUUID().toString(), new BuffInstance.Origin(owner(), "input", "", ""), Set.of(), Map.of())));
            ShieldThrowGameTest.settled(t);
        }
        void drainMelee() { h.assertValueEqual(t.runtime.useAbility(t.owner, D + "melee").outcome(), AbilityUse.Outcome.ACCEPTED, "synthetic melee payment"); }
        LivingEntity kill(int rank) {
            var victim = target(2, false); victim.addTag(D + "combatant_tier_" + rank); heatEvent("kill", victim);
            h.assertTrue(victim.isDeadOrDying(), "kill must change actual native life"); return victim;
        }
    }
    static void consumption(GameTestHelper h, String kind) {
        ready(h, () -> {
            var s = new Scenario(h);
            try {
                // All heal recipients are on the same native feet plane for the exact 9 m boundary.
                s.land(); var ally = s.target(9, true); var outside = s.target(9.001, true);
                s.t.runtime.bind(BenevolenceGameTest.source(ally, D + "ember_of_solace", false));
                if (!kind.equals("ordinary")) s.choose("grenade", D + "test_heat_healing");
                if (kind.equals("touch")) s.touch(); if (kind.equals("wisp")) s.bind("test:heat_wisp");
                if (kind.equals("ordinary")) {
                    s.press(); h.assertValueEqual(s.release().cast().orElseThrow().resolved(), D + "test_heat_grenade", "short release uses normal grenade");
                    h.assertTrue(s.heat().isEmpty(), "short release granted Heat Rises"); s.full();
                }
                s.press(); near(h, s.account(D + "grenade_energy"), 1, "press cannot spend charge");
                s.later(8, () -> {
                    s.consumed(); long start = s.now(); int tier = kind.equals("ordinary") ? 2 : 3;
                    boolean wisp = kind.equals("wisp"); near(h, s.account(D + "grenade_energy"), wisp ? 1 : 0, "actual consumed account");
                    if (wisp) near(h, s.account("test:heat_wisp_energy"), .75, "Wisp declared charge paid once");
                    int restoration = kind.equals("touch") ? 1 : wisp ? 2 : 0;
                    if (restoration > 0) {
                        h.assertValueEqual(s.buff("restoration", s.t.owner).orElseThrow().tier(), restoration, "consumed variant Restoration");
                        near(h, s.buff("restoration", s.t.owner).orElseThrow().deadline() - start, 4_000_000, "self duration");
                        near(h, s.buff("restoration", ally).orElseThrow().deadline() - start, 6_000_000, "recipient Solace");
                    } else h.assertTrue(s.buff("restoration", s.t.owner).isEmpty(), "ordinary/healing consumption invented Restoration");
                    near(h, s.ae(), 70, "20 passive and 50 active stat query");
                    s.finish(4, () -> {
                        h.assertValueEqual(s.cure().size(), 4, "two actual pulses for self and ally");
                        h.assertTrue(s.cure().stream().allMatch(c -> c.amount() == tier * 3 && c.source().ability().equals(CONSUME)), "Cure tier or consume attribution");
                        if (restoration == 0) { near(h, s.t.owner.getHealth(), 20 + tier * 6, "actual self Cure"); near(h, ally.getHealth(), 20 + tier * 6, "actual allied Cure"); }
                        else { h.assertTrue(s.t.owner.getHealth() > 20 + tier * 6 && ally.getHealth() > 20 + tier * 6, "Restoration must also change native health"); }
                        near(h, outside.getHealth(), 20, "outside 9 m gets no healing");
                        h.assertTrue(BenevolenceGameTest.benefit(s.t, s.t.owner).isPresent(), "confirmed ally healing feeds Benevolence");
                        h.assertValueEqual(s.t.runtime.abilityInput(s.t.owner, D + "grenade", AbilityInput.Edge.RELEASE, s.gesture).outcome(), AbilityInput.Outcome.NO_PRESS, "release cannot replay consumption");
                    });
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:heat_rises_ordinary", maxTicks = 100)
    public void actualShortAndHeldGrenadeInputsPayOnceAndHealSelfAndNineMeterAlly(GameTestHelper h) { consumption(h, "ordinary"); }
    @GameCase(environment = "chorus_gametest:heat_rises_healing", maxTicks = 100)
    public void consumedHealingGrenadeUsesCureThreeWithoutInventingRestoration(GameTestHelper h) { consumption(h, "healing"); }
    @GameCase(environment = "chorus_gametest:heat_rises_touch", maxTicks = 100)
    public void consumedTouchHealingGrenadeGrantsSharedRestorationWithEachRecipientsSolace(GameTestHelper h) { consumption(h, "touch"); }
    @GameCase(environment = "chorus_gametest:heat_rises_wisp", maxTicks = 100)
    public void consumedWispPaysTheTransformedAccountAndGrantsCureThreeAndRestorationTwo(GameTestHelper h) { consumption(h, "wisp"); }
    @GameCase(environment = "chorus_gametest:heat_rises_passive", maxTicks = 100)
    public void actualAirborneKillsGrantRankedMeleeEnergyWithoutActiveHeatAndDuplicateSourcesDoNotMultiplyIt(GameTestHelper h) {
        ready(h, () -> { try (var s = new Scenario(h)) {
            s.drainMelee(); s.aspect("duplicate"); s.t.owner.setOnGround(false); near(h, s.ae(), 20, "passive duplicate family maximum");
            double expected = 0; double[] gains = {.1, .125, .175, .25};
            for (int rank = 1; rank <= 4; rank++) {
                s.kill(rank); expected += gains[rank - 1]; near(h, s.melee(), expected, "ranked actual killed target with receiving CES");
                var facts = DamageFacts.from(s.t.hits.getLast(), s.t.receipts.getLast());
                s.t.runtime.start(facts.stream().filter(f -> f.type().equals("chorus:kill")).findFirst().orElseThrow());
                near(h, s.melee(), expected, "same confirmed death cannot award twice");
            }
            h.assertTrue(s.heat().isEmpty(), "passive kills fabricated active buff"); s.t.owner.setOnGround(true); s.kill(4); near(h, s.melee(), expected, "grounded kill"); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:heat_rises_historical", maxTicks = 100)
    public void nativeDeathObservationKeepsAirborneAndRankFactsAfterLiveEntitiesChange(GameTestHelper h) {
        ready(h, () -> { try (var s = new Scenario(h)) {
            s.drainMelee(); s.t.owner.setOnGround(false);
            s.t.afterDamageReceipt = (command, receipt) -> {
                s.t.owner.setOnGround(true); var victim = s.t.resolve(command.target());
                victim.removeTag(D + "combatant_tier_1"); victim.addTag(D + "combatant_tier_4"); victim.discard();
            };
            var victim = s.kill(1); h.assertTrue(victim.isRemoved() && s.t.owner.onGround(), "live observation was changed");
            near(h, s.melee(), .1, "historical airborne T1 reward"); h.succeed();
        } });
    }
    @GameCase(environment = "chorus_gametest:heat_rises_reset", maxTicks = 100)
    public void actualKillsCapRemainingTimeAndASecondPaidConsumptionOverwritesLongerDuration(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h); try {
                s.press(); s.later(8, () -> {
                    s.consumed(); s.t.owner.setOnGround(false); s.kill(4); s.kill(4); near(h, s.heat().orElseThrow().deadline() - s.now(), 30_000_000, "thirty seconds remaining cap");
                    s.full(); s.press(); s.later(8, () -> { s.consumed(); s.finish(4, () -> h.assertTrue(s.heat().orElseThrow().deadline() - s.now() < 15_000_000, "reconsume did not replace longer duration")); });
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:heat_rises_composition", maxTicks = 100)
    public void actualConsumedHeatEnablesSecondDashChargeAndHeatedPhoenixPhysicalLanding(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h); try {
                var enemy = s.target(2, false); s.press(); s.later(8, () -> {
                    s.consumed(); var state = s.t.runtime.state().engine().domain();
                    near(h, state.resources().get(new ResourceState.Key(s.owner(), D + "icarus_dash_uses")).capacity(), 2, "real Heat Rises gives second dash");
                    var before = s.t.owner.position(); s.t.owner.setOnGround(false); s.t.owner.setYRot(0);
                    h.assertValueEqual(s.t.runtime.useAbility(s.t.owner, D + "air_move").outcome(), AbilityUse.Outcome.ACCEPTED, "heated dash");
                    near(h, s.t.owner.getZ() - before.z, 8, "native Icarus displacement");
                    try { NativeMotionGameTest.acknowledge(s.t.owner); GroundContactGameTest.placePlayer(s.t); } catch (Exception e) { throw new IllegalStateException(e); }
                    s.cast(); h.assertValueEqual(s.buff("restoration", s.t.owner).orElseThrow().tier(), 2, "heated Phoenix uses shared Restoration");
                    s.land(); near(h, enemy.getHealth(), 90, "actual heated landing blast"); h.assertValueEqual(s.buff("scorch", enemy).orElseThrow().count(), 40, "shared Scorch from actual consumed Heat Rises");
                    s.t.runtime.unbind("heat-aspect"); near(h, s.ae(), 50, "accepted active state survives Aspect removal under explicit content policy");
                    s.finish(4, () -> h.assertTrue(s.heat().isPresent() && s.pending().isEmpty(), "consumed Heat Rises composition completed"));
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    static void lifecycle(GameTestHelper h, boolean death) {
        ready(h, () -> {
            var s = new Scenario(h); try {
                s.press(); s.later(8, () -> { try {
                    s.consumed(); if (death) s.t.owner.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000); else s.t.owner.discard();
                    GroundContactGameTest.settled(s.t); h.assertTrue(s.heat().isEmpty(), "native lifecycle cancels same-life active heat"); h.succeed();
                } finally { s.close(); } });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:heat_rises_death", maxTicks = 100)
    public void confirmedNativeDeathCancelsHeatRises(GameTestHelper h) { lifecycle(h, true); }
    @GameCase(environment = "chorus_gametest:heat_rises_removal", maxTicks = 100)
    public void confirmedNativeRemovalCancelsHeatRises(GameTestHelper h) { lifecycle(h, false); }
    @GameCase(environment = "chorus_gametest:heat_rises_unknown", maxTicks = 100)
    public void unknownActualCureRetainsPaymentAndHeatWithoutReplayingHealing(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h); try {
                s.press(); s.later(8, () -> {
                    s.consumed(); s.t.failAfterHealing = true;
                    s.later(4, () -> { try {
                        h.assertTrue(s.t.runtime.failure().isPresent() && s.heat().isPresent(), "unknown Cure stops after committing active heat");
                        near(h, s.t.owner.getHealth(), 26, "first actual Cure pulse remains"); h.assertValueEqual(s.cure().size(), 1, "unknown pulse continued or replayed");
                        int operations = s.t.operations.size(); MinecraftEffectRuntime.tick(h.getLevel()); h.assertValueEqual(s.t.operations.size(), operations, "unknown healing retried");
                        h.assertTrue(s.account(D + "grenade_energy") < .1 && BenevolenceGameTest.benefit(s.t, s.t.owner).isEmpty(), "unknown Cure refunded cost or invented ally benefit"); h.succeed();
                    } finally { s.close(); } });
                });
            } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
}
