package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Real skill/debuff producers; cross-class test composition is not a legal subclass loadout claim. */
public class FeedTheVoidGameTest {
    static EffectSource aspect(String owner, String id, boolean prismatic) {
        return new EffectSource(id, "chorus_d2:feed_the_void" + (prismatic ? "_prismatic" : ""), owner, new BuffInstance.Origin(owner, id, "", ""), Set.of(), Map.of("cap", new Measure(10, Unit.SECOND), "receipt_seconds", new Measure(1, Unit.SECOND)));
    }
    static void modules(JsonObject d) {
        for (String name : List.of("devour", "devour_inputs", "feed_the_void", "feed_the_void_inputs")) GamblersDodgeGameTest.merge(d, ThreadedSpikeGameTest.json(name));
    }
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean prismatic, boolean spike, String... extra) throws Exception {
        var t = new ProjectileGameTest.Harness(h, spike ? "threaded_spike" : "shield_throw", d -> {
            if (spike) ThreadedSpikeGameTest.prepare(d); else ShieldThrowGameTest.prepare(d); modules(d);
            for (String name : extra) {
                var part = ThreadedSpikeGameTest.json(name);
                // This old self-contained fixture uses another version; only its namespace is linked here.
                if (name.equals("volatile")) part = JsonParser.parseString(part.toString().replace("test-volatile-v1", "compendium-2026-10-05")).getAsJsonObject();
                GamblersDodgeGameTest.merge(d, part);
            }
        }, true);
        try {
            t.owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL); t.owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); t.owner.setHealth(100);
            t.runtime.bind(aspect(owner(t), "aspect", prismatic)); IncandescentGameTest.bind(t, "feed_inputs", "test:feed_inputs", ""); IncandescentGameTest.bind(t, "devour_inputs", "test:devour_inputs", "");
            DevourGameTest.select(t, "a"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static LivingEntity target(ProjectileGameTest.Harness t, int n, float hp) { var e = ThreadedSpikeGameTest.target(t, 2.5, 44 + n * 2, 3.5, hp); e.addTag("chorus_d2:combatant_tier_1"); return e; }
    static void cast(ProjectileGameTest.Harness t, boolean spike) throws Exception {
        var before = state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots()); slots.put("chorus_d2:melee", "chorus_d2:" + (spike ? "threaded_spike" : "shield_throw"));
        t.runtime.abilities(new AbilityChange(owner(t), before, new AbilityLoadout(slots))); TimelostMagazineGameTest.command(t, "chorus ability use chorus_d2:melee");
    }
    static void contacts(ProjectileGameTest.Harness t, int count) { var p = t.projectiles.getFirst(); for (int i = 0; i < 50 && t.hits.size() < count && !p.isRemoved(); i++) p.tick(); ShieldThrowGameTest.settled(t); t.h.assertValueEqual(t.hits.size(), count, "actual physical contacts"); p.discard(); }
    static void damage(ProjectileGameTest.Harness t, String kind, LivingEntity target, double amount) {
        t.runtime.start(new RuleEngine.Signal("test:feed_" + kind, new EffectEvent(owner(t), target.getUUID().toString(), new BuffInstance.Origin(owner(t), "input", "weapon", ""), Set.of(), Map.of("amount", new Measure(amount, Unit.DAMAGE))))); ShieldThrowGameTest.settled(t);
    }
    @GameCase(environment = "chorus_gametest:feed_paid")
    public void paidPhysicalShieldKillsActivateThenRewardExactlyOnceAndKeepGrenadeSelection(GameTestHelper h) throws Exception {
        try (var t = open(h, false, false)) {
            target(t, 0, 1); target(t, 1, 1); cast(t, false); contacts(t, 2);
            near(h, t.owner.getHealth(), 380, "two kills give activation then one active kill heal"); near(h, DevourGameTest.energy(t, "a"), .075, "only second kill returns energy");
            near(h, state(t).resources().get(new ResourceState.Key(owner(t), "chorus_d2:shield_throw_energy")).value(), 0, "actual skill payment retained");
            h.assertTrue(t.hits.stream().allMatch(d -> d.tags().contains("chorus:ability_damage") && d.killTags().contains("chorus:ability_kill")), "paid producer supplies ability credit"); h.assertTrue(DevourGameTest.buff(t).isPresent(), "actual shared state");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:feed_spike")
    public void paidStrandSpikeKillsQualifyOnlyForPrismaticAndThenUseSharedKillBenefits(GameTestHelper h) throws Exception {
        for (boolean prismatic : List.of(false, true)) try (var t = open(h, prismatic, true)) {
            target(t, 0, 1); target(t, 1, 1); cast(t, true); contacts(t, 2);
            near(h, t.owner.getHealth(), prismatic ? 380 : 100, "Strand ability qualifies only on Prismatic"); near(h, DevourGameTest.energy(t, "a"), prismatic ? .075 : 0, "shared reward on subsequent physical kill"); h.assertValueEqual(DevourGameTest.buff(t).isPresent(), prismatic, "subclass qualification");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:feed_shatter")
    public void actualFrozenEnemyShatterKillActivatesPrismaticDespiteHavingNoGenericAbilityTag(GameTestHelper h) throws Exception {
        for (boolean prismatic : List.of(false, true)) try (var t = open(h, prismatic, false, "freeze", "freeze_test_falloff")) {
            var center = target(t, 0, 1000); center.addTag("chorus_d2:elite"); var neighbor = target(t, 1, 1); var source = FreezeGameTest.source(t.owner); t.runtime.bind(source); FreezeGameTest.event(t.runtime, source, center, "apply_freeze");
            h.assertTrue(state(t).buffs().instances().values().stream().anyMatch(b -> b.definition().id().equals("chorus_d2:freeze") && b.key().holder().equals(center.getUUID().toString())), "explicit combatant rank permits Freeze"); damage(t, "weapon", center, 100);
            h.assertTrue(neighbor.isDeadOrDying(), "neighbor killed by actual frozen enemy shatter"); h.assertTrue(t.hits.stream().anyMatch(d -> d.tags().contains("chorus:freeze_shatter") && d.target().equals(neighbor.getUUID().toString())), "actual shatter producer");
            near(h, t.owner.getHealth(), prismatic ? 240 : 100, "Prismatic shatter activation"); near(h, DevourGameTest.energy(t, "a"), 0, "initial shatter activation does not double award");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:feed_volatile")
    public void actualWeaponAppliedVolatileExplosionActivatesOnlyPrismaticEvenWithLegacyAbilityCredit(GameTestHelper h) throws Exception {
        for (boolean prismatic : List.of(false, true)) try (var t = open(h, prismatic, false, "volatile")) {
            IncandescentGameTest.bind(t, "volatile_source", "chorus_d2:volatile_application", ""); var center = target(t, 0, 1000); var neighbor = target(t, 1, 1); damage(t, "volatile_apply", center, 1); damage(t, "weapon", center, 20);
            h.assertTrue(neighbor.isDeadOrDying(), "real volatile explosion killed neighbor"); h.assertTrue(t.hits.stream().anyMatch(d -> d.tags().contains("chorus_d2:volatile_damage") && d.target().equals(neighbor.getUUID().toString())), "actual volatile producer");
            near(h, t.owner.getHealth(), prismatic ? 240 : 100, "explicit extra qualification overrides legacy generic tags"); near(h, DevourGameTest.energy(t, "a"), 0, "no first-kill energy");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:feed_equipment")
    public void AspectBindingAtImpactControlsActivationAndDuplicateSourcesDoNotDoubleActualHealing(GameTestHelper h) throws Exception {
        for (boolean attached : List.of(false, true)) try (var t = open(h, false, false)) {
            t.runtime.unbind("aspect"); target(t, 0, 1); cast(t, false);
            if (attached) { t.runtime.bind(aspect(owner(t), "late", false)); t.runtime.bind(aspect(owner(t), "duplicate", true)); }
            contacts(t, 1); near(h, t.owner.getHealth(), attached ? 240 : 100, "current Aspect state at actual impact"); near(h, DevourGameTest.energy(t, "a"), 0, "no activation energy");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:feed_detach")
    public void NativeWeaponKillsDoNotActivateButContinueOrdinaryDevourAfterAspectRemoval(GameTestHelper h) throws Exception {
        try (var t = open(h, false, false)) {
            DevourGameTest.nativeKill(t, target(t, 2, 1)); h.assertTrue(DevourGameTest.buff(t).isEmpty(), "native weapon origin is not a powered ability"); target(t, 0, 1); cast(t, false); contacts(t, 1); t.runtime.unbind("aspect"); DevourGameTest.nativeKill(t, target(t, 3, 1));
            near(h, t.owner.getHealth(), 310, "140 activation then ordinary 70 after detach"); near(h, DevourGameTest.energy(t, "a"), .0375, "ordinary grenade return remains");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:feed_expiry", maxTicks = 230)
    public void realTicksExpireTheInitialTenSecondsAndANewEligibleKillStartsANewActivation(GameTestHelper h) throws Exception {
        var t = open(h, false, false);
        try {
            target(t, 0, 1); cast(t, false); contacts(t, 1);
            t.finish(210, () -> { h.assertTrue(DevourGameTest.buff(t).isEmpty(), "ten-second activation expired"); damage(t, "void", target(t, 0, 1), 10); h.assertTrue(DevourGameTest.buff(t).isPresent(), "new eligible actual death reactivates"); near(h, t.owner.getHealth(), 380, "new activation heals"); near(h, DevourGameTest.energy(t, "a"), 0, "neither initial activation awards energy"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:feed_unknown")
    public void unknownHealingAfterActualSkillKillRetainsPaymentAndHealthWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = open(h, false, false)) {
            var victim = target(t, 0, 1); cast(t, false); t.failAfterHealing = true; var p = t.projectiles.getFirst(); for (int i = 0; i < 30 && !p.isRemoved(); i++) p.tick();
            h.assertTrue(victim.isDeadOrDying() && t.runtime.failure().isPresent(), "real death then unknown heal"); near(h, t.owner.getHealth(), 240, "committed heal retained once"); near(h, DevourGameTest.energy(t, "a"), 0, "no invented energy"); near(h, state(t).resources().get(new ResourceState.Key(owner(t), "chorus_d2:shield_throw_energy")).value(), 0, "real payment retained"); p.tick(); h.assertValueEqual(t.hits.size(), 1, "no replay");
        } h.succeed();
    }
}
