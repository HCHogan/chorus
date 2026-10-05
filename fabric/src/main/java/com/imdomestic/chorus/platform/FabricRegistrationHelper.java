package com.imdomestic.chorus.platform;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.platform.services.IRegistrationHelper;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public class FabricRegistrationHelper implements IRegistrationHelper {
    @Override
    @SuppressWarnings("unchecked")
    public <T, I extends T> Supplier<I> register(ResourceKey<? extends Registry<T>> registry, String name,
            Function<ResourceKey<T>, I> factory) {
        ResourceKey<T> key = ResourceKey.create(registry, Identifier.fromNamespaceAndPath(Constants.MOD_ID, name));
        Registry<T> target = (Registry<T>) BuiltInRegistries.REGISTRY.getValue(registry.identifier());
        I value = Registry.register(target, key, factory.apply(key));
        return () -> value;
    }
}
