package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;

/** Physical paid attacks with explicit synthetic melee/sword credit; this is not a complete sword prototype. */
public class SingularityBladeGameTest {
    static void prepare(JsonObject data) {
        for (String name : List.of("singularity_blade_inputs", "weaken", "strand_defense", "combat_damage", "movement_attributes", "void_overshield")) for (var e : ThreadedSpikeGameTest.json(name).entrySet()) {
            if (e.getKey().equals("version")) continue;
            if (e.getValue().isJsonArray()) { if (!data.has(e.getKey())) data.add(e.getKey(), new JsonArray()); e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v)); }
            else data.add(e.getKey(), e.getValue());
        }
    }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        return open(h, _ -> {});
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, java.util.function.Consumer<JsonObject> edit) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "singularity_blade", d -> { prepare(d); edit.accept(d); }, true);
        try {
            t.owner.setPos(t.owner.chunkPosition().getMinBlockX() + 2.5, t.owner.getY(), t.owner.chunkPosition().getMinBlockZ() + 6.5);
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); var equipment = PlayerEquipment.get(t.owner);
            for (String id : List.of("a", "b")) {
                var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", "none")));
                t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
            }
            PugilistGameTest.draw(t, "primary"); PugilistGameTest.select(t, "test:melee");
            t.runtime.bind(new EffectSource("artifact", "chorus_d2:singularity_blade", owner(t), new BuffInstance.Origin(owner(t), "artifact", "", ""), Set.of(), Map.of("receipt_seconds", new Measure(1, Unit.SECOND))));
            return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static void control(ProjectileGameTest.Harness t, String name) {
        t.runtime.start(new RuleEngine.Signal("test:" + name, new EffectEvent(owner(t), owner(t), new BuffInstance.Origin(owner(t), "control", "", ""), Set.of(), Map.of())));
        settled(t);
    }
    static void settled(ProjectileGameTest.Harness t) { t.h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "Singularity runtime: " + t.runtime.failure()); }
    static Optional<BuffInstance> status(ProjectileGameTest.Harness t, LivingEntity target) {
        return state(t).buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:weaken") && b.key().holder().equals(target.getUUID().toString())).findFirst();
    }
    static EffectProjectile melee(ProjectileGameTest.Harness t) throws Exception {
        TimelostMagazineGameTest.command(t, "chorus ability use chorus_d2:melee");
        near(t.h, PugilistGameTest.energy(t, "test:melee_energy"), 0, "paid actual melee account"); return t.projectiles.getLast();
    }
    static void impact(ProjectileGameTest.Harness t, EffectProjectile p, LivingEntity target) {
        int hits = t.hits.size(); for (int i = 0; i < 20 && !p.isRemoved(); i++) p.tick();
        t.h.assertTrue(p.isRemoved() && t.hits.size() == hits + 1, "one physical contact");
        t.h.assertValueEqual(t.hits.getLast().target(), target.getUUID().toString(), "actual target"); settled(t);
    }
    static void nativeHit(ProjectileGameTest.Harness t, LivingEntity target, double expected) {
        target.setHealth(1000); target.damageCooldownTime = 0;
        target.hurtServer(t.h.getLevel(), target.damageSources().playerAttack(t.owner), 10);
        near(t.h, target.getHealth(), 1000 - expected, "subsequent native health loss"); settled(t);
    }
    @GameCase(environment = "chorus_gametest:singularity_recipients", maxTicks = 145)
    public void paidMeleeAndSwordHitsUseActualRecipientStrengthAndBothLastSixSeconds(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            control(t, "overshield"); var mob = ChainReactionGameTest.target(t, 0, 1000); double speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
            impact(t, melee(t), mob); near(h, mob.getHealth(), 990, "triggering hit is not retroactively amplified");
            h.assertValueEqual(status(t, mob).orElseThrow().origin().ability(), "test:melee", "melee ability attribution");
            var player = NativeMeleeGameTest.player(h); t.entities.add(player); player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); player.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); player.setHealth(1000);
            player.setPos(t.owner.getX() + 2, t.owner.getY() + 6, t.owner.getZ()); t.owner.setPos(t.owner.getX() + 2, t.owner.getY(), t.owner.getZ());
            impact(t, PugilistGameTest.fire(t), player); near(h, player.getHealth(), 990, "real player contact");
            h.assertValueEqual(TimelostMagazineGameTest.mag(t, "a"), 19, "one actual sword fixture round paid");
            near(h, status(t, mob).orElseThrow().components().numbers().get("strength"), .15, "combatant strength"); near(h, status(t, player).orElseThrow().components().numbers().get("strength"), .075, "guardian strength");
            h.assertValueEqual(status(t, mob).orElseThrow().deadline() - state(t).buffs().timeMicros(), 6_000_000L, "six seconds for combatant");
            h.assertValueEqual(status(t, player).orElseThrow().deadline() - state(t).buffs().timeMicros(), 6_000_000L, "six seconds for guardian, overriding ordinary duration");
            near(h, mob.getAttributeValue(Attributes.MOVEMENT_SPEED), speed * .8, "real movement penalty"); t.runtime.unbind("artifact");
            RampageGameTest.at(t, 65, () -> { t.runtime.prepare(); h.assertTrue(status(t, mob).isPresent() && status(t, player).isPresent(), "both survive ordinary Guardian 2.5 seconds"); nativeHit(t, mob, 11.5); nativeHit(t, player, 10.75); });
            t.finish(130, () -> { h.assertTrue(status(t, mob).isEmpty() && status(t, player).isEmpty(), "both expire after six seconds"); near(h, mob.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "native speed restored"); nativeHit(t, mob, 10); nativeHit(t, player, 10); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:singularity_impact")
    public void devourAndInvisibilityGainedOrLostDuringFlightDetermineActualHitEligibility(GameTestHelper h) throws Exception {
        for (String buff : List.of("devour", "invisibility")) for (boolean gain : List.of(false, true)) try (var t = open(h)) {
            var target = ChainReactionGameTest.target(t, 0, 100); if (!gain) control(t, buff); var p = PugilistGameTest.fire(t); control(t, gain ? buff : "clear_void");
            impact(t, p, target); near(h, target.getHealth(), 90, "real hit committed"); h.assertValueEqual(status(t, target).isPresent(), gain, "impact-time qualification");
            h.assertTrue(t.receipts.getLast().observedBuffs().isPresent(), "native receipt retains historical Buff observation");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:singularity_burst")
    public void paidMeleeAndSwordKillsApplyFourMeterBurstWithTeamAndPerTargetStatusChecks(GameTestHelper h) throws Exception {
        for (boolean melee : List.of(false, true)) try (var t = open(h)) {
            control(t, "overshield"); var corpse = ChainReactionGameTest.target(t, 0, 1); var edge = ChainReactionGameTest.target(t, 4, 1000); var outside = ChainReactionGameTest.target(t, 4.02, 1000); var ally = ChainReactionGameTest.target(t, 1, 1000); var denied = ChainReactionGameTest.target(t, 2, 1000);
            t.deniedStatusTargets.add(denied.getUUID().toString()); var scoreboard = h.getLevel().getScoreboard(); var team = scoreboard.addPlayerTeam("singularity-" + UUID.randomUUID());
            try {
                scoreboard.addPlayerToTeam(t.owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(ally.getScoreboardName(), team); impact(t, melee ? melee(t) : PugilistGameTest.fire(t), corpse);
                h.assertTrue(corpse.isDeadOrDying() && t.receipts.getLast().lethal(), "actual confirmed kill"); h.assertTrue(status(t, edge).isPresent(), "inclusive four meter boundary");
                for (var target : List.of(outside, ally, denied, corpse, t.owner)) h.assertTrue(status(t, target).isEmpty(), "excluded or denied recipient " + target.getUUID());
                near(h, edge.getHealth(), 1000, "burst applies status without damage"); h.assertValueEqual(t.hits.size(), 1, "no fabricated burst damage");
                h.assertValueEqual(status(t, edge).orElseThrow().origin(), t.hits.getFirst().source(), "original attack credit"); h.assertValueEqual(t.queries.size(), 1, "one death query"); near(h, t.queries.getFirst().radius(), 4, "four meter range"); nativeHit(t, edge, 11.5);
            } finally { scoreboard.removePlayerTeam(team); }
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:singularity_reactions")
    public void sameHitBuffReactionsCannotRewriteTheQualificationOfItsFollowingKill(GameTestHelper h) throws Exception {
        for (boolean gain : List.of(false, true)) try (var t = open(h)) {
            if (!gain) control(t, "devour"); IncandescentGameTest.bind(t, "reaction", gain ? "test:devour_on_hit" : "test:clear_void_on_hit", "");
            var corpse = ChainReactionGameTest.target(t, 0, 1); var neighbor = ChainReactionGameTest.target(t, 2, 1000); impact(t, PugilistGameTest.fire(t), corpse);
            boolean currentDevour = state(t).buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("test:devour_presence"));
            h.assertValueEqual(currentDevour, gain, "reaction really changed live state"); h.assertValueEqual(status(t, neighbor).isPresent(), !gain, "kill uses same damage boundary observation");
            h.assertValueEqual(t.queries.size(), gain ? 0 : 1, "only historically qualified kill bursts");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:singularity_history")
    public void corpseRemovalBeforeFactDeliveryStillUsesTheActualHistoricalDeathCenter(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            control(t, "devour"); var corpse = ChainReactionGameTest.target(t, 0, 1); var neighbor = ChainReactionGameTest.target(t, 2, 1000); var point = corpse.position();
            t.afterDamageReceipt = (_, receipt) -> { if (receipt.lethal()) { corpse.setPos(corpse.getX() + 30, corpse.getY(), corpse.getZ()); corpse.discard(); } };
            impact(t, PugilistGameTest.fire(t), corpse); h.assertTrue(corpse.isRemoved() && status(t, neighbor).isPresent(), "burst survives corpse removal");
            var center = ((TargetQuery.PositionCenter) t.queries.getFirst().center()).position().orElseThrow(); near(h, center.x(), point.x, "saved x"); near(h, center.y(), point.y, "saved y"); near(h, center.z(), point.z, "saved z");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:singularity_detach", maxTicks = 40)
    public void weaponStowRetainsEligibilityWhileArtifactDetachPreventsFutureHitsAndKeepsOldWeaken(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            control(t, "devour"); var first = ChainReactionGameTest.target(t, 0, 100); var shot = PugilistGameTest.fire(t); PugilistGameTest.draw(t, "secondary"); impact(t, shot, first);
            h.assertValueEqual(status(t, first).orElseThrow().origin().weapon(), "a", "stowed sword origin"); first.setPos(first.getX() + 2, first.getY(), first.getZ());
            RampageGameTest.at(t, 5, () -> { var other = ChainReactionGameTest.target(t, 0, 100); var pending = PugilistGameTest.fire(t); t.runtime.unbind("artifact"); impact(t, pending, other); h.assertTrue(status(t, first).isPresent() && status(t, other).isEmpty(), "detach cancels eligibility without undoing old debuff"); });
            t.finish(10, () -> h.assertTrue(status(t, first).isPresent(), "old status survives source removal"));
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:singularity_unknown")
    public void unknownPhysicalDamageKeepsHealthLossWithoutInventingWeakenOrReplayingTheAttack(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            control(t, "devour"); var target = ChainReactionGameTest.target(t, 0, 100); t.failAfterDamage = true; var p = PugilistGameTest.fire(t);
            for (int i = 0; i < 20 && !p.isRemoved(); i++) p.tick();
            h.assertTrue(t.runtime.failure().isPresent() && p.isRemoved(), "unknown outcome terminates flight and session"); near(h, target.getHealth(), 90, "committed damage retained"); h.assertTrue(status(t, target).isEmpty(), "no invented hit facts"); p.tick(); h.assertValueEqual(t.hits.size(), 1, "no retry after uncertainty");
        } h.succeed();
    }
}
