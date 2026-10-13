package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.object.*;
import com.imdomestic.chorus.effect.weapon.InstantReload;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Compose the real dodge definition, private brick rewards, Overflow and delayed reloads. */
class MarksmanAmmoTest {
    static class Harness extends OverflowTest.Harness {
        final Set<String> consumed = new HashSet<>();
        final List<PickupCollection.Query> queries = new ArrayList<>();
        final List<InstantReload.Check> reloads = new ArrayList<>();
        boolean allowed = true;
        Harness(boolean overflow) throws Exception {
            super(data -> {
                try {
                    for (String name : List.of("marksman_dodge", "class_regeneration")) for (var e : json(name).entrySet()) if (e.getValue().isJsonArray()) {
                        if (!data.has(e.getKey())) data.add(e.getKey(), new com.google.gson.JsonArray());
                        e.getValue().getAsJsonArray().forEach(v -> data.getAsJsonArray(e.getKey()).add(v));
                    }
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            if (!overflow) equip(new Loadout(Map.of("test:primary", OverflowTest.gear("a", "primary", "none", 1),
                    "test:secondary", OverflowTest.gear("b", "special", "none", 12), "test:power", OverflowTest.gear("c", "heavy", "none", 4)), Optional.of("test:primary")));
            session.start(now(), new AbilityChange("player", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of(MarksmanDodgeTest.SLOT, MarksmanDodgeTest.ABILITY))).signal());
        }
        @Override RuleEngine.ActionResult world(RuleEngine.WorldRequest r) {
            if (r.command() instanceof PickupCollection.Query q) {
                queries.add(q); var entries = new ArrayList<PickupCollection.Entry>();
                for (int i = 0; i < pickups.size(); i++) if (q.kinds().contains(pickups.get(i).kind()) && !consumed.contains("brick/" + i)) entries.add(new PickupCollection.Entry("brick/" + i, pickups.get(i)));
                return new PickupCollection.Result(q, PickupCollection.Outcome.FOUND, entries);
            }
            if (r.command() instanceof PickupCollection.Claim c) {
                var result = consumed.contains(c.entry().entity()) ? PickupCollection.ClaimOutcome.UNAVAILABLE
                        : program.canCollectPickup(state(), c.entry().spawn()) ? PickupCollection.ClaimOutcome.COLLECTED : PickupCollection.ClaimOutcome.INELIGIBLE;
                if (result == PickupCollection.ClaimOutcome.COLLECTED) consumed.add(c.entry().entity());
                return new PickupCollection.Claimed(c, result, result == PickupCollection.ClaimOutcome.COLLECTED
                        ? Optional.of(new WorldPickup.Contact(WorldPickup.End.COLLECTED, c.entry().spawn().position().orElseThrow(), Optional.of("player"), now())) : Optional.empty());
            }
            if (r.command() instanceof InstantReload.Check c) { reloads.add(c); return new InstantReload.Checked(c, allowed); }
            return super.world(r);
        }
        void dodge(double delay) {
            session.start(now(), new AbilityUse.Request("player", MarksmanDodgeTest.SLOT, "dodge", new EffectEvent("player", "player",
                    new BuffInstance.Origin("player", "input", "", ""), Set.of(), Map.of("calibration.reload_delay", new Measure(delay, Unit.SECOND)))).signal());
        }
    }
    @Test void newlyCollectedSpecialAndHeavyReservesFeedTheReloadEvenAtTheMinimumPositiveDelay() throws Exception {
        var h = new Harness(false); h.spawn("special"); h.spawn("heavy"); h.session.start(0, SourceChange.remove("factory")); h.dodge(.000001);
        assertEquals(2, h.consumed.size()); assertEquals(12, h.reserve("b")); assertEquals(10, h.reserve("c")); assertEquals(0, h.magazine("b")); assertTrue(h.reloads.isEmpty());
        assertEquals(15, h.queries.getFirst().radius()); assertFalse(h.queries.getFirst().lineOfSight());
        assertEquals(Set.of("chorus_d2:special_ammo", "chorus_d2:heavy_ammo"), h.queries.getFirst().kinds());
        h.session.observe(1, List.of()); assertEquals(5, h.magazine("b")); assertEquals(7, h.reserve("b")); assertEquals(1, h.magazine("c")); assertEquals(9, h.reserve("c"));
        assertEquals(3, h.reloads()); assertEquals(MarksmanDodgeTest.ABILITY, h.reloads.getFirst().cause().ability());
        assertTrue(h.pickups.stream().allMatch(p -> p.origin().equals(OverflowTest.DROP)));
    }
    @Test void fullReserveSkipsTheBrickAndLaterReloadDoesNotRevisitThatRejectedCandidate() throws Exception {
        var h = new Harness(false); h.ammoEvent("reserve", "b", 30); h.spawn("special"); h.dodge(.2);
        assertTrue(h.consumed.isEmpty()); h.session.observe(200_000, List.of()); assertEquals(5, h.magazine("b")); assertEquals(25, h.reserve("b")); assertTrue(h.consumed.isEmpty());
    }
    @Test void serialClaimsFillOnlyTheRemainingSpaceAndOverflowReactionsCanCreateAnotherGap() throws Exception {
        for (boolean overflow : List.of(false, true)) {
            var h = new Harness(overflow); h.ammoEvent("reserve", "b", 25); for (int i = 0; i < 4; i++) h.spawn("special"); h.dodge(.2);
            assertEquals(overflow ? 2 : 1, h.consumed.size()); assertEquals(30, h.reserve("b")); assertEquals(overflow ? 11 : 0, h.magazine("b"));
            h.session.observe(200_000, List.of()); assertEquals(overflow ? 11 : 5, h.magazine("b")); assertEquals(overflow ? 30 : 25, h.reserve("b"));
            assertEquals(overflow ? 0 : 3, h.reloads());
        }
    }
    @Test void unknownPickupReactionStopsLaterClaimsAndTheScheduledReloadWithoutUndoingTheFirstReward() throws Exception {
        var h = new Harness(true); h.spawn("special"); h.spawn("special"); h.failRefill = true;
        assertThrows(IllegalStateException.class, () -> h.dodge(.2)); assertEquals(1, h.consumed.size()); assertEquals(11, h.magazine("b")); assertEquals(1, h.reserve("b"));
        assertThrows(IllegalStateException.class, () -> h.session.observe(200_000, List.of())); assertTrue(h.reloads.isEmpty()); assertEquals(1, h.consumed.size());
    }
    @Test void rejectingTheLaterReloadPreservesTheSeparatelyCompletedPickup() throws Exception {
        var h = new Harness(false); h.spawn("special"); h.dodge(.2); h.allowed = false; h.session.observe(200_000, List.of());
        assertEquals(1, h.consumed.size()); assertEquals(12, h.reserve("b")); assertEquals(0, h.magazine("b")); assertEquals(0, h.reloads());
    }
}
