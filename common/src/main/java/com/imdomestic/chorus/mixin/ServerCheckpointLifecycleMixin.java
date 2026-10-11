package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftCheckpointLifecycle;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
abstract class ServerCheckpointLifecycleMixin {
    @WrapMethod(method = "loadLevel")
    private void chorus$load(Operation<Void> original) {
        var server = (MinecraftServer) (Object) this;
        MinecraftCheckpointLifecycle.starting(server); original.call(); MinecraftCheckpointLifecycle.started(server);
    }
    @WrapMethod(method = "stopServer")
    private void chorus$stop(Operation<Void> original) {
        MinecraftCheckpointLifecycle.stopping((MinecraftServer) (Object) this, () -> original.call());
    }
    @WrapOperation(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;close()V"))
    private void chorus$unlock(LevelStorageSource.LevelStorageAccess storage, Operation<Void> original) throws Throwable {
        var server = (MinecraftServer) (Object) this; MinecraftCheckpointLifecycle.beforeUnlock(server);
        try { original.call(storage); }
        catch (Throwable failure) { MinecraftCheckpointLifecycle.unlockFailed(server, failure); throw failure; }
    }
}
