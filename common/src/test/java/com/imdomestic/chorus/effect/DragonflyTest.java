package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.WorldPosition;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DragonflyTest {
    static Map<String, Measure> calibration(double radius, double delay) {
        return Map.of("pve_damage", new Measure(123.89, Unit.DAMAGE), "pvp_damage", new Measure(95.3, Unit.DAMAGE),
                "full_radius", new Measure(0, Unit.METER), "radius", new Measure(radius, Unit.METER), "delay", new Measure(delay, Unit.SECOND));
    }
    static CompiledEffects program(Set<String> tags, boolean credit) throws Exception {
        var weapon = json("dragonfly_weapon"); var fire = weapon.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire");
        for (int i : List.of(2, 3)) {
            var attack = fire.get(i).getAsJsonObject().getAsJsonObject("action"); var selected = new JsonArray(); selected.add("chorus:weapon_damage"); if (i == 3) selected.add("chorus:precision"); tags.forEach(selected::add); attack.add("tags", selected);
            if (!credit) attack.add("kill_tags", new JsonArray());
        }
        return CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("dragonfly")).getOrThrow(),
                EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("dragonfly_test_calibration")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, weapon).getOrThrow()));
    }
    static class Harness extends ChainReactionTest.Harness {
        Harness() throws Exception { this(Set.of("chorus_d2:arc_damage"), true, false); }
        Harness(Set<String> tags, boolean credit, boolean pvp) throws Exception {
            super(program(tags, credit), "chorus_d2:dragonfly_damage", pvp); a = calibration(8, .1); b = calibration(8, .1); equip("normal", "enhanced", "primary");
        }
        void region(String target) {
            var parameters = Map.of("min_x", new Measure(0, Unit.MULTIPLIER), "max_x", new Measure(1, Unit.MULTIPLIER),
                    "min_y", new Measure(.75, Unit.MULTIPLIER), "max_y", new Measure(1, Unit.MULTIPLIER), "min_z", new Measure(0, Unit.MULTIPLIER), "max_z", new Measure(1, Unit.MULTIPLIER));
            session.start(now(), SourceChange.bind(new EffectSource("region/" + target, "test:weapon_region", target, new BuffInstance.Origin(target, "region", "", ""), Set.of(), parameters)));
        }
        void impact(String target, boolean head) {
            var point = positions.get(target); var geometry = new ProjectileFlight.Geometry(new WorldPosition(point.dimension(), point.x() - .5, point.y() - (head ? 1.8 : 1), point.z() - .5), 1, 2, 1, .125);
            session.start(now(), shots.getLast().finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, point, Optional.of(target), 0, 0, 0, 100_000).withGeometry(geometry))); healthy();
        }
        void shoot(String target, boolean head) { fire(); impact(target, head); }
        void precisionKill(String target) { region(target); shoot(target, true); assertEquals(0, health.get(target)); }
    }
    @Test void precisionGeometrySelectsSixElementsBothModesAndBothVariantsWithoutPrecisionLeakage() throws Exception {
        for (String element : List.of("kinetic", "arc", "solar", "void", "stasis", "strand")) for (boolean pvp : List.of(false, true)) for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(Set.of("chorus_d2:" + element + "_damage"), true, pvp); if (enhanced) h.equip("normal", "enhanced", "secondary");
            h.entity("corpse", 5, 0); h.entity("center", 1000, 0); h.entity("half", 1000, 4); h.entity("edge", 1000, 8); h.entity("outside", 1000, 8.01);
            h.precisionKill("corpse"); assertTrue(h.bursts().isEmpty()); h.until(100_000); double damage = (pvp ? 95.3 : 123.89) * (enhanced ? 1.1 : 1) * .1;
            assertEquals(1000 - damage, h.health.get("center"), 1e-9); assertEquals(1000 - damage / 2, h.health.get("half"), 1e-9);
            assertEquals(1000, h.health.get("edge")); assertEquals(1000, h.health.get("outside")); assertEquals(2, h.bursts().size());
            assertTrue(h.hits.getFirst().tags().contains("chorus:precision"));
            assertTrue(h.bursts().stream().allMatch(d -> d.tags().equals(Set.of("chorus:weapon_damage", "chorus:explosive_perk_damage", "chorus_d2:dragonfly_damage", "chorus_d2:" + element + "_damage"))));
            assertEquals(h.hits.getFirst().source(), h.bursts().getFirst().source()); assertEquals(enhanced ? "b" : "a", h.bursts().getFirst().source().weapon());
        }
    }
    @Test void bodyKillsUnclassifiedContactsAndNonlethalHeadshotsDoNotExplode() throws Exception {
        for (String kind : List.of("body", "unclassified", "nonlethal", "uncredited")) {
            var h = new Harness(Set.of("chorus_d2:arc_damage"), !kind.equals("uncredited"), false); h.entity("victim", kind.equals("nonlethal") ? 100 : 5, 0); h.entity("near", 1000, 1);
            if (!kind.equals("unclassified")) h.region("victim"); h.shoot("victim", !kind.equals("body")); h.until(100_000);
            assertTrue(h.bursts().isEmpty()); assertEquals(1000, h.health.get("near")); assertEquals(kind.equals("nonlethal") ? 85 : 0, h.health.get("victim"));
        }
    }
    @Test void explosionKillsKeepWeaponCreditWithoutInventingAnotherPrecisionKill() throws Exception {
        var h = new Harness(); h.entity("first", 5, 0); h.entity("second", 1, 4); h.entity("far", 1000, 9); h.region("second"); h.precisionKill("first"); h.until(500_000);
        assertEquals(0, h.health.get("second")); assertEquals(1000, h.health.get("far")); assertEquals(1, h.queries.size());
        assertEquals(Set.of("chorus:weapon_kill"), h.bursts().getFirst().killTags()); assertFalse(h.bursts().getFirst().tags().contains("chorus:precision"));
    }
    @Test void stowRetainsEligibilityWhileReplacementBeforeImpactRemovesIt() throws Exception {
        for (boolean remove : List.of(false, true)) {
            var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 4); h.region("corpse"); h.fire(); h.equip(remove ? "none" : "normal", "enhanced", "secondary"); h.impact("corpse", true); h.until(100_000);
            assertEquals(remove ? 1000 : 993.8055, h.health.get("near"), 1e-9); assertEquals(remove ? 0 : 1, h.bursts().size());
        }
    }
    @Test void pendingExplosionKeepsDeathCenterElementAndBonusAfterSourceRemovalAndCheckpoint() throws Exception {
        var h = new Harness(Set.of("chorus_d2:void_damage"), true, false); h.entity("corpse", 5, 0); h.entity("near", 1000, 4); h.bind("bonus", "test:dragonfly_bonus", "a");
        h.afterReceipt = (d, _) -> { if (d.target().equals("corpse")) { h.positions.put("corpse", new WorldPosition("test:world", 100, 0, 0)); h.missing.add("corpse"); } };
        h.precisionKill("corpse"); h.equip("none", "enhanced", "secondary"); h.session.start(h.now(), SourceChange.remove("bonus")); h.restore(); h.until(100_000);
        assertEquals(1000 - 12.389 * .5 * 1.5, h.health.get("near"), 1e-9); assertEquals("a", h.bursts().getFirst().source().weapon()); assertTrue(h.bursts().getFirst().tags().contains("chorus_d2:void_damage"));
        h.restore(); h.until(300_000); assertEquals(1, h.bursts().size());
    }
    @Test void unknownOrAmbiguousElementAndMissingDeathPositionAreExplicitDiagnostics() throws Exception {
        for (var tags : List.of(Set.<String>of(), Set.of("chorus_d2:arc_damage", "chorus_d2:solar_damage"), Set.of("chorus_d2:arc_damage"))) {
            var h = new Harness(tags, true, false); h.omitPosition = tags.size() == 1; h.entity("corpse", 5, 0); h.entity("near", 1000, 1); h.precisionKill("corpse"); h.until(200_000);
            assertTrue(h.bursts().isEmpty()); assertEquals(List.of("test:dragonfly_unresolved"), h.cues);
        }
    }
    @Test void explicitBasesAndConflictingRadiusCalibrationsAreNotImplicitArchetypeMultipliers() throws Exception {
        for (double base : List.of(332d, 433d, 397d)) for (double radius : List.of(4.5, 8d)) {
            var h = new Harness(); var values = new HashMap<>(calibration(radius, 0)); values.put("pve_damage", new Measure(base, Unit.DAMAGE)); h.a = values; h.equip("normal", "enhanced", "primary");
            h.entity("corpse", 5, 0); h.entity("near", 1000, radius / 2); h.entity("outside", 1000, radius + .01); h.precisionKill("corpse");
            assertEquals(1000 - base * .05, h.health.get("near"), 1e-9); assertEquals(1000, h.health.get("outside")); assertEquals(radius, h.queries.getFirst().radius());
        }
    }
    @Test void immuneNeighborsAndUnknownWorldOutcomesDoNotInventKillsOrReplayDamage() throws Exception {
        var immune = new Harness(); immune.entity("corpse", 5, 0); immune.entity("near", 1, 1); immune.immune = true; immune.precisionKill("corpse"); immune.until(300_000);
        assertEquals(1, immune.health.get("near")); assertEquals(1, immune.queries.size());
        var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 4); h.precisionKill("corpse"); h.failBurst = true;
        assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(993.8055, h.health.get("near"), 1e-9);
        assertThrows(IllegalStateException.class, () -> h.until(300_000)); assertEquals(1, h.bursts().size());
    }
}
