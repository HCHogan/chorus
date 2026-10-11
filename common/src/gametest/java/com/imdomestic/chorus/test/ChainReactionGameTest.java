package com.imdomestic.chorus.test;

import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
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

/** Physical weapon kills and delayed, receipt-centered explosions with explicit synthetic calibration. */
public class ChainReactionGameTest {
    static void prepare(JsonObject data, Set<String> tags) {
        for (String name : List.of("chain_reaction_test_calibration", "chain_reaction_weapon")) {
            var fragment = ThreadedSpikeGameTest.json(name);
            if (name.equals("chain_reaction_weapon")) {
                var shot = fragment.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action");
                var selected = new JsonArray(); selected.add("chorus:weapon_damage"); tags.forEach(selected::add); shot.add("tags", selected);
            }
            for (var entry : fragment.entrySet()) {
                if (Set.of("version", "imports").contains(entry.getKey())) continue;
                if (entry.getValue().isJsonArray()) { if (!data.has(entry.getKey())) data.add(entry.getKey(), new JsonArray()); entry.getValue().getAsJsonArray().forEach(e -> data.getAsJsonArray(entry.getKey()).add(e)); }
                else data.add(entry.getKey(), entry.getValue());
            }
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, Set<String> tags, double delay) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "chain_reaction", d -> prepare(d, tags), true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX() + 2.5, t.owner.getY(), t.owner.chunkPosition().getMinBlockZ() + 6.5);
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
            for (String id : List.of("a", "b")) {
                boolean heavy = id.equals("b");
                var parameters = Map.of("pve_damage", new Measure(heavy ? 109.2 : 67.2, Unit.DAMAGE), "pvp_damage", new Measure(heavy ? 72.8 : 44.8, Unit.DAMAGE),
                        "full_radius", new Measure(heavy ? 3 : 2.4, Unit.METER), "radius", new Measure(heavy ? 7 : 6, Unit.METER), "delay", new Measure(delay, Unit.SECOND));
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", heavy ? "enhanced" : "normal"), parameters));
                t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, heavy ? "test:secondary" : "test:primary", 0, equipment.revision());
            }
            PugilistGameTest.draw(t, "primary"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static LivingEntity target(ProjectileGameTest.Harness t, double x, float health) {
        var e = t.cow(2.5, 46, 3.5); e.setPos(t.owner.getX() + x, t.owner.getY() + 6, t.owner.getZ());
        e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(health);
        t.h.assertTrue(t.h.getLevel().isPositionEntityTicking(e.blockPosition()), "Chain Reaction target must be simulated"); return e;
    }
    static List<DamageCommand> bursts(ProjectileGameTest.Harness t) { return t.hits.stream().filter(d -> d.tags().contains("chorus_d2:chain_reaction_damage")).toList(); }
    static void kill(ProjectileGameTest.Harness t, LivingEntity corpse) throws Exception { PugilistGameTest.impact(t, PugilistGameTest.fire(t), corpse); }
    @GameCase(environment = "chorus_gametest:chain_variants")
    public void bothAmmoRadiiAndEnhancedDamageUsePhysicalVictimsAndExcludeAllies(GameTestHelper h) throws Exception {
        for (boolean heavy : List.of(false, true)) try (var t = open(h, Set.of(heavy ? "chorus_d2:void_damage" : "chorus_d2:arc_damage"), 0)) {
            if (heavy) PugilistGameTest.draw(t, "secondary"); double full = heavy ? 3 : 2.4, radius = heavy ? 7 : 6, damage = heavy ? 12.012 : 6.72;
            var corpse = target(t, 0, 5); var inner = target(t, full, 1000); var middle = target(t, (full + radius) / 2, 1000); var edge = target(t, radius, 1000); var outside = target(t, radius + .02, 1000); var ally = target(t, 1, 1000);
            var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("chain-" + UUID.randomUUID());
            try {
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team); kill(t, corpse);
                HealingGameTest.near(h, inner.getHealth(), 1000 - damage, "plateau damage"); HealingGameTest.near(h, middle.getHealth(), 1000 - damage / 2, "synthetic interior curve");
                HealingGameTest.near(h, edge.getHealth(), 1000, "zero edge"); HealingGameTest.near(h, outside.getHealth(), 1000, "outside"); HealingGameTest.near(h, ally.getHealth(), 1000, "team exclusion");
                h.assertValueEqual(bursts(t).size(), 2, "no zero damage hit at edge"); h.assertTrue(bursts(t).stream().allMatch(d -> d.source().weapon().equals(heavy ? "b" : "a") && d.tags().contains(heavy ? "chorus_d2:void_damage" : "chorus_d2:arc_damage")), "weapon or element credit");
                h.assertValueEqual(inner.getLastDamageSource().getEntity(), t.owner, "native attacker"); h.assertTrue(t.runtime.failure().isEmpty(), "blast failed");
            } finally { scoreboard.removePlayerTeam(team); }
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:chain_history", maxTicks = 40)
    public void delayedBlastSurvivesCorpseRemovalAndPerkReplacementWithCapturedBonus(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:solar_damage"), .1);
        try {
            IncandescentGameTest.bind(t, "bonus", "test:chain_bonus", "a"); var corpse = target(t, 0, 5); var near = target(t, 1, 1000); double x = corpse.getX(); kill(t, corpse);
            h.assertTrue(bursts(t).isEmpty(), "delay must be observed"); corpse.setPos(x + 30, corpse.getY(), corpse.getZ()); corpse.discard();
            ReconstructionGameTest.replace(t, "primary", "none"); t.runtime.unbind("bonus"); PugilistGameTest.draw(t, "secondary");
            t.finish(5, () -> {
                HealingGameTest.near(h, near.getHealth(), 989.92, "captured source bonus"); h.assertValueEqual(bursts(t).size(), 1, "one detached explosion");
                HealingGameTest.near(h, ((TargetQuery.PositionCenter) t.queries.getFirst().center()).position().orElseThrow().x(), x, "original death center");
                h.assertValueEqual(bursts(t).getFirst().source().weapon(), "a", "stowed weapon credit");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:chain_feedback", maxTicks = 50)
    public void derivedPhysicalKillsContinueAtEachNewCenterWithoutGlobalSuppression(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:strand_damage"), .1);
        try {
            var first = target(t, 0, 5); var second = target(t, 4, 1); var third = target(t, 8, 1); var far = target(t, 11, 1000); double x = first.getX(); kill(t, first);
            t.finish(10, () -> {
                h.assertTrue(second.isDeadOrDying() && third.isDeadOrDying(), "derived kills failed"); h.assertValueEqual(t.queries.size(), 3, "three actual death centers");
                HealingGameTest.near(h, far.getHealth(), 994.4, "third blast reaches beyond initial radius");
                for (int i = 0; i < 3; i++) HealingGameTest.near(h, ((TargetQuery.PositionCenter) t.queries.get(i).center()).position().orElseThrow().x(), x + i * 4, "per receipt center");
                h.assertTrue(bursts(t).stream().allMatch(d -> d.source().weapon().equals("a") && d.proc().deny().isEmpty()), "chain attribution or suppression changed");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:chain_eligibility")
    public void threadlingExclusionAndInFlightRemovalRespectActualWeaponEligibility(GameTestHelper h) throws Exception {
        for (String kind : List.of("stow", "remove", "threadling", "missing", "ambiguous")) {
            var tags = switch (kind) { case "threadling" -> Set.of("chorus_d2:strand_damage", "chorus_d2:threadling_damage"); case "missing" -> Set.<String>of(); case "ambiguous" -> Set.of("chorus_d2:solar_damage", "chorus_d2:arc_damage"); default -> Set.of("chorus_d2:arc_damage"); };
            try (var t = open(h, tags, 0)) {
                var corpse = target(t, 0, 5); var near = target(t, 1, 1000); var shot = PugilistGameTest.fire(t); PugilistGameTest.draw(t, "secondary");
                if (kind.equals("remove")) ReconstructionGameTest.replace(t, "primary", "none"); PugilistGameTest.impact(t, shot, corpse);
                h.assertValueEqual(bursts(t).size(), kind.equals("stow") ? 1 : 0, "only retained eligible source explodes");
                HealingGameTest.near(h, near.getHealth(), kind.equals("stow") ? 993.28 : 1000, "eligibility damage");
                h.assertValueEqual(t.cues.size(), kind.equals("missing") || kind.equals("ambiguous") ? 1 : 0, "unresolved element diagnostic"); h.assertTrue(t.runtime.failure().isEmpty(), "eligibility failed");
            }
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:chain_unknown", maxTicks = 40)
    public void unknownNativeBlastResultRetainsDamageWithoutReplaying(GameTestHelper h) throws Exception {
        var t = open(h, Set.of("chorus_d2:arc_damage"), .1);
        try {
            var corpse = target(t, 0, 5); var near = target(t, 1, 1000); kill(t, corpse); t.failAfterDamage = true;
            h.runAfterDelay(5, () -> { try (t) {
                h.assertTrue(t.runtime.failure().isPresent(), "missing injected world failure"); HealingGameTest.near(h, near.getHealth(), 993.28, "known native mutation");
                int count = bursts(t).size(); t.projectiles.forEach(EffectProjectile::tick); h.assertValueEqual(bursts(t).size(), count, "unknown blast replayed"); h.assertValueEqual(count, 1, "one blast attempt"); h.succeed();
            } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
}
