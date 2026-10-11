package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SavedDataStorage.class)
abstract class FabricSavedDataWriteMixin {
    @WrapOperation(method = "tryWrite", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void chorus$write(CompoundTag tag, Path path, Operation<Void> original) throws IOException {
        ((MinecraftSavedDataSaveAccess) this).chorus$savedDataSaves().io(SavedDataSaveJournal.Kind.WRITE, path, () -> original.call(tag, path));
    }
}
