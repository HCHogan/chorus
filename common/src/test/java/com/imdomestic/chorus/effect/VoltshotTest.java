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

class VoltshotTest {
    private static final String WINDOW = "chorus_d2:voltshot_window", READY = "chorus_d2:voltshot_ready", JOLT = "chorus_d2:jolt";
    private static EffectSource weapon(String owner, String weapon, boolean enhanced) {
        return new EffectSource(owner + "/" + weapon, "chorus_d2:voltshot", owner, new BuffInstance.Origin(owner, "perk/" + weapon, weapon, ""), enhanced ? Set.of("chorus:enhanced") : Set.of());
    }
    private static final EffectSource A = weapon("player", "weapon-a", false), B = weapon("player", "weapon-b", true);
    private static CompiledEffects program(boolean reverse) throws Exception {
        var jolt = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("jolt")).getOrThrow();
        var voltshot = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("voltshot")).getOrThrow();
        return CompiledEffects.link(reverse ? List.of(voltshot, jolt) : List.of(jolt, voltshot));
    }
    private static List<RuleEngine.Signal> facts(String id, String target, BuffInstance.Origin origin, double amount, boolean weapon, boolean lethal, DamageReceipt.Outcome outcome) {
        return DamageFacts.from(new DamageCommand(target, origin, amount, "test:shot", weapon ? Set.of("chorus:weapon_damage") : Set.of(),
                        weapon ? Set.of("chorus:weapon_kill") : Set.of(), false),
                new DamageReceipt(id, outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? amount : 0, lethal ? Optional.of(id + "/death") : Optional.empty(), false));
    }
    private static final class Harness {
        final EffectSession session;
        final List<StatusResult.Check> checks = new ArrayList<>();
        final List<DamageCommand> chains = new ArrayList<>();
        final List<String> nearby = new ArrayList<>();
        StatusResult.Decision decision = StatusResult.Decision.ALLOWED;
        Harness() throws Exception { this(EffectState.Mode.PVE, false); }
        Harness(EffectState.Mode mode, boolean reverse) throws Exception {
            session = new EffectSession(engine(program(reverse)), EffectState.empty().withMode(mode).withSource(A).withSource(B), request -> switch (request.command()) {
                case StatusResult.Check check -> { checks.add(check); yield new StatusResult.Checked(check, decision); }
                case EntityQuery query -> new EntityQuery.Result(query, Optional.of(new EntityQuery.View(true, false, 100, 100, 0)));
                case TargetQuery query -> new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE,
                        nearby.stream().map(id -> new TargetQuery.Target(id, 1)).sorted(query.comparator()).toList());
                case DamageCommand damage -> {
                    chains.add(damage);
                    yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, damage.target().equals("chain-victim") ? 1 : damage.amount(),
                            damage.target().equals("chain-victim") ? Optional.of(request.id() + "/death") : Optional.empty(), false);
                }
                default -> throw new AssertionError(request.command());
            });
        }
        void signal(long time, String type, EffectSource source) { session.start(time, new RuleEngine.Signal(type, new EffectEvent(source.holder(), "target", source.origin(), Set.of(), Map.of()))); settled(); }
        void kill(long time, EffectSource source) { session.observe(time, facts("kill-" + time + source.instance(), "kill-target", source.origin(), 1, true, true, DamageReceipt.Outcome.APPLIED)); settled(); }
        void reload(long time, EffectSource source) { signal(time, "chorus:reload_finished", source); }
        void arm(long time, EffectSource source) { kill(time, source); reload(time, source); }
        void hit(long time, String id, String target, EffectSource source, double amount) {
            session.observe(time, facts(id, target, source.origin(), amount, true, false, DamageReceipt.Outcome.APPLIED)); settled();
        }
        void settled() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty()); }
        Optional<BuffInstance> buff(String definition, EffectSource source) { return session.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.definition().id().equals(definition) && b.key().holder().equals(source.holder()) && b.origin().weapon().equals(source.origin().weapon())).findFirst(); }
        Optional<BuffInstance> jolt(String target) { return session.state().engine().domain().buffs().instances().values().stream()
                .filter(b -> b.definition().id().equals(JOLT) && b.key().holder().equals(target)).findFirst(); }
    }
    @Test void reloadRequiresMatchingCreditedWeaponKillAndOwnerAndDoesNotConfuseOtherReloadEvents() throws Exception {
        var test = new Harness(); test.reload(0, A); assertTrue(test.buff(READY, A).isEmpty());
        test.session.observe(0, facts("ability-kill", "kill-target", A.origin(), 1, false, true, DamageReceipt.Outcome.APPLIED));
        test.reload(0, A); assertTrue(test.buff(WINDOW, A).isEmpty()); assertTrue(test.buff(READY, A).isEmpty());
        var foreign = weapon("other-owner", "weapon-a", false);
        test.kill(0, foreign); test.reload(0, A); assertTrue(test.buff(WINDOW, A).isEmpty(), "weapon identity does not bypass owner qualification");
        test.kill(0, A); test.reload(0, B); test.reload(0, foreign);
        assertTrue(test.buff(READY, A).isEmpty()); assertTrue(test.buff(READY, B).isEmpty());
        test.signal(0, "chorus:reload_started", A); test.signal(0, "chorus:ammo_refilled", A); assertTrue(test.buff(READY, A).isEmpty());
        test.reload(0, A); assertEquals(7_000_000, test.buff(READY, A).orElseThrow().deadline());
    }
    @Test void reloadWindowAndNormalOrEnhancedReadyDurationUseExactHalfOpenBoundaries() throws Exception {
        for (var source : List.of(A, B)) {
            for (long reloadAt : List.of(5_299_999L, 5_300_000L)) {
                var test = new Harness(); test.kill(0, source); test.reload(reloadAt, source);
                assertEquals(reloadAt < 5_300_000, test.buff(READY, source).isPresent());
                if (reloadAt < 5_300_000) assertEquals(reloadAt + (source == A ? 7_000_000 : 8_000_000), test.buff(READY, source).orElseThrow().deadline());
            }
            for (boolean exact : List.of(false, true)) {
                var test = new Harness(); test.arm(0, source); long duration = source == A ? 7_000_000 : 8_000_000;
                test.hit(exact ? duration : duration - 1, "next", "target", source, 1);
                assertEquals(exact ? 0 : 1, test.checks.size()); assertEquals(!exact, test.jolt("target").isPresent());
                assertTrue(test.buff(READY, source).isEmpty());
            }
        }
    }
    @Test void bothWindowsContinueThroughStowAndEachWeaponRetainsItsOwnReadyHit() throws Exception {
        var test = new Harness(); test.kill(0, A); test.kill(0, B);
        test.signal(1_000_000, "chorus:weapon_stowed", A); test.signal(1_000_000, "chorus:weapon_stowed", B);
        assertEquals(5_300_000, test.buff(WINDOW, A).orElseThrow().deadline()); assertFalse(test.buff(WINDOW, A).orElseThrow().pausedAt().isPresent());
        test.reload(2_000_000, A); test.reload(2_000_000, B);
        test.signal(3_000_000, "chorus:weapon_stowed", A); test.signal(3_000_000, "chorus:weapon_stowed", B);
        assertEquals(9_000_000, test.buff(READY, A).orElseThrow().deadline()); assertEquals(10_000_000, test.buff(READY, B).orElseThrow().deadline());
        test.signal(4_000_000, "chorus:weapon_readied", A); test.hit(4_000_000, "a", "target-a", A, 1);
        assertTrue(test.buff(READY, A).isEmpty()); assertTrue(test.buff(READY, B).isPresent()); assertEquals("weapon-a", test.jolt("target-a").orElseThrow().origin().weapon());
        test.hit(4_000_000, "b", "target-b", B, 1); assertTrue(test.buff(READY, B).isEmpty()); assertEquals("weapon-b", test.jolt("target-b").orElseThrow().origin().weapon());
    }
    @Test void repeatedReloadsCanRefreshReadyUntilKillWindowEndsWithoutExtendingTheKillWindow() throws Exception {
        var test = new Harness(); test.arm(0, A); test.reload(1_000_000, A);
        assertEquals(8_000_000, test.buff(READY, A).orElseThrow().deadline()); assertEquals(5_300_000, test.buff(WINDOW, A).orElseThrow().deadline());
        test.hit(2_000_000, "first", "target", A, 1); assertTrue(test.buff(READY, A).isEmpty());
        test.reload(3_000_000, A); assertEquals(10_000_000, test.buff(READY, A).orElseThrow().deadline());
        test.reload(5_300_000, A); assertTrue(test.buff(WINDOW, A).isEmpty()); assertEquals(10_000_000, test.buff(READY, A).orElseThrow().deadline());
        test.kill(6_000_000, A); assertEquals(11_300_000, test.buff(WINDOW, A).orElseThrow().deadline());
        test.reload(7_000_000, A); assertEquals(14_000_000, test.buff(READY, A).orElseThrow().deadline());
    }
    @Test void firingMissesNonweaponAndOtherWeaponsDoNotConsumeButOnlyFirstCommittedWeaponHitApplies() throws Exception {
        var test = new Harness(); test.arm(0, A); test.signal(0, "chorus:weapon_fired", A);
        test.session.observe(0, facts("ability", "target", A.origin(), 1, false, false, DamageReceipt.Outcome.APPLIED));
        test.hit(0, "wrong-weapon", "target", B, 1);
        assertTrue(test.buff(READY, A).isPresent()); assertTrue(test.checks.isEmpty());
        var burst = new ArrayList<RuleEngine.Signal>(); burst.addAll(facts("one", "target-a", A.origin(), 1, true, false, DamageReceipt.Outcome.APPLIED));
        burst.addAll(facts("two", "target-b", A.origin(), 1, true, false, DamageReceipt.Outcome.APPLIED));
        test.session.observe(0, burst); test.settled();
        assertEquals(1, test.checks.size()); assertTrue(test.jolt("target-a").isPresent()); assertTrue(test.jolt("target-b").isEmpty());
        assertTrue(test.buff(READY, A).isEmpty());
    }
    @Test void applyingHitCountsOnceOnNewAndRefreshedJoltInEitherModeAndLinkOrder() throws Exception {
        for (var mode : EffectState.Mode.values()) for (boolean reverse : List.of(false, true)) {
            var test = new Harness(mode, reverse); test.arm(0, A); test.hit(0, "apply", "target", A, 1);
            assertEquals(1, test.jolt("target").orElseThrow().components().numbers().get("damage"));
            test.reload(100_000, A); test.hit(100_000, "refresh", "target", A, 1);
            assertEquals(2, test.jolt("target").orElseThrow().components().numbers().get("damage"), "old listener and application initializer do not double count");
            assertEquals(100_000 + (mode == EffectState.Mode.PVP ? 5_000_000 : 10_000_000), test.jolt("target").orElseThrow().deadline());
            test.hit(200_000, "threshold", "target", B, mode == EffectState.Mode.PVP ? 2.5 : 9.5);
            assertEquals(1, test.chains.size()); assertEquals(B.origin(), test.chains.getFirst().source());
            assertEquals(mode == EffectState.Mode.PVP ? 5.1 : 11.9, test.chains.getFirst().amount(), 1e-9);
            assertEquals(A.origin(), test.jolt("target").orElseThrow().origin());
        }
    }
    @Test void deniedDeadOrMissingStatusConsumesTheCommittedHitButCancelledOrFailedDamageHasNoHit() throws Exception {
        for (var decision : List.of(StatusResult.Decision.DENIED, StatusResult.Decision.DEAD, StatusResult.Decision.MISSING)) {
            var test = new Harness(); test.arm(0, A); test.decision = decision; test.hit(0, "rejected", "target", A, 1);
            assertTrue(test.buff(READY, A).isEmpty()); assertTrue(test.jolt("target").isEmpty()); assertEquals(1, test.checks.size());
        }
        for (var outcome : List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED)) {
            var test = new Harness(); test.arm(0, A); test.session.observe(0, facts("no-hit", "target", A.origin(), 1, true, false, outcome));
            assertTrue(test.buff(READY, A).isPresent()); assertTrue(test.checks.isEmpty());
        }
        for (var outcome : List.of(DamageReceipt.Outcome.IMMUNE, DamageReceipt.Outcome.BLOCKED)) {
            var test = new Harness(); test.arm(0, A); test.session.observe(0, facts("hit", "target", A.origin(), 1, true, false, outcome));
            assertTrue(test.buff(READY, A).isEmpty()); assertTrue(test.jolt("target").isPresent()); assertEquals(0, test.jolt("target").orElseThrow().components().numbers().get("damage"));
        }
    }
    @Test void joltKillCannotOpenAWeaponKillWindowOrConsumeAnotherWeaponsReadyCharge() throws Exception {
        var test = new Harness(); test.arm(0, A); test.arm(0, B); test.nearby.add("chain-victim");
        test.hit(5_300_000, "trigger", "target", A, 11.5);
        assertEquals(2, test.chains.size()); assertTrue(test.chains.stream().allMatch(c -> c.source().equals(A.origin()) && c.killTags().isEmpty() && !c.tags().contains("chorus:weapon_damage")));
        assertTrue(test.buff(WINDOW, A).isEmpty()); assertTrue(test.buff(WINDOW, B).isEmpty());
        assertTrue(test.buff(READY, A).isEmpty()); assertTrue(test.buff(READY, B).isPresent());
        test.reload(5_400_000, A); assertTrue(test.buff(READY, A).isEmpty());
    }
    @Test void actualWeaponLethalHitConsumesReadyAndOpensANewWindowEvenWhenDeadTargetRejectsJolt() throws Exception {
        var test = new Harness(); test.arm(0, A); test.decision = StatusResult.Decision.DEAD;
        test.session.observe(6_000_000, facts("lethal", "target", A.origin(), 1, true, true, DamageReceipt.Outcome.APPLIED)); test.settled();
        assertTrue(test.buff(READY, A).isEmpty()); assertTrue(test.jolt("target").isEmpty());
        assertEquals(11_300_000, test.buff(WINDOW, A).orElseThrow().deadline());
        test.reload(6_000_000, A); assertEquals(13_000_000, test.buff(READY, A).orElseThrow().deadline());
    }
}
