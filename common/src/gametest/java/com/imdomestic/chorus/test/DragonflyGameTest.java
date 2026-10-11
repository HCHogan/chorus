package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;

/** Precision comes from physical contact geometry and an explicit target region, never a forced kill fact. */
public class DragonflyGameTest {
    static void prepare(JsonObject data, Set<String> tags) {
        for (String name : List.of("dragonfly_test_calibration", "dragonfly_weapon")) {
            var fragment = ThreadedSpikeGameTest.json(name);
            if (name.equals("dragonfly_weapon")) {
                var fire = fragment.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");
                for (int i : List.of(2, 3)) {
                    var selected = new JsonArray(); selected.add("chorus:weapon_damage"); if (i == 3) selected.add("chorus:precision"); tags.forEach(selected::add);
                    fire.get(i).getAsJsonObject().getAsJsonObject("action").add("tags", selected);
                }
            }
            for (var e : fragment.entrySet()) {
                if (Set.of("version", "imports").contains(e.getKey())) continue;
                if (e.getValue().isJsonArray()) { if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray()); e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v)); }
                else data.add(e.getKey(), e.getValue());
            }
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, Set<String> tags, double delay) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "dragonfly", d -> prepare(d, tags), true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX() + 2.5, t.owner.getY(), t.owner.chunkPosition().getMinBlockZ() + 2.5);
            t.owner.setXRot(0); t.owner.setYRot(0); t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
            for (String id : List.of("a", "b")) {
                var parameters = Map.of("pve_damage", new Measure(123.89, Unit.DAMAGE), "pvp_damage", new Measure(95.3, Unit.DAMAGE),
                        "full_radius", new Measure(0, Unit.METER), "radius", new Measure(8, Unit.METER), "delay", new Measure(delay, Unit.SECOND));
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", id.equals("a") ? "normal" : "enhanced"), parameters));
                t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            PugilistGameTest.draw(t, "primary"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static LivingEntity cow(ProjectileGameTest.Harness t, double x, double y, double z, float hp) {
        var e = t.cow(2.5, 40, 3.5); e.setPos(x, y, z); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(hp);
        t.h.assertTrue(t.h.getLevel().isPositionEntityTicking(e.blockPosition()), "Dragonfly target must be simulated"); return e;
    }
    static void region(ProjectileGameTest.Harness t, LivingEntity e) {
        String id = e.getUUID().toString(); t.runtime.bind(new EffectSource("region/" + id, "test:weapon_region", id, new BuffInstance.Origin(id, "region", "", ""), Set.of(), Map.of(
                "min_x", new Measure(-1, Unit.MULTIPLIER), "max_x", new Measure(2, Unit.MULTIPLIER), "min_y", new Measure(.75, Unit.MULTIPLIER),
                "max_y", new Measure(1.125, Unit.MULTIPLIER), "min_z", new Measure(-1, Unit.MULTIPLIER), "max_z", new Measure(2, Unit.MULTIPLIER))));
    }
    static LivingEntity victim(ProjectileGameTest.Harness t, boolean head, boolean classified) {
        var e = cow(t, t.owner.getX(), t.owner.getY(), t.owner.getZ() + 5, 5);
        e.setPos(e.getX(), t.owner.getEyeY() - e.getBoundingBox().getYsize() * (head ? .9 : .5), e.getZ()); if (classified) region(t, e); return e;
    }
    static LivingEntity neighbor(ProjectileGameTest.Harness t, LivingEntity center, double distance, float hp) { return cow(t, center.getX() + distance, center.getY(), center.getZ(), hp); }
    static List<DamageCommand> bursts(ProjectileGameTest.Harness t) { return t.hits.stream().filter(d -> d.tags().contains("chorus_d2:dragonfly_damage")).toList(); }
    static void kill(ProjectileGameTest.Harness t, LivingEntity e) throws Exception { PugilistGameTest.impact(t, PugilistGameTest.fire(t), e); }
    @GameCase(environment = "chorus_gametest:dragonfly_variants")
    public void realPrecisionKillsSelectNormalAndEnhancedElementalExplosions(GameTestHelper h) throws Exception {
        for (boolean enhanced : List.of(false, true)) try (var t = open(h, Set.of(enhanced ? "chorus_d2:void_damage" : "chorus_d2:arc_damage"), 0)) {
            if (enhanced) PugilistGameTest.draw(t, "secondary"); var corpse = victim(t, true, true); var half = neighbor(t, corpse, 4, 1000); var edge = neighbor(t, corpse, 8, 1000); var outside = neighbor(t, corpse, 8.02, 1000); var ally = neighbor(t, corpse, 2, 1000);
            var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("dragonfly-" + UUID.randomUUID());
            try {
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team); kill(t, corpse);
                h.assertTrue(t.hits.getFirst().tags().contains("chorus:precision"), "physical head contact"); HealingGameTest.near(h, half.getHealth(), 1000 - 6.1945 * (enhanced ? 1.1 : 1), "explosion damage");
                HealingGameTest.near(h, edge.getHealth(), 1000, "zero edge"); HealingGameTest.near(h, outside.getHealth(), 1000, "outside"); HealingGameTest.near(h, ally.getHealth(), 1000, "allied target");
                h.assertValueEqual(bursts(t).size(), 1, "one nonzero eligible neighbor"); var blast = bursts(t).getFirst(); h.assertValueEqual(blast.source(), t.hits.getFirst().source(), "original weapon attribution");
                h.assertTrue(blast.tags().contains(enhanced ? "chorus_d2:void_damage" : "chorus_d2:arc_damage") && blast.tags().contains("chorus:explosive_perk_damage") && !blast.tags().contains("chorus:precision"), "selected element and explosive classification without precision");
                h.assertValueEqual(half.getLastDamageSource().getEntity(), t.owner, "native attacker"); h.assertTrue(t.runtime.failure().isEmpty(), "Dragonfly failed");
            } finally { scoreboard.removePlayerTeam(team); }
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:dragonfly_eligibility")
    public void physicalBodyUnclassifiedAndRemovedPerkKillsCannotTriggerWhileStowedHeadshotsCan(GameTestHelper h) throws Exception {
        for (String kind : List.of("body", "unclassified", "removed", "stowed", "missing_element")) try (var t = open(h, kind.equals("missing_element") ? Set.of() : Set.of("chorus_d2:solar_damage"), 0)) {
            var corpse = victim(t, !kind.equals("body"), !kind.equals("unclassified")); var near = neighbor(t, corpse, 4, 1000); var shot = PugilistGameTest.fire(t); PugilistGameTest.draw(t, "secondary");
            if (kind.equals("removed")) ReconstructionGameTest.replace(t, "primary", "none"); PugilistGameTest.impact(t, shot, corpse);
            h.assertValueEqual(bursts(t).size(), kind.equals("stowed") ? 1 : 0, "kill qualification"); HealingGameTest.near(h, near.getHealth(), kind.equals("stowed") ? 993.8055 : 1000, "qualified damage");
            h.assertValueEqual(t.hits.getFirst().tags().contains("chorus:precision"), !Set.of("body", "unclassified").contains(kind), "physical classification"); h.assertValueEqual(t.cues.size(), kind.equals("missing_element") ? 1 : 0, "unresolved element"); h.assertTrue(t.runtime.failure().isEmpty(), "qualification failed");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:dragonfly_history", maxTicks = 40)
    public void capturedExplosionSurvivesCorpseRemovalAndSourceReplacement(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:stasis_damage"), .1);
        try {
            IncandescentGameTest.bind(t, "bonus", "test:dragonfly_bonus", "a"); var corpse = victim(t, true, true); var near = neighbor(t, corpse, 4, 1000); var point = corpse.position(); kill(t, corpse);
            h.assertTrue(bursts(t).isEmpty(), "declared delay"); corpse.setPos(corpse.getX() + 30, corpse.getY(), corpse.getZ()); corpse.discard(); ReconstructionGameTest.replace(t, "primary", "none"); t.runtime.unbind("bonus");
            t.finish(5, () -> {
                HealingGameTest.near(h, near.getHealth(), 990.70825, "captured bonus and falloff"); h.assertValueEqual(bursts(t).size(), 1, "one pending burst");
                var center = ((TargetQuery.PositionCenter) t.queries.getFirst().center()).position().orElseThrow(); HealingGameTest.near(h, center.x(), point.x, "receipt center x"); HealingGameTest.near(h, center.y(), point.y, "receipt center y"); h.assertTrue(bursts(t).getFirst().tags().contains("chorus_d2:stasis_damage"), "captured element");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:dragonfly_credit", maxTicks = 40)
    public void actualExplosionKillDoesNotBecomeAPrecisionKillOrTriggerAnotherDragonfly(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:strand_damage"), .1);
        try {
            var corpse = victim(t, true, true); var second = neighbor(t, corpse, 4, 1); region(t, second); var far = neighbor(t, corpse, 9, 1000); kill(t, corpse);
            t.finish(7, () -> {
                h.assertTrue(second.isDeadOrDying(), "secondary native kill"); HealingGameTest.near(h, far.getHealth(), 1000, "no fabricated precision chain"); h.assertValueEqual(t.queries.size(), 1, "one precision-triggered blast");
                var blast = bursts(t).getFirst(); h.assertValueEqual(blast.killTags(), Set.of("chorus:weapon_kill"), "weapon kill credit retained"); h.assertTrue(!blast.tags().contains("chorus:precision") && blast.proc().deny().isEmpty(), "qualification must come from tags, not a global proc block");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:dragonfly_unknown", maxTicks = 40)
    public void unknownNativeExplosionResultRetainsDamageWithoutReplay(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:arc_damage"), .1);
        try {
            var corpse = victim(t, true, true); var near = neighbor(t, corpse, 4, 1000); kill(t, corpse); t.failAfterDamage = true;
            h.runAfterDelay(5, () -> { try (t) {
                h.assertTrue(t.runtime.failure().isPresent(), "missing injected world failure"); HealingGameTest.near(h, near.getHealth(), 993.8055, "retained native loss");
                t.projectiles.forEach(EffectProjectile::tick); h.assertValueEqual(bursts(t).size(), 1, "no replay after unknown outcome"); h.succeed();
            } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
