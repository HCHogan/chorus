package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** All native ground/fluid/flying travel input acceleration funnels through this method. */
@Mixin(Entity.class)
abstract class RelativeMovementInputMixin {
    @ModifyVariable(method="moveRelative",at=@At("HEAD"),argsOnly=true)
    private Vec3 chorus$input(Vec3 input){return (Object)this instanceof LivingEntity actor&&MinecraftMovementInput.blocked(actor,MinecraftMovementInput.MOVE)?Vec3.ZERO:input;}
}
