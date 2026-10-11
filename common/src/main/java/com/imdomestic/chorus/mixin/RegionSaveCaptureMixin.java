package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(IOWorker.class)
abstract class RegionSaveCaptureMixin implements MinecraftRegionSaveAccess {
    @Unique private final RegionSaveJournal chorus$saveJournal = new RegionSaveJournal();
    @Override public RegionSaveJournal chorus$regionSaves() { return chorus$saveJournal; }

    @WrapMethod(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<Void> chorus$store(ChunkPos pos, Supplier<CompoundTag> data, Operation<CompletableFuture<Void>> original) {
        return chorus$saveJournal.submit(RegionSaveJournal.Kind.WRITE, pos.toString(), attempt -> original.call(pos, (Supplier<CompoundTag>) () -> {
            if (SharedConstants.DEBUG_DONT_SAVE_WORLD) attempt.failed(new IllegalStateException("Native region writes disabled"));
            try { return data.get(); }
            catch (RuntimeException | Error error) { attempt.failed(error); throw error; }
        }));
    }
    @WrapMethod(method = "synchronize")
    private CompletableFuture<Void> chorus$flush(boolean flush, Operation<CompletableFuture<Void>> original) {
        return chorus$saveJournal.submit(flush ? RegionSaveJournal.Kind.FLUSH : RegionSaveJournal.Kind.DRAIN,
                "storage", _ -> original.call(flush));
    }
    @WrapMethod(method = "close")
    private void chorus$close(Operation<Void> original) {
        MinecraftCheckpointLifecycle.beforeRegionClose((IOWorker) (Object) this);
        chorus$saveJournal.submit(RegionSaveJournal.Kind.CLOSE, "worker", _ -> { original.call(); return CompletableFuture.completedFuture(null); });
    }
    @WrapOperation(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;close()V"))
    private void chorus$closeStorage(RegionFileStorage storage, Operation<Void> original) throws Throwable {
        try { original.call(storage); }
        catch (Throwable error) { chorus$saveJournal.failedClose(error); throw error; }
    }
}
