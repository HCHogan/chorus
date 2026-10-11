package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge replaces the vanilla writer with its temporary-file/force/move utility and adds a directory call. */
@Mixin(SavedDataStorage.class)
abstract class NeoForgeSavedDataWriteMixin {
    @WrapOperation(method = "tryWrite", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/IOUtilities;writeNbtCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void chorus$write(CompoundTag tag, Path path, Operation<Void> original) throws IOException {
        ((MinecraftSavedDataSaveAccess) this).chorus$savedDataSaves().io(SavedDataSaveJournal.Kind.WRITE, path, () -> original.call(tag, path));
    }
    @WrapOperation(method = "tryWrite", at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;createDirectories(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;"))
    private Path chorus$extraDirectory(Path path, FileAttribute<?>[] attributes, Operation<Path> original) throws IOException {
        var result = new Path[1];
        ((MinecraftSavedDataSaveAccess) this).chorus$savedDataSaves().io(SavedDataSaveJournal.Kind.DIRECTORY, path, () -> result[0] = original.call(path, attributes));
        return result[0];
    }
}
