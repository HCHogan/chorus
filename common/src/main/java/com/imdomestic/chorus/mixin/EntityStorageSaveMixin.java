package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityStorage.class)
abstract class EntityStorageSaveMixin implements MinecraftRegionStorageAccess {
    @Shadow @Final private SimpleRegionStorage simpleRegionStorage;
    @Override public SimpleRegionStorage chorus$regionStorage() { return simpleRegionStorage; }
    @WrapMethod(method = "storeEntities")
    private void chorus$encode(ChunkEntities<Entity> chunk, Operation<Void> original) {
        MinecraftSaveSerialization.region(simpleRegionStorage, "entities:" + chunk.getPos(), () -> { original.call(chunk); return null; });
    }
    // The invocation is in the generated storeEntities lambda; NeoForge catches its exceptions and skips that entity.
    @WrapOperation(method = "lambda$storeEntities$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;save(Lnet/minecraft/world/level/storage/ValueOutput;)Z"))
    private static boolean chorus$entity(Entity entity, ValueOutput output, Operation<Boolean> original) {
        return SaveSerializationCapture.call(() -> original.call(entity, output));
    }
}
