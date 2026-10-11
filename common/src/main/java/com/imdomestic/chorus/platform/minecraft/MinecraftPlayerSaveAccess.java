package com.imdomestic.chorus.platform.minecraft;

import java.nio.file.Path;
import java.util.UUID;

/** Exact native PlayerDataStorage file path, including non-default storage roots. */
public interface MinecraftPlayerSaveAccess {
    Path chorus$playerFile(UUID player);
}
