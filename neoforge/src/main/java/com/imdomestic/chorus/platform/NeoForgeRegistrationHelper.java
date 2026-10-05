package com.imdomestic.chorus.platform;

import com.imdomestic.chorus.Constants;
import com.imdomestic.chorus.platform.services.IRegistrationHelper;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public class NeoForgeRegistrationHelper implements IRegistrationHelper {
    private static IEventBus modBus;
    private static final Map<ResourceKey<?>, DeferredRegister<?>> REGISTERS = new HashMap<>();

    public static void setModBus(IEventBus bus) {
        modBus = bus;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, I extends T> Supplier<I> register(ResourceKey<? extends Registry<T>> registry, String name,
            Function<ResourceKey<T>, I> factory) {
        DeferredRegister<T> deferred = (DeferredRegister<T>) REGISTERS.computeIfAbsent(registry, r -> {
            DeferredRegister<T> created = DeferredRegister.create(registry, Constants.MOD_ID);
            created.register(modBus);
            return created;
        });

        return deferred.register(name, id -> factory.apply(ResourceKey.create(registry, id)));
    }
}
