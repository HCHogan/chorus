package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.effect.weapon.WeaponReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Source-specific damage is explicit; the reload curve and world scale are synthetic test calibration. */
class FireflyTest {
    static Map<String, Measure> calibration(double floor, double delay) {
        return Map.of("pve_damage", new Measure(104, Unit.DAMAGE), "pvp_damage", new Measure(80, Unit.DAMAGE),
                "full_radius", new Measure(3.75, Unit.METER), "radius", new Measure(7.5, Unit.METER), "delay", new Measure(delay, Unit.SECOND),
                "pve_minimum_falloff", new Measure(floor, Unit.MULTIPLIER), "pvp_minimum_falloff", new Measure(0, Unit.MULTIPLIER));
    }
    static CompiledEffects program(Set<String> tags, boolean credit) throws Exception {
        var weapon = json("firefly_weapon"); var fire = weapon.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");
        for (int i : List.of(2, 3)) {
            var attack = fire.get(i).getAsJsonObject().getAsJsonObject("action"); var selected = new JsonArray(); selected.add("chorus:weapon_damage"); if (i == 3) selected.add("chorus:precision"); tags.forEach(selected::add); attack.add("tags", selected);
            if (!credit) attack.add("kill_tags", new JsonArray());
        }
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("firefly")).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("firefly_test_calibration")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("weapon_stats")).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, weapon).getOrThrow()));
    }
    static class Harness extends DragonflyTest.Harness {
        Harness() throws Exception { this(Set.of("chorus_d2:kinetic_damage"), true, false, 0, .1); }
        Harness(Set<String> tags, boolean credit, boolean pvp, double floor, double delay) throws Exception {
            super(program(tags, credit), "chorus_d2:firefly_damage", pvp, calibration(floor, delay), calibration(floor, delay));
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return request.command() instanceof WeaponReload.Verify q ? new WeaponReload.Verified(q, true) : super.world(request);
        }
        Optional<BuffInstance> buff(String weapon) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:firefly_reload") && b.origin().weapon().equals(weapon)).findFirst(); }
        double reloadStat(String weapon) {
            var event = new EffectEvent("player", "enemy", new BuffInstance.Origin("player", "query", weapon, ""), Set.of(), Map.of());
            return program.calculate(state(), "player", event, "chorus_d2:weapon_reload", new Measure(10, Unit.STAT_POINT), List.of()).output().value();
        }
        void reload() { session.start(now(), new WeaponReload.Request("player", "reload/" + ++sequence).signal()); healthy(); }
    }
    @Test void fixedSolarExplosionIgnoresIncomingElementWhileEnhancedOnlyChangesReload() throws Exception {
        for (var tags : List.of(Set.of("chorus_d2:kinetic_damage"), Set.of("chorus_d2:arc_damage"), Set.of("chorus_d2:void_damage", "chorus_d2:stasis_damage"), Set.<String>of()))
            for (boolean pvp : List.of(false, true)) for (boolean enhanced : List.of(false, true)) {
                var h = new Harness(tags, true, pvp, 0, .1); if (enhanced) h.equip("normal", "enhanced", "secondary");
                h.entity("corpse", 5, 0); h.entity("full", 1000, 3.75); h.entity("half", 1000, 5.625); h.entity("edge", 1000, 7.5); h.entity("outside", 1000, 7.51);
                h.precisionKill("corpse"); assertTrue(h.bursts().isEmpty()); String weapon = enhanced ? "b" : "a";
                assertEquals(enhanced ? 65 : 60, h.reloadStat(weapon)); assertEquals(10, h.reloadStat(enhanced ? "a" : "b")); h.until(100_000);
                double base = pvp ? 8 : 10.4; assertEquals(1000 - base, h.health.get("full"), 1e-9); assertEquals(1000 - base / 2, h.health.get("half"), 1e-9);
                assertEquals(1000, h.health.get("edge")); assertEquals(1000, h.health.get("outside")); assertEquals(2, h.bursts().size()); assertTrue(h.cues.isEmpty());
                assertTrue(h.bursts().stream().allMatch(d -> d.tags().equals(Set.of("chorus:weapon_damage", "chorus:explosive_perk_damage", "chorus_d2:firefly_damage", "chorus_d2:solar_damage"))));
                assertEquals(h.hits.getFirst().source(), h.bursts().getFirst().source());
            }
    }
    @Test void bodyUnclassifiedNonlethalAndUncreditedKillsGrantNeitherBenefit() throws Exception {
        for (String kind : List.of("body", "unclassified", "nonlethal", "uncredited")) {
            var h = new Harness(Set.of(), !kind.equals("uncredited"), false, 0, .1); h.entity("victim", kind.equals("nonlethal") ? 100 : 5, 0); h.entity("near", 1000, 1);
            if (!kind.equals("unclassified")) h.region("victim"); h.shoot("victim", !kind.equals("body")); h.until(200_000);
            assertTrue(h.bursts().isEmpty()); assertTrue(h.buff("a").isEmpty()); assertEquals(10, h.reloadStat("a"));
        }
    }
    @Test void missingDeathPositionAndInvalidRadiusDoNotDiscardTheIndependentReloadReward() throws Exception {
        for (boolean missing : List.of(false, true)) {
            var h = new Harness(); h.omitPosition = missing;
            if (!missing) { var parameters = new HashMap<>(h.a); parameters.put("radius", new Measure(3.75, Unit.METER)); h.a = parameters; h.equip("normal", "enhanced", "primary"); }
            h.entity("corpse", 5, 0); h.precisionKill("corpse"); h.until(100_000);
            assertEquals(60, h.reloadStat("a")); assertTrue(h.bursts().isEmpty()); assertEquals(List.of("test:firefly_unresolved"), h.cues);
        }
    }
    @Test void reloadUsesActualStatsAndTransfersOwnReservesAtItsComputedDeadline() throws Exception {
        for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(); if (enhanced) h.equip("normal", "enhanced", "secondary"); h.entity("corpse", 5, 0); h.precisionKill("corpse"); h.reload();
            String weapon = enhanced ? "b" : "a"; long duration = enhanced ? 1_350_000 : 1_400_000;
            assertEquals(duration / 1e6, h.state().reloads().get("player").duration().value()); h.until(duration - 1);
            assertEquals(4, h.state().ammunition().get(weapon).magazine()); h.until(duration); assertEquals(5, h.state().ammunition().get(weapon).magazine());
            assertEquals(11, h.state().ammunition().get(weapon).reserve().orElseThrow().rounds()); assertEquals(12, h.state().ammunition().get(enhanced ? "a" : "b").reserve().orElseThrow().rounds());
        }
    }
    @Test void repeatedKillsRefreshWithoutStackingAndStowKeepsTheExactSixSecondDeadline() throws Exception {
        var h = new Harness(); h.entity("first", 5, 0); h.precisionKill("first"); h.until(4_000_000); h.entity("second", 5, 0); h.precisionKill("second");
        assertEquals(60, h.reloadStat("a")); assertEquals(10_000_000, h.buff("a").orElseThrow().deadline()); h.equip("normal", "enhanced", "secondary");
        h.restore(); h.until(9_999_999); assertEquals(60, h.reloadStat("a")); assertEquals(10, h.reloadStat("b")); h.until(10_000_000); assertTrue(h.buff("a").isEmpty()); assertEquals(10, h.reloadStat("a"));
    }
    @Test void buffExpiryDoesNotRetimingAnAlreadyAcceptedReload() throws Exception {
        var h = new Harness(); h.entity("corpse", 5, 0); h.precisionKill("corpse"); h.until(5_500_000); h.reload(); var plan = h.state().reloads().get("player");
        h.until(6_000_000); assertTrue(h.buff("a").isEmpty()); assertEquals(plan, h.state().reloads().get("player")); h.until(6_899_999); assertEquals(4, h.state().ammunition().get("a").magazine());
        h.until(6_900_000); assertEquals(5, h.state().ammunition().get("a").magazine()); h.fire(); h.reload(); assertEquals(1.9, h.state().reloads().get("player").duration().value());
    }
    @Test void stowedPrecisionKillUsesTheOriginalWeaponButRemovingItsPerkCancelsEligibility() throws Exception {
        for (boolean remove : List.of(false, true)) {
            var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 1); h.region("corpse"); h.fire(); h.equip(remove ? "none" : "normal", "enhanced", "secondary"); h.impact("corpse", true); h.until(100_000);
            assertEquals(remove ? 0 : 1, h.bursts().size()); assertEquals(remove ? 10 : 60, h.reloadStat("a")); assertEquals(10, h.reloadStat("b"));
        }
    }
    @Test void checkpointKeepsPendingBlastCenterAndCapturedBonusButDetachRemovesReload() throws Exception {
        var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 5.625); h.bind("bonus", "test:firefly_bonus", "a");
        h.afterReceipt = (d, _) -> { if (d.target().equals("corpse")) { h.positions.put("corpse", new WorldPosition("test:world", 100, 0, 0)); h.missing.add("corpse"); } };
        h.precisionKill("corpse"); h.restore(); assertEquals(60, h.reloadStat("a")); h.equip("none", "enhanced", "secondary"); h.session.start(h.now(), SourceChange.remove("bonus"));
        assertTrue(h.buff("a").isEmpty()); h.restore(); h.until(100_000); assertEquals(992.2, h.health.get("near"), 1e-9); assertEquals("a", h.bursts().getFirst().source().weapon());
        h.restore(); h.until(300_000); assertEquals(1, h.bursts().size());
    }
    @Test void sourceSpecificMinimumAndConflictingBasesAreExplicitWithoutChangingPvpFloor() throws Exception {
        for (boolean pvp : List.of(false, true)) for (double base : List.of(100d, 104d, 436d)) {
            var h = new Harness(Set.of(), true, pvp, .2, 0); var parameters = new HashMap<>(h.a); parameters.put("pve_damage", new Measure(base, Unit.DAMAGE)); h.a = parameters; h.equip("normal", "enhanced", "primary");
            h.entity("corpse", 5, 0); h.entity("full", 1000, 3.75); h.entity("half", 1000, 5.625); h.entity("edge", 1000, 7.5); h.entity("outside", 1000, 7.51); h.precisionKill("corpse");
            assertEquals(1000 - (pvp ? 8 : base * .1), h.health.get("full"), 1e-9); assertEquals(1000 - (pvp ? 4 : base * .06), h.health.get("half"), 1e-9);
            assertEquals(1000 - (pvp ? 0 : base * .02), h.health.get("edge"), 1e-9); assertEquals(1000, h.health.get("outside"));
        }
    }
    @Test void secondaryExplosionKillsDoNotInventPrecisionOrExtendTheReloadWindow() throws Exception {
        var h = new Harness(); h.entity("corpse", 5, 0); h.entity("second", 1, 5.625); h.region("second"); h.entity("far", 1000, 10); h.precisionKill("corpse"); h.until(500_000);
        assertEquals(0, h.health.get("second")); assertEquals(1000, h.health.get("far")); assertEquals(1, h.queries.size()); assertEquals(6_000_000, h.buff("a").orElseThrow().deadline());
        assertEquals(Set.of("chorus:weapon_kill"), h.bursts().getFirst().killTags()); assertTrue(h.bursts().getFirst().proc().deny().isEmpty());
    }
    @Test void unknownExplosionOutcomeKeepsAlreadyGrantedReloadAndDoesNotReplayDamage() throws Exception {
        var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 5.625); h.precisionKill("corpse"); h.failBurst = true;
        assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(994.8, h.health.get("near"), 1e-9); assertEquals(60, h.reloadStat("a"));
        assertThrows(IllegalStateException.class, () -> h.until(300_000)); assertEquals(1, h.bursts().size());
    }
}
