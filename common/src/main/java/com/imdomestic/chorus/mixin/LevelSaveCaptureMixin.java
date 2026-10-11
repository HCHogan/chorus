package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftSaveCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelStorageSource.LevelStorageAccess.class)
abstract class LevelSaveCaptureMixin {
    @WrapMethod(method = "saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V")
    private void chorus$save(CompoundTag data, Operation<Void> original) {
        var access = (LevelStorageSource.LevelStorageAccess) (Object) this;
        MinecraftSaveCapture.saving(access.getLevelPath(LevelResource.LEVEL_DATA_FILE), () -> original.call(data));
    }
}
