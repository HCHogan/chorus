package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Actual received damage and movement attributes, independent grouping and refreshable strong Weaken. */
public class WeakenGameTest {
    static CompiledEffects program() { return CompiledEffects.link(List.of("weaken", "weaken_inputs", "strand_defense", "combat_damage", "movement_attributes").stream().map(n -> EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json(n)).getOrThrow()).toList()); }
    static EffectSource source(LivingEntity actor, String name, double duration, double guardianDuration, double strength, double guardianStrength) {
        String id = actor.getUUID().toString(); return new EffectSource(id + "/" + name, "chorus_d2:weaken_application", id, new BuffInstance.Origin(id, name, "weapon", "ability"), Set.of(), Map.of("combatant_duration", new Measure(duration, Unit.SECOND), "guardian_duration", new Measure(guardianDuration, Unit.SECOND), "combatant_strength", new Measure(strength, Unit.DELTA), "guardian_strength", new Measure(guardianStrength, Unit.DELTA)));
    }
    static EffectSource caster(FreezeGameTest.Harness t) {
        var s = source(t.mob(EntityTypes.COW, 12, 2, ""), "normal", 6, 2.5, .15, .075); t.runtime.bind(s);
        t.driver = new EffectSource("driver", "test:weaken_inputs", s.holder(), s.origin(), Set.of()); t.runtime.bind(t.driver); return s;
    }
    static void apply(FreezeGameTest.Harness t, EffectSource source, LivingEntity target) { FreezeGameTest.event(t.runtime, source, target, "apply_weaken"); t.settled(); }
    static void clear(FreezeGameTest.Harness t, EffectSource source, LivingEntity target) { FreezeGameTest.event(t.runtime, source, target, "clear_weaken"); t.settled(); }
    static Optional<BuffInstance> status(FreezeGameTest.Harness t, LivingEntity target) { return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals("chorus_d2:weaken") && b.key().holder().equals(target.getUUID().toString())).findFirst(); }
    static void hit(FreezeGameTest.Harness t, LivingEntity target, double expected) { target.setHealth(1000); target.damageCooldownTime = 0; t.extra("damage", target, 100); near(t.h, target.getHealth(), 1000 - expected, "actual received health loss"); }
    static void later(FreezeGameTest.Harness t, int ticks, Runnable body) { t.h.runAfterDelay(ticks, () -> { try { body.run(); } catch (Exception | Error e) { t.close(); throw e; } }); }
    @GameCase(environment = "chorus_gametest:weaken_recipients", maxTicks = 145)
    public void actualPlayerAndCombatantUseDifferentStrengthAndExpiryEvenInTheSameWorld(GameTestHelper h) {
        var t = new FreezeGameTest.Harness(h, program(), EffectState.empty());
        try {
            var s = caster(t); var player = t.player(2); player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); var mob = t.mob(EntityTypes.COW, 4, 2, "elite");
            double ps = player.getAttributeValue(Attributes.MOVEMENT_SPEED), ms = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
            apply(t, s, player); apply(t, s, mob); hit(t, player, 107.5); hit(t, mob, 115); near(h, player.getAttributeValue(Attributes.MOVEMENT_SPEED), ps * .8, "player native speed penalty"); near(h, mob.getAttributeValue(Attributes.MOVEMENT_SPEED), ms * .8, "combatant native speed penalty"); t.runtime.unbind(s.instance());
            later(t, 55, () -> { t.runtime.prepare(); h.assertTrue(status(t, player).isEmpty() && status(t, mob).isPresent(), "two and a half versus six second expiry"); near(h, player.getAttributeValue(Attributes.MOVEMENT_SPEED), ps, "player speed restored"); hit(t, player, 100); hit(t, mob, 115); });
            later(t, 125, () -> { try (t) { t.runtime.prepare(); h.assertTrue(status(t, mob).isEmpty(), "combatant expiry"); near(h, mob.getAttributeValue(Attributes.MOVEMENT_SPEED), ms, "combatant speed restored"); hit(t, mob, 100); h.succeed(); } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:weaken_groups")
    public void realDamageTakesTheStrongestGlobalDebuffThenIndependentResistance(GameTestHelper h) {
        try (var t = new FreezeGameTest.Harness(h, program(), EffectState.empty())) {
            var s = caster(t); var target = t.mob(EntityTypes.COW, 2, 2, "elite"); var other = t.mob(EntityTypes.COW, 4, 2, "elite"); apply(t, s, target); hit(t, target, 115);
            String id = target.getUUID().toString(); t.runtime.bind(new EffectSource("other_debuff", "test:strong_debuff", id, new BuffInstance.Origin(id, "other", "", ""), Set.of())); hit(t, target, 130);
            t.runtime.bind(new EffectSource("dr", "test:independent_resistance", id, new BuffInstance.Origin(id, "dr", "", ""), Set.of())); hit(t, target, 65); hit(t, other, 100);
            t.runtime.unbind("other_debuff"); hit(t, target, 57.5); clear(t, s, target); hit(t, target, 50); t.settled();
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:weaken_refresh", maxTicks = 155)
    public void ordinaryReapplicationExtendsRefreshableStrongWeakenWithoutDowngradingItsDamage(GameTestHelper h) {
        var t = new FreezeGameTest.Harness(h, program(), EffectState.empty());
        try {
            var normal = caster(t); var strong = source(t.actors.getFirst(), "strong", 2, 2, .3, .5); t.runtime.bind(strong); var target = t.mob(EntityTypes.COW, 2, 2, "elite"); apply(t, strong, target); hit(t, target, 130); var first = status(t, target).orElseThrow();
            later(t, 10, () -> { apply(t, normal, target); h.assertValueEqual(status(t, target).orElseThrow().origin(), first.origin(), "refresh preserves first credit"); h.assertValueEqual(status(t, target).orElseThrow().generation(), first.generation(), "one shared instance"); });
            later(t, 50, () -> { t.runtime.prepare(); h.assertTrue(status(t, target).isPresent(), "weaker input extended strong duration"); hit(t, target, 130); });
            later(t, 140, () -> { try (t) { t.runtime.prepare(); h.assertTrue(status(t, target).isEmpty(), "shared deadline elapsed"); hit(t, target, 100); apply(t, normal, target); hit(t, target, 115); h.succeed(); } });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:weaken_rejection")
    public void deniedOrUnavailableRecipientsDoNotAcquireOrRefreshEitherPenalty(GameTestHelper h) {
        try (var t = new FreezeGameTest.Harness(h, program(), EffectState.empty())) {
            var s = caster(t); var target = t.mob(EntityTypes.COW, 2, 2, "elite"); double speed = target.getAttributeValue(Attributes.MOVEMENT_SPEED); t.allow = false; apply(t, s, target); h.assertTrue(status(t, target).isEmpty(), "denied status absent"); near(h, target.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "denied slow absent"); hit(t, target, 100);
            t.allow = true; apply(t, s, target); var before = status(t, target); t.allow = false; var strong = source(t.actors.getFirst(), "strong", 20, 20, .5, .5); t.runtime.bind(strong); apply(t, strong, target); h.assertValueEqual(status(t, target), before, "denied refresh changed existing state"); hit(t, target, 115); clear(t, s, target); near(h, target.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "explicit cleanse clears penalty");
            t.allow = true; target.setHealth(0); int checks = t.checks.size(); apply(t, s, target); h.assertValueEqual(t.checks.size(), checks, "dead target rejected before status check"); target.discard(); apply(t, s, target); h.assertValueEqual(t.checks.size(), checks, "missing target rejected before status check"); t.settled();
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:weaken_death")
    public void confirmedDeathCleansLiveStatusWhileTheKillKeepsHistoricalWeakenIdentity(GameTestHelper h) {
        try (var t = new FreezeGameTest.Harness(h, program(), EffectState.empty())) {
            var s = caster(t); var target = t.mob(EntityTypes.COW, 2, 2, "elite"); apply(t, s, target); t.extra("damage", target, 2000);
            h.assertTrue(!target.isAlive() && status(t, target).isEmpty(), "actual death cleans status"); h.assertValueEqual(t.cues.stream().filter(c -> c.cue().equals("test:weakened_kill")).count(), 1L, "kill reaction retains pre-death Weaken observation"); h.assertTrue(t.receipts.getLast().lethal(), "confirmed lethal receipt"); t.settled();
        } h.succeed();
    }
}
