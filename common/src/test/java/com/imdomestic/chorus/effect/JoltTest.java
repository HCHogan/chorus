package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Executable, deliberately partial Compendium projection; see docs/d2-ruleset.md. */
class JoltTest {
    private static final String JOLT = "chorus_d2:jolt", COOLDOWN = "chorus_d2:jolt_cooldown";
    private static final EffectSource SOURCE = source("chorus_d2:jolt_application");
    private static final BuffInstance.Origin FOREIGN = new BuffInstance.Origin("other", "other-weapon", "other-weapon", "");
    private static final EntityQuery.View NPC = new EntityQuery.View(true, false, 100, 100, 0);
    private static final EntityQuery.View PLAYER = new EntityQuery.View(true, true, 100, 100, 0);
    private static final class Harness {
        final EffectSession session;
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        final List<DamageCommand> chains = new ArrayList<>();
        final Map<String, EntityQuery.View> views = new HashMap<>(Map.of("target", NPC, "neighbor", NPC));
        final Map<String, List<String>> nearby = new HashMap<>(Map.of("target", List.of("neighbor")));
        final Map<String, DamageReceipt.Outcome> outcomes = new HashMap<>();
        final Map<String, DamageReceipt> losses = new HashMap<>();
        StatusResult.Decision decision = StatusResult.Decision.ALLOWED;
        TargetQuery.Outcome queryOutcome = TargetQuery.Outcome.AVAILABLE;
        Harness(EffectState.Mode mode) throws Exception {
            var state = EffectState.empty().withMode(mode).withSource(SOURCE)
                    .withSource(new EffectSource("other-application", SOURCE.bundle(), FOREIGN.owner(), FOREIGN, Set.of()));
            session = new EffectSession(engine(load("jolt")), state, request -> {
                requests.add(request);
                return switch (request.command()) {
                    case StatusResult.Check check -> new StatusResult.Checked(check, decision);
                    case EntityQuery query -> new EntityQuery.Result(query, Optional.ofNullable(views.get(query.target())));
                    case TargetQuery query -> new TargetQuery.Result(query, queryOutcome, queryOutcome == TargetQuery.Outcome.AVAILABLE
                            ? nearby.getOrDefault(((TargetQuery.EntityCenter) query.center()).entity(), List.of()).stream()
                                    .map(id -> new TargetQuery.Target(id, 1)).sorted(query.comparator()).toList() : List.of());
                    case DamageCommand damage -> {
                        chains.add(damage);
                        var outcome = outcomes.getOrDefault(damage.target(), DamageReceipt.Outcome.APPLIED);
                        var loss = losses.get(damage.target());
                        yield new DamageReceipt(request.id().toString(), outcome, loss == null ? 0 : loss.shieldLoss(),
                                loss == null ? 0 : loss.absorptionLoss(), loss == null ? (outcome == DamageReceipt.Outcome.APPLIED ? damage.amount() : 0) : loss.healthLoss(),
                                Optional.empty(), false);
                    }
                    default -> throw new AssertionError("Unexpected command " + request.command());
                };
            });
        }
        void hit(long time, String id, double amount, boolean apply) { hit(time, id, "target", SOURCE.origin(), amount, apply, false, false); }
        void hit(long time, String id, String target, BuffInstance.Origin origin, double amount, boolean apply, boolean afterDamage, boolean lethal) {
            var tags = new HashSet<String>(Set.of("chorus:weapon_damage"));
            if (apply) tags.add("test:apply_jolt"); if (afterDamage) tags.add("test:jolt_after_damage");
            var command = new DamageCommand(target, origin, amount, "test:physical", tags, Set.of("chorus:weapon_kill"), false);
            session.observe(time, DamageFacts.from(command, new DamageReceipt(id, DamageReceipt.Outcome.APPLIED, 0, 0, amount,
                    lethal ? Optional.of(id + "/death") : Optional.empty(), false)));
            assertTrue(session.state().engine().failure().isEmpty()); assertTrue(session.state().idle());
        }
        Optional<BuffInstance> buff(String definition, String holder) { return session.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.key().holder().equals(holder) && b.definition().id().equals(definition)).findFirst(); }
        BuffInstance jolt() { return buff(JOLT, "target").orElseThrow(); }
        double accumulated() { return jolt().components().numbers().get("damage"); }
        long queries() { return requests.stream().filter(r -> r.command() instanceof TargetQuery).count(); }
        long selfHits() { return chains.stream().filter(c -> c.target().equals("target")).count(); }
    }
    @Test void applicationCountsOnceIncludingImmediateThresholdAndReapplicationRefreshesDuration() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            double threshold = mode == EffectState.Mode.PVP ? 4.5 : 11.5;
            long duration = mode == EffectState.Mode.PVP ? 5_000_000 : 10_000_000;
            var test = new Harness(mode); test.hit(0, "apply", 1, true);
            assertEquals(1, test.accumulated()); assertEquals(duration, test.jolt().deadline());
            test.hit(0, "apply", 1, true); assertEquals(1, test.accumulated());
            test.hit(1_000_000, "refresh", 1, true);
            assertEquals(2, test.accumulated(), "active listener and application continuation share a damage id");
            assertEquals(duration + 1_000_000, test.jolt().deadline());
            test.hit(1_000_000, "exact", threshold - 2, false);
            assertEquals(1, test.queries()); assertEquals(2, test.chains.size()); assertEquals(0, test.accumulated());
            assertEquals(mode == EffectState.Mode.PVP ? 5.1 : 11.9, test.chains.getFirst().amount(), 1e-9);
            var instant = new Harness(mode); instant.hit(0, "instant", threshold, true); assertEquals(1, instant.queries());
        }
    }
    @Test void cooldownIsArmedBeforeChainDamageAndDiscardsHitsWithoutAllowingLaterReplay() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.hit(0, "large-application", 100, true);
        assertEquals(1, test.queries()); assertEquals(0, test.accumulated(), "overflow and self-chain damage are discarded by this fixture");
        assertEquals(800_000, test.buff(COOLDOWN, "target").orElseThrow().deadline());
        test.hit(799_999, "during-cooldown", 100, true); assertEquals(0, test.accumulated()); assertEquals(1, test.queries());
        test.hit(800_000, "during-cooldown", 100, false); assertEquals(0, test.accumulated()); assertEquals(1, test.queries());
        test.hit(800_000, "new-threshold", 11.5, false); assertEquals(2, test.queries());
        assertEquals(1_600_000, test.buff(COOLDOWN, "target").orElseThrow().deadline());
    }
    @Test void triggeringAttackerOwnsChainsWithoutChangingApplicationOwnerOrCopyingWeaponCredit() throws Exception {
        for (var trigger : List.of(FOREIGN, new BuffInstance.Origin("", "environment", "", ""))) {
            var test = new Harness(EffectState.Mode.PVE); test.hit(0, "apply", 1, true);
            test.hit(100_000, "trigger", "target", trigger, 10.5, false, false, false);
            assertEquals(SOURCE.origin(), test.jolt().origin()); assertEquals(2, test.chains.size());
            assertTrue(test.chains.stream().allMatch(c -> c.source().equals(trigger)));
            assertTrue(test.chains.stream().allMatch(c -> c.tags().equals(Set.of("chorus:arc_damage", "chorus:status_damage", "chorus_d2:jolt_damage"))
                    && c.killTags().isEmpty() && c.scalingProfile().isEmpty()));
            var query = (TargetQuery) test.requests.stream().filter(r -> r.command() instanceof TargetQuery).findFirst().orElseThrow().command();
            assertEquals(SOURCE.origin().owner(), query.relativeTo()); assertEquals(Set.of(SOURCE.origin().owner()), query.exclude());
            assertEquals(12, query.radius()); assertFalse(query.includeCenter()); assertEquals(TargetQuery.Relation.NOT_ALLIED, query.relation());
        }
        var refreshed = new Harness(EffectState.Mode.PVE); refreshed.hit(0, "apply", 1, true);
        refreshed.hit(100_000, "other-refresh", "target", FOREIGN, 10.5, true, false, false);
        assertEquals(SOURCE.origin(), refreshed.jolt().origin()); assertEquals(10_100_000, refreshed.jolt().deadline());
        assertEquals(FOREIGN, refreshed.chains.getFirst().source());
    }
    @Test void playerCenterNeedsActualDamageToAnotherPlayerInEitherActivityMode() throws Exception {
        for (var mode : EffectState.Mode.values()) for (var outcome : DamageReceipt.Outcome.values()) {
            var test = new Harness(mode); test.views.put("target", PLAYER); test.views.put("neighbor", PLAYER); test.outcomes.put("neighbor", outcome);
            test.hit(0, "apply", 12, true);
            assertEquals(outcome == DamageReceipt.Outcome.APPLIED ? 1 : 0, test.selfHits(), mode + "/" + outcome);
        }
        var npcOnly = new Harness(EffectState.Mode.PVP); npcOnly.views.put("target", PLAYER); npcOnly.hit(0, "apply", 5, true);
        assertEquals(0, npcOnly.selfHits()); assertEquals(1, npcOnly.chains.size());
        var alone = new Harness(EffectState.Mode.PVP); alone.views.put("target", PLAYER); alone.nearby.clear(); alone.hit(0, "apply", 5, true);
        assertTrue(alone.chains.isEmpty()); assertTrue(alone.buff(COOLDOWN, "target").isPresent());
    }
    @Test void zeroLossDoesNotQualifyButRealShieldOrAbsorptionLossDoes() throws Exception {
        for (var loss : List.of(new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{0, 1, 0}, new double[]{0, 0, 1})) {
            var test = new Harness(EffectState.Mode.PVP); test.views.put("target", PLAYER); test.views.put("neighbor", PLAYER);
            test.losses.put("neighbor", new DamageReceipt("loss", DamageReceipt.Outcome.APPLIED, loss[0], loss[1], loss[2], Optional.empty(), false));
            test.hit(0, "apply", 5, true); assertEquals(loss[0] + loss[1] + loss[2] > 0 ? 1 : 0, test.selfHits());
        }
    }
    @Test void missingOrDeadNeighborsAreSkippedAndMissingCenterNeverInventsTargets() throws Exception {
        for (boolean dead : List.of(false, true)) {
            var test = new Harness(EffectState.Mode.PVE);
            if (dead) test.views.put("neighbor", new EntityQuery.View(false, true, 0, 100, 0)); else test.views.remove("neighbor");
            test.hit(0, "apply", 12, true); assertEquals(1, test.selfHits()); assertEquals(1, test.chains.size());
        }
        var missing = new Harness(EffectState.Mode.PVE); missing.views.remove("target"); missing.hit(0, "apply", 12, true);
        assertEquals(0, missing.queries()); assertTrue(missing.chains.isEmpty()); assertTrue(missing.buff(COOLDOWN, "target").isPresent());
        var unknownRelation = new Harness(EffectState.Mode.PVE); unknownRelation.queryOutcome = TargetQuery.Outcome.MISSING_RELATIVE;
        unknownRelation.hit(0, "apply", 12, true); assertTrue(unknownRelation.chains.isEmpty(), "unavailable allegiance cannot authorize any chain damage");
    }
    @Test void afterDamageApplicationExcludesOnlyNewApplicationAndAlreadyActiveStatusStillCounts() throws Exception {
        var test = new Harness(EffectState.Mode.PVE);
        test.hit(0, "late-application", "target", SOURCE.origin(), 100, true, true, false);
        assertEquals(0, test.accumulated()); assertEquals(0, test.queries());
        test.hit(100_000, "ordinary-next", 1, false); assertEquals(1, test.accumulated());
        test.hit(200_000, "late-refresh", "target", SOURCE.origin(), 10.5, true, true, false);
        assertEquals(1, test.queries()); assertEquals(2, test.chains.size());
    }
    @Test void lethalThresholdChainsBeforeDeathCleanupButDeathAloneAndExpiryDoNotDetonate() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.hit(0, "apply", 1, true);
        test.views.put("target", new EntityQuery.View(false, false, 0, 100, 0)); test.decision = StatusResult.Decision.DEAD;
        test.hit(100_000, "fatal", "target", FOREIGN, 10.5, true, false, true);
        assertEquals(1, test.queries()); assertEquals(List.of("neighbor"), test.chains.stream().map(DamageCommand::target).toList());
        assertTrue(test.buff(JOLT, "target").isEmpty());
        var below = new Harness(EffectState.Mode.PVE); below.hit(0, "apply", 1, true);
        below.hit(100_000, "fatal-below", "target", FOREIGN, 1, false, false, true);
        assertTrue(below.buff(JOLT, "target").isEmpty()); assertEquals(0, below.queries());
        for (var mode : EffectState.Mode.values()) {
            var expired = new Harness(mode); expired.hit(0, "apply", 1, true); long deadline = expired.jolt().deadline();
            expired.session.observe(deadline - 1, List.of()); assertTrue(expired.buff(JOLT, "target").isPresent());
            expired.session.observe(deadline, List.of()); assertTrue(expired.buff(JOLT, "target").isEmpty()); assertEquals(0, expired.queries());
        }
    }
    @Test void rejectedApplicationIncludingFirstFatalApplicationCannotCreateStatusOrChains() throws Exception {
        for (var decision : List.of(StatusResult.Decision.DENIED, StatusResult.Decision.DEAD, StatusResult.Decision.MISSING)) {
            var test = new Harness(EffectState.Mode.PVE); test.decision = decision;
            test.hit(0, "rejected", "target", SOURCE.origin(), 100, true, false, decision == StatusResult.Decision.DEAD);
            assertTrue(test.session.state().engine().domain().buffs().instances().isEmpty()); assertTrue(test.chains.isEmpty());
            assertFalse(((StatusResult.Check) test.requests.getFirst().command()).allowDead());
        }
    }
    @Test void adjacentJoltedEnemiesMayChainInTheSameCausalTreeWithTheirOwnCooldowns() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.nearby.put("neighbor", List.of("target"));
        test.hit(0, "apply-target", 1, true);
        test.hit(0, "apply-neighbor", "neighbor", SOURCE.origin(), 1, true, false, false);
        test.hit(100_000, "trigger", "target", FOREIGN, 10.5, false, false, false);
        assertEquals(2, test.queries()); assertEquals(4, test.chains.size());
        assertTrue(test.chains.stream().allMatch(c -> c.source().equals(FOREIGN)));
        assertTrue(test.buff(COOLDOWN, "target").isPresent()); assertTrue(test.buff(COOLDOWN, "neighbor").isPresent());
    }
    @Test void fixtureRoundTripsAndBothHitEntryPointsUseTheSameCounterBody() throws Exception {
        var compiled = load("jolt"); assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,
                EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, compiled.program()).getOrThrow()).getOrThrow().program());
        var rules = json("jolt").getAsJsonArray("bundles").get(1).getAsJsonObject().getAsJsonArray("rules");
        assertEquals(rules.get(0).getAsJsonObject().get("do"), rules.get(1).getAsJsonObject().get("do"));
    }
}
