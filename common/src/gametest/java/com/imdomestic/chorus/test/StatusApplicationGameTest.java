package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;

/** Actual world authorization and current applicant credit, distinct from retained Buff lifecycle credit. */
public class StatusApplicationGameTest {
    static EffectSource source(LivingEntity actor) { var id = actor.getUUID().toString(); return new EffectSource(id, "test:status_application", id, new BuffInstance.Origin(id, id + "/source", id + "/gun", ""), Set.of()); }
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "shield_throw", d -> { ShieldThrowGameTest.prepare(d); for (String name : List.of("suppression", "status_application")) GamblersDodgeGameTest.merge(d, ThreadedSpikeGameTest.json(name)); }, true);
        try { t.runtime.bind(source(t.owner)); return t; } catch (Exception | Error e) { t.close(); throw e; }
    }
    static void event(ProjectileGameTest.Harness t, String name, LivingEntity actor, LivingEntity target) { var s = source(actor); t.runtime.start(new RuleEngine.Signal("test:" + name, new EffectEvent(s.holder(), target.getUUID().toString(), s.origin(), Set.of(), Map.of()))); ShieldThrowGameTest.settled(t); }
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t) { return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.key().definition().equals("chorus_d2:suppression")).findFirst(); }
    @GameCase(environment = "chorus_gametest:status_applicants")
    public void realReapplicationsCreditEachApplicantWithoutChangingTheSharedStatesFirstOrigin(GameTestHelper h) throws Exception {
        try (var t = open(h)) { var target = t.cow(4.5, 40, 3.5); var second = t.cow(6.5, 40, 3.5); second.setHealth(10); t.runtime.bind(source(second));
            event(t, "apply", t.owner, target); var first = buff(t).orElseThrow(); event(t, "apply", second, target); near(h, t.owner.getHealth(), 11, "first applicant reward only"); near(h, second.getHealth(), 11, "reapplicant gets own real reward");
            h.assertValueEqual(buff(t).orElseThrow().origin(), first.origin(), "target retains first source"); h.assertValueEqual(buff(t).orElseThrow().generation(), first.generation(), "shared status generation"); near(h, target.getHealth(), 100, "recipient does not receive applicant reward");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:status_rejected")
    public void deniedDeadAndMissingWorldTargetsDoNotProduceApplicationRewards(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            for (String reason : List.of("denied", "dead", "missing")) { var target = t.cow(4.5, 40, 3.5); if (reason.equals("denied")) t.deniedStatusTargets.add(target.getUUID().toString()); if (reason.equals("dead")) target.setHealth(0); if (reason.equals("missing")) target.discard(); event(t, "apply", t.owner, target); }
            h.assertTrue(buff(t).isEmpty(), "no unauthorized shared state"); near(h, t.owner.getHealth(), 10, "no invented success rewards");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:status_authority")
    public void directGrantQualificationAndTimerRefreshRemainDistinctFromAnActualAppliedStatus(GameTestHelper h) throws Exception {
        try (var t = open(h)) { var target = t.cow(4.5, 40, 3.5); event(t, "check", t.owner, target); event(t, "grant", t.owner, target); event(t, "refresh", t.owner, target);
            h.assertTrue(buff(t).isPresent(), "pure grant does create state"); near(h, t.owner.getHealth(), 10, "state presence alone is not application credit"); event(t, "apply", t.owner, target); near(h, t.owner.getHealth(), 11, "authorized reapplication gives credit");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:status_unknown")
    public void unknownDownstreamHealingKeepsCommittedStatusAndHealthWithoutReapplying(GameTestHelper h) throws Exception {
        try (var t = open(h)) { var target = t.cow(4.5, 40, 3.5); t.failAfterHealing = true; var s = source(t.owner);
            try { t.runtime.start(new RuleEngine.Signal("test:apply", new EffectEvent(s.holder(), target.getUUID().toString(), s.origin(), Set.of(), Map.of()))); } catch (IllegalStateException expected) { }
            h.assertTrue(t.runtime.failure().isPresent() && buff(t).isPresent(), "unknown callback result preserves already applied state"); near(h, t.owner.getHealth(), 11, "world heal already committed");
            int operations = t.operations.size(); try { t.runtime.start(new RuleEngine.Signal("test:apply", new EffectEvent(s.holder(), target.getUUID().toString(), s.origin(), Set.of(), Map.of()))); } catch (IllegalStateException expected) { }
            h.assertValueEqual(t.operations.size(), operations, "failed boundary cannot be replayed"); near(h, t.owner.getHealth(), 11, "no duplicate actual heal");
        } h.succeed();
    }
}
