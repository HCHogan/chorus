package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.ammo.*;
import com.imdomestic.chorus.effect.data.Value;
import com.imdomestic.chorus.stat.Unit;
import java.util.*;
import org.junit.jupiter.api.Test;

class AmmunitionTest {
    private static AmmoState finite(int magazine, int reserves) { return new AmmoState("weapon-a", magazine, 6, Optional.of(new AmmoState.Reserve(reserves, 20))); }
    @Test void refillConservesFiniteRoundsAndRespectsSourceAndDestinationLimits() {
        for (int magazine : List.of(0, 2, 6, 9, 12)) for (int reserve : List.of(0, 1, 5, 20))
            for (int ceiling : List.of(0, 6, 12)) for (int request : List.of(0, 1, 4, 25)) {
                var before = finite(magazine, reserve); var result = Ammunition.refill(before, request, ceiling);
                assertEquals(magazine + reserve, result.after().magazine() + result.after().reserve().orElseThrow().rounds());
                assertEquals(Math.min(request, Math.min(reserve, Math.max(0, ceiling - magazine))), result.applied());
                assertEquals(6, result.after().capacity()); assertTrue(result.after().magazine() >= magazine);
                assertEquals(request - result.applied(), result.unfulfilled()); assertEquals(magazine, before.magazine());
            }
    }
    @Test void generationDoesNotDebitReservesAndLowerCeilingsNeverDestroyExistingOverflow() {
        var overflow = Ammunition.generate(finite(2, 5), Ammunition.Pool.MAGAZINE, 20, 12);
        assertEquals(12, overflow.after().magazine()); assertEquals(10, overflow.applied()); assertEquals(5, overflow.after().reserve().orElseThrow().rounds());
        var limited = Ammunition.generate(overflow.after(), Ammunition.Pool.MAGAZINE, 2, 6);
        assertEquals(overflow.after(), limited.after()); assertEquals(0, limited.applied());
        var pickup = Ammunition.generate(finite(2, 19), Ammunition.Pool.RESERVES, 5, 100);
        assertEquals(1, pickup.applied()); assertEquals(20, pickup.after().reserve().orElseThrow().rounds());
    }
    @Test void spendingIsAtomicAndUnlimitedReserveIsNotEncodedAsAZeroOrHugeFiniteAmount() {
        var first = finite(2, 5); var failed = Ammunition.spend(first, Ammunition.Pool.MAGAZINE, 3);
        assertFalse(failed.complete()); assertEquals(first, failed.after()); assertEquals(0, failed.applied());
        var paid = Ammunition.spend(first, Ammunition.Pool.MAGAZINE, 2); assertTrue(paid.complete()); assertEquals(0, paid.after().magazine());
        var unlimited = new AmmoState("primary", 0, 6, Optional.empty());
        assertEquals(12, Ammunition.refill(unlimited, 12, 12).after().magazine());
        assertEquals(0, Ammunition.generate(unlimited, Ammunition.Pool.RESERVES, 10, 100).applied());
        var debit = Ammunition.spend(unlimited, Ammunition.Pool.RESERVES, 100); assertEquals(100, debit.applied()); assertFalse(debit.changed());
        assertThrows(IllegalArgumentException.class, () -> unlimited.read(AmmoState.Field.RESERVES));
    }
    @Test void maximumIntegerTransfersCannotWrapAndFractionalMutationMustBeExplicitlyRounded() {
        var state = new AmmoState("max", Integer.MAX_VALUE - 1, 1, Optional.of(new AmmoState.Reserve(Integer.MAX_VALUE, Integer.MAX_VALUE)));
        var moved = Ammunition.refill(state, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(1, moved.applied()); assertEquals(Integer.MAX_VALUE, moved.after().magazine()); assertEquals(Integer.MAX_VALUE - 1, moved.after().reserve().orElseThrow().rounds());
        for (double bad : new double[] {-1, 0.5, Double.NaN, Double.POSITIVE_INFINITY, 2147483648.0}) assertThrows(IllegalArgumentException.class, () -> AmmoState.rounds(bad));
        assertEquals(0, AmmoState.rounds(0)); assertEquals(Integer.MAX_VALUE, AmmoState.rounds(Integer.MAX_VALUE));
    }
    @Test void fabricatedReceiptsCannotClaimTransfersGenerationOrDebitsThatDidNotHappen() {
        var state = finite(2, 5);
        assertThrows(IllegalArgumentException.class, () -> new Ammunition.Result(Ammunition.Kind.REFILL, Ammunition.Pool.MAGAZINE, state, state.magazine(3), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Ammunition.Result(Ammunition.Kind.SPEND, Ammunition.Pool.MAGAZINE, state, state.magazine(1), 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new Ammunition.Result(Ammunition.Kind.GENERATE, Ammunition.Pool.MAGAZINE, state, state, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AmmoState.Reserve(5, 4));
    }
    @Test void roundingPreservesUnitsAndDeclaresCeilingFloorAndHalfUpSemantics() {
        assertEquals(2, new Value.Round(new Value.Constant(1.2, Unit.ROUND), Value.Rounding.CEILING).evaluate(null).value());
        assertEquals(-2, new Value.Round(new Value.Constant(-1.2, Unit.ROUND), Value.Rounding.FLOOR).evaluate(null).value());
        assertEquals(-3, new Value.Round(new Value.Constant(-2.5, Unit.ROUND), Value.Rounding.HALF_UP).evaluate(null).value());
        var result = new Value.Round(new Value.Constant(2.5, Unit.METER), Value.Rounding.HALF_UP).evaluate(null);
        assertEquals(Unit.METER, result.unit()); assertEquals(3, result.value());
    }
}
