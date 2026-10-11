package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;

/** Real player containers, private ammo entities, current per-weapon profiles and Overflow reactions. */
public class OverflowGameTest {
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static int magazine(ProjectileGameTest.Harness t, String id) { return state(t).ammunition().get(id).magazine(); }
    static int reserve(ProjectileGameTest.Harness t, String id) { return state(t).ammunition().get(id).reserve().orElseThrow().rounds(); }
    static void prepare(JsonObject data) {
        for (String name : List.of("ammo_pickups", "overflow_weapon")) for (var e : ThreadedSpikeGameTest.json(name).entrySet()) {
            if (e.getKey().equals("version")) continue;
            if (e.getValue().isJsonArray()) {
                if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray());
                e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v));
            } else data.add(e.getKey(), e.getValue());
        }
    }
    static void equip(ProjectileGameTest.Harness t, String slot, String id, String kind, String perk, int rounds) {
        var equipment = PlayerEquipment.get(t.owner);
        if (state(t).equipment().getOrDefault(owner(t), Loadout.EMPTY).slots().containsKey("test:" + slot)) {
            t.owner.getInventory().setItem(0, ItemStack.EMPTY); equipment.swap(t.owner, "test:" + slot, 0, equipment.revision());
        }
        var stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:" + kind, Map.of("perk", perk), Map.of("brick_rounds", new Measure(rounds, Unit.ROUND))));
        t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, "test:" + slot, 0, equipment.revision());
    }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "overflow", OverflowGameTest::prepare, true);
        try {
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            equip(t, "primary", "a", "primary", "normal", 1); equip(t, "secondary", "b", "special", "enhanced", 12); equip(t, "power", "c", "heavy", "normal", 4);
            PugilistGameTest.draw(t, "primary");
            var producer = UUID.randomUUID().toString();
            t.runtime.bind(new EffectSource("factory", "chorus_d2:ammo_pickups", owner(t), new BuffInstance.Origin(producer, "drop", "", ""), Set.of(),
                    Map.of("lifetime", new Measure(10, Unit.SECOND), "radius", new Measure(0.5, Unit.METER))));
            t.runtime.bind(new EffectSource("collector", "test:pickup_observer", owner(t), new BuffInstance.Origin(owner(t), "listener", "", ""), Set.of()));
            return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static EffectObject spawn(ProjectileGameTest.Harness t, String kind, Entity anchor) {
        t.runtime.start(new RuleEngine.Signal("chorus_d2:spawn_" + kind + "_ammo", new EffectEvent(owner(t), anchor.getUUID().toString(),
                new BuffInstance.Origin(owner(t), "drop-request", "", ""), Set.of(), Map.of())));
        return t.pickups.getLast();
    }
    static void reserve(ProjectileGameTest.Harness t, String id, int amount) {
        t.runtime.start(new RuleEngine.Signal("test:reserve", new EffectEvent(owner(t), "", new BuffInstance.Origin(owner(t), "test", id, ""), Set.of(), Map.of("amount", new Measure(amount, Unit.ROUND)))));
    }
    static long cues(ProjectileGameTest.Harness t, String kind) { return t.cues.stream().filter(c -> c.cue().equals("test:" + kind)).count(); }
    @GameCase(environment="chorus_gametest:overflow_special", maxTicks=60)
    public void specialBrickTriggersNormalEnhancedAndOtherAmmoWeaponsWhileStowedThenFeedsActualFire(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            var object = spawn(t, "special", t.owner); t.runtime.unbind("factory");
            RampageGameTest.at(t, 4, () -> {
                h.assertTrue(object.isRemoved(), "actual brick was not claimed");
                h.assertValueEqual(magazine(t, "a"), 10, "normal primary overflow"); h.assertValueEqual(magazine(t, "b"), 11, "enhanced stowed overflow from newly received reserves");
                h.assertValueEqual(magazine(t, "c"), 2, "stowed heavy responds to special brick"); h.assertValueEqual(reserve(t, "b"), 1, "special reserve conservation"); h.assertValueEqual(reserve(t, "c"), 4, "heavy uses own reserves");
                h.assertTrue(!object.pickup().orElseThrow().origin().owner().equals(owner(t)), "fixture needs a different producer");
                RampageGameTest.kill(t, "secondary");
                h.assertValueEqual(t.runtime.reload(t.owner).outcome(), WeaponReload.Outcome.FULL, "loaded overflow is not manually discarded");
            });
            t.finish(10, () -> {
                h.assertValueEqual(magazine(t, "b"), 10, "real shot consumed one overflow round"); h.assertValueEqual(t.hits.size(), 1, "actual projectile damage");
                h.assertValueEqual(cues(t, "reloaded"), 0L, "refill never fakes reload completion"); h.assertValueEqual(cues(t, "collected"), 1L, "one collection event");
                object.tick(); h.assertValueEqual(cues(t, "refilled"), 3L, "claimed unit cannot refill again");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:overflow_heavy", maxTicks=60)
    public void heavyBrickCannotSupplyEmptySpecialReservesButStillActivatesPrimaryOverflow(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            spawn(t, "heavy", t.owner);
            t.finish(8, () -> {
                h.assertValueEqual(magazine(t, "a"), 10, "unlimited primary refill"); h.assertValueEqual(magazine(t, "b"), 0, "no special reserve borrowing");
                h.assertValueEqual(reserve(t, "b"), 0, "heavy grant stays in heavy accounts"); h.assertValueEqual(magazine(t, "c"), 2, "normal heavy overflow");
                h.assertValueEqual(reserve(t, "c"), 8, "six plus four granted minus two transferred"); h.assertValueEqual(cues(t, "reloaded"), 0L, "no reload fact");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:overflow_full", maxTicks=60)
    public void fullSpecialBrickWaitsForActualManualReloadThenGrantsReservesBeforeOverflow(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            reserve(t, "b", 30); var object = spawn(t, "special", t.owner);
            RampageGameTest.at(t, 4, () -> {
                h.assertTrue(!object.isRemoved() && cues(t, "collected") == 0, "full corresponding reserves consumed brick");
                h.assertValueEqual(magazine(t, "b"), 0, "empty magazine alone cannot claim");
                PugilistGameTest.draw(t, "secondary"); h.assertValueEqual(t.runtime.reload(t.owner).outcome(), WeaponReload.Outcome.ACCEPTED, "real container manual reload accepted");
            });
            t.finish(16, () -> {
                h.assertTrue(object.isRemoved(), "brick did not become collectible after manual reload");
                h.assertValueEqual(magazine(t, "b"), 11, "five manual plus six overflow rounds"); h.assertValueEqual(reserve(t, "b"), 24, "brick clamps five free reserves before six-round transfer");
                h.assertValueEqual(cues(t, "reloaded"), 1L, "only the manual reload counted"); h.assertValueEqual(cues(t, "collected"), 1L, "one logical unit claimed");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:overflow_replace", maxTicks=60)
    public void replacingWeaponsBeforeContactUsesNewAllocationAndOnlyCurrentPerks(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            var anchor = t.cow(2.5, 43, 3.5); var object = spawn(t, "special", anchor);
            RampageGameTest.at(t, 4, () -> {
                h.assertTrue(!object.isRemoved(), "out-of-range brick collected");
                equip(t, "primary", "x", "special", "normal", 4); equip(t, "secondary", "y", "special", "none", 13); PugilistGameTest.draw(t, "power");
                t.owner.setPos(object.position());
            });
            t.finish(12, () -> {
                h.assertTrue(object.isRemoved(), "new equipped weapons did not qualify"); h.assertValueEqual(magazine(t, "x"), 4, "normal overflow limited by four allocated rounds");
                h.assertValueEqual(magazine(t, "y"), 0, "new weapon without Overflow does not refill"); h.assertValueEqual(reserve(t, "y"), 13, "weapon-specific allocation");
                h.assertValueEqual(magazine(t, "a"), 1, "old primary account unchanged"); h.assertValueEqual(reserve(t, "b"), 0, "old special account excluded");
                h.assertValueEqual(magazine(t, "c"), 2, "current heavy perk still reacts"); h.assertValueEqual(cues(t, "reloaded"), 0L, "no fake reload");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:overflow_multiple", maxTicks=60)
    public void successiveBricksUseSpaceFreedByOverflowThenRetainTheFirstUnneededUnit(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            for (int i = 0; i < 5; i++) spawn(t, "special", t.owner);
            t.finish(10, () -> {
                h.assertValueEqual(t.pickups.stream().filter(Entity::isRemoved).count(), 4L, "four units fill the post-overflow reserve gap");
                h.assertValueEqual(magazine(t, "b"), 11, "Overflow does not accumulate beyond its ceiling"); h.assertValueEqual(reserve(t, "b"), 30, "reserve cap");
                h.assertValueEqual(cues(t, "collected"), 4L, "one event per claimed physical unit"); h.assertValueEqual(cues(t, "refilled"), 3L, "already loaded weapons do not refill again");
                t.pickups.forEach(EffectObject::tick); h.assertValueEqual(cues(t, "collected"), 4L, "full reserves retain the fifth unit");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:overflow_failure", maxTicks=60)
    public void unknownPostRefillWorldReactionKeepsTransferredAmmoAndNeverReclaimsBrick(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            t.onCue = cue -> { if (cue.cue().equals("test:refilled") && cue.source().weapon().equals("b")) throw new IllegalStateException("Injected unknown Overflow reaction"); };
            var object = spawn(t, "special", t.owner);
            h.runAfterDelay(8, () -> { try {
                h.assertTrue(t.runtime.failure().isPresent() && object.isRemoved(), "unknown reaction left a collectible unit");
                h.assertValueEqual(magazine(t, "b"), 11, "committed magazine retained"); h.assertValueEqual(reserve(t, "b"), 1, "committed reserve consumption retained");
                int cues = t.cues.size(); object.tick(); h.assertValueEqual(t.cues.size(), cues, "world fault replayed pickup"); h.succeed();
            } finally { t.close(); } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
