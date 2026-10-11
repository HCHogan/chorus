package com.imdomestic.chorus.platform.minecraft;

import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** The exact backing storage of an entity or section storage instance. */
public interface MinecraftRegionStorageAccess {
    SimpleRegionStorage chorus$regionStorage();
}
