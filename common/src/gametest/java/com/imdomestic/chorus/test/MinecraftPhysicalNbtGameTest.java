package com.imdomestic.chorus.test;

import com.imdomestic.chorus.platform.minecraft.MinecraftPhysicalNbt;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;

/** Typed native NBT coverage; these checks need the Minecraft runtime, not the pure-core test classpath. */
public class MinecraftPhysicalNbtGameTest {
    private static void check(GameTestHelper h, boolean value) { check(h, value, "Expected condition to hold"); }
    private static void check(GameTestHelper h, boolean value, String message) { h.assertTrue(value, message); }
    private static void equal(GameTestHelper h, Object expected, Object actual) { equal(h, expected, actual, "Typed NBT value"); }
    private static void equal(GameTestHelper h, Object expected, Object actual, String message) { h.assertValueEqual(actual, expected, message); }
    private static void different(GameTestHelper h, Object first, Object second) { h.assertTrue(!Objects.equals(first, second), "Distinct NBT values must have distinct digests"); }
    private static void throwsType(GameTestHelper h, Class<? extends Throwable> type, Runnable action) {
        Throwable observed = null;
        try { action.run(); } catch (Throwable failure) { observed = failure; }
        h.assertTrue(type.isInstance(observed), "Expected " + type.getSimpleName() + " but received " + observed);
    }
    private static CompoundTag body(Tag value) {
        var result = new CompoundTag(); result.put("value", value); return result;
    }
    private static ListTag list(Tag... values) {
        var result = new ListTag(); for (var value : values) result.add(value); return result;
    }
    private static String digest(Tag value) { return MinecraftPhysicalNbt.digest(body(value)); }
    private static void distinct(GameTestHelper h, List<? extends Tag> values) {
        var digests = new HashSet<String>();
        for (var value : values) check(h, digests.add(digest(value)), "Lost typed value: " + value);
    }

    @GameCase public void compoundOrderIsCanonicalAtTheRootAndInsideLists(GameTestHelper h) {
        // These keys collide in HashMap; reversed insertion exercises genuinely different iteration.
        var first = new CompoundTag(); first.putInt("FB", 1); first.putInt("Ea", 2);
        var second = new CompoundTag(); second.putInt("Ea", 2); second.putInt("FB", 1);
        equal(h, MinecraftPhysicalNbt.digest(first), MinecraftPhysicalNbt.digest(second));
        equal(h, digest(list(first, body(first.copy()))), digest(list(second, body(second.copy()))));
        second.putInt("FB", 3);
        different(h, digest(list(first)), digest(list(second)));
        h.succeed();
    }

    @GameCase public void listOrderLengthAndEveryMixedElementTypeArePreserved(GameTestHelper h) {
        var one = IntTag.valueOf(1); var two = IntTag.valueOf(2);
        different(h, digest(list(one, two)), digest(list(two, one)));
        different(h, digest(list(one)), digest(list(one, one)));
        different(h, digest(list(one, StringTag.valueOf("1"))), digest(list(StringTag.valueOf("1"), one)));
        var wrapped = new CompoundTag(); wrapped.put("", one);
        different(h, digest(list(one)), digest(list(wrapped)));
        distinct(h, List.of(new CompoundTag(), list(), new ByteArrayTag(new byte[0]),
                new IntArrayTag(new int[0]), new LongArrayTag(new long[0]), EndTag.INSTANCE));
        h.succeed();
    }

    @GameCase public void allNumericTagTypesRemainDistinctEvenForTheSameNumber(GameTestHelper h) {
        distinct(h, List.of(ByteTag.valueOf((byte) 1), ShortTag.valueOf((short) 1), IntTag.valueOf(1),
                LongTag.valueOf(1), FloatTag.valueOf(1), DoubleTag.valueOf(1), StringTag.valueOf("1")));
        h.succeed();
    }

    @GameCase public void stringAndKeyFramingPreservesUnicodeAndCannotMergeAdjacentValues(GameTestHelper h) {
        distinct(h, List.of(StringTag.valueOf(""), StringTag.valueOf("\u0000"), StringTag.valueOf("a:b"),
                StringTag.valueOf("命运"), StringTag.valueOf("\ud83c\udf1f"), StringTag.valueOf("\ud800")));
        var first = new CompoundTag(); first.putString("a", "bc");
        var second = new CompoundTag(); second.putString("ab", "c");
        different(h, MinecraftPhysicalNbt.digest(first), MinecraftPhysicalNbt.digest(second));
        different(h, digest(list(StringTag.valueOf("a"), StringTag.valueOf("bc"))),
                digest(list(StringTag.valueOf("ab"), StringTag.valueOf("c"))));
        h.succeed();
    }

    @GameCase public void integerBoundariesAndValuesAboveDoublePrecisionAreExact(GameTestHelper h) {
        distinct(h, List.of(ByteTag.valueOf(Byte.MIN_VALUE), ByteTag.valueOf(Byte.MAX_VALUE),
                ShortTag.valueOf(Short.MIN_VALUE), ShortTag.valueOf(Short.MAX_VALUE),
                IntTag.valueOf(Integer.MIN_VALUE), IntTag.valueOf(Integer.MAX_VALUE),
                LongTag.valueOf(Long.MIN_VALUE), LongTag.valueOf(Long.MAX_VALUE),
                LongTag.valueOf(9_007_199_254_740_992L), LongTag.valueOf(9_007_199_254_740_993L)));
        h.succeed();
    }

