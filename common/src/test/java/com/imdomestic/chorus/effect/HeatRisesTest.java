package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class HeatRisesTest {
    static final String D = "chorus_d2:", ASPECT = D + "heat_rises_aspect", HEAT = D + "heat_rises", CONSUME = D + "heat_rises_consume";
    static CompiledEffects program() throws Exception {
        var parts = new ArrayList<EffectProgram>(); parts.add(PhoenixDiveTest.program(true).program());
        for (String name : List.of("heat_rises", "heat_rises_test_inputs", "icarus_dash", "icarus_dash_inputs", "icarus_dash_cure")) {
            var data = json(name); AmplifiedTest.version(data); parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        return CompiledEffects.link(parts);
    }
    static EffectSource aspect(String id, double receipts) { return new EffectSource(id, ASPECT, "player", new BuffInstance.Origin("player", id, "", ""), Set.of(), Map.of("hold_time", new Measure(.3, Unit.SECOND), "receipt_seconds", new Measure(receipts, Unit.SECOND))); }
    static class Harness extends PhoenixDiveTest.Harness {
        final Map<String, Optional<EntityQuery.View>> observed = new HashMap<>(); final List<String> cues = new ArrayList<>(); List<RuleEngine.Signal> lastFacts = List.of(); Runnable afterReceipt = () -> {};
        Harness() throws Exception { this(EffectState.Mode.PVE); }
        Harness(EffectState.Mode mode) throws Exception {
            super(program(), mode); choose("grenade", "test_heat_grenade"); choose("air_move", "icarus_dash"); bind("player", "test:heat_inputs");
            send(SourceChange.bind(aspect("aspect", 60))); airborne(true); rank(1, false);
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if (r.command() instanceof Action.CueCommand c) { cues.add(c.cue()); return RuleEngine.Empty.INSTANCE; }
            if (r.command() instanceof DamageCommand d && d.tags().contains("test:heat_kill")) {
                var receipt = new DamageReceipt(r.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, 1000, Optional.of("death/" + r.id()), false).withObservedEntities(new EntityObservation(now(), observed));
                lastFacts = DamageFacts.from(d, receipt); afterReceipt.run(); return receipt;
            }
            return super.world(r);
        }
        void airborne(boolean air) { observed.put("player", Optional.of(new EntityQuery.View(true, true, 20, 100, 0, Set.of(), Set.of(), Optional.of(new EntityQuery.Movement(!air, false, false, false, false, false, false))))); }
        void rank(int tier, boolean type) { var tags = tier == 5 ? Set.<String>of() : Set.of(D + "combatant_tier_" + tier); observed.put("enemy", Optional.of(new EntityQuery.View(false, tier == 5, 0, 100, 0, type ? Set.of() : tags, type ? tags : Set.of()))); }
        void heatInput(String kind) { send(new RuleEngine.Signal("test:heat_" + kind, new EffectEvent("player", "enemy", new BuffInstance.Origin("player", "input", "weapon", ""), Set.of(), Map.of()))); }
        void kill() { heatInput("kill"); }
        void repeatKill() { send(lastFacts.stream().filter(f -> f.type().equals("chorus:kill")).findFirst().orElseThrow()); }
        AbilityUse.Receipt consume(double hold) {
            var e = new EffectEvent("player", "player", new BuffInstance.Origin("player", "input", "", ""), Set.of("chorus:ability_input_release"), Map.of("input_hold_time", new Measure(hold, Unit.SECOND)), Map.of("on_ground", true), Map.of());
            var r = new AbilityUse.Request("player", D + "grenade", "heat/" + ++cast, e); var plan = p.useAbility(state(), r); send(r.signal()); return (AbilityUse.Receipt)plan.result();
        }
        Optional<BuffInstance> heat() { return buff("heat_rises", "player"); }
        double grenade() { return energy("grenade_energy"); }
        double wisp() { return state().resources().get(new ResourceState.Key("player", "test:heat_wisp_energy")).value(); }
        double melee() { return energy("solar_melee_progress"); }
        double ae() { var e = new EffectEvent("player", "player", new BuffInstance.Origin("player", "query", "", ""), Set.of(), Map.of()); return p.calculate(state(), "player", e, D + "weapon_airborne_effectiveness", new Measure(0, Unit.STAT_POINT), List.of()).output().value(); }
        void touch() { send(SourceChange.bind(new EffectSource("touch", "test:heat_touch", "player", new BuffInstance.Origin("player", "touch", "", ""), Set.of(D + "touch_of_flame")))); }
        void solace(String who) { bind(who, D + "ember_of_solace"); }
    }
    @Test void heldConversionRequiresTheAspectAndExplicitThresholdBeforeSpendingTheActualGrenade() throws Exception {
        var h = new Harness(); assertEquals(D + "test_heat_grenade", h.consume(.299).resolved()); assertTrue(h.heat().isEmpty()); assertEquals(List.of("test:ordinary_grenade"), h.cues);
        assertEquals(AbilityUse.Outcome.INSUFFICIENT_ENERGY, h.consume(.3).outcome()); assertTrue(h.heat().isEmpty()); h.heatInput("full");
        assertEquals(CONSUME, h.consume(.3).resolved()); assertEquals(0, h.grenade()); assertEquals(15_000_000, h.heat().orElseThrow().deadline());
        var detached = new Harness(); detached.send(SourceChange.remove("aspect")); assertEquals(D + "test_heat_grenade", detached.consume(1).resolved()); assertTrue(detached.heat().isEmpty());
        var p = program(); assertEquals(p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow()).getOrThrow());
    }
    @Test void ordinaryHealingTouchOfFlameAndWispUseSharedCureAndEachRecipientsSolaceInBothModes() throws Exception {
        for (var mode : EffectState.Mode.values()) for (String kind : List.of("ordinary", "healing", "touch", "wisp")) {
            var h = new Harness(mode); h.solace("ally"); if (Set.of("healing", "touch").contains(kind)) h.choose("grenade", "test_heat_healing"); if (kind.equals("touch")) h.touch(); if (kind.equals("wisp")) h.bind("player", "test:heat_wisp");
            var receipt = h.consume(.3); int tier = kind.equals("ordinary") ? 2 : 3; int restoration = kind.equals("touch") ? 1 : kind.equals("wisp") ? 2 : 0;
            assertEquals(CONSUME, receipt.resolved()); assertEquals(kind.equals("wisp") ? .25 : 1, receipt.cost().orElseThrow().receipt().paid());
            if (restoration > 0) {
                assertEquals(restoration, h.buff("restoration", "player").orElseThrow().tier()); assertEquals(4_000_000, h.buff("restoration", "player").orElseThrow().deadline()); assertEquals(6_000_000, h.buff("restoration", "ally").orElseThrow().deadline());
            } else assertTrue(h.buff("restoration", "player").isEmpty());
            h.until(100_000); assertEquals(4, h.cureHeals().size()); double pulse = tier * (mode == EffectState.Mode.PVE ? 3 : 1.5);
            assertTrue(h.cureHeals().stream().allMatch(c -> c.amount() == pulse && c.source().ability().equals(CONSUME))); assertTrue(h.benefit().isPresent());
            assertTrue(h.buff("cure_cooldown", "outer_ally").isEmpty()); assertTrue(h.buff("cure_cooldown", "enemy").isEmpty());
        }
    }
    @Test void wispConversionPaysItsOwnAccountRatherThanTheUnderlyingHealingGrenadeAndDoesNotRunEitherProducer() throws Exception {
        var h = new Harness(); h.choose("grenade", "test_heat_healing"); h.bind("player", "test:heat_wisp"); h.consume(.3);
        assertEquals(1, h.grenade()); assertEquals(.75, h.wisp()); assertEquals(2, h.buff("restoration", "player").orElseThrow().tier()); assertTrue(h.cues.isEmpty());
        assertEquals(D + "test_heat_healing", h.state().abilities().get("player").slots().get(D + "grenade"));
    }
    @Test void everyAirborneKillTierGivesMeleeEnergyWithoutRequiringTheActiveBuff() throws Exception {
        double[] amount = {.2, .25, .35, .5, .5};
        for (var mode : EffectState.Mode.values()) for (boolean type : List.of(false, true)) for (int rank = 1; rank <= 5; rank++) {
            var h = new Harness(mode); h.use("melee", true); h.rank(rank, type); h.kill(); assertEquals(amount[rank - 1] * .5, h.melee(), 1e-12); assertTrue(h.heat().isEmpty()); assertEquals(20, h.ae());
            h.repeatKill(); assertEquals(amount[rank - 1] * .5, h.melee(), 1e-12);
        }
    }
    @Test void activeKillExtensionsUseRankAndThirtySecondRemainingCapAndConsumeAlwaysResetsToFifteen() throws Exception {
        int[] extension = {5, 10, 10, 15, 5};
        for (int rank = 1; rank <= 5; rank++) {
            var h = new Harness(); h.consume(.3); h.rank(rank, false); h.kill(); assertEquals((15L + extension[rank - 1]) * 1_000_000, h.heat().orElseThrow().deadline());
        }
        var h = new Harness(); h.consume(.3); h.rank(4, false); h.until(1_000_000); h.kill(); h.kill(); assertEquals(31_000_000, h.heat().orElseThrow().deadline());
        h.heatInput("full"); h.consume(.3); assertEquals(16_000_000, h.heat().orElseThrow().deadline());
        for (int i = 2; i <= 65; i++) { h.until(i * 1_000_000L); h.kill(); h.kill(); assertEquals((i + 30L) * 1_000_000, h.heat().orElseThrow().deadline()); }
    }
    @Test void groundedForeignMissingAndAmbiguousObservationsDoNotGuessAnAirborneReward() throws Exception {
        for (String reason : List.of("ground", "actor", "movement", "victim", "rank", "ambiguous", "foreign")) {
            var h = new Harness(); h.use("melee", true);
            switch (reason) {
                case "ground" -> h.airborne(false); case "actor" -> h.observed.remove("player"); case "victim" -> h.observed.remove("enemy");
                case "movement" -> h.observed.put("player", Optional.of(new EntityQuery.View(true, true, 20, 100, 0)));
                case "rank", "ambiguous" -> h.observed.put("enemy", Optional.of(new EntityQuery.View(false, false, 0, 100, 0, reason.equals("rank") ? Set.of() : Set.of(D + "combatant_tier_1", D + "combatant_tier_4"), Set.of())));
                default -> {}
            }
            if (reason.equals("foreign")) {
                h.bind("other", "test:heat_inputs"); h.observed.put("other", h.observed.get("player"));
                h.send(new RuleEngine.Signal("test:heat_kill", new EffectEvent("other", "enemy", new BuffInstance.Origin("other", "foreign", "", ""), Set.of(), Map.of())));
                var kill = h.lastFacts.stream().filter(f -> f.type().equals("chorus:kill")).findFirst().orElseThrow();
                assertEquals("other", ((EffectEvent)kill.payload()).actor());
            } else h.kill();
            assertEquals(0, h.melee(), reason);
        }
    }
    @Test void immutableKillObservationWinsOverLaterMovementAndRankChanges() throws Exception {
        var h = new Harness(); h.use("melee", true); h.afterReceipt = () -> { h.airborne(false); h.rank(4, false); }; h.kill(); assertEquals(.1, h.melee(), 1e-12);
    }
    @Test void duplicateAspectsDoNotMultiplyEnergyOrAirborneStatsAndActiveBonusExpiresSeparately() throws Exception {
        var h = new Harness(); h.send(SourceChange.bind(aspect("copy", 60))); assertEquals(20, h.ae()); h.consume(.3); assertEquals(70, h.ae()); h.use("melee", true); h.kill(); assertEquals(.1, h.melee(), 1e-12);
        h.send(SourceChange.remove("aspect")); assertEquals(70, h.ae()); h.send(SourceChange.remove("copy")); assertEquals(50, h.ae()); h.until(20_000_000); assertTrue(h.heat().isEmpty()); assertEquals(0, h.ae());
    }
    @Test void consumedHeatFeedsRealPhoenixAndIcarusDefinitionsWithoutSyntheticHeatGrant() throws Exception {
        var h = new Harness(); h.consume(.3); assertEquals(2, h.state().resources().get(new ResourceState.Key("player", IcarusDashTest.USES)).capacity());
        h.use("air_move", false); h.until(5_000_000); assertEquals(2, h.state().resources().get(new ResourceState.Key("player", IcarusDashTest.USES)).value());
        h.use("class", false); assertEquals(2, h.buff("restoration", "player").orElseThrow().tier()); h.land(); assertEquals(40, h.buff("scorch", "enemy").orElseThrow().count()); assertEquals(2, h.blasts().size());
    }
    @Test void sameLifeStateSurvivesCheckpointButMatchingDeathCancelsAndOldDeathDoesNot() throws Exception {
        var h = new Harness(); h.consume(.3); h.restore(); assertEquals(70, h.ae());
        for (String life : List.of("old-life", "life-1")) {
            h.send(new EntityLifecycle.Died(new EntityAnchor.Reference("player", life, "world"), "death/" + life, h.observation("player", "world", false)).signal());
            assertEquals(life.equals("old-life"), h.heat().isPresent());
        }
        assertEquals(20, h.ae()); var dead = new Harness(); dead.alive = false; dead.consume(.3); assertEquals(0, dead.grenade()); assertTrue(dead.heat().isEmpty()); assertTrue(dead.heals.isEmpty());
    }
    @Test void sharedCureCooldownDoesNotRefundOrPreventHeatAndRecipientDenialRemainsIndependent() throws Exception {
        var h = new Harness(); h.denied.add("ally"); h.consume(.3); h.until(100_000); assertEquals(2, h.cureHeals().size()); assertTrue(h.benefit().isEmpty());
        h.heatInput("full"); h.denied.clear(); h.consume(.3); assertEquals(15_100_000, h.heat().orElseThrow().deadline()); assertEquals(0, h.grenade()); h.until(200_000);
        assertEquals(4, h.cureHeals().size()); assertTrue(h.benefit().isPresent());
    }
    @Test void unknownRestorationOrHealingKeepsSpentEnergyAndCommittedHeatWithoutReplay() throws Exception {
        for (boolean status : List.of(true, false)) {
            var h = new Harness();
            if (status) { h.bind("player", "test:heat_wisp"); h.failBuff = D + "restoration"; assertThrows(IllegalStateException.class, () -> h.consume(.3)); assertEquals(.75, h.wisp()); }
            else { h.consume(.3); h.unknownHeal = true; assertThrows(IllegalStateException.class, () -> h.until(50_000)); assertTrue(h.grenade() < .01); }
            assertTrue(h.heat().isPresent()); int heals = h.heals.size(), checks = h.statuses.size(); assertThrows(IllegalStateException.class, () -> h.until(1_000_000)); assertEquals(heals, h.heals.size()); assertEquals(checks, h.statuses.size());
        }
    }
}
