package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.random.RandomState;
import com.imdomestic.chorus.effect.random.RandomState.Distribution;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RandomStateTest {
    static Measure value(double value) { return new Measure(value, Unit.MULTIPLIER); }
    @Test void pinnedStreamMatchesIndependentVectorsAndCanResumeAtAnySavedCursor() {
        var state = RandomState.ZERO;
        for (String hex : List.of("e220a8397b1dcdaf", "6e789e6aa1b965f4", "06c45d188009454f", "f88bb8a8724c81ec")) {
            var draw = state.next(); assertEquals(Long.parseUnsignedLong(hex, 16), draw.bits()); assertEquals(state.cursor() + 1, draw.after().cursor()); state = draw.after();
        }
        var jdk = new SplittableRandom(12345); state = new RandomState(12345, 0);
        for (int i = 0; i < 128; i++) { var draw = state.next(); assertEquals(jdk.nextLong(), draw.bits()); state = draw.after(); }
        assertEquals(state.next(), new RandomState(12345, 128).next());
    }
    @Test void realSamplesUseHalfOpenBoundsAndDegenerateBoundsStillConsumeOneDraw() {
        var state = RandomState.ZERO; var first = state.sample(Distribution.UNIFORM_REAL, value(.1), value(.5));
        assertEquals(.453324323285457, first.value().value()); assertEquals(1, first.draws()); assertEquals(0, state.cursor());
        for (int i = 0; i < 500; i++) {
            var sample = state.sample(Distribution.UNIFORM_REAL, value(-20), value(15));
            assertTrue(sample.value().value() >= -20 && sample.value().value() < 15); state = sample.after();
        }
        var constant = state.sample(Distribution.UNIFORM_REAL, value(9), value(9)); assertEquals(value(9), constant.value()); assertEquals(1, constant.draws());
        var adjacent = state.sample(Distribution.UNIFORM_REAL, value(1), value(Math.nextUp(1.0))); assertEquals(value(1), adjacent.value());
    }
    @Test void integerSamplingRejectsBiasedTailAndHandlesEntireSignedRange() {
        var rejectedTail = RandomState.ZERO.sample(Distribution.UNIFORM_INTEGER, value(-1), value(Integer.MAX_VALUE));
        assertEquals(2, rejectedTail.draws()); assertEquals(value(0x6e789e6aL - 1), rejectedTail.value());
        var full = RandomState.ZERO.sample(Distribution.UNIFORM_INTEGER, value(Integer.MIN_VALUE), value(Integer.MAX_VALUE));
        assertEquals(value(Integer.MIN_VALUE + 0xe220a839L), full.value()); assertEquals(1, full.draws());
        var state = RandomState.ZERO; var seen = new HashSet<Double>();
        for (int i = 0; i < 200; i++) { var sample = state.sample(Distribution.UNIFORM_INTEGER, value(-2), value(2)); seen.add(sample.value().value()); state = sample.after(); }
        assertEquals(Set.of(-2.0, -1.0, 0.0, 1.0, 2.0), seen);
    }
    @Test void receiptsCanBeVerifiedButCannotChangeSeedCursorBoundsOrValue() {
        var sample = new RandomState(-1, 20).sample(Distribution.UNIFORM_REAL, value(1), value(2));
        assertEquals(RandomState.ALGORITHM, sample.algorithm());
        assertEquals(sample, new RandomState.Sample(sample.distribution(), sample.before(), sample.after(), sample.lower(), sample.upper(), sample.value()));
        assertThrows(IllegalArgumentException.class, () -> new RandomState.Sample(sample.distribution(), sample.before(), sample.after(), sample.lower(), sample.upper(), value(1)));
        assertThrows(IllegalArgumentException.class, () -> new RandomState.Sample(sample.distribution(), sample.before(), new RandomState(0, 21), sample.lower(), sample.upper(), sample.value()));
        assertThrows(IllegalArgumentException.class, () -> new RandomState.Sample(sample.distribution(), sample.before(), new RandomState(-1, 22), sample.lower(), sample.upper(), sample.value()));
    }
    @Test void invalidBoundsAndExhaustedCounterFailBeforeAdvancing() {
        assertThrows(IllegalArgumentException.class, () -> new RandomState(0, -1));
        assertThrows(ArithmeticException.class, () -> new RandomState(0, Long.MAX_VALUE).next());
        for (var bounds : List.of(new double[]{2, 1}, new double[]{-Double.MAX_VALUE, Double.MAX_VALUE}))
            assertThrows(IllegalArgumentException.class, () -> RandomState.ZERO.sample(Distribution.UNIFORM_REAL, value(bounds[0]), value(bounds[1])));
        for (var bounds : List.of(new double[]{.5, 2}, new double[]{-1, 1.5}, new double[]{0, 2147483648.0}))
            assertThrows(IllegalArgumentException.class, () -> RandomState.ZERO.sample(Distribution.UNIFORM_INTEGER, value(bounds[0]), value(bounds[1])));
        assertThrows(IllegalArgumentException.class, () -> RandomState.ZERO.sample(Distribution.UNIFORM_REAL, value(0), new Measure(1, Unit.SECOND)));
        assertEquals(0, RandomState.ZERO.cursor());
    }
}
