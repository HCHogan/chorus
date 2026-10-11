package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.DragonflyGameTest.*;
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
public class FireflyGameTest {
    static void prepare(JsonObject data, Set<String> tags) {
        for (String name : List.of("weapon_stats", "firefly_test_calibration", "firefly_weapon")) {
            var fragment = ThreadedSpikeGameTest.json(name);
            if (name.equals("firefly_weapon")) {
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
    static ProjectileGameTest.Harness open(GameTestHelper h, Set<String> tags, double delay, double floor) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "firefly", d -> prepare(d, tags), true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX() + 2.5, t.owner.getY(), t.owner.chunkPosition().getMinBlockZ() + 2.5);
            t.owner.setXRot(0); t.owner.setYRot(0); t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
            for (String id : List.of("a", "b")) {
                var parameters = Map.of("pve_damage", new Measure(104, Unit.DAMAGE), "pvp_damage", new Measure(80, Unit.DAMAGE),
                        "full_radius", new Measure(3.75, Unit.METER), "radius", new Measure(7.5, Unit.METER), "delay", new Measure(delay, Unit.SECOND),
                        "pve_minimum_falloff", new Measure(floor, Unit.MULTIPLIER), "pvp_minimum_falloff", new Measure(0, Unit.MULTIPLIER));
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", id.equals("a") ? "normal" : "enhanced"), parameters));
                t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            PugilistGameTest.draw(t, "primary"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static List<DamageCommand> bursts(ProjectileGameTest.Harness t) { return t.hits.stream().filter(d -> d.tags().contains("chorus_d2:firefly_damage")).toList(); }
    static EffectState state(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain(); }
    static boolean active(ProjectileGameTest.Harness t, String weapon) {
        t.runtime.prepare(); return state(t).buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:firefly_reload") && b.origin().weapon().equals(weapon));
    }
    static double stat(ProjectileGameTest.Harness t, String weapon) {
        String id = t.owner.getUUID().toString(); var event = new EffectEvent(id, id, new BuffInstance.Origin(id, "query", weapon, ""), Set.of(), Map.of());
        return t.runtime.program().calculate(state(t), id, event, "chorus_d2:weapon_reload", new Measure(10, Unit.STAT_POINT), List.of()).output().value();
    }
    static void reload(ProjectileGameTest.Harness t, double expected) throws Exception {
        var source = t.owner.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
        t.h.assertValueEqual(t.h.getLevel().getServer().getCommands().getDispatcher().execute("chorus weapon reload", source), 1, "low-permission reload accepted");
        HealingGameTest.near(t.h, state(t).reloads().get(t.owner.getUUID().toString()).duration().value(), expected, "computed reload duration");
    }
    @GameCase(environment = "chorus_gametest:firefly_variants")
    public void physicalPrecisionKillsAlwaysProduceSolarAndOnlyEnhancedReloadIsStronger(GameTestHelper h) throws Exception {
        for (boolean enhanced : List.of(false, true)) try (var t = open(h, Set.of(enhanced ? "chorus_d2:void_damage" : "chorus_d2:kinetic_damage"), 0, 0)) {
            if (enhanced) PugilistGameTest.draw(t, "secondary"); var corpse = victim(t, true, true); var half = neighbor(t, corpse, 5.625, 1000); var edge = neighbor(t, corpse, 7.5, 1000); var outside = neighbor(t, corpse, 7.52, 1000); var ally = neighbor(t, corpse, 2, 1000);
            var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("firefly-" + UUID.randomUUID());
            try {
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team); kill(t, corpse);
                HealingGameTest.near(h, half.getHealth(), 994.8, "same base explosion in both variants"); HealingGameTest.near(h, edge.getHealth(), 1000, "zero edge"); HealingGameTest.near(h, outside.getHealth(), 1000, "outside"); HealingGameTest.near(h, ally.getHealth(), 1000, "allied target");
                h.assertValueEqual(bursts(t).size(), 1, "one nonzero enemy"); var blast = bursts(t).getFirst(); h.assertValueEqual(blast.source(), t.hits.getFirst().source(), "original attribution");
                h.assertValueEqual(blast.tags(), Set.of("chorus:weapon_damage", "chorus:explosive_perk_damage", "chorus_d2:firefly_damage", "chorus_d2:solar_damage"), "fixed solar without precision inheritance");
                HealingGameTest.near(h, stat(t, enhanced ? "b" : "a"), enhanced ? 65 : 60, "normal/enhanced reload stat"); HealingGameTest.near(h, stat(t, enhanced ? "a" : "b"), 10, "other weapon unchanged");
                h.assertValueEqual(half.getLastDamageSource().getEntity(), t.owner, "native attacker"); reload(t, enhanced ? 1.35 : 1.4); h.assertTrue(t.runtime.failure().isEmpty(), "Firefly failed");
            } finally { scoreboard.removePlayerTeam(team); }
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:firefly_eligibility")
    public void bodyUnclassifiedAndRemovedPerkKillsFailWhileStowedAndUnlabelledHeadshotsWork(GameTestHelper h) throws Exception {
        for (String kind : List.of("body", "unclassified", "removed", "stowed", "missing_element")) try (var t = open(h, kind.equals("missing_element") ? Set.of() : Set.of("chorus_d2:arc_damage"), 0, 0)) {
            var corpse = victim(t, !kind.equals("body"), !kind.equals("unclassified")); var near = neighbor(t, corpse, 5.625, 1000); var shot = PugilistGameTest.fire(t); PugilistGameTest.draw(t, "secondary");
            if (kind.equals("removed")) ReconstructionGameTest.replace(t, "primary", "none"); PugilistGameTest.impact(t, shot, corpse); boolean expected = Set.of("stowed", "missing_element").contains(kind);
            h.assertValueEqual(bursts(t).size(), expected ? 1 : 0, "precision qualification"); HealingGameTest.near(h, near.getHealth(), expected ? 994.8 : 1000, "qualified explosion");
            h.assertValueEqual(active(t, "a"), expected, "original weapon reload reward"); h.assertTrue(!active(t, "b") && t.cues.isEmpty() && t.runtime.failure().isEmpty(), "other weapon or unresolved classification leaked");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:firefly_reload", maxTicks = 175)
    public void realReloadTransfersReservesForBothVariantsAndStowedBuffStillExpires(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:kinetic_damage"), 0, 0);
        try {
            var first = victim(t, true, true); kill(t, first); first.discard(); reload(t, 1.4);
            RampageGameTest.at(t, 20, () -> h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "normal reload pending"));
            RampageGameTest.at(t, 33, () -> { h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "normal reload completed"); h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 11, "normal reserves paid"); });
            RampageGameTest.at(t, 40, () -> { PugilistGameTest.draw(t, "secondary"); var second = victim(t, true, true); kill(t, second); second.discard(); reload(t, 1.35); h.assertTrue(active(t, "a"), "stowed buff retained"); });
            RampageGameTest.at(t, 62, () -> h.assertValueEqual(state(t).ammunition().get("b").magazine(), 4, "enhanced reload pending"));
            RampageGameTest.at(t, 72, () -> { h.assertValueEqual(state(t).ammunition().get("b").magazine(), 5, "enhanced reload completed"); h.assertValueEqual(state(t).ammunition().get("b").reserve().orElseThrow().rounds(), 11, "enhanced own reserves paid"); });
            RampageGameTest.at(t, 130, () -> h.assertTrue(!active(t, "a") && active(t, "b"), "independent six second windows"));
            t.finish(165, () -> h.assertTrue(!active(t, "a") && !active(t, "b"), "both windows expired"));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:firefly_expiry", maxTicks = 160)
    public void buffCanExpireDuringAnAcceptedReloadWithoutChangingItsCompletion(GameTestHelper h) throws Exception {
        var t = open(h, Set.of(), 0, 0);
        try {
            var corpse = victim(t, true, true); kill(t, corpse); corpse.discard();
            RampageGameTest.at(t, 110, () -> reload(t, 1.4));
            RampageGameTest.at(t, 125, () -> { h.assertTrue(!active(t, "a") && !state(t).reloads().isEmpty(), "buff expired while accepted reload remains"); h.assertValueEqual(state(t).ammunition().get("a").magazine(), 4, "not completed early"); });
            t.finish(145, () -> { h.assertValueEqual(state(t).ammunition().get("a").magazine(), 5, "accepted 1.4 second reload completed"); h.assertValueEqual(state(t).ammunition().get("a").reserve().orElseThrow().rounds(), 11, "one round transferred"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:firefly_history", maxTicks = 40)
    public void sourceRemovalCleansReloadButPendingSolarBlastKeepsCenterAndBonus(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:void_damage"), .1, 0);
        try {
            IncandescentGameTest.bind(t, "bonus", "test:firefly_bonus", "a"); var corpse = victim(t, true, true); var near = neighbor(t, corpse, 5.625, 1000); var point = corpse.position(); kill(t, corpse);
            h.assertTrue(active(t, "a") && bursts(t).isEmpty(), "reward precedes delayed explosion"); corpse.setPos(corpse.getX() + 30, corpse.getY(), corpse.getZ()); corpse.discard(); ReconstructionGameTest.replace(t, "primary", "none"); t.runtime.unbind("bonus");
            t.finish(5, () -> { h.assertTrue(!active(t, "a"), "detached reload buff removed"); HealingGameTest.near(h, near.getHealth(), 992.2, "captured bonus and original center"); h.assertValueEqual(bursts(t).size(), 1, "one pending blast");
                var center = ((TargetQuery.PositionCenter) t.queries.getFirst().center()).position().orElseThrow(); HealingGameTest.near(h, center.x(), point.x, "historical x"); HealingGameTest.near(h, center.y(), point.y, "historical y"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:firefly_floor")
    public void explicitMinimumAppliesAtTheOuterEdgeButNeverOutsideTheRadius(GameTestHelper h) throws Exception {
        try (var t = open(h, Set.of(), 0, .2)) {
            var corpse = victim(t, true, true); var full = neighbor(t, corpse, 3.75, 1000); var half = neighbor(t, corpse, 5.625, 1000); var edge = neighbor(t, corpse, 7.5, 1000); var outside = neighbor(t, corpse, 7.52, 1000); kill(t, corpse);
            HealingGameTest.near(h, full.getHealth(), 989.6, "full plateau"); HealingGameTest.near(h, half.getHealth(), 993.76, "interior interpolates toward floor"); HealingGameTest.near(h, edge.getHealth(), 997.92, "explicit nonzero minimum"); HealingGameTest.near(h, outside.getHealth(), 1000, "outside radius");
            h.assertValueEqual(bursts(t).size(), 3, "three admitted targets"); h.assertTrue(t.runtime.failure().isEmpty(), "floor failed");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:firefly_unknown", maxTicks = 40)
    public void unknownExplosionOutcomeKeepsPriorReloadRewardAndActualDamageWithoutReplay(GameTestHelper h) throws Exception {
        var t = open(h, Set.of(), .1, 0);
        try {
            var corpse = victim(t, true, true); var near = neighbor(t, corpse, 5.625, 1000); kill(t, corpse); t.failAfterDamage = true;
            h.runAfterDelay(5, () -> { try (t) {
                h.assertTrue(t.runtime.failure().isPresent(), "injected world failure"); HealingGameTest.near(h, near.getHealth(), 994.8, "committed health loss");
                HealingGameTest.near(h, stat(t, "a"), 60, "already granted reload persists"); t.projectiles.forEach(EffectProjectile::tick); h.assertValueEqual(bursts(t).size(), 1, "no replay"); h.succeed();
            } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
