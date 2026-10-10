package com.imdomestic.chorus.effect.buff;

import static com.imdomestic.chorus.effect.buff.BuffDefinition.*;
import static com.imdomestic.chorus.effect.buff.BuffInstance.*;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/** Immutable buff operations. Hosts must settle each returned deadline's reactions before advancing again. */
public final class Buffs {
    private Buffs() {}
    public enum Kind { GAINED, STACKS_CHANGED, REFRESHED, ENDED, PAUSED, RESUMED, EXTENDED }
    public enum Reason { APPLIED, EXPIRED, CONSUMED, REMOVED, STOWED, DRAWN, EXTENDED }
    public record Receipt(int requested, int credited, int storedDelta, boolean applied) implements RuleEngine.ActionResult {}
    public record Change(long timeMicros, Kind kind, Reason reason, Optional<BuffInstance> before,
            Optional<BuffInstance> after, Receipt receipt) implements com.imdomestic.chorus.effect.EffectEvent.Carrier {
        public Change {
            Objects.requireNonNull(kind); Objects.requireNonNull(reason); Objects.requireNonNull(receipt);
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (before.isEmpty() && after.isEmpty()) throw new IllegalArgumentException("A lifecycle change needs an instance");
        }
        public BuffInstance instance() { return after.orElseGet(before::orElseThrow); }
        /** Lifecycle measurements are the committed receipt, not a later read of the live stack count. */
        @Override public com.imdomestic.chorus.effect.EffectEvent event() {
            var value = instance();
            return new com.imdomestic.chorus.effect.EffectEvent(value.origin().owner(), value.key().holder(), value.origin(), value.definition().tags(),
                    java.util.Map.of("requested", count(receipt.requested()), "credited", count(receipt.credited()),
                            "stored_delta", count(receipt.storedDelta()), "stacks_before", count(before.map(BuffInstance::count).orElse(0)),
                            "stacks_after", count(after.map(BuffInstance::count).orElse(0))),
                    java.util.Map.of("applied", receipt.applied()),
                    java.util.Map.of("buff_definition", value.definition().id(), "buff_generation", Long.toString(value.generation()),
                            "reason", reason.name().toLowerCase(java.util.Locale.ROOT)));
        }
        private static com.imdomestic.chorus.stat.Measure count(int value) {
            return new com.imdomestic.chorus.stat.Measure(value, com.imdomestic.chorus.stat.Unit.COUNT);
        }
    }
    public record Result(BuffStore store, Receipt receipt, List<Change> changes) {
        public Result { changes = List.copyOf(changes); }
        public List<RuleEngine.Signal> signals() {
            return changes.stream().map(change -> new RuleEngine.Signal(
                    "chorus:buff_" + change.kind().name().toLowerCase(java.util.Locale.ROOT), change)).toList();
        }
    }

    public static Key key(BuffDefinition definition, String holder, String target, Origin origin) {
        String attached = switch (definition.binding().attach()) {
            case HOLDER -> holder;
            case TARGET -> target;
            case WEAPON -> origin.weapon();
        };
        String instance = switch (definition.binding().instancedBy()) {
            case NONE -> "";
            case SOURCE -> required(origin.source(), "source");
            case WEAPON -> required(origin.weapon(), "weapon");
        };
        if (definition.binding().affects() == Affects.INSTANCE_WEAPON) required(origin.weapon(), "affected weapon");
        if (definition.binding().affects() == Affects.ABILITY) required(origin.ability(), "affected ability");
        return new Key(required(attached, "holder"), definition.id(), instance);
    }

