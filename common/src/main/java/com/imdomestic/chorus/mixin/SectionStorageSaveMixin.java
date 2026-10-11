package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionStorage.class)
abstract class SectionStorageSaveMixin implements MinecraftRegionStorageAccess {
    @Shadow @Final private SimpleRegionStorage simpleRegionStorage;
    @Override public SimpleRegionStorage chorus$regionStorage() { return simpleRegionStorage; }
    @WrapMethod(method = "writeChunk(Lnet/minecraft/world/level/ChunkPos;)V")
    private void chorus$encode(ChunkPos pos, Operation<Void> original) {
        MinecraftSaveSerialization.region(simpleRegionStorage, "sections:" + pos, () -> { original.call(pos); return null; });
    }
    @WrapOperation(method = "writeChunk(Lnet/minecraft/world/level/ChunkPos;Lcom/mojang/serialization/DynamicOps;)Lcom/mojang/serialization/Dynamic;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/DataResult;resultOrPartial(Ljava/util/function/Consumer;)Ljava/util/Optional;"))
    private <T> Optional<T> chorus$partial(DataResult<T> result, Consumer<String> logger, Operation<Optional<T>> original) {
        result.error().ifPresent(error -> SaveSerializationCapture.problem(error.message()));
        return original.call(result, logger);
    }
}
