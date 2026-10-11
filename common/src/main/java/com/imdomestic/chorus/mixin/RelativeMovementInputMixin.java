package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import com.imdomestic.chorus.platform.minecraft.MinecraftDirectionalMovement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** All native ground/fluid/flying travel input acceleration funnels through this method. */
@Mixin(Entity.class)
abstract class RelativeMovementInputMixin {
    @ModifyVariable(method="moveRelative",at=@At("HEAD"),argsOnly=true)
    private Vec3 chorus$input(Vec3 input){return (Object)this instanceof LivingEntity actor&&MinecraftMovementInput.blocked(actor,MinecraftMovementInput.MOVE)?Vec3.ZERO:input;}

    @WrapOperation(method="moveRelative", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 chorus$directionalAcceleration(Vec3 input, float speed, float yaw, Operation<Vec3> original) {
        Vec3 acceleration = original.call(input, speed, yaw);
        return (Object)this instanceof LivingEntity actor
                ? MinecraftDirectionalMovement.forwardGroundAcceleration(actor, input, acceleration) : acceleration;
    }
}
