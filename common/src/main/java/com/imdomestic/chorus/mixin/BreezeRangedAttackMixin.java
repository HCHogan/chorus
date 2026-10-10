package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="net.minecraft.world.entity.monster.breeze.Shoot")
abstract class BreezeRangedAttackMixin {
    private static boolean chorus$allowed(Breeze actor){return MinecraftNativeActions.ranged(actor,actor.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null),"minecraft:breeze_wind_charge");}
    @Inject(method="checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/breeze/Breeze;)Z",at=@At("RETURN"),cancellable=true)
    private void chorus$start(ServerLevel level,Breeze actor,CallbackInfoReturnable<Boolean> cir){if(cir.getReturnValueZ()&&!chorus$allowed(actor))cir.setReturnValue(false);}
    @Inject(method="canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/breeze/Breeze;J)Z",at=@At("RETURN"),cancellable=true)
    private void chorus$continue(ServerLevel level,Breeze actor,long tick,CallbackInfoReturnable<Boolean> cir){if(cir.getReturnValueZ()&&!chorus$allowed(actor))cir.setReturnValue(false);}
    @Inject(method="tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/breeze/Breeze;J)V",at=@At("HEAD"),cancellable=true)
    private void chorus$tick(ServerLevel level,Breeze actor,long tick,CallbackInfo ci){if(!chorus$allowed(actor))ci.cancel();}
}
