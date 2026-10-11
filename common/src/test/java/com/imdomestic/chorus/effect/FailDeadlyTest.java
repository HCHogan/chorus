package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class FailDeadlyTest {
    static Map<String, Measure> calibration() { return Map.of("receipt_seconds", new Measure(1, Unit.SECOND), "enhanced_range", new Measure(18, Unit.STAT_POINT), "enhanced_aim_assist", new Measure(6, Unit.STAT_POINT)); }
    static CompiledEffects program(int capacity, java.util.function.Consumer<JsonObject> edit) throws Exception {
        var w = json("fail_deadly_weapon"); var ammo = w.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition"); ammo.addProperty("capacity", capacity); ammo.addProperty("magazine", capacity); edit.accept(w);
        var fragments = new ArrayList<EffectProgram>(); fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, w).getOrThrow());
        for (String name : List.of("fail_deadly", "fail_deadly_test_calibration", "weapon_stats", "reload_insert_rounds", "overflow")) fragments.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json(name)).getOrThrow());
        return CompiledEffects.link(fragments);
    }
    static class Harness extends ChainReactionTest.Harness {
        String overflow; DamageReceipt receipt; DamageCommand command;
        Harness() throws Exception { this(5, _ -> {}); }
        Harness(int capacity, java.util.function.Consumer<JsonObject> edit) throws Exception {
            super(program(capacity, edit), "test:unused", false, calibration(), calibration());
            afterReceipt = (d, r) -> { command = d; receipt = r; };
        }
        @Override void equip(String aPerk, String bPerk, String draw) {
            var next = new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", aPerk, "overflow", overflow == null ? "none" : overflow), a),
                    "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", bPerk, "overflow", "none"), b)), Optional.of("test:" + draw));
            session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); healthy();
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) { return request.command() instanceof WeaponReload.Verify q ? new WeaponReload.Verified(q, true) : super.world(request); }
        Optional<BuffInstance> counter(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:fail_deadly_state") && b.origin().weapon().equals(weapon)).findFirst(); }
        double stacks(String weapon) { return counter(weapon).map(b -> b.components().numbers().get("hits")).orElse(0d); }
        int capacity(String weapon) { return program.ammoCapacity(state(), weapon).capacity(); }
        int mag(String weapon) { return state().ammunition().get(weapon).magazine(); }
        int reserve(String weapon) { return state().ammunition().get(weapon).reserve().orElseThrow().rounds(); }
        void hit() { entity("target", 1000, 0); fire(); impact("target"); }
        void reload() { session.start(now(), new WeaponReload.Request("player", "reload/" + ++sequence).signal()); healthy(); }
        void nextHit() { until(now() + 200_000); var loadout = state().equipment().get("player"); if (mag(loadout.slots().get(loadout.drawn().orElseThrow()).instance()) == 0) { reload(); until(now() + 2_000_000); } hit(); }
        double stat(String name, String weapon) { return program.calculate(state(), "player", new EffectEvent("player", "target", new BuffInstance.Origin("player", "query", weapon, ""), Set.of(), Map.of()), "chorus_d2:weapon_" + name, new Measure(10, Unit.STAT_POINT), List.of()).output().value(); }
        void replay(List<RuleEngine.Signal> signals) { session.observe(now(), signals); healthy(); }
        void pickup() { session.start(now(), new RuleEngine.Signal("chorus:pickup", new EffectEvent("player", "player", new BuffInstance.Origin("player", "pickup", "", ""), Set.of(), Map.of(), Map.of(), Map.of("pickup_kind", "chorus_d2:special_ammo")))); healthy(); }
    }
    @Test void eachActualDamageInstanceAddsOneOrdinaryRoundUpToTheStableBaseLimitWithoutRefilling() throws Exception {
        for (int base : List.of(1, 2, 3, 5, 25)) {
            var h = new Harness(base, _ -> {});
            for (int i = 1; i <= base; i++) { h.nextHit(); assertEquals(i, h.stacks("a")); assertEquals(base + i, h.capacity("a")); assertEquals(base, h.state().ammunition().get("a").capacity()); }
            assertEquals(0, h.mag("a")); h.reload(); h.until(h.now() + 5_000_000); assertEquals(Math.min(base * 2, 30), h.mag("a"));
            h.nextHit(); assertEquals(base, h.stacks("a")); assertEquals(base * 2, h.capacity("a")); assertEquals(25, h.stat("range", "a")); assertEquals(15, h.stat("aim_assist", "a"));
        }
    }
    @Test void normalRocketAndBipodUseTwoDamageInstancesPerAdditionalRocket() throws Exception {
        for (boolean bipod : List.of(false, true)) {
            int base = bipod ? 2 : 1; var h = new Harness(base, _ -> {}); h.equip(bipod ? "bipod" : "rocket", "enhanced", "primary");
            for (int i = 1; i <= base * 2; i++) { h.nextHit(); assertEquals(i, h.stacks("a")); assertEquals(base + i / 2, h.capacity("a")); }
            assertEquals(base * 2, h.counter("a").orElseThrow().components().numbers().get("limit")); assertEquals(25, h.stat("range", "a"));
        }
    }
    @Test void enhancedUnknownStatEndpointsAreExplicitAndStatsAreWeaponScoped() throws Exception {
        var h = new Harness(3, _ -> {}); h.equip("normal", "enhanced", "secondary"); for (int i = 0; i < 3; i++) h.nextHit();
        assertEquals(6, h.capacity("b")); assertEquals(28, h.stat("range", "b")); assertEquals(16, h.stat("aim_assist", "b")); assertEquals(10, h.stat("range", "a")); assertEquals(10, h.stat("aim_assist", "a"));
    }
    @Test void realWeaponKillClearsAllBonusesButRetainsAlreadyLoadedOverflowAndRejectsOldReceiptReplay() throws Exception {
        var h = new Harness(); h.overflow = "normal"; h.equip("normal", "enhanced", "primary");
        for (int i = 0; i < 5; i++) h.nextHit(); h.pickup(); int loaded = h.mag("a"); h.until(h.now() + 200_000); h.entity("victim", 1, 0); h.kill("victim");
        var killFacts = DamageFacts.from(h.command, h.receipt); assertEquals(0, h.stacks("a")); assertEquals(5, h.capacity("a")); assertEquals(10, h.stat("range", "a")); assertEquals(loaded - 1, h.mag("a"));
        h.replay(killFacts); assertEquals(0, h.stacks("a")); h.nextHit(); assertEquals(1, h.stacks("a")); h.replay(killFacts); assertEquals(1, h.stacks("a"), "old death cannot reset a new cycle");
    }
    @Test void duplicateDamageDoesNotDoubleCountAndFiniteReceiptHistoryDoesNotErasePersistentProgress() throws Exception {
        var h = new Harness(); h.hit(); var facts = DamageFacts.from(h.command, h.receipt); h.replay(facts); assertEquals(1, h.stacks("a"));
        h.until(1_000_000); assertEquals(1, h.stacks("a")); assertTrue(h.state().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("chorus_d2:fail_deadly_receipts")));
        h.until(100_000_000); assertEquals(1, h.stacks("a")); h.hit(); assertEquals(2, h.stacks("a"));
    }
    @Test void stowingKeepsProgressAndLateDamageWhileRemovingTheSourceCleansStateAndFutureEligibility() throws Exception {
        for (boolean remove : List.of(false, true)) {
            var h = new Harness(); h.hit(); h.until(200_000); h.fire(); h.equip(remove ? "none" : "normal", "enhanced", "secondary"); h.impact("target");
            assertEquals(remove ? 0 : 2, h.stacks("a")); assertEquals(0, h.stacks("b")); h.until(10_000_000); assertEquals(remove ? 5 : 7, h.capacity("a"));
        }
    }
    @Test void overflowUsesExpandedCapacityAndFoundShotgunInsertionUsesConfiguredBonus() throws Exception {
        var h = new Harness(); h.overflow = "enhanced"; h.equip("shotgun", "enhanced", "primary"); for (int i = 0; i < 5; i++) h.nextHit(); h.pickup();
        assertEquals(22, h.mag("a")); assertEquals(8, h.reserve("a")); assertEquals(10, h.capacity("a"));
        var shotgun = new Harness(); shotgun.equip("shotgun", "enhanced", "primary"); for (int i = 0; i < 5; i++) shotgun.nextHit(); shotgun.reload();
        assertEquals(2, shotgun.state().reloads().get("player").portion().orElseThrow().rounds()); shotgun.until(shotgun.now() + 200_000); assertEquals(2, shotgun.mag("a")); assertEquals(28, shotgun.reserve("a"));
    }
    @Test void nonWeaponAndZeroImmuneOrUnappliedDamageCannotCreateProgress() throws Exception {
        var h = new Harness(); var origin = new BuffInstance.Origin("player", "shot", "a", "");
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED, DamageReceipt.Outcome.APPLIED)) {
            var command = new DamageCommand("target", origin, 10, "minecraft:generic", Set.of("chorus:weapon_damage"), Set.of("chorus:weapon_kill"), false);
            h.replay(DamageFacts.from(command, new DamageReceipt("zero/" + outcome, outcome, 0, 0, 0, Optional.empty(), false)));
        }
        var ability = new DamageCommand("target", origin, 10, "minecraft:generic", Set.of("chorus:ability_damage"), Set.of(), false);
        h.replay(DamageFacts.from(ability, new DamageReceipt("ability", DamageReceipt.Outcome.APPLIED, 0, 0, 10, Optional.empty(), false))); assertEquals(0, h.stacks("a"));
    }
    @Test void checkpointRestoresCounterAndReceiptIdentityAndUnknownDamageNeverInventsAStack() throws Exception {
        var h = new Harness(); h.hit(); var facts = DamageFacts.from(h.command, h.receipt); h.restore(); h.replay(facts); assertEquals(1, h.stacks("a")); h.until(200_000); h.hit(); assertEquals(2, h.stacks("a"));
        h.restore(); h.until(10_000_000); assertEquals(7, h.capacity("a"));
        var failed = new Harness(5, w -> { var attack = w.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action"); attack.getAsJsonArray("tags").add("test:unused"); });
        failed.failBurst = true; failed.entity("target", 100, 0); failed.fire(); assertThrows(IllegalStateException.class, () -> failed.impact("target")); assertEquals(90, failed.health.get("target")); assertEquals(0, failed.stacks("a"));
    }
}