    public static Result grant(BuffStore store, BuffDefinition definition, String holder, String target,
            Origin origin, int requested, int tier, long durationMicros) {
        settled(store);
        if (requested < 1 || tier < 1 || durationMicros < 1) throw new IllegalArgumentException("Invalid buff application");
        Key key = key(definition, holder, target, origin);
        BuffInstance before = store.instances().get(key);
        if (before != null && !before.definition().equals(definition)) throw new IllegalArgumentException("Live buff definition changed");
        if (before != null && ((definition.binding().affects() == Affects.INSTANCE_WEAPON && !before.origin().weapon().equals(origin.weapon()))
                || (definition.binding().affects() == Affects.ABILITY && !before.origin().ability().equals(origin.ability())))) {
            throw new IllegalArgumentException("Incompatible effect binding; use a separate instance key");
        }
        int oldCount = before == null ? 0 : before.count();
        long count = switch (definition.stackMode()) {
            case ADD -> (long) oldCount + requested;
            case MAX -> Math.max(oldCount, requested);
            case REPLACE -> requested;
        };
        int newCount = (int) Math.min(definition.maximumStacks(), count);
        int delta = newCount - oldCount;
        long clock = before == null ? store.timeMicros() : before.pausedAt().orElse(store.timeMicros());
        long history = before == null ? durationMicros : Math.max(before.longestDurationMicros(), durationMicros);
        long deadline = at(clock, durationMicros);
        boolean refreshed = before != null && definition.timer().mode() == TimerMode.SHARED && definition.timer().refresh() != Refresh.NONE;
        if (before != null && definition.timer().mode() == TimerMode.SHARED) {
            long previous = before.stacks().getFirst().expiresAt();
            deadline = switch (definition.timer().refresh()) {
                case NONE -> previous;
                case RESET -> deadline;
                case MAX_REMAINING -> Math.max(previous, deadline);
                case HISTORIC_MAX -> at(clock, history);
                case EXTEND -> at(clock, extendRemaining(remaining(previous, clock), durationMicros, definition.timer().extensionCapMicros()));
            };
            history = Math.max(history, remaining(deadline, clock));
        }
        var stacks = new ArrayList<Stack>(before == null ? List.of() : before.stacks());
        stacks.sort(Comparator.comparingLong(Stack::expiresAt).thenComparingLong(Stack::id));
        while (stacks.size() > newCount) stacks.removeFirst();
        long nextStackId = before == null ? 1 : before.nextStackId();
        while (stacks.size() < newCount) {
            stacks.add(new Stack(nextStackId, deadline, origin));
            nextStackId = Math.incrementExact(nextStackId);
        }
        if (definition.timer().mode() == TimerMode.SHARED) stacks = retime(stacks, deadline);
        var after = new BuffInstance(before == null ? store.nextGeneration() : before.generation(), key, definition,
                before == null ? origin : before.origin(), stacks, nextStackId,
                before != null && definition.keepHighestTier() ? Math.max(before.tier(), tier) : tier,
                before == null ? durationMicros : before.originalDurationMicros(), history,
                before == null ? OptionalLong.empty() : before.pausedAt(), before == null ? definition.components().initial() : before.components());
        var receipt = new Receipt(requested, definition.creditOverflow() ? requested : Math.max(0, delta), delta, true);
        var changes = new ArrayList<Change>();
        changes.add(change(store, Kind.GAINED, Reason.APPLIED, before, after, receipt));
        if (before != null && delta != 0) changes.add(change(store, Kind.STACKS_CHANGED, Reason.APPLIED, before, after, receipt));
        if (refreshed) changes.add(change(store, Kind.REFRESHED, Reason.APPLIED, before, after, receipt));
        return new Result(replace(store, key, after, before == null ? Math.incrementExact(store.nextGeneration()) : store.nextGeneration()), receipt, changes);
    }

    public static Result consume(BuffStore store, Key key, int requested) {
        settled(store);
        if (requested < 1) throw new IllegalArgumentException("Consumption must be positive");
        BuffInstance before = store.instances().get(key);
        if (before == null) return unchanged(store, requested);
        var remaining = new ArrayList<>(before.stacks());
        remaining.sort(Comparator.comparingLong(Stack::expiresAt).thenComparingLong(Stack::id));
        int consumed = Math.min(requested, remaining.size());
        remaining.subList(0, consumed).clear();
        return updateStacks(store, before, remaining, requested, Reason.CONSUMED);
    }

    public static Result remove(BuffStore store, Key key, Reason reason) {
        settled(store);
        if (reason != Reason.REMOVED && reason != Reason.STOWED) throw new IllegalArgumentException("Invalid removal reason");
        BuffInstance before = store.instances().get(key);
        return before == null ? unchanged(store, 0) : updateStacks(store, before, List.of(), 0, reason);
    }

