package com.imdomestic.chorus.platform.minecraft;

/** Implemented by each native SavedDataStorage; reports only that exact instance's save work. */
public interface MinecraftSavedDataSaveAccess {
    SavedDataSaveJournal chorus$savedDataSaves();
}
