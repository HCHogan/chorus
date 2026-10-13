package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Actual motion, damage, status and equipped weapon/energy benefits; calibration is explicitly synthetic. */
public class AscensionGameTest {
    static final String D = "chorus_d2:", ABILITY = D + "ascension", SLOT = D + "air_move";
    static void version(JsonElement e) {
        if (e.isJsonObject()) { var o = e.getAsJsonObject(); if (o.has("version")) o.addProperty("version", "compendium-2026-10-05"); o.entrySet().forEach(x -> version(x.getValue())); }
        else if (e.isJsonArray()) e.getAsJsonArray().forEach(AscensionGameTest::version);
    }
    static void calibrate(String name, JsonObject data) {
        ThreadedSpikeGameTest.json(name + "_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
    }
    static void prepare(JsonObject data) {
        calibrate("ascension", data);
        for (String name : List.of("marksman_dodge", "gamblers_dodge", "class_regeneration", "character_stats", "amplified", "amplified_inputs", "amplified_movement", "movement_attributes", "weapon_stats", "jolt", "weapons", "kill_clip")) {
            var part = ThreadedSpikeGameTest.json(name); if (name.endsWith("_dodge")) calibrate(name, part); GamblersDodgeGameTest.merge(data, part);
        }
        for (String name : List.of("ascension", "gamblers_dodge")) GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json(name + "_test_calibration").getAsJsonObject("program"));
        version(data);
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, String dodge) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "ascension", AscensionGameTest::prepare, true);
        try {
            t.owner.setGameMode(GameType.SURVIVAL); t.owner.setOnGround(false); t.owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); t.owner.setHealth(100);
            choose(t, D + "class", D + dodge); choose(t, SLOT, ABILITY); choose(t, D + "melee", "test:ascension_melee");
            t.runtime.bind(AmplifiedGameTest.calibration(owner(t))); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static String owner(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static void choose(ProjectileGameTest.Harness t, String slot, String ability) {
        var old = state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY); var slots = new HashMap<>(old.slots()); if (ability == null) slots.remove(slot); else slots.put(slot, ability);
        t.runtime.abilities(new AbilityChange(owner(t), old, new AbilityLoadout(slots)));
    }
    static double energy(ProjectileGameTest.Harness t, String resource) { return state(t).resources().get(new ResourceState.Key(owner(t), resource)).value(); }
    static void stat(ProjectileGameTest.Harness t, String name, double points) { t.runtime.bind(new EffectSource("stat/" + name, name.equals("melee") ? "test:ascension_melee_stat" : "test:ascension_stat", owner(t), new BuffInstance.Origin(owner(t), "stat/" + name, "", ""), Set.of(), Map.of("points", new Measure(points, Unit.STAT_POINT)))); }
    static void cast(ProjectileGameTest.Harness t) { t.h.assertValueEqual(t.runtime.useAbility(t.owner, SLOT).outcome(), AbilityUse.Outcome.ACCEPTED, "Ascension rejected"); ShieldThrowGameTest.settled(t); }
    static LivingEntity target(ProjectileGameTest.Harness t, double distance) { var e = t.cow(2.5, 40, 3.5); e.setPos(t.owner.position().add(distance, 0, 0)); return e; }
    static int count(ProjectileGameTest.Harness t, String buff, LivingEntity e) { return AmplifiedGameTest.count(t.runtime, D + buff, e.getUUID().toString()); }
    @GameCase public void paidVerticalCastUsesCapturedTenMeterOriginAndSeparatesAlliesFromEnemies(GameTestHelper h) throws Exception {
        try (var t = open(h, "marksman_dodge")) {
            var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("ascent-" + UUID.randomUUID());
            try {
                var enemy = target(t, 10); var outside = target(t, 10.001); var ally = target(t, 2);
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team);
                stat(t, "class", 200); var before = t.owner.position(); double speed = t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED); cast(t);
                near(h, t.owner.getY(), before.y + 3, "calibrated upward displacement"); near(h, t.owner.getX(), before.x, "look direction changed upward axis");
                near(h, energy(t, D + "marksman_dodge_energy"), 0, "selected dodge charge not consumed");
                var direct = t.hits.stream().filter(c -> c.tags().contains(D + "ascension_damage")).toList(); h.assertValueEqual(direct.size(), 1, "direct radius/relations");
                h.assertValueEqual(direct.getFirst().target(), enemy.getUUID().toString(), "captured cast origin lost inclusive boundary");
                near(h, t.receipts.get(t.hits.indexOf(direct.getFirst())).healthLoss(), 18.1 * 1.65, "Class-scaled direct damage");
                h.assertTrue(t.hits.stream().noneMatch(c -> c.target().equals(ally.getUUID().toString())), "ally took direct or Jolt damage");
                h.assertTrue(direct.stream().noneMatch(c -> c.target().equals(outside.getUUID().toString())), "outside enemy took direct damage");
                h.assertValueEqual(count(t, "amplified", t.owner), 1, "self Amplified"); h.assertValueEqual(count(t, "amplified", ally), 1, "ally Amplified"); h.assertValueEqual(count(t, "jolt", enemy), 1, "enemy Jolt");
                near(h, t.owner.getAttributeValue(Attributes.MOVEMENT_SPEED), speed * 1.2, "existing Amplified movement projection");
                h.assertTrue(t.hits.stream().allMatch(c -> c.source().ability().equals(ABILITY)), "Jolt lost originating ability credit");
            } finally { scoreboard.removePlayerTeam(team); }
        } h.succeed();
    }
    @GameCase public void solidCeilingClipsAscentWhileStatusDenialOnlyRejectsThatTargetsJolt(GameTestHelper h) throws Exception {
        try (var t = open(h, "marksman_dodge")) {
            var enemy = target(t, 4); t.deniedStatusTargets.add(enemy.getUUID().toString()); var before = t.owner.position(); var roof = BlockPos.containing(before).above(2);
            t.blocks.put(roof, h.getLevel().getBlockState(roof)); h.getLevel().setBlockAndUpdate(roof, Blocks.STONE.defaultBlockState()); cast(t);
            h.assertTrue(t.owner.getY() >= before.y && t.owner.getY() < before.y + .3, "body passed through ceiling");
            near(h, enemy.getHealth(), 100 - 18.1, "denied Jolt cancelled explosion"); h.assertValueEqual(count(t, "jolt", enemy), 0, "denied Jolt granted"); h.assertValueEqual(count(t, "amplified", t.owner), 1, "clipped cast lost Amplified");
        } h.succeed();
    }
    @GameCase public void groundedMissingSelectionAndExhaustedChargeProduceNoAdditionalWorldEffects(GameTestHelper h) throws Exception {
        try (var t = open(h, "marksman_dodge")) {
            var p = t.owner.position(); t.owner.setOnGround(true); h.assertValueEqual(t.runtime.useAbility(t.owner, SLOT).outcome(), AbilityUse.Outcome.CONDITION, "grounded accepted");
            t.owner.setOnGround(false); choose(t, D + "class", null); h.assertValueEqual(t.runtime.useAbility(t.owner, SLOT).outcome(), AbilityUse.Outcome.CONDITION, "retained account bypassed selection");
            h.assertValueEqual(t.owner.position(), p, "rejection moved actor"); near(h, energy(t, D + "marksman_dodge_energy"), 1, "rejection paid");
            choose(t, D + "class", D + "marksman_dodge"); cast(t); p = t.owner.position(); h.assertValueEqual(t.runtime.useAbility(t.owner, SLOT).outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "exhausted accepted"); h.assertValueEqual(t.owner.position(), p, "exhausted action moved actor");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:ascension_reload", maxTicks=16)
    public void selectedMarksmanBenefitReloadsActualWeaponsAfterSelectionChangesWithoutAnotherCast(GameTestHelper h) throws Exception {
        var t = open(h, "marksman_dodge");
        try {
            var e = PlayerEquipment.get(t.owner);
            for (String slot : List.of("primary", "secondary")) { var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear("ascension/" + slot, "test:rifle", Map.of("perk", "kill_clip"))); t.owner.getInventory().setItem(0, stack); e.swap(t.owner, "test:" + slot, 0, e.revision()); }
            cast(t); double y = t.owner.getY(); h.assertValueEqual(state(t).ammunition().get("ascension/primary").magazine(), 1, "reload preceded calibrated delay");
            choose(t, D + "class", D + "gamblers_dodge"); choose(t, SLOT, null);
            t.finish(6, () -> {
                ShieldThrowGameTest.settled(t); for (String slot : List.of("primary", "secondary")) { var ammo = state(t).ammunition().get("ascension/" + slot); h.assertValueEqual(ammo.magazine(), 5, "actual equipped magazine not filled"); h.assertValueEqual(ammo.reserve().orElseThrow().rounds(), 8, "reload created reserves"); }
                near(h, t.owner.getY(), y, "benefit ran another movement"); near(h, energy(t, D + "gamblers_dodge_energy"), 1, "new class selection was charged");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:ascension_return", maxTicks=16)
    public void selectedGamblerBenefitReturnsTwoChargesWithoutReplayingGroundedDodge(GameTestHelper h) throws Exception {
        var t = open(h, "gamblers_dodge");
        try {
            stat(t, "melee", 100); t.runtime.useAbility(t.owner, D + "melee"); t.runtime.useAbility(t.owner, D + "melee"); target(t, 8);
            var before = t.owner.position(); cast(t); near(h, energy(t, "test:ascension_melee_energy"), 0, "melee returned before delay");
            choose(t, D + "class", null); choose(t, SLOT, null);
            t.finish(6, () -> { ShieldThrowGameTest.settled(t); near(h, energy(t, "test:ascension_melee_energy"), 2, "nearby enemy did not give double return"); near(h, t.owner.getZ(), before.z, "benefit replayed horizontal dodge"); near(h, t.owner.getY(), before.y + 3, "air displacement lost"); h.assertValueEqual(count(t, "gamblers_dodge_tracking", t.owner), 0, "dodge animation window applied to Ascension"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void unknownRelocationRetainsActualMovementAndPaymentAndCannotReplay(GameTestHelper h) throws Exception {
        try (var t = open(h, "marksman_dodge")) {
            var before = t.owner.position(); t.failAfterDisplacement = true; boolean failed = false; try { cast(t); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown motion did not stop runtime"); near(h, t.owner.getY(), before.y + 3, "already applied movement lost"); near(h, energy(t, D + "marksman_dodge_energy"), 0, "paid charge returned"); h.assertValueEqual(count(t, "amplified", t.owner), 0, "effects continued after unknown result");
            failed = false; try { t.runtime.useAbility(t.owner, SLOT); } catch (IllegalStateException expected) { failed = true; } h.assertTrue(failed, "unknown cast retried"); near(h, t.owner.getY(), before.y + 3, "unknown movement replayed");
        } h.succeed();
    }
    @GameCase public void actualAscensionTriggersEquippedKickstartThenAttractionRefillsOnlyTheLiveChargeGap(GameTestHelper h) throws Exception {
        try (var t = PowerfulAttractionGameTest.open(h, data -> {
            var ascent = ThreadedSpikeGameTest.json("ascension"); calibrate("ascension", ascent); GamblersDodgeGameTest.merge(data, ascent);
            var gambler = ThreadedSpikeGameTest.json("gamblers_dodge"); calibrate("gamblers_dodge", gambler); GamblersDodgeGameTest.merge(data, gambler);
            for (String name : List.of("amplified", "amplified_movement", "movement_attributes", "weapon_stats", "jolt")) GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json(name));
            var defense = ThreadedSpikeGameTest.json("amplified_inputs"); defense.remove("bundles"); GamblersDodgeGameTest.merge(data, defense);
            var calibration = ThreadedSpikeGameTest.json("ascension_test_calibration").getAsJsonObject("program"); calibration.remove("profiles"); GamblersDodgeGameTest.merge(data, calibration);
            GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json("gamblers_dodge_test_calibration").getAsJsonObject("program")); version(data);
        })) {
            PowerfulAttractionGameTest.equip(t, "powerful_attraction", "utility_kickstart"); choose(t, D + "class", D + "marksman_dodge"); choose(t, SLOT, ABILITY); t.owner.setOnGround(false);
            ArmorChargeGameTest.action(t, "gain", owner(t), 3, 1); StarvationGameTest.fill(t, "super"); for (int i = 0; i < 5; i++) PowerfulAttractionGameTest.orb(t, 8);
            var before = t.owner.position(); cast(t); near(h, t.owner.getY(), before.y + 3, "actual Ascension body did not run");
            near(h, energy(t, D + "marksman_dodge_energy"), .284, "Utility Kickstart missed class-tagged air input or used post-collection charges");
            h.assertValueEqual(ArmorChargeGameTest.charges(t), 3, "collected Orbs did not refill spent charges");
            h.assertValueEqual(t.pickups.stream().filter(p -> p.isRemoved()).count(), 3L, "Attraction over-collected after the live gap was full");
            h.assertValueEqual(count(t, "amplified", t.owner), 1, "equipped mod reactions lost Amplified");
        } h.succeed();
    }
}
