package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.finisher.Finisher;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Server-authorized pairs, physical delayed damage, historical death evidence and failure boundaries. */
public class FinisherGameTest {
    static EffectSource source(LivingEntity actor) { String id = actor.getUUID().toString(); return new EffectSource("finisher/" + id, "test:finisher", id, new BuffInstance.Origin(id, id + "/finisher", "", ""), Set.of()); }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "finisher", _ -> {}, true);
        try { t.runtime.bind(source(t.owner)); return t; } catch (Exception | Error e) { t.close(); throw e; }
    }
    static LivingEntity target(ProjectileGameTest.Harness t, double x) { var e = t.cow(x, 40, 3.5); e.addTag("test:finishable"); e.setHealth(20); return e; }
    static void begin(ProjectileGameTest.Harness t, LivingEntity actor, LivingEntity victim) {
        var s = source(actor); t.runtime.start(new RuleEngine.Signal("test:begin", new EffectEvent(s.holder(), victim.getUUID().toString(), s.origin(), Set.of(), Map.of()))); ShieldThrowGameTest.settled(t);
    }
    static long count(ProjectileGameTest.Harness t, String cue) { return t.cues.stream().filter(c -> c.cue().equals("test:" + cue)).count(); }
    static boolean busy(ProjectileGameTest.Harness t, LivingEntity e) { return Finisher.busy(t.runtime.state().engine().domain(), e.getUUID().toString()); }
    @GameCase(environment = "chorus_gametest:finisher_completed", maxTicks = 40)
    public void delayedStrikeKillsAndPreservesHistoryAfterCorpseRemovalWhileBothParticipantsAreLocked(GameTestHelper h) throws Exception {
        var t = open(h);
        try { var victim = target(t, 4.5); var position = victim.position(); begin(t, t.owner, victim);
            h.assertTrue(busy(t, t.owner) && busy(t, victim), "both participants reserved"); h.assertValueEqual(count(t, "started"), 1L, "start fact after reservation");
            t.owner.setOnGround(true); t.owner.setDeltaMovement(Vec3.ZERO); t.owner.moveRelative(1, new Vec3(0, 0, 1)); near(h, t.owner.getDeltaMovement().length(), 0, "accepted actor movement gate");
            victim.setDeltaMovement(Vec3.ZERO); victim.moveRelative(1, new Vec3(0, 0, 1)); near(h, victim.getDeltaMovement().length(), 0, "target movement gate");
            t.afterDamageReceipt = (_, receipt) -> { if (receipt.lethal()) victim.discard(); };
            h.runAfterDelay(2, () -> { h.assertTrue(t.hits.isEmpty(), "start is not immediate damage"); h.assertValueEqual(count(t, "completed"), 0L, "no premature rewards"); });
            t.finish(10, () -> { h.assertValueEqual(t.hits.size(), 1, "one delayed physical strike"); h.assertTrue(victim.isRemoved() && t.receipts.getFirst().lethal(), "actual death and removed corpse");
                h.assertValueEqual(count(t, "completed"), 1L, "confirmed completion"); h.assertValueEqual(count(t, "kill"), 1L, "normal kill fact");
                var captured = t.receipts.getFirst().observedEntities().orElseThrow().requirePosition(victim.getUUID().toString(), com.imdomestic.chorus.effect.target.TargetQuery.Anchor.FEET).orElseThrow();
                near(h, captured.x(), position.x, "historical death position"); h.assertTrue(!busy(t, t.owner) && !busy(t, victim), "locks released before rewards");
                t.owner.moveRelative(1, new Vec3(0, 0, 1)); h.assertTrue(t.owner.getDeltaMovement().length() > .5, "movement restored"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:finisher_race", maxTicks = 40)
    public void twoActualActorsCannotReserveTheSameVictimAndAnActorCannotReserveTwice(GameTestHelper h) throws Exception {
        var t = open(h);
        try { var victim = target(t, 4.5); var other = target(t, 3.5); var second = t.cow(4.5, 40, 5.5); t.runtime.bind(source(second));
            begin(t, t.owner, victim); begin(t, second, victim); begin(t, t.owner, other); h.assertValueEqual(count(t, "busy"), 2L, "both reservation conflicts rejected");
            t.finish(10, () -> { h.assertValueEqual(t.hits.size(), 1, "one claimant strike"); h.assertValueEqual(t.hits.getFirst().source(), source(t.owner).origin(), "accepted claimant attribution"); near(h, other.getHealth(), 20, "other target untouched"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:finisher_rejected")
    public void HealthTagsPlayersSelfDistanceAndObstructionsAreCheckedInTheActualWorld(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var high = target(t, 4.5); high.setHealth(21); begin(t, t.owner, high); high.discard();
            var unmarked = t.cow(4.5, 40, 3.5); unmarked.setHealth(1); begin(t, t.owner, unmarked); unmarked.discard();
            var far = target(t, 7); begin(t, t.owner, far); far.discard(); begin(t, t.owner, t.owner);
            var second = target(t, 4.5); t.runtime.bind(source(second)); begin(t, second, t.owner); second.discard();
            var blocked = target(t, 5); t.wall(); begin(t, t.owner, blocked);
            h.assertValueEqual(count(t, "started"), 0L, "no rejected request starts a finisher"); h.assertTrue(t.hits.isEmpty() && !busy(t, t.owner), "no effects or reservations from rejection");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:finisher_allied")
    public void FriendlyTeamMembersCannotBeFinishedEvenWithAnEligibilityTag(GameTestHelper h) throws Exception {
        var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("finisher-" + UUID.randomUUID());
        try (var t = open(h)) { var victim = target(t, 4.5); scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(victim.getScoreboardName(), team);
            begin(t, t.owner, victim); h.assertValueEqual(count(t, "started"), 0L, "allied target rejected");
        } finally { scoreboard.removePlayerTeam(team); } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:finisher_escape", maxTicks = 40)
    public void ATargetLeavingTheAcceptedRangeCancelsAndReleasesTheActor(GameTestHelper h) throws Exception {
        var t = open(h); try { var victim = target(t, 4.5); begin(t, t.owner, victim); victim.setPos(h.absoluteVec(new Vec3(12, 40, 3.5)));
            t.finish(10, () -> { h.assertTrue(t.hits.isEmpty() && !busy(t, t.owner) && !busy(t, victim), "cancel releases without striking"); h.assertValueEqual(count(t, "cancelled"), 1L, "one cancellation"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:finisher_missing", maxTicks = 40)
    public void ARemovedTargetReleasesItsActorWithoutTurningDisappearanceIntoAKill(GameTestHelper h) throws Exception {
        var t = open(h); try { var victim = target(t, 4.5); begin(t, t.owner, victim); victim.discard();
            t.finish(10, () -> { h.assertValueEqual(count(t, "cancelled"), 1L, "missing target cancelled"); h.assertValueEqual(count(t, "completed"), 0L, "disappearance is not completion"); h.assertTrue(t.hits.isEmpty() && !busy(t, t.owner), "no phantom damage"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:finisher_prevented", maxTicks = 40)
    public void ActualDeathProtectionConsumesTheTotemWithoutPublishingFinisherCompletion(GameTestHelper h) throws Exception {
        var t = open(h); try { var victim = target(t, 4.5); victim.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING)); begin(t, t.owner, victim);
            t.finish(10, () -> { h.assertValueEqual(t.hits.size(), 1, "one physical attempt"); h.assertTrue(victim.isAlive() && victim.getOffhandItem().isEmpty() && t.receipts.getFirst().deathPrevented(), "real totem prevented death");
                h.assertValueEqual(count(t, "completed"), 0L, "no finisher completion"); h.assertValueEqual(count(t, "kill"), 0L, "no kill fact"); h.assertValueEqual(count(t, "cancelled"), 1L, "known nonlethal outcome released"); h.assertTrue(!busy(t, t.owner) && !busy(t, victim), "both locks released"); });
        } catch (Throwable e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:finisher_unknown", maxTicks = 40)
    public void UnknownReceiptAfterPhysicalDeathStopsWithoutCompletionOrAnotherStrike(GameTestHelper h) throws Exception {
        var t = open(h); try { var victim = target(t, 4.5); t.failAfterDamage = true; begin(t, t.owner, victim);
            h.runAfterDelay(10, () -> { try { h.assertTrue(t.runtime.failure().isPresent() && !victim.isAlive(), "world death happened before unknown receipt"); h.assertValueEqual(t.hits.size(), 1, "one world write");
                h.assertValueEqual(count(t, "completed"), 0L, "no guessed reward"); int operations = t.operations.size(); t.runtime.prepare(); t.runtime.prepare(); h.assertValueEqual(t.operations.size(), operations, "stopped runtime never replays"); h.succeed();
            } finally { t.close(); } });
        } catch (Throwable e) { t.close(); throw e; }
    }
}
