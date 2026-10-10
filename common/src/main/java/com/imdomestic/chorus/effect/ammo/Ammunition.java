package com.imdomestic.chorus.effect.ammo;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;

/** Pure operations: transfer conserves finite ammunition; generation is an explicit separate operation. */
public final class Ammunition {
    private Ammunition() {}
    public enum Pool { MAGAZINE, RESERVES }
    public enum Kind { SPEND, REFILL, GENERATE }
    public record Result(Kind kind, Pool pool, AmmoState before, AmmoState after, int requested, int applied) implements RuleEngine.ActionResult {
        public Result {
            Objects.requireNonNull(kind); Objects.requireNonNull(pool); Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (requested < 0 || applied < 0 || applied > requested || !before.weapon().equals(after.weapon()) || before.capacity() != after.capacity()
                    || !before.reserve().map(AmmoState.Reserve::capacity).equals(after.reserve().map(AmmoState.Reserve::capacity))) throw new IllegalArgumentException("Invalid ammunition receipt");
            long magazineDelta = (long) after.magazine() - before.magazine();
            long reserveDelta = before.reserve().isPresent() ? (long) after.reserve().orElseThrow().rounds() - before.reserve().orElseThrow().rounds() : 0;
            long expectedMagazine = kind == Kind.REFILL || pool == Pool.MAGAZINE ? (kind == Kind.SPEND ? -applied : applied) : 0;
            long expectedReserve = before.reserve().isEmpty() ? 0 : kind == Kind.REFILL ? -applied : pool == Pool.RESERVES ? (kind == Kind.SPEND ? -applied : applied) : 0;
            if (kind == Kind.REFILL && pool != Pool.MAGAZINE || kind == Kind.SPEND && applied != 0 && applied != requested
                    || magazineDelta != expectedMagazine || reserveDelta != expectedReserve || kind == Kind.GENERATE && pool == Pool.RESERVES && before.reserve().isEmpty() && applied != 0)
                throw new IllegalArgumentException("Ammunition receipt violates its operation");
        }
        public boolean complete() { return requested == applied; }
        public boolean changed() { return !before.equals(after); }
        public int unfulfilled() { return requested - applied; }
    }
    public static Result spend(AmmoState state, Pool pool, int requested) {
        if (requested < 0) throw new IllegalArgumentException("Negative ammunition cost");
        int available = pool == Pool.MAGAZINE ? state.magazine() : state.reserve().map(AmmoState.Reserve::rounds).orElse(Integer.MAX_VALUE);
        int paid = available >= requested ? requested : 0;
        var after = pool == Pool.MAGAZINE ? state.magazine(state.magazine() - paid) : state.reserve().isEmpty() ? state : state.reserve(available - paid);
        return new Result(Kind.SPEND, pool, state, after, requested, paid);
    }
    public static Result refill(AmmoState state, int requested, int ceiling) {
        if (requested < 0 || ceiling < 0) throw new IllegalArgumentException("Negative ammunition refill");
        int moved = Math.min(Math.min(requested, Math.max(0, ceiling - state.magazine())), state.reserve().map(AmmoState.Reserve::rounds).orElse(Integer.MAX_VALUE));
        var after = state.magazine(state.magazine() + moved);
        if (state.reserve().isPresent()) after = after.reserve(state.reserve().orElseThrow().rounds() - moved);
        return new Result(Kind.REFILL, Pool.MAGAZINE, state, after, requested, moved);
    }
    public static Result generate(AmmoState state, Pool pool, int requested, int ceiling) {
        if (requested < 0 || ceiling < 0) throw new IllegalArgumentException("Negative ammunition grant");
        int credited; AmmoState after;
        if (pool == Pool.MAGAZINE) {
            credited = Math.min(requested, Math.max(0, ceiling - state.magazine())); after = state.magazine(state.magazine() + credited);
        } else if (state.reserve().isPresent()) {
            var reserve = state.reserve().orElseThrow(); credited = Math.min(requested, Math.max(0, Math.min(ceiling, reserve.capacity()) - reserve.rounds())); after = state.reserve(reserve.rounds() + credited);
        } else { credited = 0; after = state; }
        return new Result(Kind.GENERATE, pool, state, after, requested, credited);
    }
}
