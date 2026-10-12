package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.NoDistractionsGameTest.*;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;

public class UnflinchingGameTest {
    static void prepare(JsonObject data) {
        RadiantGameTest.merge(data, "unflinching");
        var armor = ThreadedSpikeGameTest.json("unflinching_equipment").getAsJsonObject("equipment");
        for (String field : List.of("slots", "items")) armor.getAsJsonArray(field).forEach(value -> data.getAsJsonObject("equipment").getAsJsonArray(field).add(value));
        var items = data.getAsJsonObject("equipment").getAsJsonArray("items");
        var rifle = items.get(0).getAsJsonObject(); var arc = rifle.deepCopy();
        rifle.getAsJsonArray("tags").add(D + "solar"); arc.addProperty("id", "test:arc_rifle"); arc.getAsJsonArray("tags").add(D + "arc"); items.add(arc);
        var weapon = data.getAsJsonArray("weapons").get(0).getAsJsonObject().deepCopy();
        weapon.addProperty("item", "test:arc_rifle"); data.getAsJsonArray("weapons").add(weapon);
    }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception { return NoDistractionsGameTest.open(h, false, UnflinchingGameTest::prepare); }
    static void mods(ProjectileGameTest.Harness t, String... elements) {
        var choices = new HashMap<String, String>();
        for (int i = 0; i < 3; i++) choices.put("mod_" + i, i < elements.length ? elements[i] : "none");
        put(t, "chest", new Loadout.Gear("chest-" + UUID.randomUUID(), "test:unflinching_chest", choices));
    }
    @GameCase(environment="chorus_gametest:unflinching_tiers", maxTicks=20)
    public void threePhysicalChestSocketsSelectTiersWithoutMultiplyingEachCopy(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "solar"); WeaponAimGameTest.input(t, true);
        at(t, 4, () -> { try (t) {
            double initial = t.owner.getHealth();
            for (int copies = 1; copies <= 3; copies++) {
                mods(t, Collections.nCopies(copies, "solar").toArray(String[]::new)); hit(t); angle(t, .90625 * .9 * new double[]{1,.75,.7,.65}[copies]);
            }
            mods(t); hit(t); angle(t, .90625 * .9); near(h, t.owner.getHealth(), initial - 4, "armor only changes angles, not damage");
            h.assertTrue(!active(t), "passive armor fabricated a quiet ADS perk"); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:unflinching_switch", maxTicks=55)
    public void mixedElementSocketsFollowCurrentSolarOrArcWeaponAndClearTheOldPerk(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "solar", "solar", "arc");
        put(t, "secondary", new Loadout.Gear("arc-weapon", "test:arc_rifle", Map.of("perk", "enhanced"), Map.of("stability", new Measure(50, Unit.STAT_POINT), "enhanced_delay", new Measure(.9, Unit.SECOND))));
        WeaponAimGameTest.input(t, true);
        at(t, 20, () -> {
            t.runtime.prepare(); hit(t); angle(t, .90625 * .9 * .7 * .65); PugilistGameTest.draw(t, "secondary");
            h.assertTrue(!active(t), "old held perk survived switch"); hit(t); angle(t, .90625); WeaponAimGameTest.input(t, true);
        });
        at(t, 24, () -> { hit(t); angle(t, .90625 * .9 * .75); });
        at(t, 38, () -> { try (t) { t.runtime.prepare(); hit(t); angle(t, .90625 * .9 * .75 * .65); h.succeed(); }});
    }
    @GameCase(environment="chorus_gametest:unflinching_fire", maxTicks=40)
    public void actualWeaponFireKeepsArmorAndActiveNoDistractionsAsIndependentFactors(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "solar", "solar", "solar"); WeaponAimGameTest.input(t, true);
        at(t, 20, () -> { try (t) {
            t.runtime.prepare(); var target = t.cow(2, 40, 8); t.owner.setXRot(0); t.owner.setYRot(0);
            target.setPos(t.owner.getX(), t.owner.getEyeY() - target.getBbHeight() / 2, t.owner.getZ() + 5);
            h.assertValueEqual(t.runtime.fire(t.owner).outcome(), WeaponFire.Outcome.ACCEPTED, "actual shot accepted");
            near(h, target.getHealth(), 99, "armor did not change shot damage"); h.assertValueEqual(PrecisionAmmoGameTest.magazine(t, "a"), 11, "one round paid");
            int[] writes = {0}; t.beforeWorld = q -> { if (q.command() instanceof AimImpulse.Command) writes[0]++; };
            hit(t); angle(t, .90625 * .9 * .65 * .65); h.assertValueEqual(writes[0], 1, "three mods still cause one actual impulse");
            h.assertTrue(active(t), "accepted shot removed active perk"); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:unflinching_ads", maxTicks=20)
    public void absorptionLossUsesCompletedAdsAndReleaseRemovesTheArmorBenefit(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "solar", "solar", "solar");
        t.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 600, 0));
        t.owner.setAbsorptionAmount(4); hit(t); angle(t, .90625); WeaponAimGameTest.input(t, true);
        at(t, 3, () -> { hit(t); angle(t, .90625); });
        at(t, 4, () -> { try (t) {
            hit(t); angle(t, .90625 * .9 * .65); near(h, t.owner.getAbsorptionAmount(), 1, "real absorption damage flinched");
            WeaponAimGameTest.input(t, false); hit(t); angle(t, .90625);
            near(h, t.owner.getAbsorptionAmount(), 0, "four actual absorption losses"); near(h, t.owner.getHealth(), 10, "no health loss needed"); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:unflinching_ownership", maxTicks=40)
    public void foreignModSourcesDoNotCountAndReplacingArmorPreservesTheActiveWeaponPerk(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "solar"); var other = t.cow(12, 40, 12); String foreign = other.getUUID().toString();
        for (int i = 0; i < 3; i++) t.runtime.bind(new EffectSource("foreign-" + i, D + "unflinching_solar", foreign, new BuffInstance.Origin(foreign, "armor", "", ""), Set.of()));
        WeaponAimGameTest.input(t, true);
        at(t, 20, () -> { try (t) {
            t.runtime.prepare(); hit(t); angle(t, .90625 * .9 * .75 * .65);
            mods(t, "arc", "arc", "arc"); h.assertTrue(active(t), "armor replacement restarted quiet timer"); hit(t); angle(t, .90625 * .9 * .65);
            mods(t, "solar", "solar"); hit(t); angle(t, .90625 * .9 * .7 * .65); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:unflinching_unknown", maxTicks=40)
    public void unknownComposedImpulseRetainsActualDamageAndRotationWithoutReplay(GameTestHelper h) throws Exception {
        var t = open(h); mods(t, "solar", "solar", "solar"); WeaponAimGameTest.input(t, true);
        at(t, 20, () -> { try (t) {
            t.runtime.prepare(); int[] writes = {0}; t.beforeWorld = q -> {
                if (q.command() instanceof AimImpulse.Command) { writes[0]++; t.world.apply(q); throw new IllegalStateException("Unknown composed flinch"); }
            };
            boolean stopped = false; try { hit(t); } catch (IllegalStateException expected) { stopped = true; }
            h.assertTrue(stopped && t.runtime.failure().isPresent(), "runtime continued after unknown impulse");
            near(h, t.owner.getHealth(), 9, "actual damage retained"); near(h, t.owner.getXRot(), -10 * .90625 * .9 * .65 * .65, "actual angle retained");
            var attacker = t.runtime.state().engine().domain().sources().get("attack").origin();
            boolean rejected = false; try { t.runtime.start(new RuleEngine.Signal("test:hit", new EffectEvent(attacker.owner(), t.owner.getUUID().toString(), attacker, Set.of(), Map.of()))); } catch (IllegalStateException expected) { rejected = true; }
            h.assertTrue(rejected && t.cues.isEmpty(), "replay or later cue"); h.assertValueEqual(writes[0], 1, "single aim write"); h.assertValueEqual(t.hits.size(), 1, "single damage write"); h.succeed();
        }});
    }
}
