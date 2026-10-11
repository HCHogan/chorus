package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual equipment/fire/kill facts, explicit contemporary bases and synthetic distance/world calibration. */
class ChainReactionTest {
    static final String TAG = "chorus_d2:chain_reaction_damage";
    static Map<String, Measure> calibration(boolean heavy, double delay) {
        return Map.of("pve_damage", new Measure(heavy ? 109.2 : 67.2, Unit.DAMAGE), "pvp_damage", new Measure(heavy ? 72.8 : 44.8, Unit.DAMAGE),
                "full_radius", new Measure(heavy ? 3 : 2.4, Unit.METER), "radius", new Measure(heavy ? 7 : 6, Unit.METER), "delay", new Measure(delay, Unit.SECOND));
    }
    static JsonObject weapon(Set<String> tags, boolean credit) throws Exception {
        var d = json("chain_reaction_weapon"); var shot = d.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("fire").getAsJsonArray("on_fire").get(2).getAsJsonObject().getAsJsonObject("action");
        var all = new JsonArray(); all.add("chorus:weapon_damage"); tags.forEach(all::add); shot.add("tags", all);
        if (!credit) shot.add("kill_tags", new JsonArray()); return d;
    }
    static class Harness {
        final CompiledEffects program; final String burstTag; EffectSession session; int sequence;
        final Map<String, Double> health = new HashMap<>(); final Map<String, WorldPosition> positions = new HashMap<>();
        final Set<String> missing = new HashSet<>(); final List<DamageCommand> hits = new ArrayList<>(); final List<Double> amounts = new ArrayList<>();
        final List<TargetQuery> queries = new ArrayList<>(); final List<ProjectileFlight.Launch> shots = new ArrayList<>(); final List<String> cues = new ArrayList<>();
        boolean omitPosition, failBurst, immune; java.util.function.BiConsumer<DamageCommand, DamageReceipt> afterReceipt = (_, _) -> {};
        Map<String, Measure> a = calibration(false, .1), b = calibration(true, .1);
        Harness() throws Exception { this(Set.of("chorus_d2:arc_damage"), true, false); }
        Harness(Set<String> tags, boolean credit, boolean pvp) throws Exception {
            this(CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("chain_reaction")).getOrThrow(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("chain_reaction_test_calibration")).getOrThrow(),
                    EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, weapon(tags, credit)).getOrThrow())), TAG, pvp);
        }
        Harness(CompiledEffects program, String burstTag, boolean pvp) {
            this(program, burstTag, pvp, calibration(false, .1), calibration(true, .1));
        }
        Harness(CompiledEffects program, String burstTag, boolean pvp, Map<String, Measure> normal, Map<String, Measure> enhanced) {
            this.program = program; this.burstTag = burstTag; a = normal; b = enhanced;
            session = new EffectSession(program, EffectCheckpointTest.clock(), 1, EffectState.empty().withMode(pvp ? EffectState.Mode.PVP : EffectState.Mode.PVE), this::world);
            entity("player", 1000, -10); equip("normal", "enhanced", "primary"); bind("observer", "test:projectile", "");
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void healthy() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty(), session.state().engine().failure().toString()); }
        void until(long time) { session.observe(time, List.of()); healthy(); }
        void entity(String id, double hp, double x) { health.put(id, hp); positions.put(id, new WorldPosition("test:world", x, 0, 0)); }
        void bind(String id, String bundle, String weapon) { session.start(now(), SourceChange.bind(new EffectSource(id, bundle, "player", new BuffInstance.Origin("player", id, weapon, ""), Set.of()))); healthy(); }
        void equip(String normal, String enhanced, String draw) {
            var next = new Loadout(Map.of("test:primary", new Loadout.Gear("a", "test:rifle", Map.of("perk", normal), a),
                    "test:secondary", new Loadout.Gear("b", "test:rifle", Map.of("perk", enhanced), b)), Optional.of("test:" + draw));
            session.start(now(), new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); healthy();
        }
        void fire() { session.start(now(), new WeaponFire.Request("player", "fire-" + ++sequence).signal()); healthy(); }
        void impact(String target) { var shot = shots.getLast(); session.start(now(), shot.finish(new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, positions.get(target), Optional.of(target), 0, 0, 0, 100_000))); healthy(); }
        void kill(String target) { fire(); impact(target); assertEquals(0, health.get(target)); }
        List<DamageCommand> bursts() { return hits.stream().filter(c -> c.tags().contains(burstTag)).toList(); }
        Optional<EntityQuery.View> view(String id) { return missing.contains(id) || !health.containsKey(id) ? Optional.empty() : Optional.of(new EntityQuery.View(health.get(id) > 0, false, health.get(id), 1000, 0, Set.of(), Set.of())); }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) { return switch (request.command()) {
            case PositionQuery q -> new PositionQuery.Result(q, Optional.ofNullable(positions.get(q.target())));
            case DirectionQuery q -> new DirectionQuery.Result(q, Optional.of(new WorldDirection("test:world", 1, 0, 0)));
            case ProjectileFlight.Launch launch -> { shots.add(launch); yield new ProjectileFlight.Receipt(launch, ProjectileFlight.Outcome.LAUNCHED, Optional.of("flight/" + shots.size())); }
            case EntityQuery q -> new EntityQuery.Result(q, view(q.target()));
            case Action.CueCommand cue -> { cues.add(cue.cue()); yield RuleEngine.Empty.INSTANCE; }
            case TargetQuery q -> {
                queries.add(q); var center = ((TargetQuery.PositionCenter) q.center()).position().orElseThrow();
                var targets = positions.entrySet().stream().filter(e -> !q.exclude().contains(e.getKey())).map(e -> new TargetQuery.Target(e.getKey(), Math.abs(e.getValue().x() - center.x())))
                        .filter(t -> t.distance() <= q.radius()).sorted(q.comparator()).toList();
                yield new TargetQuery.Result(q, TargetQuery.Outcome.AVAILABLE, targets);
            }
            case DamageCommand d -> {
                hits.add(d); double value = program.outgoing(state(), d, d.amount()).orElseThrow().output().value(); amounts.add(value);
                boolean suppressed = immune && d.tags().contains(burstTag);
                double before = health.get(d.target()), loss = suppressed ? 0 : Math.min(before, value); health.put(d.target(), before - loss);
                if (failBurst && d.tags().contains(burstTag)) throw new IllegalStateException("unknown blast after health mutation");
                var receipt = new DamageReceipt(request.id().toString(), suppressed ? DamageReceipt.Outcome.IMMUNE : DamageReceipt.Outcome.APPLIED, 0, 0, loss,
                        before > 0 && loss == before ? Optional.of(request.id() + "/death") : Optional.empty(), false);
                var points = omitPosition ? Map.<PositionQuery, Optional<WorldPosition>>of() : Map.of(new PositionQuery(d.target()), Optional.ofNullable(positions.get(d.target())));
                receipt = receipt.withObservedEntities(new EntityObservation(now(), Map.of(d.target(), view(d.target())), points)); afterReceipt.accept(d, receipt); yield receipt;
            }
            default -> throw new AssertionError(request.command());
        }; }
        void restore() {
            var saved = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint()));
            session = EffectSession.restore(new CompiledEffects(saved.program()), EffectCheckpointTest.clock(), 256, saved, this::world);
        }
    }
    @Test void sixElementsTwoModesAndAmmoBranchesUseEndpointsAndEnhancedTenPercent() throws Exception {
        for (var element : List.of("kinetic", "arc", "solar", "void", "stasis", "strand")) for (boolean heavy : List.of(false, true)) for (boolean enhanced : List.of(false, true)) for (boolean pvp : List.of(false, true)) {
            var h = new Harness(Set.of("chorus_d2:" + element + "_damage"), true, pvp);
            h.a = calibration(heavy, .1); h.b = calibration(heavy, .1); h.equip("normal", "enhanced", enhanced ? "secondary" : "primary");
            double full = heavy ? 3 : 2.4, radius = heavy ? 7 : 6, damage = (pvp ? (heavy ? 72.8 : 44.8) : (heavy ? 109.2 : 67.2)) * (enhanced ? 1.1 : 1) * .1;
            h.entity("corpse", 5, 0); h.entity("full", 1000, full); h.entity("middle", 1000, (full + radius) / 2); h.entity("edge", 1000, radius); h.entity("outside", 1000, radius + .01);
            h.kill("corpse"); assertTrue(h.bursts().isEmpty()); h.until(100_000);
            assertEquals(1000 - damage, h.health.get("full"), 1e-9); assertEquals(1000 - damage / 2, h.health.get("middle"), 1e-9);
            assertEquals(1000, h.health.get("edge")); assertEquals(1000, h.health.get("outside")); assertEquals(2, h.bursts().size());
            assertEquals(radius, h.queries.getFirst().radius());
            assertTrue(h.bursts().stream().allMatch(d -> d.tags().equals(Set.of("chorus:weapon_damage", "chorus:explosive_perk_damage", TAG, "chorus_d2:" + element + "_damage"))));
            assertEquals(h.hits.getFirst().source(), h.bursts().getFirst().source()); assertEquals(enhanced ? "b" : "a", h.bursts().getFirst().source().weapon());
        }
    }
    @Test void missingAndAmbiguousElementsAndMissingHistoricalPositionAreDiagnosedWithoutGuessing() throws Exception {
        for (var tags : List.of(Set.<String>of(), Set.of("chorus_d2:arc_damage", "chorus_d2:solar_damage"), Set.of("chorus_d2:arc_damage"))) {
            var h = new Harness(tags, true, false); h.omitPosition = tags.size() == 1; h.entity("corpse", 5, 0); h.entity("near", 1000, 1); h.kill("corpse"); h.until(200_000);
            assertTrue(h.bursts().isEmpty()); assertEquals(List.of("test:chain_reaction_unresolved"), h.cues);
        }
    }
    @Test void weaponThreadlingExclusionAndMissingCreditDoNotAffectOtherDerivedKills() throws Exception {
        for (boolean threadling : List.of(false, true)) {
            var h = new Harness(Set.of("chorus_d2:strand_damage", threadling ? "chorus_d2:threadling_damage" : "test:derived_damage"), true, false);
            h.entity("corpse", 5, 0); h.entity("near", 1000, 1); h.kill("corpse"); h.until(100_000); assertEquals(threadling ? 0 : 1, h.bursts().size());
        }
        var h = new Harness(Set.of("chorus_d2:arc_damage"), false, false); h.entity("corpse", 5, 0); h.entity("near", 1000, 1); h.kill("corpse"); h.until(100_000); assertTrue(h.bursts().isEmpty());
    }
    @Test void stowingUsesOriginalWeaponAndRemovingPerkBeforeKillCancelsEligibility() throws Exception {
        for (boolean remove : List.of(false, true)) {
            var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 1); h.fire(); h.equip(remove ? "none" : "normal", "enhanced", "secondary"); h.impact("corpse"); h.until(100_000);
            assertEquals(remove ? 0 : 1, h.bursts().size()); assertEquals(remove ? 1000 : 993.28, h.health.get("near"), 1e-9);
        }
    }
    @Test void delayedBlastKeepsReceiptCenterAndCapturedBonusAfterCorpseAndSourceRemoval() throws Exception {
        var h = new Harness(); h.bind("bonus", "test:chain_bonus", "a"); h.entity("corpse", 5, 0); h.entity("near", 1000, 1);
        h.afterReceipt = (d, _) -> { if (d.target().equals("corpse")) { h.positions.put("corpse", new WorldPosition("test:world", 100, 0, 0)); h.missing.add("corpse"); } };
        h.kill("corpse"); h.equip("none", "enhanced", "secondary"); h.session.start(h.now(), SourceChange.remove("bonus")); h.until(100_000);
        assertEquals(1000 - 6.72 * 1.5, h.health.get("near"), 1e-9); assertEquals(0, ((TargetQuery.PositionCenter) h.queries.getFirst().center()).position().orElseThrow().x());
    }
    @Test void legitimateTwelveHopFeedbackHasNoRepeatLimitAndUsesEachDeathCenter() throws Exception {
        var h = new Harness(); h.entity("first", 5, 0); for (int i = 1; i <= 12; i++) h.entity("chain" + i, 1, i * 4);
        h.kill("first"); h.until(1_400_000);
        for (int i = 1; i <= 12; i++) assertEquals(0, h.health.get("chain" + i));
        assertEquals(13, h.queries.size()); assertEquals(12, h.bursts().size());
        for (int i = 0; i < 13; i++) assertEquals(i * 4, ((TargetQuery.PositionCenter) h.queries.get(i).center()).position().orElseThrow().x());
        assertTrue(h.bursts().stream().allMatch(d -> d.source().weapon().equals("a") && d.proc().deny().isEmpty()));
    }
    @Test void deadMissingAndImmuneNeighborsCannotProduceAnotherExplosion() throws Exception {
        for (String kind : List.of("dead", "missing", "immune")) {
            var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", kind.equals("dead") ? 0 : 1, 1);
            h.immune = kind.equals("immune"); if (kind.equals("missing")) h.missing.add("near"); h.kill("corpse"); h.until(300_000);
            assertEquals(1, h.queries.size()); assertEquals(kind.equals("immune") ? 1 : 0, h.bursts().size());
        }
    }
    @Test void checkpointWithPendingBlastRestoresRatioAndExecutesOnce() throws Exception {
        var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 4.2); h.kill("corpse"); h.restore(); h.until(100_000);
        assertEquals(996.64, h.health.get("near"), 1e-9); h.restore(); h.until(300_000); assertEquals(1, h.bursts().size());
    }
    @Test void unknownWorldResultDoesNotReplayCommittedBlast() throws Exception {
        var h = new Harness(); h.entity("corpse", 5, 0); h.entity("near", 1000, 1); h.kill("corpse"); h.failBurst = true;
        assertThrows(IllegalStateException.class, () -> h.until(100_000)); assertEquals(993.28, h.health.get("near"), 1e-9);
        assertThrows(IllegalStateException.class, () -> h.until(300_000)); assertEquals(1, h.bursts().size());
    }
    @Test void missingCalibrationIsRejectedAndInvalidRangeIsAnUnresolvedKill() throws Exception {
        var h = new Harness(); var values = new HashMap<>(h.a); values.remove("delay"); h.a = values;
        var failure = assertThrows(IllegalStateException.class, () -> h.equip("normal", "enhanced", "primary"));
        assertTrue(failure.toString().contains("Equipment parameter names do not match"), failure.toString());
        var invalid = new Harness(); values = new HashMap<>(invalid.a); values.put("radius", new Measure(2.4, Unit.METER)); invalid.a = values;
        invalid.equip("normal", "enhanced", "primary"); invalid.entity("corpse", 5, 0); invalid.kill("corpse"); invalid.until(100_000);
        assertEquals(List.of("test:chain_reaction_unresolved"), invalid.cues); assertTrue(invalid.bursts().isEmpty());
    }
}
