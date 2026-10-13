package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.OverflowGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;

/** Private world bricks, real weapon containers and the shared Marksman / Ascension benefit. */
public class MarksmanAmmoGameTest {
    static void prepare(JsonObject data, boolean ascension) {
        AscensionGameTest.calibrate("marksman_dodge", data);
        GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json("class_regeneration"));
        OverflowGameTest.prepare(data); GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json("overflow"));
        if (ascension) {
            for (String name : List.of("ascension", "gamblers_dodge", "character_stats", "amplified", "amplified_inputs", "amplified_movement", "movement_attributes", "weapon_stats", "jolt")) {
                var part = ThreadedSpikeGameTest.json(name);
                if (name.equals("ascension") || name.equals("gamblers_dodge")) AscensionGameTest.calibrate(name, part);
                if (name.equals("amplified_inputs")) part.remove("bundles"); // Keep its incoming profile; Overflow supplies the harness stubs.
                GamblersDodgeGameTest.merge(data, part);
            }
            for (String name : List.of("ascension", "gamblers_dodge")) GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json(name + "_test_calibration").getAsJsonObject("program"));
        }
        AscensionGameTest.version(data);
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean overflow, boolean ascension) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "marksman_dodge", d -> prepare(d, ascension), true);
        try {
            t.owner.setGameMode(GameType.SURVIVAL); t.owner.setOnGround(false);
            equip(t, "primary", "a", "primary", overflow ? "normal" : "none", 1);
            equip(t, "secondary", "b", "special", overflow ? "enhanced" : "none", 12);
            equip(t, "power", "c", "heavy", overflow ? "normal" : "none", 4);
            PugilistGameTest.draw(t, "primary"); AscensionGameTest.choose(t, "chorus_d2:class", "chorus_d2:marksman_dodge");
            t.runtime.bind(new EffectSource("factory", "chorus_d2:ammo_pickups", owner(t), new BuffInstance.Origin("producer", "drop", "", ""), Set.of(), Map.of("lifetime", new Measure(10, Unit.SECOND), "radius", new Measure(.5, Unit.METER))));
            t.runtime.bind(new EffectSource("collector", "test:pickup_observer", owner(t), new BuffInstance.Origin(owner(t), "listener", "", ""), Set.of()));
            if (ascension) { AscensionGameTest.choose(t, AscensionGameTest.SLOT, AscensionGameTest.ABILITY); t.runtime.bind(AmplifiedGameTest.calibration(owner(t))); }
            return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static net.minecraft.world.entity.LivingEntity anchor(ProjectileGameTest.Harness t, double x, double y) {
        var point = t.owner.position().add(x, y, 0);
        // Load the destination before moving the entity; a moved anchor in an unloaded section may be absent from lookup.
        t.h.getLevel().getChunk(net.minecraft.core.BlockPos.containing(point)); var anchor = t.cow(2.5, 40, 3.5); anchor.setPos(point); return anchor;
    }
    static EffectObject brick(ProjectileGameTest.Harness t, String kind, double x, double y) {
        var anchor = anchor(t, x, y); int before = t.pickups.size();
        t.runtime.start(new RuleEngine.Signal("chorus_d2:spawn_" + kind + "_ammo", new EffectEvent(owner(t), anchor.getUUID().toString(), new BuffInstance.Origin(owner(t), "drop-request", "", ""), Set.of(), Map.of())));
        t.h.assertValueEqual(t.pickups.size(), before + 1, "ammo fixture must create one new brick at its loaded anchor"); return t.pickups.getLast();
    }
    static void dodge(ProjectileGameTest.Harness t) { t.h.assertValueEqual(t.runtime.useAbility(t.owner, "chorus_d2:class").outcome(), AbilityUse.Outcome.ACCEPTED, "dodge rejected"); ShieldThrowGameTest.settled(t); }
    @GameCase(environment="chorus_gametest:marksman_ammo_reload", maxTicks=16)
    public void fifteenMeterSpecialAndHeavyBricksSupplyTheActualDelayedReload(GameTestHelper h) throws Exception {
        var t = open(h, false, false);
        try {
            var special = brick(t, "special", 15, 0); var heavy = brick(t, "heavy", 14, 0); var outside = brick(t, "special", 15.001, 0);
            t.runtime.unbind("factory"); dodge(t);
            h.assertTrue(special.isRemoved() && heavy.isRemoved() && !outside.isRemoved(), "inclusive 15 m / kinds failed");
            h.assertValueEqual(reserve(t, "b"), 12, "original special reward"); h.assertValueEqual(reserve(t, "c"), 10, "heavy capacity clamp"); h.assertValueEqual(magazine(t, "b"), 0, "reload too early");
            h.assertValueEqual(special.pickup().orElseThrow().origin().owner(), "producer", "collector replaced producer credit");
            t.finish(6, () -> { h.assertValueEqual(magazine(t, "b"), 5, "new reserves were not reloaded"); h.assertValueEqual(reserve(t, "b"), 7, "special ammo conservation"); h.assertValueEqual(magazine(t, "c"), 1, "heavy reload"); h.assertValueEqual(reserve(t, "c"), 9, "heavy ammo conservation"); h.assertValueEqual(cues(t, "reloaded"), 3L, "three real reload facts"); h.assertTrue(!outside.isRemoved(), "later timer recollected outside brick"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment="chorus_gametest:marksman_ammo_full", maxTicks=16)
    public void fullReservesKeepTheRemoteBrickEvenWhenTheLaterReloadCreatesSpace(GameTestHelper h) throws Exception {
        var t = open(h, false, false);
        try {
            reserve(t, "b", 30); var object = brick(t, "special", 8, 0); dodge(t); h.assertTrue(!object.isRemoved(), "full reserves consumed brick");
            t.finish(6, () -> { h.assertValueEqual(magazine(t, "b"), 5, "full reserve reload"); h.assertValueEqual(reserve(t, "b"), 25, "reload transfer"); h.assertTrue(!object.isRemoved(), "one-shot collection revisited rejected brick"); h.assertValueEqual(cues(t, "collected"), 0L, "rejected brick dispatched fact"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void multipleBricksStopAtCapacityAndAnExhaustedSecondCastCannotCollectAgain(GameTestHelper h) throws Exception {
        try (var t = open(h, false, false)) {
            reserve(t, "b", 25); for (int i = 0; i < 4; i++) brick(t, "special", 8 + i, 0); dodge(t);
            h.assertValueEqual(t.pickups.stream().filter(o -> o.isRemoved()).count(), 1L, "extra bricks consumed"); h.assertValueEqual(reserve(t, "b"), 30, "first reward not committed");
            h.assertValueEqual(t.runtime.useAbility(t.owner, "chorus_d2:class").outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "second cast accepted"); h.assertValueEqual(cues(t, "collected"), 1L, "duplicate collection fact");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:marksman_ammo_overflow", maxTicks=16)
    public void equippedOverflowSettlesBeforeTheNextBrickAndTheDodgeReloadPreservesOverfilledMagazines(GameTestHelper h) throws Exception {
        var t = open(h, true, false);
        try {
            reserve(t, "b", 25); for (int i = 0; i < 4; i++) brick(t, "special", 8 + i, 0); dodge(t);
            h.assertValueEqual(t.pickups.stream().filter(o -> o.isRemoved()).count(), 2L, "Overflow gap not reevaluated"); h.assertValueEqual(magazine(t, "b"), 11, "enhanced Overflow"); h.assertValueEqual(reserve(t, "b"), 30, "second brick fills new gap");
            t.finish(6, () -> { h.assertValueEqual(magazine(t, "b"), 11, "reload trimmed overflow"); h.assertValueEqual(reserve(t, "b"), 30, "reload lost reserves"); h.assertValueEqual(cues(t, "reloaded"), 0L, "zero transfers forged reloads"); h.assertValueEqual(cues(t, "collected"), 2L, "original pickup facts"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void privateBricksOfAnotherRecipientCannotBeTakenByTheDodge(GameTestHelper h) throws Exception {
        try (var t = open(h, false, false)) {
            var other = anchor(t, 8, 0); String who = other.getUUID().toString();
            t.runtime.bind(new EffectSource("foreign", "chorus_d2:ammo_pickups", who, new BuffInstance.Origin(who, "foreign", "", ""), Set.of(), Map.of("lifetime", new Measure(10, Unit.SECOND), "radius", new Measure(.5, Unit.METER))));
            t.runtime.start(new RuleEngine.Signal("chorus_d2:spawn_special_ammo", new EffectEvent(who, who, new BuffInstance.Origin(who, "request", "", ""), Set.of(), Map.of())));
            var foreign = t.pickups.getLast(); var own = brick(t, "special", 9, 0); dodge(t);
            h.assertTrue(!foreign.isRemoved() && own.isRemoved(), "private recipient bypass"); h.assertValueEqual(reserve(t, "b"), 12, "foreign reward stolen");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:marksman_ammo_ascension", maxTicks=16)
    public void ascensionInvokesTheSamePickupBenefitAtItsCurrentPositionAndRetainsTheCapturedDodge(GameTestHelper h) throws Exception {
        var t = open(h, false, true);
        try {
            var atLandingHeight = brick(t, "special", 15, 3); var onlyBeforeAscent = brick(t, "special", -15, 0); double y = t.owner.getY();
            AscensionGameTest.cast(t); near(h, t.owner.getY(), y + 3, "test ascent displacement");
            h.assertTrue(atLandingHeight.isRemoved() && !onlyBeforeAscent.isRemoved(), "benefit query did not use current caster position"); h.assertValueEqual(reserve(t, "b"), 12, "shared original pickup reward");
            AscensionGameTest.choose(t, "chorus_d2:class", "chorus_d2:gamblers_dodge"); AscensionGameTest.choose(t, AscensionGameTest.SLOT, null);
            t.finish(6, () -> { h.assertValueEqual(magazine(t, "b"), 5, "captured Marksman reload lost"); h.assertValueEqual(reserve(t, "b"), 7, "shared reload duplicated ammo"); near(h, AscensionGameTest.energy(t, "chorus_d2:gamblers_dodge_energy"), 1, "benefit paid current selection twice"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void unknownPickupReactionRetainsOneRealRewardAndStopsFurtherClaimsAndReloads(GameTestHelper h) throws Exception {
        try (var t = open(h, false, false)) {
            brick(t, "special", 8, 0); brick(t, "special", 9, 0);
            t.onCue = c -> { if (c.cue().equals("test:collected")) throw new IllegalStateException("unknown committed pickup reaction"); };
            boolean failed = false; try { dodge(t); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown reaction reported success"); h.assertValueEqual(reserve(t, "b"), 12, "first reward was rolled back");
            MinecraftEffectRuntime.tick(h.getLevel()); h.assertValueEqual(t.pickups.stream().filter(o -> o.isRemoved()).count(), 1L, "failed batch continued"); h.assertValueEqual(magazine(t, "b"), 0, "failed runtime reloaded"); h.assertValueEqual(cues(t, "collected"), 1L, "pickup reaction replayed");
        } h.succeed();
    }
}
