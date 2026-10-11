package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.EffectObject;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

/** Real private pickup contacts, current recipient eligibility, base energy and shared Devour. */
public class StarvationGameTest {
    static final String D = "chorus_d2:";
    static EffectSource fragment(ProjectileGameTest.Harness t, String id, double cap) { return new EffectSource(id, D + "echo_of_starvation", owner(t), new BuffInstance.Origin(owner(t), id, "", ""), Set.of(), Map.of("cap", new Measure(cap, Unit.SECOND), "receipt_seconds", new Measure(1, Unit.SECOND))); }
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean enhanced, String... additional) throws Exception {
        var modules = new ArrayList<>(List.of("echo_of_starvation", "void_breach", "orb_of_power", "starvation_inputs")); modules.addAll(List.of(additional));
        var t = FeedTheVoidGameTest.open(h, false, false, modules.toArray(String[]::new));
        try { if (!enhanced) t.runtime.unbind("aspect"); t.runtime.bind(fragment(t, "starvation", 10)); IncandescentGameTest.bind(t, "starvation_inputs", "test:starvation_inputs", ""); select(t, "class", "class_a"); select(t, "super", "super"); return t; } catch (Exception | Error e) { t.close(); throw e; }
    }
    static void select(ProjectileGameTest.Harness t, String slot, String ability) { var before = state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots()); if (ability == null) slots.remove(D + slot); else slots.put(D + slot, "test:" + ability); t.runtime.abilities(new AbilityChange(owner(t), before, new AbilityLoadout(slots))); }
    static double account(ProjectileGameTest.Harness t, String name) { return state(t).resources().get(new ResourceState.Key(owner(t), "test:" + name)).value(); }
    static void fill(ProjectileGameTest.Harness t, String name) { t.runtime.start(new RuleEngine.Signal("test:fill_" + name, new EffectEvent(owner(t), owner(t), new BuffInstance.Origin(owner(t), "input", "", ""), Set.of(), Map.of()))); ShieldThrowGameTest.settled(t); }
    static void spawn(ProjectileGameTest.Harness t, String kind, LivingEntity creator) {
        String id = "producer/" + kind; var source = new EffectSource(id, D + (kind.equals("orb_of_power") ? "orb_of_power_application" : "void_breach_system"), owner(t), new BuffInstance.Origin(creator.getUUID().toString(), id, "", ""), Set.of(), kind.equals("orb_of_power") ? Map.of("super_energy", new Measure(.025, Unit.CHARGE), "lifetime", new Measure(20, Unit.SECOND), "collection_radius", new Measure(.5, Unit.METER)) : Map.of());
        t.runtime.bind(source); var point = new WorldPosition(t.h.getLevel().dimension().identifier().toString(), t.owner.getX() + 4, t.owner.getY(), t.owner.getZ());
        var e = new EffectEvent(source.holder(), "recorded_spawn_target", source.origin(), Set.of(), Map.of(), Map.of(), Map.of("source_instance", id, "bundle", source.bundle())).withObservedEntities(Optional.of(new EntityObservation(state(t).buffs().timeMicros(), Map.of(), Map.of(new PositionQuery("recorded_spawn_target"), Optional.of(point)))));
        t.runtime.start(new RuleEngine.Signal(D + "spawn_" + kind, e)); ShieldThrowGameTest.settled(t);
    }
    static void collect(ProjectileGameTest.Harness t, EffectObject pickup) { t.owner.setPos(pickup.position()); pickup.tick(); ShieldThrowGameTest.settled(t); t.h.assertTrue(pickup.isRemoved(), "actual logical unit consumed"); }
    @GameCase(environment = "chorus_gametest:starvation_pickups")
    public void actualOrbAndBreachContactsGrantNormalOrEnhancedDevourAlongsideTheirBaseEnergy(GameTestHelper h) throws Exception {
        for (boolean enhanced : List.of(false, true)) for (String kind : List.of("orb_of_power", "void_breach")) try (var t = open(h, enhanced)) {
            spawn(t, kind, t.owner); var p = t.pickups.getFirst(); collect(t, p); near(h, t.owner.getHealth(), enhanced ? 240 : 170, "current Feed the Void enhancement"); near(h, (DevourGameTest.buff(t).orElseThrow().deadline() - state(t).buffs().timeMicros()) / 1e6, 5, "Starvation five seconds");
            near(h, account(t, "super"), kind.equals("orb_of_power") ? .025 : 0, "explicit Orb base energy"); near(h, account(t, "class_a"), kind.equals("void_breach") ? .025 : 0, "normalized breach times class scalar once"); near(h, DevourGameTest.energy(t, "a"), 0, "pickup activation has no kill energy");
            p.tick(); near(h, t.owner.getHealth(), enhanced ? 240 : 170, "consumed unit cannot heal twice"); DevourGameTest.nativeKill(t, DevourGameTest.target(t, 1)); near(h, DevourGameTest.energy(t, "a"), enhanced ? .075 : .0375, "subsequent real kill uses shared Devour");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:starvation_full_super")
    public void fullSuperOrbRemainsUntilStarvationIsActuallyEquippedAndForeignCreatorGetsNoReward(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            t.runtime.unbind("starvation"); fill(t, "super"); var creator = ThreadedSpikeGameTest.target(t, 6.5, 40, 3.5, 1000); spawn(t, "orb_of_power", creator); var p = t.pickups.getFirst(); creator.setPos(p.position()); p.tick(); h.assertTrue(!p.isRemoved(), "foreign player or creator cannot collect a private recipient copy");
            t.owner.setPos(p.position()); p.tick(); h.assertTrue(!p.isRemoved() && DevourGameTest.buff(t).isEmpty(), "full Super without permission leaves Orb"); t.runtime.bind(fragment(t, "late", 10)); p.tick(); ShieldThrowGameTest.settled(t);
            h.assertTrue(p.isRemoved() && DevourGameTest.buff(t).isPresent(), "live Fragment enables existing Orb"); near(h, account(t, "super"), 1, "full account stays capped"); near(h, t.owner.getHealth(), 170, "recipient heal"); near(h, creator.getHealth(), 1000, "creator receives no recipient benefit");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:starvation_live")
    public void removingFragmentRevokesFullOrbPermissionWhileMissingSuperStillAllowsDevourWithFragment(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            fill(t, "super"); spawn(t, "orb_of_power", t.owner); var p = t.pickups.getFirst(); t.runtime.unbind("starvation"); t.owner.setPos(p.position()); p.tick(); h.assertTrue(!p.isRemoved(), "removed Fragment cannot permit collection");
            select(t, "super", null); p.tick(); h.assertTrue(!p.isRemoved(), "missing Super is not an empty account"); t.runtime.bind(fragment(t, "again", 10)); p.tick(); h.assertTrue(p.isRemoved(), "Fragment permits its own reward without a Super selection"); near(h, t.owner.getHealth(), 170, "Devour independent of absent account"); near(h, account(t, "super"), 1, "old account untouched");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:starvation_class")
    public void privateBreachRetainsItsBaseRewardAfterProducerAndFragmentRemovalAndUsesCurrentClassAbility(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            spawn(t, "void_breach", t.owner); var p = t.pickups.getFirst(); var other = ThreadedSpikeGameTest.target(t, 8, 40, 3.5, 1000); other.setPos(p.position()); p.tick(); h.assertTrue(!p.isRemoved(), "other entity cannot steal private breach");
            select(t, "class", "class_b"); t.runtime.unbind("producer/void_breach"); t.runtime.unbind("starvation"); collect(t, p); near(h, account(t, "class_a"), 0, "old selection gets nothing"); near(h, account(t, "class_b"), .0375, "current class account and scalar"); near(h, t.owner.getHealth(), 100, "removed Fragment grants no Devour"); h.assertTrue(DevourGameTest.buff(t).isEmpty(), "base pickup is independent of Fragment");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:starvation_breach_time", maxTicks = 530)
    public void realBreachSpawnsShareFiveSecondCooldownAndUncollectedCopiesExpireAfterTwentyFiveSeconds(GameTestHelper h) throws Exception {
        var t = open(h, false);
        try {
            spawn(t, "void_breach", t.owner); var first = t.pickups.getFirst(); spawn(t, "void_breach", t.owner); h.assertValueEqual(t.pickups.size(), 1, "shared spawn cooldown");
            RampageGameTest.at(t, 102, () -> { spawn(t, "void_breach", t.owner); h.assertValueEqual(t.pickups.size(), 2, "new spawn after cooldown"); });
            t.finish(502, () -> { h.assertTrue(first.isRemoved() && !t.pickups.getLast().isRemoved(), "each copy uses its own 25-second lifetime"); h.assertTrue(DevourGameTest.buff(t).isEmpty(), "expiry is not collection"); near(h, account(t, "class_a"), 0, "expiry grants no energy"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:starvation_duration", maxTicks = 140)
    public void fiveSecondDevourExpiresOnActualTicksWhileItsFragmentRemainsEquipped(GameTestHelper h) throws Exception {
        var t = open(h, false);
        try { spawn(t, "orb_of_power", t.owner); collect(t, t.pickups.getFirst()); t.finish(102, () -> { h.assertTrue(DevourGameTest.buff(t).isEmpty(), "five-second initial duration expired"); spawn(t, "orb_of_power", t.owner); collect(t, t.pickups.getLast()); near(h, t.owner.getHealth(), 240, "new physical collection starts another activation"); }); } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:starvation_full_class")
    public void fullClassEnergyAndFullHealthDoNotPreventBreachCollectionOrFutureDevourKillRewards(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            fill(t, "class_a"); t.owner.setHealth(1000); spawn(t, "void_breach", t.owner); collect(t, t.pickups.getFirst()); near(h, account(t, "class_a"), 1, "full class account capped"); near(h, t.owner.getHealth(), 1000, "heal clipped"); DevourGameTest.nativeKill(t, DevourGameTest.target(t, 1)); near(h, DevourGameTest.energy(t, "a"), .075, "subsequent enhanced energy independent of clipped healing");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:starvation_unknown")
    public void unknownPickupHealingConsumesPhysicalUnitAndRetainsBaseEnergyAndHealthWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            spawn(t, "orb_of_power", t.owner); var p = t.pickups.getFirst(); t.failAfterHealing = true; t.owner.setPos(p.position()); p.tick(); h.assertTrue(p.isRemoved() && t.runtime.failure().isPresent(), "claimed pickup and unknown healing"); near(h, account(t, "super"), .025, "base energy already committed"); near(h, t.owner.getHealth(), 170, "actual heal committed"); for (int i = 0; i < 3; i++) p.tick(); near(h, account(t, "super"), .025, "no replayed energy"); near(h, t.owner.getHealth(), 170, "no replayed heal");
        } h.succeed();
    }
}
