package com.imdomestic.chorus.platform.services;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

public interface IRegistrationHelper {
    <T, I extends T> Supplier<I> register(ResourceKey<? extends Registry<T>> registry, String name,
            Function<ResourceKey<T>, I> factory);
}
