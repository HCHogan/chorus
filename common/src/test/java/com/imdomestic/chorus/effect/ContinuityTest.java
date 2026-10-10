package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContinuityTest {
    private static final String SLICE = "chorus_d2:slice", SEVER = "chorus_d2:sever";
    private static final class Harness {
        final CompiledEffects program;
        final EffectSession session;
        final EffectSource weapon;
        final List<StatusResult.Check> checks = new ArrayList<>();
        StatusResult.Decision decision = StatusResult.Decision.ALLOWED;
        Harness(EffectState.Mode mode, boolean enhanced) throws Exception {
            program = link("continuity", "combat_damage", "strand_defense", "slice");
            var base = source(SLICE); weapon = new EffectSource(base.instance(), base.bundle(), base.holder(), base.origin(), enhanced ? Set.of("chorus:enhanced") : Set.of());
            session = new EffectSession(engine(program), EffectState.empty().withMode(mode).withSource(weapon), request -> {
                var check = (StatusResult.Check) request.command(); checks.add(check); return new StatusResult.Checked(check, decision);
            });
            session.start(0, new RuleEngine.Signal("chorus:class_ability_used", event(weapon)));
        }
        void fragment(String id, String holder) { session.start(now(), SourceChange.bind(CalculationActionTest.fragment(id, holder))); }
        long now() { return session.state().engine().domain().buffs().timeMicros(); }
        void hit(long time, String target) {
            var damage = new DamageCommand(target, weapon.origin(), 1, "minecraft:generic", Set.of(), Set.of(), false);
            session.observe(time, DamageFacts.from(damage, new DamageReceipt("hit/" + target + "/" + time, DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false)));
            assertTrue(session.state().idle() && session.state().engine().failure().isEmpty());
        }
    }

    @Test void applicationUsesCurrentApplierFragmentEvenIfItWasEquippedAfterSliceActivation() throws Exception {
        for (var mode : EffectState.Mode.values()) for (boolean enhanced : List.of(false, true)) {
            var h = new Harness(mode, enhanced); h.fragment("recipient", "target"); h.fragment("ally", "ally"); h.hit(0, "target");
            long base = mode == EffectState.Mode.PVE ? 10_000_000 : 5_000_000, extended = mode == EffectState.Mode.PVE ? 15_000_000 : 7_500_000;
            assertEquals(base, h.checks.getLast().duration());
            h.fragment("own", "player"); h.fragment("duplicate", "player"); h.hit(1_000_000, "extended");
            assertEquals(extended, h.checks.getLast().duration());
            assertEquals(1_000_000 + extended, buff(h.session.state(), SEVER, "extended").stacks().getFirst().expiresAt());
            assertEquals(3, buff(h.session.state(), SLICE, "player").count());
            assertEquals((enhanced ? 10 : 9) * 1_000_000L, buff(h.session.state(), SLICE, "player").stacks().getFirst().expiresAt(), "Continuity does not extend the weapon activation window");
        }
    }

    @Test void removingFragmentAffectsNewApplicationsWithoutResizingCommittedStatusesAndExpiryIsExact() throws Exception {
        for (var mode : EffectState.Mode.values()) {
            var h = new Harness(mode, false); h.fragment("own", "player"); h.hit(0, "extended");
            long end = mode == EffectState.Mode.PVE ? 15_000_000 : 7_500_000;
            h.session.start(0, SourceChange.remove("own")); h.hit(1_000_000, "base");
            assertEquals(mode == EffectState.Mode.PVE ? 10_000_000 : 5_000_000, h.checks.getLast().duration());
            assertEquals(end, buff(h.session.state(), SEVER, "extended").stacks().getFirst().expiresAt());
            h.session.observe(end - 1, List.of()); assertEquals(end, buff(h.session.state(), SEVER, "extended").stacks().getFirst().expiresAt());
            h.session.observe(end, List.of());
            assertTrue(h.session.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.key().holder().equals("extended")));
        }
    }

    @Test void extendedStatusStillRequiresAuthorizationAndAlreadySeveredTargetsConsumeNoExtraCharge() throws Exception {
        var h = new Harness(EffectState.Mode.PVE, false); h.fragment("own", "player"); h.decision = StatusResult.Decision.DENIED;
        h.hit(1_000_000, "blocked"); assertEquals(15_000_000, h.checks.getLast().duration()); assertEquals(5, buff(h.session.state(), SLICE, "player").count());
        assertEquals(8_000_000, buff(h.session.state(), SLICE, "player").stacks().getFirst().expiresAt());
        h.decision = StatusResult.Decision.ALLOWED; h.hit(2_000_000, "target");
        int checks = h.checks.size(); h.hit(3_000_000, "target"); assertEquals(checks, h.checks.size());
        assertEquals(4, buff(h.session.state(), SLICE, "player").count()); assertEquals(17_000_000, buff(h.session.state(), SEVER, "target").stacks().getFirst().expiresAt());
    }
}
