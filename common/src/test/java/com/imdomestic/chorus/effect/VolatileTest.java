package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.TargetQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class VolatileTest {
    private static final String VOLATILE = "chorus_d2:volatile", COOLDOWN = "chorus_d2:volatile_cooldown";
    private static final EffectSource SOURCE = source("chorus_d2:volatile_application");
    private static final BuffInstance.Origin FOREIGN = new BuffInstance.Origin("other", "other-weapon", "other-weapon", "");
    private static List<RuleEngine.Signal> facts(String id, String target, BuffInstance.Origin origin, double amount, boolean apply, boolean lethal) {
        var command = new DamageCommand(target, origin, amount, "test:physical", apply ? Set.of("test:apply_volatile") : Set.of(), Set.of(), false);
        var receipt = new DamageReceipt(id, DamageReceipt.Outcome.APPLIED, 0, 0, amount, lethal ? Optional.of(id + "/death") : Optional.empty(), false);
        return DamageFacts.from(command, receipt);
    }
    private static final class Harness {
        final EffectSession session;
        final List<RuleEngine.WorldRequest> requests = new ArrayList<>();
        final List<DamageCommand> explosions = new ArrayList<>();
        StatusResult.Decision decision = StatusResult.Decision.ALLOWED;
        TargetQuery.Outcome queryOutcome = TargetQuery.Outcome.AVAILABLE;
        double distance;
        Harness(EffectState.Mode mode) throws Exception { this(mode, false); }
        Harness(EffectState.Mode mode, boolean anotherSource) throws Exception {
            var initial = EffectState.empty().withMode(mode).withSource(SOURCE);
            if (anotherSource) initial = initial.withSource(new EffectSource("other-source", SOURCE.bundle(), "other", FOREIGN, Set.of()));
            session = new EffectSession(engine(load("volatile")), initial, request -> {
                requests.add(request);
                return switch (request.command()) {
                    case StatusResult.Check check -> new StatusResult.Checked(check, decision);
                    case TargetQuery query -> new TargetQuery.Result(query, queryOutcome,
                            queryOutcome == TargetQuery.Outcome.AVAILABLE ? List.of(new TargetQuery.Target("neighbor", distance)) : List.of());
                    case DamageCommand damage -> {
                        explosions.add(damage);
                        yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, damage.amount(), Optional.empty(), false);
                    }
                    default -> throw new AssertionError("Unexpected command " + request.command());
                };
            });
        }
        void hit(long time, String id, double amount, boolean apply, boolean lethal) { hit(time, id, "target", SOURCE.origin(), amount, apply, lethal); }
        void hit(long time, String id, String target, BuffInstance.Origin origin, double amount, boolean apply, boolean lethal) {
            session.observe(time, facts(id, target, origin, amount, apply, lethal));
            assertTrue(session.state().engine().failure().isEmpty()); assertTrue(session.state().idle());
        }
        Optional<BuffInstance> buff(String id) { return state().buffs().instances().values().stream().filter(b -> b.key().holder().equals("target") && b.definition().id().equals(id)).findFirst(); }
        EffectState state() { return session.state().engine().domain(); }
        double accumulated() { return buff(VOLATILE).orElseThrow().components().numbers().get("damage"); }
        long queries() { return requests.stream().filter(r -> r.command() instanceof TargetQuery).count(); }
    }
    @Test void applyingHitIsExcludedEvenWhenLargeOrReplayedAndSubsequentHitsAccumulateOnce() throws Exception {
        var test = new Harness(EffectState.Mode.PVE);
        test.hit(0, "apply", 100, true, false);
        assertEquals(0, test.accumulated()); assertEquals(0, test.queries());
        assertEquals(Set.of("apply"), test.buff(VOLATILE).orElseThrow().components().sets().get("seen_damage"));
        test.hit(0, "apply", 100, true, false); assertEquals(0, test.accumulated());
        test.hit(100_000, "one", 10, true, false); assertEquals(10, test.accumulated());
        test.hit(100_000, "one", 10, true, false); assertEquals(10, test.accumulated());
        test.hit(200_000, "two", 8.5, false, false); assertEquals(18.5, test.accumulated()); assertEquals(0, test.queries());
        test.hit(300_000, "threshold", .5, false, false);
        assertTrue(test.buff(VOLATILE).isEmpty()); assertEquals(1, test.queries()); assertEquals(14.5, test.explosions.getFirst().amount());
        assertEquals(1_300_000, test.buff(COOLDOWN).orElseThrow().deadline());
    }
    @Test void cooldownIsSharedByTargetAndStartsAtDetonationWithExactReapplicationBoundary() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.hit(0, "apply", 1, true, false); long generation = test.buff(VOLATILE).orElseThrow().generation();
        test.hit(2_000_000, "threshold", 19, false, false);
        test.hit(2_999_999, "blocked", 100, true, false);
        assertTrue(test.buff(VOLATILE).isEmpty()); assertEquals(1, test.queries()); assertEquals(3_000_000, test.buff(COOLDOWN).orElseThrow().deadline());
        test.hit(3_000_000, "next-apply", 100, true, false);
        assertEquals(0, test.accumulated()); assertTrue(test.buff(COOLDOWN).isEmpty()); assertNotEquals(generation, test.buff(VOLATILE).orElseThrow().generation());
        assertEquals(13_000_000, test.buff(VOLATILE).orElseThrow().deadline());
    }
    @Test void pvpUsesItsOwnThresholdDurationAndExplosionDamageAndExpiryIsHarmless() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var test = new Harness(mode); long duration = mode == EffectState.Mode.PVP ? 6_000_000 : 10_000_000;
            double threshold = mode == EffectState.Mode.PVP ? 20 : 19;
            test.hit(0, "apply", 1, true, false); assertEquals(duration, test.buff(VOLATILE).orElseThrow().deadline());
            test.hit(1_000_000, "below", threshold - 1, true, false);
            assertEquals(duration, test.buff(VOLATILE).orElseThrow().deadline(), "Fixture retains the first duration while active");
            test.hit(2_000_000, "exact", 1, false, false);
            assertEquals(mode == EffectState.Mode.PVP ? 8 : 14.5, test.explosions.getFirst().amount());
            var expired = new Harness(mode); expired.hit(0, "apply", 1, true, false);
            expired.session.observe(duration - 1, List.of()); assertTrue(expired.buff(VOLATILE).isPresent());
            expired.session.observe(duration, List.of()); assertTrue(expired.buff(VOLATILE).isEmpty()); assertTrue(expired.buff(COOLDOWN).isEmpty()); assertEquals(0, expired.queries());
        }
    }
    @Test void anotherOwnersApplicationCannotStealAnActiveStatusOrBypassItsTargetCooldown() throws Exception {
        var test = new Harness(EffectState.Mode.PVE, true); test.hit(0, "apply", 1, true, false);
        test.hit(0, "other-trigger", "target", FOREIGN, 19, true, false);
        assertEquals(SOURCE.origin(), test.explosions.getFirst().source());
        test.hit(999_999, "other-blocked", "target", FOREIGN, 1, true, false);
        assertTrue(test.buff(VOLATILE).isEmpty()); assertEquals(1, test.queries());
        test.hit(1_000_000, "other-new", "target", FOREIGN, 100, true, false);
        assertEquals(FOREIGN, test.buff(VOLATILE).orElseThrow().origin()); assertEquals(0, test.accumulated());
    }
    @Test void firstLethalApplicationChecksEligibilityAndExplodesOnceWithoutCreatingAStatus() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.hit(0, "fatal-application", 1, true, true);
        var check = (StatusResult.Check) test.requests.getFirst().command(); assertTrue(check.allowDead());
        assertEquals("target", check.target()); assertTrue(test.buff(VOLATILE).isEmpty()); assertTrue(test.buff(COOLDOWN).isPresent());
        assertEquals(1, test.queries()); assertEquals(1, test.explosions.size());
        assertEquals(Set.of(COOLDOWN), test.state().buffs().instances().values().stream().map(b -> b.definition().id()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void rejectedEligibilityAndOrdinaryFailedApplicationsDoNotGrantCooldownOrExplode() throws Exception {
        for (boolean lethal : List.of(false, true)) for (var decision : List.of(StatusResult.Decision.DENIED, StatusResult.Decision.DEAD, StatusResult.Decision.MISSING)) {
            var test = new Harness(EffectState.Mode.PVE); test.decision = decision; test.hit(0, "rejected", 1, true, lethal);
            assertTrue(test.state().buffs().instances().isEmpty()); assertEquals(0, test.queries()); assertTrue(test.explosions.isEmpty());
            assertEquals(lethal, ((StatusResult.Check) test.requests.getFirst().command()).allowDead());
        }
    }
    @Test void alreadyActiveLethalHitAndFollowingDeathFactDoNotDoubleDetonate() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.hit(0, "apply", 1, true, false);
        test.hit(100_000, "fatal", 1, true, true);
        assertEquals(1, test.queries()); assertEquals(1, test.explosions.size()); assertTrue(test.buff(VOLATILE).isEmpty());
        assertEquals(1, test.requests.stream().filter(r -> r.command() instanceof StatusResult.Check).count(), "No second application transaction on an already-active target");
    }
    @Test void deathWithoutHitStillDetonatesAndMissingCenterConsumesStatusWithoutInventingDamage() throws Exception {
        var test = new Harness(EffectState.Mode.PVE); test.hit(0, "apply", 1, true, false);
        var death = facts("death-only", "target", FOREIGN, 1, false, true).stream().filter(f -> f.type().equals("chorus:death")).findFirst().orElseThrow();
        test.session.start(100_000, death); assertEquals(1, test.queries()); assertTrue(test.buff(VOLATILE).isEmpty());
        var missing = new Harness(EffectState.Mode.PVE); missing.hit(0, "apply", 1, true, false); missing.queryOutcome = TargetQuery.Outcome.MISSING_CENTER;
        missing.hit(100_000, "fatal", 1, false, true);
        assertTrue(missing.buff(VOLATILE).isEmpty()); assertTrue(missing.buff(COOLDOWN).isPresent()); assertEquals(1, missing.queries()); assertTrue(missing.explosions.isEmpty());
    }
    @Test void anyAttackerCanDetonateButExplosionKeepsApplicationOwnerAndExplicitAbilityCredit() throws Exception {
        var test = new Harness(EffectState.Mode.PVE);
        test.hit(0, "foreign-apply", "target", FOREIGN, 100, true, false); assertTrue(test.state().buffs().instances().isEmpty());
        test.hit(0, "apply", 1, true, false); test.hit(100_000, "elsewhere", "elsewhere", FOREIGN, 100, false, false); assertEquals(0, test.accumulated());
        test.hit(200_000, "foreign-threshold", "target", FOREIGN, 19, false, false);
        var explosion = test.explosions.getFirst(); assertEquals(SOURCE.origin(), explosion.source());
        assertTrue(explosion.tags().containsAll(Set.of("chorus:void_damage", "chorus:ability_damage", "chorus_d2:volatile_damage")));
        assertEquals(Set.of("chorus:ability_kill"), explosion.killTags()); assertFalse(explosion.tags().contains("test:apply_volatile"));
    }
    @Test void explicitTestFalloffUsesCapturedDistanceAndProgramRoundTrips() throws Exception {
        var compiled = load("volatile");
        assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, compiled.program()).getOrThrow()).getOrThrow().program());
        var test = new Harness(EffectState.Mode.PVE); test.distance = 2.5; test.hit(0, "apply", 1, true, false); test.hit(100_000, "threshold", 19, false, false);
        assertEquals(7.25, test.explosions.getFirst().amount());
    }
    @Test void qualificationCannotSmuggleDeadPermissionIntoApplicationAndCannotBeReadAsApplied() throws Exception {
        var compiled = load("volatile"); var engine = engine(compiled); var initial = engine.initial(EffectState.empty().withSource(SOURCE));
        var event = (EffectEvent) facts("apply", "target", SOURCE.origin(), 1, true, false).getFirst().payload();
        var wait = send(engine, initial, 0, "chorus:hit", event); var request = (StatusResult.Check) wait.actions().getFirst().command(); assertFalse(request.allowDead());
        var changed = new StatusResult.Check(request.target(), request.definition(), request.source(), request.stacks(), request.tier(), request.duration(), true);
        var failure = engine.transition(wait.state(), new RuleEngine.Completed(wait.actions().getFirst().id(), new StatusResult.Checked(changed, StatusResult.Decision.ALLOWED)));
        assertTrue(failure.state().engine().failure().isPresent()); assertTrue(failure.state().engine().domain().buffs().instances().isEmpty());
        var data = json("volatile"); var then = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject()
                .getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonArray("then");
        then.get(1).getAsJsonObject().getAsJsonObject("if").addProperty("field", "applied");
        assertThrows(RuntimeException.class, () -> compile(data));
    }
}
