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

class BenevolenceTest {
    static final String D = "chorus_d2:";
    static CompiledEffects program() throws Exception {
        var parts = new ArrayList<EffectProgram>(); parts.add(AcrobatDodgeTest.program(true).program());
        for (String name : List.of("ember_of_benevolence", "grenade_energy", "solar_melee_energy", "cure", "restoration", "healing_rift", "benevolence_test_inputs")) {
            var data = json(name); AmplifiedTest.version(data); parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, data).getOrThrow());
        }
        return CompiledEffects.link(parts);
    }
    static EffectSource fragment(String who, String id) { return new EffectSource(id, D + "ember_of_benevolence", who, new BuffInstance.Origin(who, id, "", ""), Set.of()); }
    static EffectSource input(String who, boolean excluded) { var tags = excluded ? Set.of(D + "benevolence_excluded") : Set.<String>of(); return new EffectSource(who + "/test:benevolence_inputs", "test:benevolence_inputs", who, new BuffInstance.Origin(who, who + "/producer", "", "", tags), tags); }
    static class Harness extends AcrobatDodgeTest.Harness {
        Optional<Boolean> allied = Optional.of(true); final Set<String> dead = new HashSet<>(), missing = new HashSet<>();
        final List<HealingCommand> heals = new ArrayList<>(); List<RuleEngine.Signal> lastHealingFacts = List.of();
        boolean rejectHeal, unknownHeal, overheal;
        Harness() throws Exception { this(EffectState.Mode.PVE); }
        Harness(EffectState.Mode mode) throws Exception {
            this(program(), mode);
        }
        Harness(CompiledEffects program, EffectState.Mode mode) {
            super(program, mode); send(SourceChange.bind(input("player", false))); bind("player", "test:benevolence_observer");
            send(SourceChange.bind(fragment("player", "fragment")));
            var old = state().abilities().get("player"); var slots = new HashMap<>(old.slots()); slots.put(D + "grenade", "test:benevolence_grenade"); slots.put(D + "melee", "test:benevolence_melee");
            send(new AbilityChange("player", old, new AbilityLoadout(slots)).signal());
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            return switch (r.command()) {
                case RelationQuery q -> new RelationQuery.Result(q, allied);
                case EntityQuery q -> new EntityQuery.Result(q, missing.contains(q.target()) ? Optional.empty() : Optional.of(new EntityQuery.View(!dead.contains(q.target()), true, dead.contains(q.target()) ? 0 : 100, 100, 0)));
                case HealingCommand c -> {
                    heals.add(c); if (unknownHeal) throw new IllegalStateException("Unknown healing outcome");
                    var receipt = rejectHeal ? HealingReceipt.unapplied(r.id().toString(), c, HealingReceipt.Outcome.REJECTED)
                            : new HealingReceipt(r.id().toString(), c, HealingReceipt.Outcome.APPLIED, c.amount(), overheal ? 0 : c.amount(), overheal ? c.amount() : 0);
                    if (c.tags().contains(D + "cure")) lastHealingFacts = HealingFacts.from(receipt);
                    yield receipt;
                }
                default -> super.world(r);
            };
        }
        Optional<BuffInstance> buff(String name, String who) { return state().buffs().instances().values().stream().filter(b -> b.key().definition().equals(D + name) && b.key().holder().equals(who)).findFirst(); }
        Optional<BuffInstance> benefit() { return buff("benevolence_regeneration", "player"); }
        void give(String event, String actor, String victim) { send(new RuleEngine.Signal("test:" + event, new EffectEvent(actor, victim, input(actor, false).origin(), Set.of(), Map.of()))); }
        double energy(String resource) { return state().resources().get(new ResourceState.Key("player", D + resource)).value(); }
        void drain() { queryMissing = true; for (String slot : List.of("class", "grenade", "melee")) assertEquals(AbilityUse.Outcome.ACCEPTED, use(slot, true).outcome()); }
        void stats(double grenade, double melee, double classDelta) { send(SourceChange.bind(new EffectSource("stats", "test:benevolence_stats", "player", new BuffInstance.Origin("player", "stats", "", ""), Set.of(), Map.of("grenade", new Measure(grenade, Unit.STAT_POINT), "melee", new Measure(melee, Unit.STAT_POINT), "class_delta", new Measure(classDelta, Unit.DELTA))))); }
        long rewards() { return heals.stream().filter(h -> h.tags().contains("test:benevolence_credit")).count(); }
    }
    @Test void sharedProfilesAddThreeTimesThePreStatBaselineForAllThreeSelectedReceivingAccounts() throws Exception {
        var h = new Harness(); h.stats(110, 100, 1); h.drain(); h.give("radiant_apply_status", "player", "ally"); h.until(1_000_000);
        assertEquals(.0575, h.energy("grenade_energy"), 1e-10); assertEquals(.0575, h.energy("solar_melee_progress"), 1e-10); assertEquals(5.0 / 56, h.energy(), 1e-10);
        assertEquals(1, h.rewards()); assertEquals(7_000_000, h.benefit().orElseThrow().deadline());
        assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow()).getOrThrow());
    }
    @Test void bothModesRefreshRatherThanStackAndExpirySplitsPassiveRecoveryAtTheExactBoundary() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode); h.drain(); h.give("radiant_grant_buff", "player", "ally"); long seconds = mode == EffectState.Mode.PVE ? 7 : 4;
            h.until(2_000_000); h.give("radiant_apply_status", "player", "ally"); assertEquals((seconds + 2) * 1_000_000, h.benefit().orElseThrow().deadline());
            h.until((seconds + 3) * 1_000_000); assertTrue(h.benefit().isEmpty());
            assertEquals(.04 * (seconds + 2) + .01, h.energy("grenade_energy"), 1e-9);
        }
    }
    @Test void duplicatesDoNotRepeatTheFragmentPenaltyOrOneConfirmedBenefitReward() throws Exception {
        var h = new Harness(); h.send(SourceChange.bind(fragment("player", "copy"))); h.stats(110, 100, 1); h.drain();
        h.give("radiant_grant_buff", "player", "ally"); h.until(1_000_000); assertEquals(1, h.rewards()); assertEquals(.0575, h.energy("grenade_energy"), 1e-10);
        h.send(SourceChange.remove("copy")); h.until(2_000_000); assertEquals(.115, h.energy("grenade_energy"), 1e-10);
    }
    @Test void currentApplicantReceivesRefreshCreditEvenWhenRadiantKeepsTheOriginalGiver() throws Exception {
        var h = new Harness(); h.send(SourceChange.bind(input("other", false))); h.send(SourceChange.bind(fragment("other", "other_fragment")));
        h.give("radiant_apply_status", "other", "ally"); assertTrue(h.benefit().isEmpty()); var origin = h.radiant("ally").orElseThrow().origin();
        h.until(1_000_000); h.give("radiant_grant_buff", "player", "ally"); assertEquals(8_000_000, h.benefit().orElseThrow().deadline());
        assertEquals(origin, h.radiant("ally").orElseThrow().origin()); assertEquals(7_000_000, h.buff("benevolence_regeneration", "other").orElseThrow().deadline());
    }
    @Test void selfEnemyUnknownRelationDeadMissingAndExplicitlyExcludedProducersCannotTrigger() throws Exception {
        for (String reason : List.of("self", "enemy", "unknown", "dead", "missing", "dead_giver", "excluded")) {
            var h = new Harness(); String victim = reason.equals("self") ? "player" : "ally";
            switch (reason) { case "enemy" -> h.allied = Optional.of(false); case "unknown" -> h.allied = Optional.empty(); case "dead" -> h.dead.add(victim); case "missing" -> h.missing.add(victim); case "dead_giver" -> h.dead.add("player"); case "excluded" -> h.send(SourceChange.bind(input("player", true))); default -> {} }
            h.give("radiant_grant_buff", "player", victim); assertTrue(h.benefit().isEmpty(), reason); assertEquals(0, h.rewards());
        }
    }
    @Test void cureNeedsAnAcceptedHealAndOverhealRemainsDistinctFromRejectedOrOrdinaryHealing() throws Exception {
        var h = new Harness(); h.overheal = true; h.give("cure_bestow", "player", "ally"); assertTrue(h.benefit().isEmpty());
        h.until(50_000); assertEquals(7_050_000, h.benefit().orElseThrow().deadline()); h.until(100_000); assertEquals(7_100_000, h.benefit().orElseThrow().deadline()); assertEquals(2, h.rewards());
        var facts = h.lastHealingFacts; h.until(200_000); for (var fact : facts) h.send(fact); assertEquals(7_100_000, h.benefit().orElseThrow().deadline()); assertEquals(2, h.rewards());
        var denied = new Harness(); denied.rejectHeal = true; denied.give("cure_bestow", "player", "ally"); denied.until(100_000); assertTrue(denied.benefit().isEmpty());
        var ordinary = new Harness(); ordinary.give("ordinary_heal", "player", "ally"); assertTrue(ordinary.benefit().isEmpty());
    }
    @Test void restorationGrantTriggersOnceWhileItsContinuousHealingDoesNotRefreshTheFragment() throws Exception {
        var h = new Harness(); h.give("restoration_grant_buff", "player", "ally"); h.until(3_000_000);
        assertEquals(7_000_000, h.benefit().orElseThrow().deadline()); assertEquals(1, h.rewards()); assertTrue(h.heals.stream().anyMatch(c -> c.tags().contains(D + "restoration")));
        h.give("restoration_apply_status", "player", "ally"); assertEquals(10_000_000, h.benefit().orElseThrow().deadline());
    }
    @Test void realAcrobatBenefitsReachTheFragmentOnlyAfterTheCompletionPhase() throws Exception {
        var h = new Harness(); h.use("class", true); assertTrue(h.benefit().isEmpty()); h.until(200_000); assertEquals(7_200_000, h.benefit().orElseThrow().deadline()); assertEquals(1, h.rewards());
        var denied = new Harness(); denied.denied.add("ally"); denied.use("class", true); denied.until(200_000); assertTrue(denied.benefit().isEmpty());
    }
    @Test void checkpointDetachDeselectionAndDeathRespectTheExistingBuffAndAccountLifetimes() throws Exception {
        var h = new Harness(); h.drain(); h.give("radiant_grant_buff", "player", "ally"); h.until(1_000_000); h.restore(); h.send(SourceChange.remove("fragment")); h.until(2_000_000);
        assertEquals(.08, h.energy("grenade_energy"), 1e-10); h.choose("grenade", null); h.until(3_000_000); assertEquals(.08, h.energy("grenade_energy"), 1e-10);
        h.send(new RuleEngine.Signal("chorus:death", new EffectEvent("enemy", "player", new BuffInstance.Origin("enemy", "kill", "", ""), Set.of(), Map.of()))); assertTrue(h.benefit().isEmpty());
    }
    @Test void unknownAuthorizationOrHealingStopsWithoutInventingRegeneration() throws Exception {
        var h = new Harness(); h.unknownStatus = true; assertThrows(IllegalStateException.class, () -> h.give("radiant_apply_status", "player", "ally")); assertTrue(h.benefit().isEmpty());
        var cure = new Harness(); cure.unknownHeal = true; cure.give("cure_bestow", "player", "ally"); assertThrows(IllegalStateException.class, () -> cure.until(50_000)); assertTrue(cure.benefit().isEmpty());
    }
    @Test void independentBaseFamiliesAddWhileChunkEnergyKeepsItsOwnStatAndRecipientScalar() throws Exception {
        var h = new Harness(); h.stats(110, 100, 1); h.drain(); h.give("radiant_grant_buff", "player", "ally");
        h.give("energy_gain", "player", "player"); assertEquals(.1125, h.energy("grenade_energy"), 1e-10); assertEquals(.1125, h.energy("solar_melee_progress"), 1e-10); assertEquals(.05, h.energy(), 1e-10);
        h.bind("player", "test:benevolence_independent_regeneration"); h.until(1_000_000);
        assertEquals(.05 + 6.5 / 56, h.energy(), 1e-10);
    }
    @Test void alliedCureUsesTheSharedCooldownAndPreservesItsActualGiverAcrossDelayedPulses() throws Exception {
        var h = new Harness(); h.send(SourceChange.bind(input("other", false))); h.send(SourceChange.bind(fragment("other", "other_fragment")));
        h.give("cure_bestow", "player", "ally"); h.give("cure_bestow", "other", "ally"); h.until(100_000);
        assertEquals(2, h.heals.stream().filter(c -> c.tags().contains(D + "cure")).count()); assertTrue(h.benefit().isPresent()); assertTrue(h.buff("benevolence_regeneration", "other").isEmpty());
        h.until(1_000_000); h.give("cure_bestow", "other", "ally"); h.until(1_100_000); assertEquals(8_100_000, h.buff("benevolence_regeneration", "other").orElseThrow().deadline());
        var denied = new Harness(); denied.denied.add("ally"); denied.give("cure_bestow", "player", "ally"); denied.until(100_000); assertTrue(denied.heals.isEmpty()); assertTrue(denied.buff("cure_cooldown", "ally").isEmpty());
    }
}
