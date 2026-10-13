package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.object.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real Orb/Armor Charge/Starvation rules, with a deterministic world adapter. */
class OrbCollectionTest {
    static class Harness extends ArmorChargeTest.Harness {
        final Set<String> consumed = new HashSet<>();
        final List<Integer> chargeAtClaim = new ArrayList<>();
        Harness() throws Exception { super("pickup_collection_inputs"); bind("collector", "test:orb_collector", "player"); }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            if (request.command() instanceof PickupCollection.Query q) {
                var entries = new ArrayList<PickupCollection.Entry>();
                for (int i = 0; i < spawns.size(); i++) if (!consumed.contains("pickup/" + i)) entries.add(new PickupCollection.Entry("pickup/" + i, spawns.get(i)));
                return new PickupCollection.Result(q, PickupCollection.Outcome.FOUND, entries);
            }
            if (request.command() instanceof PickupCollection.Claim claim) {
                chargeAtClaim.add(charges());
                var outcome = consumed.contains(claim.entry().entity()) ? PickupCollection.ClaimOutcome.UNAVAILABLE
                        : !p.canCollectPickup(state(), claim.entry().spawn()) ? PickupCollection.ClaimOutcome.INELIGIBLE : PickupCollection.ClaimOutcome.COLLECTED;
                if (outcome == PickupCollection.ClaimOutcome.COLLECTED) consumed.add(claim.entry().entity());
                return new PickupCollection.Claimed(claim, outcome, outcome == PickupCollection.ClaimOutcome.COLLECTED
                        ? Optional.of(new WorldPickup.Contact(WorldPickup.End.COLLECTED, StarvationTest.POINT, Optional.of("player"), 0)) : Optional.empty());
            }
            return super.world(request);
        }
        void orbs(int count) { for (int i = 0; i < count; i++) spawn("orb_of_power", "giver"); }
        void collect() { session.start(now(), new RuleEngine.Signal("test:collect_orbs", new EffectEvent("player", "player", StarvationTest.producer("orb_of_power", "giver").origin(), Set.of(), Map.of()))); healthy(); }
    }
    @Test void nextOrbSeesArmorChargeApplicationAndPermissionReconciliationBeforeItsClaim() throws Exception {
        var h = new Harness(); h.fill("super"); h.mod("grenade_font", 0); h.orbs(5); h.collect();
        assertEquals(3, h.consumed.size(), "leave surplus Orbs when both Super and Armor Charge are full");
        assertEquals(3, h.charges()); assertEquals(List.of(0, 1, 2, 3, 3), h.chargeAtClaim);
        assertEquals(1, h.account("super"));
        h.spend(1); h.collect(); assertEquals(4, h.consumed.size()); assertEquals(3, h.charges());
    }
    @Test void stacksOnStacksReachesCapacityAfterTwoOrbsInsteadOfConsumingTheWholeBatch() throws Exception {
        var h = new Harness(); h.fill("super"); h.mod("grenade_font", 0); h.mod("stacks_on_stacks", 0); h.orbs(5); h.collect();
        assertEquals(2, h.consumed.size()); assertEquals(List.of(0, 2, 3, 3, 3), h.chargeAtClaim);
    }
    @Test void independentStarvationPermissionAllowsEveryOrbAndItsOriginalRewardExactlyOnce() throws Exception {
        var h = new Harness(); h.fill("super"); h.mod("grenade_font", 0); h.equip(StarvationTest.fragment("starvation", 10)); h.orbs(5); h.collect();
        assertEquals(5, h.consumed.size()); assertEquals(5, h.heals.size()); assertEquals(450, h.health.get("player"));
        assertEquals(3, h.charges()); assertEquals(1, h.account("super")); h.collect(); assertEquals(5, h.heals.size());
    }
    @Test void theFirstClaimAlsoWaitsForOtherReactionsInTheIssuingBoundary() throws Exception {
        var h = new Harness(); h.fill("super"); h.mod("grenade_font", 0); h.gain(3); h.orbs(1);
        var origin = StarvationTest.producer("orb_of_power", "giver").origin();
        h.replay(List.of(
                new RuleEngine.Signal("test:collect_orbs", new EffectEvent("player", "player", origin, Set.of(), Map.of())),
                new RuleEngine.Signal("test:spend_armor_charge", new EffectEvent("player", "player", origin, Set.of(), Map.of("stacks", new com.imdomestic.chorus.stat.Measure(1, com.imdomestic.chorus.stat.Unit.COUNT))))));
        assertEquals(1, h.consumed.size()); assertEquals(List.of(2), h.chargeAtClaim); assertEquals(3, h.charges());
    }
    @Test void unknownNestedDevourRewardStopsBeforeTheNextPhysicalClaim() throws Exception {
        var h = new Harness(); h.equip(StarvationTest.fragment("starvation", 10)); h.orbs(3); h.unknownHeal = true;
        assertThrows(IllegalStateException.class, h::collect);
        assertEquals(1, h.consumed.size()); assertEquals(.025, h.account("super")); assertEquals(170, h.health.get("player"));
        assertThrows(IllegalStateException.class, h::collect); assertEquals(1, h.heals.size());
    }
}
