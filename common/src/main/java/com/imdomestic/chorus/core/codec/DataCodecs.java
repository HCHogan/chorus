package com.imdomestic.chorus.core.codec;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public final class DataCodecs {
    private DataCodecs() {}
    public static final Codec<String> ID = Codec.STRING.validate(value -> value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            ? DataResult.success(value) : DataResult.error(() -> "Invalid namespaced id: " + value));
    public static final Codec<Double> FINITE = Codec.DOUBLE.validate(value -> Double.isFinite(value)
            ? DataResult.success(value) : DataResult.error(() -> "Number must be finite"));

    public static <T> DataResult<T> safe(Supplier<T> supplier) {
        try { return DataResult.success(supplier.get()); }
        catch (IllegalArgumentException | ArithmeticException exception) { return DataResult.error(exception::getMessage); }
    }
    public static <E extends Enum<E>> Codec<E> enumeration(Class<E> type) {
        return Codec.STRING.comapFlatMap(value -> safe(() -> Enum.valueOf(type, value.toUpperCase(Locale.ROOT))),
                value -> value.name().toLowerCase(Locale.ROOT));
    }

    /** Reject typos and fields belonging to not-yet-compiled content rather than ignoring them. */
    public static <T> Codec<T> strict(Codec<T> codec, Set<String> fields) {
        Set<String> allowed = Set.copyOf(fields);
        return new Codec<>() {
            @Override public <O> DataResult<Pair<T, O>> decode(DynamicOps<O> ops, O input) {
                return ops.getMap(input).flatMap(map -> {
                    var unknown = map.entries().map(entry -> ops.getStringValue(entry.getFirst()).result().orElse("<non-string field>"))
                            .filter(field -> !allowed.contains(field)).sorted().toList();
                    return unknown.isEmpty() ? codec.decode(ops, input) : DataResult.error(() -> "Unknown fields: " + unknown);
                });
            }
            @Override public <O> DataResult<O> encode(T value, DynamicOps<O> ops, O prefix) { return codec.encode(value, ops, prefix); }
        };
    }

    public static <T> MapCodec<T> strictType(MapCodec<T> codec) {
        return new MapCodec<>() {
            @Override public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
                var allowed = new java.util.HashSet<String>(); allowed.add("type");
                codec.keys(ops).forEach(key -> ops.getStringValue(key).result().ifPresent(allowed::add));
                var unknown = input.entries().map(entry -> ops.getStringValue(entry.getFirst()).result().orElse("<non-string field>"))
                        .filter(field -> !allowed.contains(field)).sorted().toList();
                return unknown.isEmpty() ? codec.decode(ops, input) : DataResult.error(() -> "Unknown fields: " + unknown);
            }
            @Override public <O> RecordBuilder<O> encode(T value, DynamicOps<O> ops, RecordBuilder<O> prefix) { return codec.encode(value, ops, prefix); }
            @Override public <O> java.util.stream.Stream<O> keys(DynamicOps<O> ops) { return codec.keys(ops); }
        };
    }

    /** Exact decimal seconds on input. Positive lifetimes are validated by the owning definition. */
    public static final Codec<Long> MICROS = FINITE.flatXmap(seconds -> safe(() -> {
        long micros = BigDecimal.valueOf(seconds).movePointRight(6).longValueExact();
        if (micros < 0 || micros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid finite duration");
        return micros;
    }), micros -> safe(() -> {
        if (micros < 0 || micros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid finite duration");
        double seconds = BigDecimal.valueOf(micros, 6).doubleValue();
        if (BigDecimal.valueOf(seconds).movePointRight(6).longValueExact() != micros) {
            throw new IllegalArgumentException("Duration cannot round-trip as JSON seconds");
        }
        return seconds;
    }));
    public static final Codec<Long> DURATION = Codec.either(Codec.STRING, MICROS).flatXmap(value -> value.map(
            text -> text.equals("permanent") ? DataResult.success(Long.MAX_VALUE) : DataResult.error(() -> "Expected permanent or seconds"),
            DataResult::success), micros -> DataResult.success(micros == Long.MAX_VALUE ? Either.left("permanent") : Either.right(micros)));
}
