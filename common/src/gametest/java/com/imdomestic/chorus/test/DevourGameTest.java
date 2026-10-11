package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Actual healing, death observations, selected grenade payment and composition with Singularity Blade. */
public class DevourGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h, boolean enhanced) throws Exception {
        var t = SingularityBladeGameTest.open(h, d -> {
            for (String name : List.of("devour", "devour_inputs")) for (var e : ThreadedSpikeGameTest.json(name).entrySet()) {
                if (e.getKey().equals("version")) continue;
                if (!d.has(e.getKey())) d.add(e.getKey(), new JsonArray()); e.getValue().getAsJsonArray().forEach(v -> d.getAsJsonArray(e.getKey()).add(v));
            }
        });
        try {
            t.owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); t.owner.setHealth(100);
            IncandescentGameTest.bind(t, "devour_input", "test:devour_inputs", ""); if (enhanced) IncandescentGameTest.bind(t, "enhanced_devour", "chorus_d2:enhanced_devour", ""); select(t, "a"); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static void select(ProjectileGameTest.Harness t, String name) {
        var before = state(t).abilities().getOrDefault(owner(t), AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots());
        if (name == null) slots.remove("chorus_d2:grenade"); else slots.put("chorus_d2:grenade", "test:grenade_" + name);
        t.runtime.abilities(new AbilityChange(owner(t), before, new AbilityLoadout(slots)));
    }
    static EffectSource grant(ProjectileGameTest.Harness t, LivingEntity giver, double duration, double cap) {
        String id = giver.getUUID().toString(); var s = new EffectSource("devour_grant", "chorus_d2:devour_application", id, new BuffInstance.Origin(id, "devour_grant", "", ""), Set.of(), Map.of("duration", new Measure(duration, Unit.SECOND), "cap", new Measure(cap, Unit.SECOND), "receipt_seconds", new Measure(1, Unit.SECOND)));
        t.runtime.bind(s); FreezeGameTest.event(t.runtime, s, t.owner, "grant_devour"); SingularityBladeGameTest.settled(t); return s;
    }
    static Optional<BuffInstance> buff(ProjectileGameTest.Harness t) { return state(t).buffs().instances().values().stream().filter(b -> b.key().holder().equals(owner(t)) && b.definition().id().equals("chorus_d2:devour")).findFirst(); }
    static double energy(ProjectileGameTest.Harness t, String name) { return state(t).resources().get(new ResourceState.Key(owner(t), "test:grenade_" + name)).value(); }
    static LivingEntity target(ProjectileGameTest.Harness t, int tier) { var e = ChainReactionGameTest.target(t, 0, 1); if (tier > 0) e.addTag("chorus_d2:combatant_tier_" + tier); return e; }
    static void physicalKill(ProjectileGameTest.Harness t, LivingEntity victim) throws Exception { PugilistGameTest.impact(t, PugilistGameTest.fire(t), victim); }
    static void nativeKill(ProjectileGameTest.Harness t, LivingEntity victim) { MinecraftDamageExecutor.execute("devour/" + UUID.randomUUID(), victim, t.h.getLevel().damageSources().playerAttack(t.owner), 10, false); t.h.assertTrue(victim.isDeadOrDying(), "native confirmed death"); victim.discard(); SingularityBladeGameTest.settled(t); }
    @GameCase(environment = "chorus_gametest:devour_tiers")
    public void actualTieredAndPlayerDeathsRestoreNormalOrEnhancedHealthAndGrenadeEnergy(GameTestHelper h) throws Exception {
        double[] gains = {.075, .116, .1375, .2, .2, .2, .2};
        for (boolean enhanced : List.of(false, true)) for (int tier = 1; tier <= 7; tier++) try (var t = open(h, enhanced)) {
            grant(t, t.owner, 10, 10); LivingEntity victim;
            if (tier == 7) { var p = NativeMeleeGameTest.player(h); t.entities.add(p); p.setPos(t.owner.getX(), t.owner.getY() + 6, t.owner.getZ()); p.setHealth(1); victim = p; }
            else victim = target(t, tier);
            physicalKill(t, victim); near(h, t.owner.getHealth(), enhanced ? 380 : 240, "activation and one real kill heal"); near(h, energy(t, "a"), gains[tier - 1] * .5 * (enhanced ? 2 : 1), "actual observed victim tier and recipient scalar");
            h.assertTrue(t.receipts.getFirst().lethal() && t.receipts.getFirst().observedBuffs().isPresent(), "known lethal receipt with pre-hit Devour");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:devour_payment")
    public void nativeDeathsFillCurrentGrenadeAccountThenRealPlayerInputSpendsExactlyOneCharge(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            grant(t, t.owner, 10, 10); for (int i = 0; i < 6; i++) nativeKill(t, target(t, 4)); near(h, energy(t, "a"), 1.2, "multiple native kills can cross one charge");
            TimelostMagazineGameTest.command(t, "chorus ability use chorus_d2:grenade"); near(h, energy(t, "a"), .2, "actual accepted skill spends one charge"); h.assertValueEqual(TimelostMagazineGameTest.cues(t, "grenade_cast"), 1L, "real skill body executed");
            select(t, "b"); nativeKill(t, target(t, 1)); near(h, energy(t, "b"), .1125, "next kill routes to newly selected account"); near(h, energy(t, "a"), .2, "previous account retained"); select(t, null); nativeKill(t, target(t, 1)); near(h, energy(t, "b"), .1125, "no selection produces no grant");
        } h.succeed();
    }
    static void timeline(GameTestHelper h, boolean persistence) throws Exception {
        var t = open(h, false);
        try {
            if (persistence) IncandescentGameTest.bind(t, "quoted_extension", "test:devour_persistence_extension", ""); grant(t, t.owner, persistence ? 15 : 10, persistence ? 15 : 10);
            int tick = persistence ? 280 : 160; double extension = persistence ? 8 : 5;
            RampageGameTest.at(t, tick, () -> { var victim = target(t, 1); physicalKill(t, victim); near(h, (buff(t).orElseThrow().deadline() - state(t).buffs().timeMicros()) / 1e6, (persistence ? 1 : 2) + extension, "adds quoted seconds to remaining duration"); near(h, t.owner.getHealth(), 240, "one actual kill heal"); });
            RampageGameTest.at(t, persistence ? 320 : 240, () -> { t.runtime.prepare(); h.assertTrue(buff(t).isPresent(), "past initial deadline but extended state remains"); });
            t.finish(persistence ? 470 : 310, () -> { h.assertTrue(buff(t).isEmpty(), "extended deadline expires"); near(h, energy(t, "a"), .0375, "no passive or duplicate energy"); });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase(environment = "chorus_gametest:devour_extension", maxTicks = 330)
    public void normalKillExtendsByFiveSecondsAndActualTicksEventuallyExpireTheSharedState(GameTestHelper h) throws Exception { timeline(h, false); }
    @GameCase(environment = "chorus_gametest:devour_persistence", maxTicks = 490)
    public void quotedPersistenceKillExtensionAddsEightSecondsWithoutMultiplyingItAgain(GameTestHelper h) throws Exception { timeline(h, true); }
    @GameCase(environment = "chorus_gametest:devour_singularity")
    public void actualDevourAcquiredInFlightQualifiesSingularityAndTheFollowingKillPaysBothEffects(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            var victim = target(t, 1); var neighbor = ChainReactionGameTest.target(t, 2, 1000); var shot = PugilistGameTest.fire(t); grant(t, t.owner, 10, 10); PugilistGameTest.impact(t, shot, victim);
            h.assertTrue(SingularityBladeGameTest.status(t, neighbor).isPresent(), "real shared Devour qualifies four meter weakening burst"); near(h, t.owner.getHealth(), 240, "real Devour kill heal"); near(h, energy(t, "a"), .0375, "real Devour energy");
            h.assertTrue(state(t).buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals("test:devour_presence")), "qualification does not use the marker fixture");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:devour_history")
    public void corpseRemovalKeepsObservedTierWhileUnknownTierRetainsOnlyKnownBenefits(GameTestHelper h) throws Exception {
        for (boolean known : List.of(false, true)) try (var t = open(h, false)) {
            grant(t, t.owner, 10, 10); var victim = target(t, known ? 2 : 0); t.afterDamageReceipt = (_, receipt) -> { if (receipt.lethal()) { victim.addTag("chorus_d2:combatant_tier_4"); victim.discard(); } };
            physicalKill(t, victim); near(h, t.owner.getHealth(), 240, "known healing irrespective of missing tier"); near(h, energy(t, "a"), known ? .116 * .5 : 0, "historical tier or explicit unknown"); h.assertValueEqual(TimelostMagazineGameTest.cues(t, "devour_unclassified"), known ? 0L : 1L, "missing tier diagnostic");
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:devour_lifecycle")
    public void foreignGrantorDetachRetainsRecipientBenefitsAndActualDeathCleansTheState(GameTestHelper h) throws Exception {
        try (var t = open(h, true)) {
            var giver = ChainReactionGameTest.target(t, 4, 1000); var source = grant(t, giver, 10, 10); t.runtime.unbind(source.instance()); physicalKill(t, target(t, 1));
            near(h, t.owner.getHealth(), 380, "recipient enhancement and recipient kill benefits"); near(h, giver.getHealth(), 1000, "foreign giver receives no healing"); near(h, energy(t, "a"), .075, "recipient account");
            t.owner.damageCooldownTime = 0; MinecraftDamageExecutor.execute("devour-death/" + UUID.randomUUID(), t.owner, h.getLevel().damageSources().genericKill(), 2000, false); h.assertTrue(t.owner.isDeadOrDying() && buff(t).isEmpty(), "confirmed native death clears shared Devour"); SingularityBladeGameTest.settled(t);
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:devour_unknown")
    public void unknownHealKeepsActualHealthWriteWithoutGrantingLaterEnergyOrReplayingTheKill(GameTestHelper h) throws Exception {
        try (var t = open(h, false)) {
            grant(t, t.owner, 10, 10); var victim = target(t, 1); t.failAfterHealing = true; var p = PugilistGameTest.fire(t); for (int i = 0; i < 20 && !p.isRemoved(); i++) p.tick();
            h.assertTrue(t.runtime.failure().isPresent() && victim.isDeadOrDying(), "unknown healing stops after real death"); near(h, t.owner.getHealth(), 240, "committed heal retained exactly once"); near(h, energy(t, "a"), 0, "later grant never fabricated"); p.tick(); h.assertValueEqual(t.hits.size(), 1, "physical hit not retried");
        } h.succeed();
    }
}
