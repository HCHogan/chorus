package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets={"net.minecraft.world.entity.LivingEntity","net.minecraft.world.entity.Mob","net.minecraft.world.entity.animal.bee.Bee","net.minecraft.world.entity.monster.cubemob.MagmaCube"})
abstract class LiquidJumpMixin {
    @Inject(method="jumpInLiquid",at=@At("HEAD"),cancellable=true)
    private void chorus$jump(TagKey<Fluid> fluid,CallbackInfo ci){if(MinecraftMovementInput.blocked((LivingEntity)(Object)this,MinecraftMovementInput.JUMP))ci.cancel();}
}
