package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="net.minecraft.world.entity.ai.behavior.warden.SonicBoom")
abstract class WardenRangedAttackMixin {
    private static boolean chorus$allowed(Warden actor){return MinecraftNativeActions.ranged(actor,actor.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null),"minecraft:sonic_boom");}
    @Inject(method="checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;)Z",at=@At("RETURN"),cancellable=true)
    private void chorus$start(ServerLevel level,Warden actor,CallbackInfoReturnable<Boolean> cir){if(cir.getReturnValueZ()&&!chorus$allowed(actor))cir.setReturnValue(false);}
    @Inject(method="canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)Z",at=@At("RETURN"),cancellable=true)
    private void chorus$continue(ServerLevel level,Warden actor,long tick,CallbackInfoReturnable<Boolean> cir){if(cir.getReturnValueZ()&&!chorus$allowed(actor))cir.setReturnValue(false);}
    @Inject(method="tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)V",at=@At("HEAD"),cancellable=true)
    private void chorus$tick(ServerLevel level,Warden actor,long tick,CallbackInfo ci){if(!chorus$allowed(actor))ci.cancel();}
}
