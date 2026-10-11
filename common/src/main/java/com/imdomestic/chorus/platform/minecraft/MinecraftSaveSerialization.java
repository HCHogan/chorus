package com.imdomestic.chorus.platform.minecraft;

import java.util.function.Supplier;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

public final class MinecraftSaveSerialization {
    private MinecraftSaveSerialization() {}
    public static <T> T region(SimpleRegionStorage storage, String target, Supplier<T> original) {
        var journal = ((MinecraftRegionSaveAccess) storage.chunkScanner()).chorus$regionSaves();
        return journal.encode(target, attempt -> SaveSerializationCapture.observe(
                message -> attempt.failed(new IllegalStateException("Serialization: " + message)), original));
    }
}
