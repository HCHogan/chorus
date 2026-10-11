package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class TimelostMagazineTest {
    // Unknown original-game values remain mandatory parameters; these are deliberately synthetic.
    static Map<String, Measure> calibration() { return Map.of("base_super_gain", new Measure(.01, Unit.CHARGE), "enhanced_super_gain", new Measure(.02, Unit.CHARGE)); }
    static CompiledEffects program(java.util.function.Consumer<JsonObject> edit) throws Exception {
        var weapon = json("timelost_weapon"); edit.accept(weapon);
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, weapon).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("timelost_magazine")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("timelost_test_calibration")).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("reload_insert_rounds")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("overflow")).getOrThrow()));
    }
    static class Harness extends ChainReactionTest.Harness {
        String overflow; boolean failRefill;
        Harness() throws Exception { this(_ -> {}); }
        Harness(java.util.function.Consumer<JsonObject> edit) throws Exception { super(program(edit), "test:unused_burst", false, calibration(), calibration()); select("test:super"); }
        @Override void equip(String normal, String enhanced, String draw) {
            var loadout = new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", normal, "overflow", overflow == null ? "none" : overflow), a),
                    "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", enhanced, "overflow", "none"), b)), Optional.of("test:" + draw));
            session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), loadout).signal()); healthy();
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if (request.command() instanceof WeaponReload.Verify q) return new WeaponReload.Verified(q, true);
            if (failRefill && request.command() instanceof Action.CueCommand c && c.cue().equals("test:refilled")) throw new IllegalStateException("unknown post-refill reaction");
            return super.world(request);
        }
        void select(String id) { session.start(now(), new AbilityChange("player", state().abilities().getOrDefault("player", AbilityLoadout.EMPTY), id == null ? AbilityLoadout.EMPTY : new AbilityLoadout(Map.of("chorus_d2:super", id))).signal()); healthy(); }
        void cast() { session.start(now(), new AbilityUse.Request("player", "chorus_d2:super", "cast/" + ++sequence, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of())).signal()); healthy(); }
        void event(String name, String weapon, double rounds) { session.start(now(), new RuleEngine.Signal("test:" + name, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", weapon, ""), Set.of(), Map.of("amount", new Measure(rounds, Unit.ROUND))))); healthy(); }
        void spend(String weapon, int rounds) { event("spend", weapon, rounds); }
        int mag(String weapon) { return state().ammunition().get(weapon).magazine(); }
        int reserve(String weapon) { return state().ammunition().get(weapon).reserve().orElseThrow().rounds(); }
        int capacity(String weapon) { return program.ammoCapacity(state(), weapon).capacity(); }
        double energy(String resource) { return state().resources().get(new ResourceState.Key("player", resource)).value(); }
        Optional<BuffInstance> active(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:timelost_magazine_active") && b.origin().weapon().equals(weapon)).findFirst(); }
        void reload() { session.start(now(), new WeaponReload.Request("player", "reload/" + ++sequence).signal()); healthy(); }
        void pickup() { session.start(now(), new RuleEngine.Signal("chorus:pickup", new EffectEvent("player", "player", new BuffInstance.Origin("player", "pickup", "", ""), Set.of(), Map.of(), Map.of(), Map.of("pickup_kind", "chorus_d2:special_ammo")))); healthy(); }
    }
    @Test void paidSuperExpiryDoublesAndRefillsBothHeldAndStowedWeaponsWithoutManualReloadFacts() throws Exception {
        var h = new Harness(); h.spend("a", 2); h.spend("b", 4); h.cast(); assertEquals(0, h.energy("test:super_energy")); h.until(199_999);
        assertEquals(5, h.capacity("a")); assertEquals(3, h.mag("a")); assertEquals(1, h.mag("b")); h.until(200_000);
        assertEquals(10, h.capacity("a")); assertEquals(10, h.capacity("b")); assertEquals(10, h.mag("a")); assertEquals(10, h.mag("b")); assertEquals(23, h.reserve("a")); assertEquals(21, h.reserve("b"));
        assertEquals(20_200_000, h.active("a").orElseThrow().deadline()); assertEquals(21_200_000, h.active("b").orElseThrow().deadline());
        assertEquals(1, Collections.frequency(h.cues, "test:super_ended")); assertEquals(2, Collections.frequency(h.cues, "test:refilled")); assertFalse(h.cues.contains("test:reloaded"));
        h.equip("normal", "enhanced", "secondary"); h.until(20_199_999); assertEquals(10, h.capacity("a")); h.until(20_200_000); assertEquals(5, h.capacity("a")); assertEquals(10, h.mag("a")); assertEquals(10, h.capacity("b"));
        h.until(21_200_000); assertEquals(5, h.capacity("b")); assertEquals(10, h.mag("b"));
    }
    @Test void rejectedAndExplicitlyCancelledSupersDoNotPretendToExpireAndASecondExpiryRefreshes() throws Exception {
        var h = new Harness(); h.cast(); h.cast(); h.until(200_000); h.cast(); h.until(400_000); assertEquals(1, Collections.frequency(h.cues, "test:super_ended"));
        h.until(5_000_000); h.event("charge", "", 0); h.cast(); h.until(5_200_000); assertEquals(25_200_000, h.active("a").orElseThrow().deadline()); assertEquals(10, h.capacity("a"));
        var cancelled = new Harness(); cancelled.cast(); cancelled.event("cancel_super", "", 0); cancelled.until(300_000); assertTrue(cancelled.active("a").isEmpty()); assertFalse(cancelled.cues.contains("test:super_ended"));
    }
    @Test void finiteAndUnlimitedReservesClipWithoutGeneratingAmmoOrDeletingExistingOverflow() throws Exception {
        for (boolean infinite : List.of(false, true)) {
            var h = new Harness(w -> { var ammo = w.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("ammunition");
                if (infinite) ammo.addProperty("reserves", "unlimited"); else ammo.getAsJsonObject("reserves").addProperty("rounds", 2); });
            h.cast(); h.until(200_000); assertEquals(infinite ? 10 : 7, h.mag("a")); assertEquals(10, h.capacity("a")); if (infinite) assertTrue(h.state().ammunition().get("a").reserve().isEmpty()); else assertEquals(0, h.reserve("a"));
        }
        var h = new Harness(); h.overflow = "enhanced"; h.equip("normal", "enhanced", "primary"); h.pickup(); assertEquals(11, h.mag("a")); h.cast(); h.until(200_000);
        assertEquals(11, h.mag("a")); assertEquals(24, h.reserve("a")); h.until(20_200_000); assertEquals(11, h.mag("a"));
    }
    @Test void overflowReadsTheDoubledBaseRatherThanTheUnmodifiedCapacity() throws Exception {
        for (String overflow : List.of("normal", "enhanced")) {
            var h = new Harness(); h.overflow = overflow; h.equip("normal", "enhanced", "primary"); h.cast(); h.until(200_000); h.pickup();
            assertEquals(overflow.equals("normal") ? 20 : 22, h.mag("a")); assertEquals(10, h.capacity("a")); assertEquals(5, h.state().ammunition().get("a").capacity());
            h.until(20_200_000); assertEquals(5, h.capacity("a")); assertEquals(overflow.equals("normal") ? 20 : 22, h.mag("a"));
        }
    }
    @Test void foundVerdictLoadsTwoShellsOnlyDuringItsWindowAndNextStepResamplesAfterExpiry() throws Exception {
        var h = new Harness(); h.equip("found", "enhanced_found", "primary"); h.cast(); h.until(200_000); h.spend("a", 10); h.reload();
        assertEquals(2, h.state().reloads().get("player").portion().orElseThrow().rounds()); h.until(400_000); assertEquals(2, h.mag("a")); h.until(800_000); assertEquals(10, h.mag("a")); assertEquals(15, h.reserve("a"));
        h.spend("a", 10); h.until(20_000_000); h.reload(); h.until(20_200_000);
        assertEquals(2, h.mag("a"), "accepted insertion keeps two rounds"); assertEquals(5, h.capacity("a")); assertEquals(1, h.state().reloads().get("player").portion().orElseThrow().rounds()); h.until(20_500_000); assertEquals(5, h.mag("a"));
        h.equip("found", "enhanced_found", "secondary"); h.spend("b", 10); h.reload(); assertEquals(2, h.state().reloads().get("player").portion().orElseThrow().rounds());
        var plain = new Harness(); plain.cast(); plain.until(200_000); plain.spend("a", 10); plain.reload(); assertEquals(1, plain.state().reloads().get("player").portion().orElseThrow().rounds());
    }
    @Test void realWeaponKillUsesItsOwnConfiguredGainAndCurrentSuperSelection() throws Exception {
        var h = new Harness(); h.cast(); h.until(200_000); h.entity("first", 1, 0); h.kill("first"); assertEquals(.01, h.energy("test:super_energy"), 1e-12);
        h.until(400_000); h.equip("normal", "enhanced", "secondary"); h.entity("second", 1, 0); h.fire(); h.select("test:alternate_super"); h.impact("second");
        assertEquals(.02, h.energy("test:alternate_super_energy"), 1e-12); assertEquals(.01, h.energy("test:super_energy"), 1e-12);
        h.until(600_000); h.select(null); h.entity("third", 1, 0); h.kill("third"); assertEquals(.02, h.energy("test:alternate_super_energy"), 1e-12);
    }
    @Test void nonlethalAndUncreditedKillsDoNotGiveEnergyAndOtherWeaponsCannotDoubleTheReward() throws Exception {
        var h = new Harness(w -> w.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action").add("kill_tags", new JsonArray()));
        h.cast(); h.until(200_000); h.entity("first", 1, 0); h.kill("first"); assertEquals(0, h.energy("test:super_energy"));
        var nonlethal = new Harness(); nonlethal.cast(); nonlethal.until(200_000); nonlethal.entity("target", 100, 0); nonlethal.fire(); nonlethal.impact("target"); assertEquals(0, nonlethal.energy("test:super_energy"));
    }
    @Test void equipmentAtExpiryDecidesRecipientsAndDetachingAnActivePerkRestoresCapacityWithoutLosingRounds() throws Exception {
        var h = new Harness(); h.cast(); h.equip("none", "enhanced", "secondary"); h.until(200_000); assertEquals(5, h.mag("a")); assertEquals(10, h.mag("b")); assertTrue(h.active("a").isEmpty());
        h.equip("normal", "none", "primary"); assertEquals(5, h.capacity("b")); assertEquals(10, h.mag("b")); assertTrue(h.active("b").isEmpty());
        h.until(400_000); assertTrue(h.active("a").isEmpty(), "equipping after expiry cannot replay old fact");
        var late = new Harness(); late.equip("none", "none", "primary"); late.cast(); late.equip("normal", "enhanced", "secondary"); late.until(200_000); assertEquals(10, late.mag("a")); assertEquals(10, late.mag("b"));
    }
    @Test void checkpointRestoresActiveSuperAndWindowsWithoutRepeatingReserveTransfers() throws Exception {
        var h = new Harness(); h.cast(); h.until(100_000); h.restore(); h.until(200_000); assertEquals(10, h.mag("a")); assertEquals(25, h.reserve("a"));
        h.spend("a", 1); h.restore(); h.until(1_000_000); assertEquals(9, h.mag("a")); assertEquals(25, h.reserve("a")); assertEquals(1, Collections.frequency(h.cues, "test:super_ended"));
        h.until(20_200_000); assertEquals(5, h.capacity("a")); assertEquals(9, h.mag("a"));
    }
    @Test void unknownReactionAfterRefillPreservesCommittedAmmoAndCannotReplay() throws Exception {
        var h = new Harness(); h.cast(); h.failRefill = true; assertThrows(IllegalStateException.class, () -> h.until(200_000));
        assertEquals(10, h.mag("a")); assertEquals(25, h.reserve("a")); assertThrows(IllegalStateException.class, () -> h.until(400_000)); assertEquals(10, h.mag("a")); assertEquals(25, h.reserve("a"));
    }
}
