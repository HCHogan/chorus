package com.imdomestic.chorus.core.codec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Startup builder for an open, data-driven sum type. Built codecs have immutable registries. */
public final class TypeRegistry<T> {
    private final Map<String, MapCodec<? extends T>> codecs = new LinkedHashMap<>();
    private final Map<Class<?>, String> names = new LinkedHashMap<>();

    public <S extends T> TypeRegistry<T> register(String id, Class<S> type, MapCodec<S> codec) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(codec);
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid type id: " + id);
        if (codecs.containsKey(id) || names.containsKey(type)) throw new IllegalArgumentException("Duplicate type registration: " + id);
        codecs.put(id, DataCodecs.strictType(codec));
        names.put(type, id);
        return this;
    }

    public Codec<T> build() {
        Map<String, MapCodec<? extends T>> frozenCodecs = Map.copyOf(codecs);
        Map<Class<?>, String> frozenNames = Map.copyOf(names);
        return Codec.STRING.partialDispatch("type", value -> {
            String id = frozenNames.get(value.getClass());
            return id == null ? DataResult.error(() -> "Unregistered type: " + value.getClass().getName()) : DataResult.success(id);
        }, id -> {
            MapCodec<? extends T> codec = frozenCodecs.get(id);
            return codec == null ? DataResult.error(() -> "Unknown type: " + id) : DataResult.success(codec);
        });
    }
}