    /** Advances to the earliest deadline at or before until, or directly to until when no expiry intervenes. */
    public static Result advanceStep(BuffStore store, long until) {
        settled(store);
        if (until < store.timeMicros() || until == FOREVER) throw new IllegalArgumentException("Invalid target time");
        long next = Math.min(until, store.nextDeadline());
        var advanced = new BuffStore(next, store.nextGeneration(), store.instances());
        var changes = new ArrayList<Change>();
        for (BuffInstance before : store.instances().values()) {
            if (before.deadline() != next) continue;
            var remaining = new ArrayList<>(before.stacks());
            if (before.definition().timer().decay() == Decay.ONE_BY_ONE) {
                remaining.sort(Comparator.comparingLong(Stack::id));
                remaining.removeFirst();
                if (!remaining.isEmpty()) remaining = retime(remaining, at(next, before.definition().timer().decayIntervalMicros()));
            } else {
                remaining.removeIf(stack -> stack.expiresAt() <= next);
            }
            Result result = updateStacks(advanced, before, remaining, 0, Reason.EXPIRED);
            advanced = result.store(); changes.addAll(result.changes());
        }
        return new Result(advanced, new Receipt(0, 0, 0, !changes.isEmpty()), changes);
    }

    /** Refresh an existing timer without manufacturing an application or adding a stack. */
    public static Result refresh(BuffStore store, Key key, long duration) {
        settled(store);
        if (duration < 1) throw new IllegalArgumentException("Invalid refresh duration");
        BuffInstance before = store.instances().get(key);
        if (before == null || before.definition().timer().refresh() == Refresh.NONE) return unchanged(store, 0);
        if (before.definition().timer().mode() != TimerMode.SHARED) throw new IllegalArgumentException("Refresh requires a shared timer");
        long clock = before.pausedAt().orElse(store.timeMicros());
        long history = Math.max(before.longestDurationMicros(), duration);
        long nextDuration = switch (before.definition().timer().refresh()) {
            case RESET -> duration;
            case MAX_REMAINING -> Math.max(remaining(before.stacks().getFirst().expiresAt(), clock), duration);
            case HISTORIC_MAX -> history;
            case EXTEND -> extendRemaining(remaining(before.stacks().getFirst().expiresAt(), clock), duration, before.definition().timer().extensionCapMicros());
            case NONE -> throw new IllegalStateException("Unreachable refresh policy");
        };
        var after = copy(before, retime(before.stacks(), at(clock, nextDuration)), Math.max(history, nextDuration), before.pausedAt());
        return changed(store, before, after, Kind.REFRESHED, Reason.APPLIED);
    }

    /** Extending is distinct from reapplying: the explicit cap may shorten an already longer timer. */
    public static Result extend(BuffStore store, Key key, long amount, long cap) {
        settled(store);
        if (amount <= 0 || cap <= 0) throw new IllegalArgumentException("Invalid extension");
        BuffInstance before = store.instances().get(key);
        if (before == null) return unchanged(store, 0);
        if (before.definition().timer().mode() != TimerMode.SHARED) throw new IllegalArgumentException("Extension requires a shared timer");
        long clock = before.pausedAt().orElse(store.timeMicros());
        long duration = extendRemaining(remaining(before.stacks().getFirst().expiresAt(), clock), amount, cap);
        var after = copy(before, retime(before.stacks(), at(clock, duration)), Math.max(before.longestDurationMicros(), duration), before.pausedAt());
        return changed(store, before, after, Kind.EXTENDED, Reason.EXTENDED);
    }

