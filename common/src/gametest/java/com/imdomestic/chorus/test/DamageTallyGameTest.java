package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.combat.DamageTallies;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

public class DamageTallyGameTest {
    private static double energy(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain().resources().get(new ResourceState.Key(t.owner.getUUID().toString(), "test:energy")).value(); }
    private static void cast(ProjectileGameTest.Harness t) {
        t.runtime.abilities(new AbilityChange(t.owner.getUUID().toString(), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:melee", "test:return"))));
        t.h.assertValueEqual(t.runtime.useAbility(t.owner, "test:melee").outcome(), AbilityUse.Outcome.ACCEPTED, "paid cast accepted");
    }
    private static void targets(ProjectileGameTest.Harness t) { t.cow(2.5, 46, 3.5).setHealth(5); t.cow(6, 46, 3.5).setHealth(5); t.cow(6, 46, 7); }
    private static EffectProjectile returnFlight(ProjectileGameTest.Harness t) {
        targets(t); cast(t); var outward = t.projectiles.getFirst();
        for (int i = 0; i < 40 && t.projectiles.size() < 2; i++) outward.tick();
        t.h.assertValueEqual(t.projectiles.size(), 2, "one return only after outgoing terminal contact");
        t.h.assertValueEqual(t.hits.size(), 3, "three distinct physical damage actions");
        t.h.assertTrue(outward.isRemoved() && outward.progress().entityContacts() == 3, "three-contact policy ends outgoing flight");
        return t.projectiles.getLast();
    }
    private static void readyToCatch(ProjectileGameTest.Harness t, EffectProjectile p) { p.setPos(t.owner.getBoundingBox().getCenter().add(0, 2, 0)); p.setDeltaMovement(Vec3.ZERO); p.tick(); }
    private static DamageTallies.Summary summary(ProjectileGameTest.Harness t) {
        var state = t.runtime.state().engine().domain(); return DamageTallies.read(state, state.damageTallies().values().iterator().next().handle()).summary().orElseThrow();
    }
    @GameCase(environment = "chorus_gametest:tally_return", maxTicks = 60)
    public void realTicksChainThreeTargetsReturnAndPayForEarlierKillsAfterSelectionClears(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "tally_return", _ -> {}, true);
        try {
            targets(t); cast(t); t.runtime.unbind("launch"); t.runtime.unbind("boost");
            t.runtime.abilities(new AbilityChange(t.owner.getUUID().toString(), t.runtime.state().engine().domain().abilities().get(t.owner.getUUID().toString()), AbilityLoadout.EMPTY));
            t.finish(35, () -> {
                h.assertValueEqual(t.hits.size(), 3, "all three actual damage receipts");
                h.assertTrue(!t.entities.get(0).isAlive() && !t.entities.get(1).isAlive(), "earlier two hits killed"); near(h, t.entities.get(2).getHealth(), 90, "last hit is nonlethal");
                h.assertValueEqual(t.cues.stream().map(c -> c.cue()).toList(), List.of("test:arrived"), "one automatic return");
                near(h, energy(t), 1.3, "three hits determine arrival resource gain"); near(h, t.owner.getHealth(), 16, "both earlier kills contribute to return healing");
                h.assertTrue(t.runtime.state().engine().domain().damageTallies().isEmpty(), "payoff consumes tally");
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase public void catchUsesWholeCastReceiptsAndConsumesTallyBeforeWorldPayoff(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "tally_return", _ -> {}, true)) {
            var back = returnFlight(t); var totals = summary(t);
            h.assertValueEqual(totals, new DamageTallies.Summary(3, 3, 3, 2, 0, 0, 20), "actual losses exclude overkill");
            readyToCatch(t, back); h.assertTrue(t.runtime.catchProjectile(t.owner).isPresent(), "receiver caught returning flight");
            near(h, energy(t), 1.6, "caught rate differs from automatic arrival"); near(h, t.owner.getHealth(), 16, "whole-cast kills retained");
            h.assertTrue(t.runtime.catchProjectile(t.owner).isEmpty(), "consumed return cannot pay again"); h.assertTrue(t.runtime.state().engine().domain().damageTallies().isEmpty(), "shared tally closed");
        }
        h.succeed();
    }
    @GameCase public void zeroHitExpiryStillReturnsWithoutInventingHitOrKillRewards(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "tally_return", _ -> {}, true)) {
            cast(t); var outward = t.projectiles.getFirst(); outward.setDeltaMovement(Vec3.ZERO);
            for (int i = 0; i < 40 && t.projectiles.size() < 2; i++) outward.tick();
            h.assertValueEqual(t.projectiles.size(), 2, "expiry can begin return without an entity hit");
            h.assertValueEqual(summary(t), DamageTallies.Summary.ZERO, "empty confirmed tally");
            var back = t.projectiles.getLast(); readyToCatch(t, back); t.runtime.catchProjectile(t.owner);
            near(h, energy(t), 1, "no invented hit reward"); near(h, t.owner.getHealth(), 10, "no invented kill reward"); h.assertTrue(t.runtime.state().engine().domain().damageTallies().isEmpty(), "empty tally consumed");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:tally_expiry", maxTicks = 30)
    public void abandonedFlightWithoutAnyCallbackStillReleasesTallyAtDeadline(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "tally_return", d -> d.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonArray("on_use").get(0).getAsJsonObject().getAsJsonObject("action").getAsJsonObject("duration").addProperty("value", .1), true);
        try {
            cast(t); t.projectiles.getFirst().discard();
            t.finish(4, () -> { h.assertTrue(t.runtime.state().engine().domain().damageTallies().isEmpty(), "no-callback cleanup at declared deadline"); h.assertTrue(t.cues.isEmpty(), "expiry does not manufacture a return payoff"); near(h, energy(t), 1, "cost stays paid"); });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase public void unknownDamageNeverBecomesConfirmedTallyAndUnknownPayoffCannotReopenIt(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "tally_return", _ -> {}, true)) {
            targets(t); cast(t); t.failAfterDamage = true; var p = t.projectiles.getFirst();
            for (int i = 0; i < 10 && t.runtime.failure().isEmpty(); i++) p.tick();
            h.assertTrue(t.runtime.failure().isPresent() && p.isRemoved(), "unknown actual damage stops flight"); h.assertTrue(!t.entities.getFirst().isAlive(), "world loss remains committed");
            h.assertValueEqual(summary(t), DamageTallies.Summary.ZERO, "undelivered receipt is not inferred from current health"); near(h, energy(t), 1, "no speculative return gain");
        }
        try (var t = new ProjectileGameTest.Harness(h, "tally_return", _ -> {}, true)) {
            var p = returnFlight(t); readyToCatch(t, p); t.failAfterHealing = true; t.runtime.catchProjectile(t.owner);
            h.assertTrue(t.runtime.failure().isPresent() && p.isRemoved(), "unknown payoff consumes return");
            near(h, energy(t), 1.6, "confirmed gain remains"); near(h, t.owner.getHealth(), 16, "actual heal remains"); h.assertTrue(t.runtime.state().engine().domain().damageTallies().isEmpty(), "closed tally stays closed");
            t.failAfterHealing = false; p.tick(); h.assertValueEqual(t.cues.size(), 1, "no replayed payoff");
        }
        h.succeed();
    }
}
