package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.SaveSerializationCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntity.class)
abstract class BlockEntitySaveCaptureMixin {
    @WrapMethod(method = "saveWithFullMetadata(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;")
    private CompoundTag chorus$encode(HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return SaveSerializationCapture.call(() -> original.call(registries));
    }
}
