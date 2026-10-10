package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets={"net.minecraft.world.entity.LivingEntity","net.minecraft.world.entity.animal.rabbit.Rabbit","net.minecraft.world.entity.animal.sniffer.Sniffer","net.minecraft.world.entity.monster.cubemob.AbstractCubeMob","net.minecraft.world.entity.monster.cubemob.MagmaCube"})
abstract class GroundJumpMixin {
    @Inject(method="jumpFromGround",at=@At("HEAD"),cancellable=true)
    private void chorus$jump(CallbackInfo ci){if(MinecraftMovementInput.blocked((LivingEntity)(Object)this,MinecraftMovementInput.JUMP))ci.cancel();}
}
