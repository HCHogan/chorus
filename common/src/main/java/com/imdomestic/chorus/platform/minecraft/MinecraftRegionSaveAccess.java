package com.imdomestic.chorus.platform.minecraft;

/** Implemented by each native IOWorker; SimpleRegionStorage exposes it through chunkScanner(). */
public interface MinecraftRegionSaveAccess {
    RegionSaveJournal chorus$regionSaves();
}
