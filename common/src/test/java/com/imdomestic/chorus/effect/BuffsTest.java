package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.buff.BuffDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.buff.BuffInstance.*;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuffsTest {
    static final long SECOND = 1_000_000;
    static final Origin A = new Origin("player", "perk-a", "weapon-a", "");
    static final Origin B = new Origin("player", "perk-b", "weapon-b", "");
    static BuffDefinition definition(String id, int maximum, long duration, TimerMode mode, Decay decay,
            Refresh refresh, OnStow stow, boolean creditOverflow, boolean highestTier) {
        return new BuffDefinition(id, "1", maximum,
                new Timer(duration, mode, decay, decay == Decay.ONE_BY_ONE ? SECOND : 0, refresh, 15 * SECOND,
                        stow == OnStow.PAUSE ? SECOND : 0),
                new Binding(Attach.HOLDER, InstanceBy.WEAPON, Affects.INSTANCE_WEAPON, stow),
                StackMode.ADD, highestTier, creditOverflow, Set.of());
    }
    static Buffs.Result grant(BuffStore store, BuffDefinition definition, Origin origin, int count) {
        return Buffs.grant(store, definition, "player", "target", origin, count, 1, definition.timer().durationMicros());
    }
    static Key key(BuffDefinition definition, Origin origin) { return Buffs.key(definition, "player", "target", origin); }
    static BuffInstance instance(BuffStore store, BuffDefinition definition, Origin origin) { return store.active(key(definition, origin)).orElseThrow(); }

    @Test void independentStacksKeepTheirDeadlinesAndSources() {
        // Disruption Break: Weapon Perks, CSV snapshot C71 (5.5 s PvE; separate stacks).
        var base = definition("test:disruption_break", 10, 5_500_000, TimerMode.PER_STACK, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var definition = new BuffDefinition(base.id(), base.version(), base.maximumStacks(), base.timer(),
                new Binding(Attach.TARGET, InstanceBy.NONE, Affects.ALL, OnStow.KEEP), StackMode.ADD, false, false, Set.of());
        var first = grant(BuffStore.empty(), definition, A, 1).store();
        var atOne = Buffs.advanceStep(first, SECOND).store();
        var both = grant(atOne, definition, B, 1).store();
        var stacks = instance(both, definition, A).stacks();
        assertEquals(5_500_000, stacks.get(0).expiresAt());
        assertEquals(6_500_000, stacks.get(1).expiresAt());
        assertEquals(B, stacks.get(1).origin());
        var expired = Buffs.advanceStep(both, 10 * SECOND);
        assertEquals(5_500_000, expired.store().timeMicros()); // Must stop to process reactions.
        assertEquals(1, instance(expired.store(), definition, A).count());
        assertEquals(Buffs.Kind.STACKS_CHANGED, expired.changes().getFirst().kind());
        assertEquals(2, expired.changes().getFirst().before().orElseThrow().count());
        var last = Buffs.advanceStep(expired.store(), 10 * SECOND);
        assertEquals(6_500_000, last.store().timeMicros());
        assertTrue(last.store().instances().isEmpty());
        assertEquals(Buffs.Kind.ENDED, last.changes().getFirst().kind());
        assertTrue(last.changes().getFirst().after().isEmpty());
        assertEquals(1, first.instances().values().iterator().next().count()); // Inputs remain unchanged.
    }

    @Test void stowPausesAndRoundsRemainingTimeBeforeDrawResumesIt() {
        // Frame of Reference: C369, 8 s timer, stow rounds remaining time up to a second.
        var definition = definition("test:frame_of_reference", 5, 8 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.RESET, OnStow.PAUSE, false, false);
        var store = grant(BuffStore.empty(), definition, A, 1).store();
        store = Buffs.advanceStep(store, 2_250_000).store();
        var paused = Buffs.weaponState(store, "player", "weapon-a", true);
        assertEquals(FOREVER, paused.store().nextDeadline());
        assertEquals(8_250_000, instance(paused.store(), definition, A).stacks().getFirst().expiresAt());
        assertEquals(Buffs.Kind.PAUSED, paused.changes().getFirst().kind());
        assertTrue(Buffs.weaponState(paused.store(), "player", "weapon-a", true).changes().isEmpty());
        var later = Buffs.advanceStep(paused.store(), 100 * SECOND);
        assertEquals(1, instance(later.store(), definition, A).activeCount(100 * SECOND));
        assertTrue(Buffs.weaponState(later.store(), "other-owner", "weapon-a", false).changes().isEmpty());
        var resumed = Buffs.weaponState(later.store(), "player", "weapon-a", false);
        assertEquals(106 * SECOND, resumed.store().nextDeadline());
        assertEquals(Buffs.Kind.RESUMED, resumed.changes().getFirst().kind());
        assertTrue(Buffs.advanceStep(resumed.store(), 106 * SECOND).store().instances().isEmpty());
    }

    @Test void historyAndTierSurviveExtensionCapAndWeakerReapplication() {
        // Restoration: Solar D7. These durations are scenario inputs, not a specific ability's values.
        var base = definition("test:restoration", 1, 6 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.HISTORIC_MAX, OnStow.KEEP, false, true);
        var definition = new BuffDefinition(base.id(), "1", 1, base.timer(),
                new Binding(Attach.HOLDER, InstanceBy.NONE, Affects.ALL, OnStow.KEEP), StackMode.MAX, true, false, Set.of());
        var first = Buffs.grant(BuffStore.empty(), definition, "player", "", A, 1, 1, 6 * SECOND).store();
        var atTwo = Buffs.advanceStep(first, 2 * SECOND).store();
        var strong = Buffs.grant(atTwo, definition, "player", "", B, 1, 2, 20 * SECOND).store();
        var atThree = Buffs.advanceStep(strong, 3 * SECOND).store();
        var extended = Buffs.extend(atThree, key(definition, A), SECOND, 15 * SECOND);
        assertEquals(18 * SECOND, extended.store().nextDeadline()); // 19 remaining -> cap 15, even though shorter.
        assertEquals(Buffs.Kind.EXTENDED, extended.changes().getFirst().kind());
        var atFour = Buffs.advanceStep(extended.store(), 4 * SECOND).store();
        var reapplied = Buffs.grant(atFour, definition, "player", "", A, 1, 1, 3 * SECOND);
        var value = instance(reapplied.store(), definition, A);
        assertEquals(24 * SECOND, value.deadline());
        assertEquals(6 * SECOND, value.originalDurationMicros());
        assertEquals(20 * SECOND, value.longestDurationMicros());
        assertEquals(2, value.tier());
        assertEquals(1, value.count());
        assertEquals(0, reapplied.receipt().storedDelta());
        assertTrue(reapplied.changes().stream().anyMatch(change -> change.kind() == Buffs.Kind.REFRESHED));
    }

    @Test void overflowCreditIsIndependentOfStoredStacks() {
        var definition = definition("test:bolt_charge", 10, FOREVER, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, true, false);
        var store = grant(BuffStore.empty(), definition, A, 9).store();
        var result = grant(store, definition, A, 4);
        assertEquals(new Buffs.Receipt(4, 4, 1, true), result.receipt());
        assertEquals(10, instance(result.store(), definition, A).count());
        assertEquals(4, result.changes().getFirst().receipt().credited());
        assertTrue(Buffs.advanceStep(result.store(), Long.MAX_VALUE - 1).changes().isEmpty());
    }

    @Test void fullStackRefreshEmitsEvenWhenDeadlineAndCountDoNotChange() {
        var definition = definition("test:refresh", 10, 5 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.RESET, OnStow.KEEP, false, false);
        var store = grant(BuffStore.empty(), definition, A, 10).store();
        var result = grant(store, definition, A, 1);
        assertEquals(new Buffs.Receipt(1, 0, 0, true), result.receipt());
        assertEquals(java.util.List.of(Buffs.Kind.GAINED, Buffs.Kind.REFRESHED), result.changes().stream().map(Buffs.Change::kind).toList());
    }

    @Test void sequentialDecayUsesExplicitIntervalsAndConsumptionKeepsDeadline() {
        // Generic policy test. Rampage's subsequent decay interval is not established by C172.
        var definition = definition("test:sequential", 3, 4_500_000, TimerMode.SHARED, Decay.ONE_BY_ONE, Refresh.RESET, OnStow.KEEP, false, false);
        var store = grant(BuffStore.empty(), definition, A, 3).store();
        var first = Buffs.advanceStep(store, 20 * SECOND);
        assertEquals(4_500_000, first.store().timeMicros());
        assertEquals(2, instance(first.store(), definition, A).count());
        assertEquals(5_500_000, first.store().nextDeadline());
        var consumed = Buffs.consume(first.store(), key(definition, A), 1);
        assertEquals(5_500_000, consumed.store().nextDeadline());
        var end = Buffs.advanceStep(consumed.store(), 20 * SECOND);
        assertEquals(Buffs.Kind.ENDED, end.changes().getFirst().kind());
        assertEquals(Buffs.Reason.EXPIRED, end.changes().getFirst().reason());
    }

    @Test void weaponInstancesAreIndependentAndStowOnlyRemovesTheMatchingOne() {
        var definition = definition("test:kill_clip", 1, 5 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.RESET, OnStow.REMOVE, false, false);
        var store = grant(BuffStore.empty(), definition, A, 1).store();
        store = grant(store, definition, B, 1).store();
        assertTrue(instance(store, definition, A).affects("weapon-a", ""));
        assertFalse(instance(store, definition, A).affects("weapon-b", ""));
        var stowed = Buffs.weaponState(store, "player", "weapon-a", true);
        assertEquals(1, stowed.store().instances().size());
        assertTrue(stowed.store().active(key(definition, B)).isPresent());
        assertEquals(Buffs.Reason.STOWED, stowed.changes().getFirst().reason());
        assertEquals(A, stowed.changes().getFirst().before().orElseThrow().origin());
        var regranted = grant(stowed.store(), definition, A, 1).store();
        assertNotEquals(stowed.changes().getFirst().instance().generation(), instance(regranted, definition, A).generation());
    }

    @Test void consumedFinalSnapshotPreservesComponentsAndNoZeroStackChangeIsEmitted() {
        var definition = definition("test:components", 1, 5 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.NONE, OnStow.KEEP, false, false);
        var initial = grant(BuffStore.empty(), definition, A, 1).store();
        var components = BuffComponents.EMPTY.number("accumulated", BuffComponents.Update.ADD, 115)
                .number("negative-max", BuffComponents.Update.MAX, -4).remember("weapons", "a").remember("weapons", "a")
                .reference("anchor", "entity-id");
        var changed = Buffs.components(initial, key(definition, A), components);
        var removed = Buffs.consume(changed, key(definition, A), 4);
        assertEquals(-1, removed.receipt().storedDelta());
        assertEquals(1, removed.changes().size());
        var event = removed.changes().getFirst();
        assertEquals(Buffs.Kind.ENDED, event.kind());
        assertEquals(Buffs.Reason.CONSUMED, event.reason());
        assertEquals(components, event.before().orElseThrow().components());
        assertTrue(event.after().isEmpty());
        assertEquals(-4, components.numbers().get("negative-max"));
        assertEquals(Set.of("a"), components.sets().get("weapons"));
        assertTrue(instance(initial, definition, A).components().numbers().isEmpty());
        assertFalse(Buffs.consume(removed.store(), key(definition, A), 1).receipt().applied());
    }

    @Test void invalidOrUnsettledStateCannotBeSilentlyRefreshed() {
        var definition = definition("test:expiry", 1, 5 * SECOND, TimerMode.SHARED, Decay.ALL, Refresh.RESET, OnStow.KEEP, false, false);
        var store = grant(BuffStore.empty(), definition, A, 1).store();
        var unsettled = new BuffStore(5 * SECOND, store.nextGeneration(), store.instances());
        assertTrue(unsettled.active(key(definition, A)).isEmpty());
        assertThrows(IllegalStateException.class, () -> grant(unsettled, definition, A, 1));
        assertThrows(IllegalArgumentException.class, () -> Buffs.advanceStep(store, -1));
        assertThrows(IllegalArgumentException.class, () -> Buffs.advanceStep(store, FOREVER));
        var nearLimit = new BuffStore(Long.MAX_VALUE - SECOND, 1, Map.of());
        assertThrows(ArithmeticException.class, () -> grant(nearLimit, definition, A, 1));
        assertThrows(IllegalArgumentException.class, () -> new Timer(1, TimerMode.PER_STACK, Decay.ALL, 0, Refresh.RESET, FOREVER, 0));
        assertThrows(IllegalArgumentException.class, () -> Buffs.key(definition, "player", "", new Origin("player", "source", "", "")));
    }
}
