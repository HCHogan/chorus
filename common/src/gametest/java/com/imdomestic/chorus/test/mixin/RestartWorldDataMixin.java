package com.imdomestic.chorus.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.serialization.*;
import java.io.*;
import java.nio.file.*;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.*;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** The vanilla GameTest factory always creates fresh world metadata. Opt-in restart tests read its actual level.dat. */
@Mixin(GameTestServer.class)
abstract class RestartWorldDataMixin {
    @Unique private static volatile Path chorus$restartData;
    @WrapMethod(method = "create")
    private static GameTestServer chorus$restartWorld(Thread thread, LevelStorageSource.LevelStorageAccess storage, PackRepository packs,
            Optional<String> selection, boolean verify, int repeats, Operation<GameTestServer> original) {
        String phase = System.getProperty("chorus.test.restart.phase");
        if (phase == null) return original.call(thread, storage, packs, selection, verify, repeats);
        chorus$restartData = phase.startsWith("restore") ? storage.getLevelPath(LevelResource.LEVEL_DATA_FILE) : null;
        try { return original.call(thread, storage, packs, Optional.of("*:*checkpoint*restart*"), verify, repeats); }
        finally { chorus$restartData = null; }
    }
    @WrapOperation(method = "lambda$create$1", at = @At(value = "NEW", target = "net/minecraft/world/level/storage/PrimaryLevelData"))
    private static PrimaryLevelData chorus$readSavedWorld(LevelSettings settings, PrimaryLevelData.SpecialWorldProperty type,
            Lifecycle lifecycle, Operation<PrimaryLevelData> original) {
        var file = chorus$restartData;
        if (file == null) return original.call(settings, type, lifecycle);
        try {
            var data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            return PrimaryLevelData.parse(new Dynamic<>(NbtOps.INSTANCE, data), settings, type, lifecycle);
        } catch (IOException failure) { throw new UncheckedIOException("Cannot read restart fixture level.dat", failure); }
    }
}
