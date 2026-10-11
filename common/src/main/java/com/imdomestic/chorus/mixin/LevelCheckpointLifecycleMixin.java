package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftCheckpointLifecycle;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class LevelCheckpointLifecycleMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void chorus$restoreBeforeEntityLoad(CallbackInfo callback) { MinecraftCheckpointLifecycle.created((ServerLevel) (Object) this); }
}
