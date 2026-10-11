package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkMap.class)
abstract class ChunkMapSaveMixin {
    @WrapMethod(method = "save")
    private boolean chorus$encode(ChunkAccess chunk, Operation<Boolean> original) {
        return MinecraftSaveSerialization.region((SimpleRegionStorage) (Object) this, "chunk:" + chunk.getPos(), () -> original.call(chunk));
    }
    @WrapOperation(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;reportChunkSaveFailure(Ljava/lang/Throwable;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;)V"))
    private void chorus$failed(MinecraftServer server, Throwable failure, RegionStorageInfo storage, ChunkPos pos, Operation<Void> original) {
        SaveSerializationCapture.problem(failure.toString()); original.call(server, failure, storage, pos);
    }
}
