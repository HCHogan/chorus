package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.object.PickupCollection;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class PowerfulAttractionGameTest {
    static final String D = "chorus_d2:", PERK = "powerful_attraction";
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        return open(h, _ -> {});
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, java.util.function.Consumer<com.google.gson.JsonObject> edit) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "shield_throw", data -> {
            ShieldThrowGameTest.prepare(data); FeedTheVoidGameTest.modules(data);
            for (String name : List.of("echo_of_starvation", "void_breach", "orb_of_power", "starvation_inputs", "armor_charge", "armor_charge_mods", "armor_charge_inputs", "kickstart", "kickstart_inputs", "powerful_attraction", "powerful_attraction_inputs", "powerful_attraction_equipment", "marksman_dodge")) {
                var part = ThreadedSpikeGameTest.json(name);
                if (name.equals("marksman_dodge")) part.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject("reload_delay")
                        .add("value", ThreadedSpikeGameTest.json("marksman_dodge_test_calibration").getAsJsonObject("parameters").get("reload_delay"));
                GamblersDodgeGameTest.merge(data, part);
            }
            edit.accept(data);
        }, true);
        try {
            t.owner.setGameMode(GameType.SURVIVAL); t.owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); t.owner.setHealth(100);
            DevourGameTest.select(t, "a"); StarvationGameTest.select(t, "super", "super"); StarvationGameTest.select(t, "class", "kick_class");
            for (String bundle : List.of("starvation_inputs", "armor_charge_inputs", "kick_inputs")) IncandescentGameTest.bind(t, bundle, "test:" + bundle, "");
            t.runtime.bind(ArmorChargeGameTest.system(owner(t))); return t;
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    static void equip(ProjectileGameTest.Harness t, String... choices) {
        var e = PlayerEquipment.get(t.owner); e.grants(t.owner, Set.of(D + "unlock/powerful_attraction", D + "unlock/utility_kickstart"), e.revision());
        var sockets = new HashMap<String,String>(); for (int i = 0; i < 3; i++) sockets.put("mod_" + i, i < choices.length ? choices[i] : "none");
        var stack = new ItemStack(Items.PAPER); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear("class-item/" + UUID.randomUUID(), "test:attraction_class_item", sockets));
        t.owner.getInventory().setItem(0, stack); e.swap(t.owner, D + "class_item", 0, e.revision()); t.runtime.prepare();
    }
    static void choose(ProjectileGameTest.Harness t, String id) {
        var before = state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots()); slots.put(D + "class", id);
        t.runtime.abilities(new AbilityChange(owner(t), before, new AbilityLoadout(slots)));
    }
    static EffectObject orb(ProjectileGameTest.Harness t, double distance) {
        var p = ArmorChargeGameTest.spawn(t); p.setPos(t.owner.position().add(0, distance, 0)); return p;
    }
    static void use(ProjectileGameTest.Harness t) {
        t.h.assertValueEqual(t.runtime.useAbility(t.owner, D + "class").outcome(), AbilityUse.Outcome.ACCEPTED, "class use accepted"); ShieldThrowGameTest.settled(t);
    }
    @GameCase public void independentPhysicalClassItemCopiesCollectAtBothExactRangeBoundaries(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            int[] queries = {0}; t.beforeWorld = q -> { if (q.command() instanceof PickupCollection.Query) queries[0]++; };
            for (int copies = 1; copies <= 3; copies++) {
                equip(t, Collections.nCopies(copies, PERK).toArray(String[]::new));
                var near = orb(t, 12.5); var middle = orb(t, 12.5001); var far = orb(t, 18.5); var outside = orb(t, 18.5001);
                StarvationGameTest.fill(t, "kick_class"); use(t);
                h.assertTrue(near.isRemoved() && !outside.isRemoved(), "range endpoints");
                h.assertTrue(middle.isRemoved() == (copies > 1) && far.isRemoved() == (copies > 1), "two-plus copy radius");
                h.assertValueEqual(queries[0], copies, "duplicate sockets caused duplicate query");
                for (var p : List.of(near, middle, far, outside)) p.discard();
            }
            h.assertTrue(!PlayerEquipment.get(t.owner).item(D + "class_item").isEmpty(), "independent class item missing");
        } h.succeed();
    }
    @GameCase public void actualMarksmanDodgeCommandConsumesItsChargeAndCollectsWithoutMovement(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            equip(t, PERK); choose(t, D + "marksman_dodge"); var point = t.owner.position(); var first = orb(t, 8);
            TimelostMagazineGameTest.command(t, "chorus ability use chorus_d2:class"); ShieldThrowGameTest.settled(t);
            h.assertTrue(first.isRemoved(), "real accepted dodge did not collect"); h.assertValueEqual(t.owner.position(), point, "collection teleported player");
            near(h, state(t).resources().get(new ResourceState.Key(owner(t), D + "marksman_dodge_energy")).value(), 0, "real class charge paid");
            var second = orb(t, 8); h.assertValueEqual(t.runtime.useAbility(t.owner, D + "class").outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "second cast rejected"); h.assertTrue(!second.isRemoved(), "rejection collected Orb");
        } h.succeed();
    }
    @GameCase public void resolvedReplacementMetadataControlsEligibilityAndUnrelatedPickupKindsRemain(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            equip(t, PERK); var first = orb(t, 4); StarvationGameTest.spawn(t, "void_breach", t.owner); var breach = t.pickups.getLast();
            IncandescentGameTest.bind(t, "override", "test:attraction_nonclass_override", ""); StarvationGameTest.fill(t, "kick_class"); use(t); h.assertTrue(!first.isRemoved(), "non-class replacement inherited base eligibility");
            t.runtime.unbind("override"); IncandescentGameTest.bind(t, "override", "test:attraction_ascent_override", ""); StarvationGameTest.fill(t, "kick_class"); use(t);
            h.assertTrue(first.isRemoved() && !breach.isRemoved(), "qualified replacement or pickup kind filter");
        } h.succeed();
    }
    @GameCase public void socketConfiguredUtilityKickstartSpendsThenOrbsRefillTheLivePool(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            equip(t, PERK, "utility_kickstart", PERK); ArmorChargeGameTest.action(t, "gain", owner(t), 3, 1);
            StarvationGameTest.fill(t, "super"); StarvationGameTest.fill(t, "kick_class"); for (int i = 0; i < 5; i++) orb(t, 15); use(t);
            h.assertValueEqual(t.pickups.stream().filter(p -> p.isRemoved()).count(), 3L, "only current charge gap consumed"); h.assertValueEqual(ArmorChargeGameTest.charges(t), 3, "three spent and three gained");
            near(h, StarvationGameTest.account(t, "kick_class"), 1 + .284 * .5, "refund used pre-collection charges");
            var e = PlayerEquipment.get(t.owner); var choices = Map.of("mod_0", PERK, "mod_1", "utility_kickstart", "mod_2", "none");
            e.configure(t.owner, D + "class_item", choices, e.revision()); StarvationGameTest.fill(t, "kick_class"); use(t);
            h.assertValueEqual(ArmorChargeGameTest.charges(t), 0, "removing second copy leaves distant Orbs outside new range");
            h.assertValueEqual(t.pickups.stream().filter(p -> p.isRemoved()).count(), 3L, "old copy radius survived socket edit");
        } h.succeed();
    }
    @GameCase public void starvationAllowsFullSuperOrbsAndOnlyTheCollectorReceivesDevour(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            equip(t, PERK); choose(t, D + "marksman_dodge"); StarvationGameTest.fill(t, "super"); t.runtime.bind(StarvationGameTest.fragment(t, "starvation", 10));
            var creator = t.cow(7, 40, 3); StarvationGameTest.spawn(t, "orb_of_power", creator); var first = t.pickups.getLast(); use(t);
            h.assertTrue(first.isRemoved(), "independent pickup permission not respected"); near(h, t.owner.getHealth(), 170, "recipient Devour heal"); near(h, creator.getHealth(), 100, "creator got recipient benefit");
            near(h, StarvationGameTest.account(t, "super"), 1, "no Super overflow");
        } h.succeed();
    }
    @GameCase public void unknownNestedHealingKeepsPaidDodgeAndStopsBeforeTheSecondOrb(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            equip(t, PERK); choose(t, D + "marksman_dodge"); t.runtime.bind(StarvationGameTest.fragment(t, "starvation", 10)); for (int i = 0; i < 3; i++) orb(t, 4); t.failAfterHealing = true;
            boolean failed = false; try { use(t); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown healing must stop runtime");
            h.assertValueEqual(t.pickups.stream().filter(p -> p.isRemoved()).count(), 1L, "later physical Orbs consumed");
            near(h, t.owner.getHealth(), 170, "actual first heal retained"); near(h, StarvationGameTest.account(t, "super"), .025, "first base reward retained");
            near(h, state(t).resources().get(new ResourceState.Key(owner(t), D + "marksman_dodge_energy")).value(), 0, "dodge payment retained");
        } h.succeed();
    }
}
