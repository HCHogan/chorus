package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SavedDataStorage.class)
abstract class SavedDataSaveCaptureMixin implements MinecraftSavedDataSaveAccess {
    @Unique private final SavedDataSaveJournal chorus$saveJournal = new SavedDataSaveJournal();
    @Override public SavedDataSaveJournal chorus$savedDataSaves() { return chorus$saveJournal; }

    @WrapMethod(method = "scheduleSave")
    private CompletableFuture<?> chorus$schedule(Operation<CompletableFuture<?>> original) {
        return chorus$saveJournal.schedule(() -> original.call());
    }
    @WrapOperation(method = "tryWrite", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/FileUtil;createDirectoriesSafe(Ljava/nio/file/Path;)V"))
    private void chorus$directory(Path path, Operation<Void> original) throws IOException {
        chorus$saveJournal.io(SavedDataSaveJournal.Kind.DIRECTORY, path, () -> original.call(path));
    }
    @WrapMethod(method = "close")
    private void chorus$close(Operation<Void> original) { chorus$saveJournal.close(() -> original.call()); }
}
