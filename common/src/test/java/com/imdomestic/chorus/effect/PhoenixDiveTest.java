package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class PhoenixDiveTest {
    static final String D = "chorus_d2:", ABILITY = D + "phoenix_dive", ENERGY = D + "phoenix_dive_energy", PENDING = D + "phoenix_dive_pending";
    static CompiledEffects program(boolean calibrated) throws Exception {
        var base = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, BenevolenceTest.program().program()).getOrThrow().getAsJsonObject();
        base.getAsJsonArray("buffs").asList().removeIf(x -> x.getAsJsonObject().getAsJsonObject("definition").get("id").getAsString().equals(D + "restoration"));
        base.getAsJsonArray("bundles").asList().removeIf(x -> x.getAsJsonObject().get("id").getAsString().equals(D + "restoration_active"));
        var parts = new ArrayList<EffectProgram>(); parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, base).getOrThrow());
        for (String name : List.of("phoenix_dive", "restoration_effect", "restoration_test_calibration", "solar", "solar_test_calibration", "ember_of_char")) {
            var data = json(name);
            if (name.equals("phoenix_dive") && calibrated) json("phoenix_dive_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e -> data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value", e.getValue()));
            parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("phoenix_dive_test_calibration").get("program")).getOrThrow());
        return CompiledEffects.link(parts);
    }
    static class Harness extends BenevolenceTest.Harness {
        String failBuff = ""; boolean rejectedBlast;
        Harness() throws Exception { this(true, EffectState.Mode.PVE); }
        Harness(boolean calibrated, EffectState.Mode mode) throws Exception {
            this(program(calibrated), mode);
        }
        Harness(CompiledEffects program, EffectState.Mode mode) {
            super(program, mode); choose("class", "phoenix_dive"); bind("player", "test:phoenix_inputs"); bind("player", D + "solar_scaling");
        }
        @Override double energy() { return state().resources().get(new ResourceState.Key("player", ENERGY)).value(); }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case EntityQuery q -> new EntityQuery.Result(q, missing.contains(q.target()) ? Optional.empty() : Optional.of(new EntityQuery.View(!dead.contains(q.target()), !q.target().equals("enemy"), 1000, 1000, 0)));
                case TargetQuery q -> {
                    queries.add(q); var pool = q.relation() == TargetQuery.Relation.ALLIED ? List.of(new TargetQuery.Target("ally", 9), new TargetQuery.Target("outer_ally", 9.001))
                            : List.of(new TargetQuery.Target("enemy", 2), new TargetQuery.Target("guardian", 5), new TargetQuery.Target("outer_enemy", 5.001));
                    yield new TargetQuery.Result(q, queryMissing ? TargetQuery.Outcome.MISSING_CENTER : TargetQuery.Outcome.AVAILABLE,
                            queryMissing ? List.of() : pool.stream().filter(t -> t.distance() <= q.radius() && !q.exclude().contains(t.entity())).sorted(q.comparator()).toList());
                }
                case StatusResult.Check q -> {
                    statuses.add(q); if (q.definition().id().equals(failBuff)) throw new IllegalStateException("Unknown status write");
                    yield new StatusResult.Checked(q, denied.contains(q.target()) || dead.contains(q.target()) ? StatusResult.Decision.DENIED : StatusResult.Decision.ALLOWED);
                }
                case DamageCommand c -> {
                    hits.add(c); if (unknownDamage) throw new IllegalStateException("Unknown Phoenix blast");
                    double amount = p.outgoing(state(), c, c.amount()).map(n -> n.output().value()).orElse(c.amount());
                    yield new DamageReceipt(r.id().toString(), rejectedBlast ? DamageReceipt.Outcome.IMMUNE : DamageReceipt.Outcome.APPLIED, 0, 0, rejectedBlast ? 0 : amount, Optional.empty(), false);
                }
                default -> super.world(r);
            };
        }
        void input(String name) { send(new RuleEngine.Signal("test:phoenix_" + name, new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of()))); }
        Optional<BuffInstance> pending() { return buff("phoenix_dive_pending", "player"); }
        EntityObservation observation(String target, String world, boolean alive) {
            var positions = new HashMap<PositionQuery, Optional<WorldPosition>>();
            for (var anchor : TargetQuery.Anchor.values()) positions.put(new PositionQuery(target, anchor), Optional.of(new WorldPosition(world, 12, 30, 0)));
            return new EntityObservation(now(), Map.of(target, Optional.of(new EntityQuery.View(alive, true, alive ? 100 : 0, 100, 0))), positions);
        }
        GroundContact.Landed landing(String target, String life, String world) { return new GroundContact.Landed(new EntityAnchor.Reference(target, life, world), "landing/" + cast, -2, -2, observation(target, world, true)); }
        void land() { send(landing("player", "life-1", "world").signal()); }
        List<HealingCommand> cureHeals() { return heals.stream().filter(c -> c.tags().contains(D + "cure")).toList(); }
        List<DamageCommand> blasts() { return hits.stream().filter(c -> c.tags().contains(D + "phoenix_dive_damage")).toList(); }
    }
    @Test void groundUseAndMissingCalibrationDoNotPayAndTheReferenceProgramRoundTrips() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVE); assertEquals(AbilityUse.Outcome.CONDITION, h.use("class", true).outcome());
        assertThrows(IllegalArgumentException.class, () -> h.p.useAbility(h.state(), h.request("class", false))); assertEquals(1, h.energy());
        var p = program(true); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
    }
    @Test void airborneInputPaysOnceAndCureWaitsForActualLandingRatherThanATimerInBothModes() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(true, mode); assertEquals(AbilityUse.Outcome.ACCEPTED, h.use("class", false).outcome()); assertEquals(0, h.energy());
            assertEquals(2, h.impulses.size()); assertEquals(-12, h.impulses.getLast().delta().orElseThrow().y()); assertTrue(h.pending().isPresent());
            h.until(2_000_000); assertTrue(h.heals.isEmpty()); assertTrue(h.statuses.isEmpty()); h.land(); assertTrue(h.pending().isEmpty());
            h.until(2_100_000); assertEquals(4, h.cureHeals().size()); double pulse = mode == EffectState.Mode.PVE ? 6 : 3;
            assertTrue(h.cureHeals().stream().allMatch(c -> c.amount() == pulse && c.source().ability().equals(ABILITY)));
            assertEquals(Set.of("player", "ally"), h.cureHeals().stream().map(HealingCommand::target).collect(java.util.stream.Collectors.toSet()));
            assertTrue(h.hits.isEmpty()); assertTrue(h.queries.stream().allMatch(q -> q.center() instanceof TargetQuery.PositionCenter));
        }
    }
    @Test void heatRisesRestoresImmediatelyAndRetainsCastCreditThroughAshesScorchAndLanding() throws Exception {
        var h = new Harness(); h.bind("player", D + "ember_of_solace"); h.bind("player", D + "ember_of_ashes"); h.input("heat"); h.use("class", false);
        var restore = h.buff("restoration", "player").orElseThrow(); assertEquals(2, restore.tier()); assertEquals(4_500_000, restore.deadline());
        assertTrue(h.cureHeals().isEmpty()); h.input("unheat"); h.until(200_000); h.land();
        assertEquals(List.of(10.0, 10.0), h.blasts().stream().map(DamageCommand::amount).toList());
        for (String target : List.of("enemy", "guardian")) {
            var scorch = h.buff("scorch", target).orElseThrow(); assertEquals(60, scorch.count()); assertEquals(ABILITY, scorch.origin().ability());
            assertEquals(Set.of("chorus:class_kill", "chorus:ability_kill"), scorch.components().damageSnapshots().get("dot").orElseThrow().attack().killTags());
        }
        assertTrue(h.buff("scorch", "outer_enemy").isEmpty()); h.until(700_000);
        assertTrue(h.hits.stream().anyMatch(c -> c.tags().contains(D + "scorch_damage") && c.tags().contains("chorus:class_damage")));
    }
    @Test void baseHeatGrantIsThreeSecondsAndBaseScorchIsFortyWithIndependentTargetAuthorization() throws Exception {
        var h = new Harness(); h.input("heat"); h.use("class", false); assertEquals(3_000_000, h.buff("restoration", "player").orElseThrow().deadline());
        h.denied.add("guardian"); h.land(); assertEquals(2, h.blasts().size()); assertEquals(40, h.buff("scorch", "enemy").orElseThrow().count()); assertTrue(h.buff("scorch", "guardian").isEmpty());
        var rejected = new Harness(); rejected.input("heat"); rejected.use("class", false); rejected.rejectedBlast = true; rejected.land(); assertTrue(rejected.buff("scorch", "enemy").isEmpty());
    }
    @Test void neutralInputAddsOnlyTheDownwardImpulseAndUnavailableInputNeverArmsLandingBenefits() throws Exception {
        var h = new Harness(); h.input = Optional.empty(); h.use("class", false); assertEquals(1, h.impulses.size()); assertEquals(-12, h.impulses.getFirst().delta().orElseThrow().y());
        for (var outcome : List.of(InputDirection.Outcome.MISSING_TARGET, InputDirection.Outcome.DEAD, InputDirection.Outcome.UNLOADED, InputDirection.Outcome.UNSUPPORTED)) {
            h = new Harness(); h.input = Optional.empty(); h.inputOutcome = outcome; h.use("class", false); assertEquals(0, h.energy()); assertTrue(h.pending().isEmpty()); assertTrue(h.impulses.isEmpty()); h.land(); assertTrue(h.statuses.isEmpty());
        }
    }
    @Test void foreignLandingDoesNotConsumeTheCastAndCurrentDeathOrReplacementCannotReceiveBenefits() throws Exception {
        var h = new Harness(); h.use("class", false);
        for (var fact : List.of(h.landing("other", "life-1", "world"), h.landing("player", "life-2", "world"), h.landing("player", "life-1", "nether"))) h.send(fact.signal());
        assertTrue(h.pending().isPresent()); assertTrue(h.statuses.isEmpty()); h.land(); assertTrue(h.pending().isEmpty());
        for (String failure : List.of("dead", "missing", "replacement")) {
            var other = new Harness(); other.use("class", false); switch (failure) { case "dead" -> other.alive = false; case "missing" -> other.present = false; default -> other.life = "life-2"; }
            other.land(); assertTrue(other.pending().isEmpty()); assertTrue(other.statuses.isEmpty()); assertTrue(other.hits.isEmpty());
        }
    }
    @Test void pendingReferenceAndAttributionSurviveUnequipAndCheckpointBeforeLanding() throws Exception {
        var h = new Harness(); h.input("heat"); h.use("class", false); String cast = h.pending().orElseThrow().origin().source();
        h.choose("class", null); h.restore(); h.land(); h.until(100_000);
        assertTrue(h.pending().isEmpty()); assertEquals(4, h.cureHeals().size());
        assertTrue(h.cureHeals().stream().allMatch(c -> c.source().source().equals(cast) && c.source().ability().equals(ABILITY)));
        assertTrue(h.blasts().stream().allMatch(c -> c.source().source().equals(cast))); assertEquals(0, h.energy());
    }
    @Test void matchingLifecycleCancelsPendingButOldLifeNotificationsCannotCancelAnotherLife() throws Exception {
        for (boolean death : new boolean[] {true, false}) {
            var h = new Harness(); h.use("class", false);
            for (String life : List.of("old-life", "life-1")) {
                var ref = new EntityAnchor.Reference("player", life, "world");
                var fact = death ? new EntityLifecycle.Died(ref, "death/"+life, h.observation("player", "world", false)).signal()
                        : new EntityLifecycle.Removed(ref, EntityLifecycle.Kind.UNLOADED, "unloaded_to_chunk", true,
                            new EntityObservation(h.now(), Map.of("player", Optional.empty()), h.observation("player", "world", true).positions())).signal();
                h.send(fact); assertEquals(life.equals("old-life"), h.pending().isPresent());
            }
            h.land(); assertTrue(h.statuses.isEmpty());
        }
    }
    @Test void consumedCastDoesNotRepeatAndAnotherPaidAirborneCastCanLandAgain() throws Exception {
        var h = new Harness(); h.use("class", false); h.input("full"); assertEquals(AbilityUse.Outcome.CONDITION, h.use("class", false).outcome()); assertEquals(1, h.energy());
        var landing = h.landing("player", "life-1", "world").signal(); h.send(landing); h.send(landing); h.until(100_000); assertEquals(4, h.cureHeals().size());
        h.until(1_000_000); assertEquals(AbilityUse.Outcome.ACCEPTED, h.use("class", false).outcome()); assertEquals(0, h.energy()); h.land(); h.until(1_100_000); assertEquals(8, h.cureHeals().size());
    }
    @Test void classAccountUsesTheCurrentBaseCooldownAndChunkScalarAndStopsWhenUnselected() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(true, mode); h.use("class", false); h.input("chunk"); assertEquals(.8, h.energy(), 1e-12); h.until(1_000_000); assertEquals(.8 + 1/79.6, h.energy(), 1e-10);
            h.choose("class", null); h.until(2_000_000); assertEquals(.8 + 1/79.6, h.energy(), 1e-10);
        }
    }
    @Test void confirmedAllyCureActivatesBenevolenceOnTheSelectedPhoenixClassAccountAndOtherTwoAccounts() throws Exception {
        var h = new Harness(); h.stats(110, 100, 1); h.use("grenade", true); h.use("melee", true); h.use("class", false); h.land(); assertTrue(h.benefit().isEmpty());
        h.until(1_000_000); assertTrue(h.benefit().isPresent()); assertEquals(4.85/79.6, h.energy(), 1e-10);
        // Stat-scaled passive recovery runs for the full second; Benevolence starts at the first 50 ms Cure pulse.
        double expectedRecovery = .0275 + 3 * .01 * .95;
        assertEquals(expectedRecovery, h.energy("grenade_energy"), 1e-10); assertEquals(expectedRecovery, h.energy("solar_melee_progress"), 1e-10);
    }
    @Test void unknownMotionStatusDamageAndHealingNeverReplayAlreadyCommittedStages() throws Exception {
        for (String phase : List.of("motion", "restoration", "blast", "healing")) {
            var h = new Harness(); h.input("heat");
            if (phase.equals("motion")) h.failureAtImpulse = 1;
            if (phase.equals("restoration")) h.failBuff = D + "restoration";
            if (Set.of("motion", "restoration").contains(phase)) assertThrows(IllegalStateException.class, () -> h.use("class", false));
            else {
                h.use("class", false);
                if (phase.equals("blast")) { h.unknownDamage = true; assertThrows(IllegalStateException.class, h::land); }
                else { h.land(); h.unknownHeal = true; assertThrows(IllegalStateException.class, () -> h.until(50_000)); }
                assertTrue(h.pending().isEmpty());
            }
            int motions = h.impulses.size(), hits = h.hits.size(), statuses = h.statuses.size(), heals = h.heals.size(); assertTrue(h.energy() < .01);
            assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(motions, h.impulses.size()); assertEquals(hits, h.hits.size()); assertEquals(statuses, h.statuses.size()); assertEquals(heals, h.heals.size());
        }
    }
}
