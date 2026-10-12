package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;

public class EntityLifeDeathGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "entity_death", _ -> {}, true);
        IncandescentGameTest.bind(t, "driver", "test:life_death", ""); return t;
    }
    static List<EntityLifecycle.Died> captured(ProjectileGameTest.Harness t) {
        return t.runtime.state().engine().domain().timers().values().stream().map(EffectState.Timer::signal).map(RuleEngine.Signal::payload)
                .filter(EffectContinuations.Pending.class::isInstance).map(EffectContinuations.Pending.class::cast)
                .map(p -> p.cause().signal().payload()).filter(EntityLifecycle.Died.class::isInstance).map(EntityLifecycle.Died.class::cast).toList();
    }
    static Cow special(ProjectileGameTest.Harness t, boolean fail) {
        var cow = new Cow(EntityTypes.COW, t.h.getLevel()) {
            @Override public void die(DamageSource source) {
                super.die(source);
                DamageCapture.death(this, source); // A repeated observation inside this same hurt is not another death.
                setPos(getX() + 100, getY() + 5, getZ());
                if (fail) throw new IllegalStateException("Injected failure after confirmed native death");
            }
        };
        cow.setPos(t.owner.getX() + 2, t.owner.getY() + 3, t.owner.getZ()); cow.setNoGravity(true); cow.setNoAi(true);
        t.h.getLevel().addFreshEntity(cow); t.entities.add(cow); return cow;
    }
    @GameCase public void nativeDeathKeepsTheExactBoundaryBeforeLaterMovementAndRemoval(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var victim = special(t, false); var points = new EnumMap<TargetQuery.Anchor, WorldPosition>(TargetQuery.Anchor.class);
            for (var a : TargetQuery.Anchor.values()) points.put(a, EventPositionObservationGameTest.point(victim, a));
            var life = MinecraftEntityLife.get(victim).id(); victim.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(t.owner), 1000);
            EntityRemovalGameTest.settled(t); h.assertValueEqual(captured(t).size(), 1, "duplicate confirmed death");
            var fact = captured(t).getFirst(); h.assertValueEqual(fact.reference().life(), life, "exact dead life");
            for (var a : TargetQuery.Anchor.values()) h.assertValueEqual(fact.anchor(a).position(), Optional.of(points.get(a)), "death anchor before callback movement " + a);
            h.assertTrue(!fact.anchor(TargetQuery.Anchor.FEET).requireObservation().alive(), "death anchor alive");
            h.assertTrue(!EventPositionObservationGameTest.point(victim, TargetQuery.Anchor.FEET).equals(points.get(TargetQuery.Anchor.FEET)), "test callback did not move corpse");
            h.assertValueEqual(EntityRemovalGameTest.cues(t), List.of("test:death", "test:kill", "test:life_death"), "death credit or life fact duplicated");
            victim.discard(); EntityRemovalGameTest.settled(t); h.assertValueEqual(captured(t).size(), 1, "removal published another life death");
        } h.succeed();
    }
    @GameCase public void managedDamageKeepsTheReceiptDeathIdentityAndParentFrameOrdering(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var victim = t.cow(2.5, 46, 3.5); var owner = t.owner.getUUID().toString();
            var event = new EffectEvent(owner, victim.getUUID().toString(), new com.imdomestic.chorus.effect.buff.BuffInstance.Origin(owner, "driver", "", ""), Set.of(), Map.of());
            t.runtime.start(new RuleEngine.Signal("test:managed_lethal", event)); EntityRemovalGameTest.settled(t);
            h.assertValueEqual(t.receipts.size(), 1, "managed receipt count");
            h.assertValueEqual(captured(t).size(), 1, "managed life death count");
            h.assertValueEqual(captured(t).getFirst().deathId(), t.receipts.getFirst().deathId().orElseThrow(), "life fact and damage receipt disagree on death identity");
            var cues = EntityRemovalGameTest.cues(t); h.assertValueEqual(cues.getFirst(), "test:after_managed", "native death callback recursively ran rules");
            h.assertValueEqual(Collections.frequency(cues, "test:death"), 1, "managed death repeated");
            h.assertValueEqual(Collections.frequency(cues, "test:kill"), 1, "managed kill repeated");
            h.assertValueEqual(Collections.frequency(cues, "test:life_death"), 1, "managed life death repeated");
        } h.succeed();
    }
    @GameCase public void actualServerPlayerDeathCapturesTheSameOldLife(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            t.owner.setGameMode(GameType.SURVIVAL); var life = MinecraftEntityLife.get(t.owner).id();
            var point = EventPositionObservationGameTest.point(t.owner, TargetQuery.Anchor.FEET);
            t.owner.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000); EntityRemovalGameTest.settled(t);
            h.assertTrue(t.owner.isDeadOrDying(), "actual player death"); var fact = captured(t).getFirst();
            h.assertValueEqual(fact.reference().life(), life, "player death life"); h.assertValueEqual(fact.anchor(TargetQuery.Anchor.FEET).position(), Optional.of(point), "player death anchor");
            h.assertValueEqual(EntityRemovalGameTest.cues(t), List.of("test:death", "test:life_death"), "environmental death made kill credit");
        } h.succeed();
    }
    @GameCase public void nativeProtectionDoesNotCreateADeathLifeObservation(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var victim = t.cow(2.5, 46, 3.5); victim.setHealth(1); var life = MinecraftEntityLife.get(victim).id();
            victim.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            victim.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1000); EntityRemovalGameTest.settled(t);
            h.assertTrue(victim.isAlive() && victim.getOffhandItem().isEmpty(), "native protection did not execute");
            h.assertValueEqual(MinecraftEntityLife.get(victim).id(), life, "protection changed life");
            h.assertTrue(captured(t).isEmpty() && t.cues.isEmpty(), "protection emitted a confirmed death or reward");
        } h.succeed();
    }
    @GameCase public void unknownOutcomeRetainsConfirmedDeathButDoesNotRunRewards(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var victim = special(t, true); var point = EventPositionObservationGameTest.point(victim, TargetQuery.Anchor.FEET);
            boolean stopped = false;
            try { victim.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(t.owner), 1000); }
            catch (IllegalStateException expected) { stopped = true; }
            h.assertTrue(stopped && t.runtime.failure().isPresent(), "unknown native death outcome not stopped");
            var facts = t.runtime.failure().orElseThrow().committedCombatFacts().stream().map(RuleEngine.Signal::payload)
                    .filter(EntityLifecycle.Died.class::isInstance).map(EntityLifecycle.Died.class::cast).toList();
            h.assertValueEqual(facts.size(), 1, "known death boundary lost or duplicated");
            h.assertValueEqual(facts.getFirst().anchor(TargetQuery.Anchor.FEET).position(), Optional.of(point), "failure death position");
            t.runtime.prepare(); h.assertTrue(t.cues.isEmpty(), "unknown outcome executed death rewards");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:entity_life_death_delayed", maxTicks = 25)
    public void detachedDeathReadRetainsTheOldPointAfterSourceAndCorpseDisappear(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            var victim = t.cow(2.5, 46, 3.5); var point = EventPositionObservationGameTest.point(victim, TargetQuery.Anchor.FEET);
            victim.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000); EntityRemovalGameTest.settled(t);
            t.runtime.unbind("driver"); var replacement = TimedPayloadGameTest.replace(t, victim); replacement.setPos(replacement.getX() + 100, replacement.getY(), replacement.getZ());
            t.finish(3, () -> {
                h.assertValueEqual(EntityRemovalGameTest.cues(t), List.of("test:death", "test:life_death", "test:delayed_death"), "detached death work");
                h.assertValueEqual(t.queries.size(), 2, "historical death queries");
                h.assertTrue(t.queries.stream().allMatch(q -> ((TargetQuery.PositionCenter) q.center()).position().equals(Optional.of(point))), "death work observed the replacement");
            });
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