    /** The host calls this at the weapon's actual stow/draw boundary, after earlier expirations settle. */
    public static Result weaponState(BuffStore store, String owner, String weapon, boolean stowed) {
        settled(store); required(owner, "owner"); required(weapon, "weapon");
        BuffStore current = store;
        var changes = new ArrayList<Change>();
        for (BuffInstance before : store.instances().values()) {
            if (!before.origin().owner().equals(owner) || !before.origin().weapon().equals(weapon)) continue;
            Result result;
            if (stowed && before.definition().binding().onStow() == OnStow.REMOVE) {
                result = remove(current, before.key(), Reason.STOWED);
            } else if (before.definition().binding().onStow() == OnStow.PAUSE && stowed != before.pausedAt().isPresent()) {
                var stacks = new ArrayList<Stack>();
                long duration = before.longestDurationMicros();
                for (Stack stack : before.stacks()) {
                    long left = remaining(stack.expiresAt(), before.pausedAt().orElse(store.timeMicros()));
                    if (stowed) left = roundUp(left, before.definition().timer().pauseRoundingMicros());
                    duration = Math.max(duration, left);
                    stacks.add(new Stack(stack.id(), at(store.timeMicros(), left), stack.origin()));
                }
                var after = copy(before, stacks, duration, stowed ? OptionalLong.of(store.timeMicros()) : OptionalLong.empty());
                result = changed(current, before, after, stowed ? Kind.PAUSED : Kind.RESUMED, stowed ? Reason.STOWED : Reason.DRAWN);
            } else continue;
            current = result.store(); changes.addAll(result.changes());
        }
        return new Result(current, new Receipt(0, 0, 0, !changes.isEmpty()), changes);
    }

    public static BuffStore components(BuffStore store, Key key, BuffComponents components) {
        settled(store);
        BuffInstance before = store.instances().get(key);
        if (before == null) throw new IllegalArgumentException("Missing buff instance: " + key);
        return replace(store, key, before.withComponents(components), store.nextGeneration());
    }

    private static Result updateStacks(BuffStore store, BuffInstance before, List<Stack> stacks, int requested, Reason reason) {
        BuffInstance after = stacks.isEmpty() ? null : copy(before, stacks, before.longestDurationMicros(), before.pausedAt());
        var receipt = new Receipt(requested, 0, stacks.size() - before.count(), true);
        return new Result(replace(store, before.key(), after, store.nextGeneration()), receipt,
                List.of(change(store, after == null ? Kind.ENDED : Kind.STACKS_CHANGED, reason, before, after, receipt)));
    }
    private static Result changed(BuffStore store, BuffInstance before, BuffInstance after, Kind kind, Reason reason) {
        var receipt = new Receipt(0, 0, after.count() - before.count(), true);
        return new Result(replace(store, before.key(), after, store.nextGeneration()), receipt, List.of(change(store, kind, reason, before, after, receipt)));
    }
    private static Result unchanged(BuffStore store, int requested) { return new Result(store, new Receipt(requested, 0, 0, false), List.of()); }
    private static Change change(BuffStore store, Kind kind, Reason reason, BuffInstance before, BuffInstance after, Receipt receipt) {
        return new Change(store.timeMicros(), kind, reason, Optional.ofNullable(before), Optional.ofNullable(after), receipt);
    }
    private static BuffInstance copy(BuffInstance before, List<Stack> stacks, long history, OptionalLong paused) {
        return new BuffInstance(before.generation(), before.key(), before.definition(), before.origin(), stacks, before.nextStackId(),
                before.tier(), before.originalDurationMicros(), history, paused, before.components());
    }
    private static BuffStore replace(BuffStore store, Key key, BuffInstance after, long sequence) {
        var instances = new TreeMap<>(store.instances());
        if (after == null) instances.remove(key); else instances.put(key, after);
        return new BuffStore(store.timeMicros(), sequence, instances);
    }
    private static ArrayList<Stack> retime(List<Stack> stacks, long deadline) {
        var result = new ArrayList<Stack>();
        for (Stack stack : stacks) result.add(new Stack(stack.id(), deadline, stack.origin()));
        return result;
    }
    private static long remaining(long deadline, long clock) { return deadline == FOREVER ? FOREVER : deadline - clock; }
    private static long at(long clock, long duration) {
        if (duration == FOREVER) return FOREVER;
        long result = Math.addExact(clock, duration);
        if (result == FOREVER) throw new IllegalArgumentException("Finite deadline collides with permanent lifetime");
        return result;
    }
    private static long extendRemaining(long remaining, long amount, long cap) {
        return remaining >= cap || amount >= cap - remaining ? cap : remaining + amount;
    }
    private static long roundUp(long remaining, long quantum) {
        if (remaining == FOREVER || quantum == 0 || remaining % quantum == 0) return remaining;
        return Math.addExact(remaining, quantum - remaining % quantum);
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing buff " + field);
        return value;
    }
    static void settled(BuffStore store) {
        if (store.nextDeadline() <= store.timeMicros()) throw new IllegalStateException("Settle expired buffs before another operation");
    }
}
