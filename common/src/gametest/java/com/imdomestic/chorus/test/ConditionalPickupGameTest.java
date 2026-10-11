package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.PickupGameTest.id;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;

/** Live claim eligibility, real physical units, and all local rewards before child pickup reactions. */
public class ConditionalPickupGameTest {
    static PickupGameTest.Harness open(GameTestHelper h) throws Exception { return new PickupGameTest.Harness(h, "conditional_pickup", _ -> {}); }
    static Loadout.Gear gear(String id, String kind) { return new Loadout.Gear(id, "test:" + kind, Map.of()); }
    static EffectState state(PickupGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static int reserves(EffectState state, String weapon) { return state.ammunition().get(weapon).reserve().orElseThrow().rounds(); }
    static void equip(PickupGameTest.Harness t, Entity who, Loadout after) {
        t.runtime.equip(new EquipmentChange(id(who), state(t).equipment().getOrDefault(id(who), Loadout.EMPTY), after));
    }
    static void refill(PickupGameTest.Harness t, Entity who, String weapon) {
        t.runtime.start(new RuleEngine.Signal("test:refill", new EffectEvent(id(who), id(who), new BuffInstance.Origin(id(who), "test:refill", weapon, ""), Set.of(), Map.of())));
    }
    @GameCase(environment="chorus_gametest:conditional_pickup_player", maxTicks=60)
    public void fullNativePlayerCannotCollectUntilManualReloadConsumesReserves(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            var equipment = PlayerEquipment.get(t.owner);
            var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), gear("a", "finite"));
            t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, "test:primary", 0, equipment.revision());
            equipment.draw(t.owner, Optional.of("test:primary"), equipment.revision());
            t.runtime.start(new RuleEngine.Signal("test:spawn", new EffectEvent(id(t.owner), id(t.owner), t.source.origin(), Set.of(), Map.of())));
            h.runAfterDelay(4, () -> {
                h.assertTrue(!t.objects.getFirst().isRemoved() && t.heals.isEmpty() && t.cues.isEmpty(), "full reserves consumed the brick");
                h.assertValueEqual(reserves(state(t), "a"), 10, "initial reserves");
                try {
                    h.assertValueEqual(h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon reload", t.owner.createCommandSourceStack().withSuppressedOutput()
                            .withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS)), 1, "actual manual reload accepted");
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
            });
            t.finish(16, () -> {
                h.assertTrue(t.objects.getFirst().isRemoved(), "same brick never became eligible");
                h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "manual reload filled magazine");
                h.assertValueEqual(reserves(state(t), "a"), 10, "brick restores three spent reserves");
                h.assertValueEqual(t.collectedStates.size(), 1, "one physical claim"); h.assertValueEqual(reserves(t.collectedStates.getFirst(), "a"), 10, "pickup reaction sees committed reserves");
                t.objects.getFirst().tick(); h.assertValueEqual(t.heals.size(), 1, "claimed unit cannot replay");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:conditional_pickup_loadout", maxTicks=60)
    public void lateLoadoutChangeRoutesRewardToCurrentStowedWeaponsNotProducerOrOldAccounts(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            equip(t, t.collector, new Loadout(Map.of("test:primary", gear("old", "finite")), Optional.empty()));
            equip(t, t.outsider, new Loadout(Map.of("test:primary", gear("outsider", "finite")), Optional.empty()));
            refill(t, t.outsider, "outsider"); t.spawn(); t.runtime.unbind("producer"); t.collector.setPos(t.owner.position());
            h.runAfterDelay(4, () -> {
                h.assertTrue(!t.objects.getFirst().isRemoved() && t.heals.isEmpty(), "other holder reserve space made a private brick collectible");
                equip(t, t.collector, new Loadout(Map.of("test:primary", gear("a", "finite"), "test:secondary", gear("b", "finite"), "test:third", gear("heavy", "heavy")), Optional.of("test:third")));
                refill(t, t.collector, "a"); refill(t, t.collector, "b"); refill(t, t.collector, "heavy");
            });
            t.finish(12, () -> {
                h.assertValueEqual(reserves(state(t), "a"), 10, "first stowed weapon reward"); h.assertValueEqual(reserves(state(t), "b"), 10, "second stowed weapon reward");
                h.assertValueEqual(reserves(state(t), "heavy"), 17, "wrong class excluded"); h.assertValueEqual(reserves(state(t), "outsider"), 7, "wrong holder excluded");
                h.assertValueEqual(reserves(state(t), "old"), 10, "old unequipped account not rewarded");
                h.assertValueEqual(t.collectedStates.size(), 1, "one collection fact");
                for (var weapon : List.of("a", "b")) h.assertValueEqual(reserves(t.collectedStates.getFirst(), weapon), 10, "all local grants precede collector reactions");
                h.assertTrue(t.heals.getFirst().source().equals(t.source.origin()), "detached producer attribution changed");
                h.assertValueEqual(t.cues.getFirst().target(), id(t.collector), "collector is the pickup actor");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:conditional_pickup_compete", maxTicks=60)
    public void firstBrickFillsGapAndSecondWaitsForAnotherEligibleWeapon(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            equip(t, t.collector, new Loadout(Map.of("test:primary", gear("a", "finite")), Optional.empty()));
            refill(t, t.collector, "a"); t.spawn(); t.spawn(); t.collector.setPos(t.owner.position());
            h.runAfterDelay(4, () -> {
                h.assertValueEqual(t.objects.stream().filter(Entity::isRemoved).count(), 1L, "two bricks consumed one reserve gap"); h.assertValueEqual(t.heals.size(), 1, "first reward once");
                equip(t, t.collector, new Loadout(Map.of("test:primary", gear("b", "finite")), Optional.empty())); refill(t, t.collector, "b");
            });
            t.finish(12, () -> { h.assertTrue(t.objects.stream().allMatch(Entity::isRemoved), "second brick did not recheck current state"); h.assertValueEqual(t.heals.size(), 2, "one reward per physical unit"); h.assertValueEqual(reserves(state(t), "b"), 10, "new account receives remaining brick"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:conditional_pickup_expire", maxTicks=60)
    public void infiniteReservesDoNotQualifyAndRejectedClaimStillExpiresNormally(GameTestHelper h) throws Exception {
        var t = new PickupGameTest.Harness(h, "conditional_pickup", data -> PickupGameTest.spec(data).getAsJsonObject("lifetime").addProperty("value", 0.2));
        try {
            equip(t, t.collector, new Loadout(Map.of("test:primary", gear("unlimited", "infinite")), Optional.empty()));
            t.collector.setPos(t.owner.position()); t.spawn(); t.objects.getFirst().tick();
            h.assertTrue(!t.objects.getFirst().isRemoved() && t.heals.isEmpty(), "infinite reserves claimed a finite brick");
            t.finish(10, () -> { h.assertTrue(t.objects.getFirst().isRemoved() && t.heals.isEmpty() && t.collectedStates.isEmpty(), "expiry invented a claim"); h.assertValueEqual(t.cues.getFirst().cue(), "test:expired", "normal expiry continuation"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:conditional_pickup_failure", maxTicks=60)
    public void rewardFailureKeepsCommittedReservesAndConsumesClaimWithoutPickupReactionReplay(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            equip(t, t.collector, new Loadout(Map.of("test:primary", gear("a", "finite")), Optional.empty())); refill(t, t.collector, "a");
            t.collector.setPos(t.owner.position()); t.failAfterHealing = true; t.spawn();
            h.runAfterDelay(8, () -> { try {
                h.assertTrue(t.runtime.failure().isPresent() && t.objects.getFirst().isRemoved(), "unknown reward left a claim");
                h.assertValueEqual(reserves(state(t), "a"), 10, "committed ammunition was rolled back"); h.assertTrue(t.collectedStates.isEmpty(), "failed parent emitted a successful pickup reaction");
                t.objects.getFirst().tick(); h.assertValueEqual(t.heals.size(), 1, "unknown reward replayed"); h.succeed();
            } finally { t.close(); } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