    @SuppressWarnings("removal") // Factories normalize signed zero; the digest must preserve the actual input tag.
    @GameCase public void floatingBitsPreserveSignedZeroSubnormalsInfinitiesAndNanPayloads(GameTestHelper h) {
        distinct(h, List.of(new FloatTag(0f), new FloatTag(-0f), FloatTag.valueOf(Float.MIN_VALUE),
                FloatTag.valueOf(Float.MAX_VALUE), FloatTag.valueOf(Float.POSITIVE_INFINITY), FloatTag.valueOf(Float.NEGATIVE_INFINITY),
                FloatTag.valueOf(Float.intBitsToFloat(0x7fc00001)), FloatTag.valueOf(Float.intBitsToFloat(0x7fc00002)),
                new DoubleTag(0d), new DoubleTag(-0d), DoubleTag.valueOf(Double.MIN_VALUE), DoubleTag.valueOf(Double.MAX_VALUE),
                DoubleTag.valueOf(Double.POSITIVE_INFINITY), DoubleTag.valueOf(Double.NEGATIVE_INFINITY),
                DoubleTag.valueOf(Double.longBitsToDouble(0x7ff8000000000001L)), DoubleTag.valueOf(Double.longBitsToDouble(0x7ff8000000000002L)),
                DoubleTag.valueOf(1d), DoubleTag.valueOf(Math.nextUp(1d))));
        h.succeed();
    }

    @GameCase public void arraysPreserveElementTypeLengthOrderAndFullIntegerWidth(GameTestHelper h) {
        distinct(h, List.of(new ByteArrayTag(new byte[] {1, 2}), new IntArrayTag(new int[] {1, 2}),
                new LongArrayTag(new long[] {1, 2}), list(IntTag.valueOf(1), IntTag.valueOf(2)),
                new ByteArrayTag(new byte[] {2, 1}), new IntArrayTag(new int[] {2, 1}),
                new LongArrayTag(new long[] {2, 1}), new ByteArrayTag(new byte[] {1}),
                new ByteArrayTag(new byte[] {Byte.MIN_VALUE, Byte.MAX_VALUE}),
                new IntArrayTag(new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}),
                new LongArrayTag(new long[] {Long.MIN_VALUE, Long.MAX_VALUE}),
                new LongArrayTag(new long[] {9_007_199_254_740_992L}), new LongArrayTag(new long[] {9_007_199_254_740_993L})));
        h.succeed();
    }

    @GameCase public void onlyTheRootMarkerIsExcludedAndNestedNamesRemainPartOfTheBody(GameTestHelper h) {
        var nested = new CompoundTag(); nested.putString(MinecraftPhysicalNbt.KEY, "first");
        var input = body(list(nested)); var plain = input.copy();
        input.putString(MinecraftPhysicalNbt.KEY, "marker one");
        equal(h, MinecraftPhysicalNbt.digest(plain), MinecraftPhysicalNbt.digest(input));
        input.put(MinecraftPhysicalNbt.KEY, list(IntTag.valueOf(9), new CompoundTag()));
        equal(h, MinecraftPhysicalNbt.digest(plain), MinecraftPhysicalNbt.digest(input));
        nested.putString(MinecraftPhysicalNbt.KEY, "second");
        different(h, MinecraftPhysicalNbt.digest(plain), MinecraftPhysicalNbt.digest(input));
        h.succeed();
    }

    @GameCase public void digestDoesNotMutateInputAndWithoutMarkerReturnsAnIsolatedDeepCopy(GameTestHelper h) {
        var nested = new CompoundTag(); nested.putInt("number", 3); nested.putString(MinecraftPhysicalNbt.KEY, "nested");
        var input = body(list(nested, new ByteArrayTag(new byte[] {1, 2}), new IntArrayTag(new int[] {3, 4}), new LongArrayTag(new long[] {5, 6})));
        input.putString(MinecraftPhysicalNbt.KEY, "root"); var before = input.copy();
        var copy = MinecraftPhysicalNbt.withoutMarker(input);
        check(h, input != copy, "withoutMarker must return a new compound"); equal(h, before, input); check(h, !copy.contains(MinecraftPhysicalNbt.KEY), "Root marker must be omitted");
        equal(h, MinecraftPhysicalNbt.digest(input), MinecraftPhysicalNbt.digest(copy)); equal(h, before, input);
        var values = copy.getListOrEmpty("value");
        ((CompoundTag) values.get(0)).putInt("number", 9);
        values.get(1).asByteArray().orElseThrow()[0] = 9;
        values.get(2).asIntArray().orElseThrow()[0] = 9;
        values.get(3).asLongArray().orElseThrow()[0] = 9;
        equal(h, before, input); different(h, MinecraftPhysicalNbt.digest(input), MinecraftPhysicalNbt.digest(copy));
        equal(h, "nested", ((CompoundTag) values.get(0)).getStringOr(MinecraftPhysicalNbt.KEY, ""));
        input.putString("added", "later"); check(h, !copy.contains("added"), "Mutating the original must not mutate the copy");
        h.succeed();
    }

    @GameCase public void digestHasAnExplicitFormatAndStableLowercaseSha256Width(GameTestHelper h) {
        equal(h, 1, MinecraftPhysicalNbt.DIGEST_FORMAT);
        String digest = MinecraftPhysicalNbt.digest(new CompoundTag());
        check(h, digest.matches("[0-9a-f]{64}"));
        equal(h, "42b21973fb3354ce120e94b22689344ca3a6e8cb037ab2088d6a2ba55bbe9091", digest,
                "Format 1 known vector: domain separator, version, compound type and zero entries");
        equal(h, digest, MinecraftPhysicalNbt.digest(new CompoundTag()));
        throwsType(h, NullPointerException.class, () -> MinecraftPhysicalNbt.digest(null));
        throwsType(h, NullPointerException.class, () -> MinecraftPhysicalNbt.withoutMarker(null));
        h.succeed();
    }
}
