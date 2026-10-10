package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Set;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityMotionConstraintMixin {
    @ModifyVariable(method="move",at=@At("HEAD"),argsOnly=true)
    private Vec3 chorus$move(Vec3 requested){
        var actor=(Entity)(Object)this;if(actor instanceof LivingEntity living)MinecraftMovementInput.mask(living);
        return MinecraftMotionConstraints.velocity(actor,requested);
    }
    @ModifyVariable(method="setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",at=@At("HEAD"),argsOnly=true)
    private Vec3 chorus$velocity(Vec3 requested){return MinecraftMotionConstraints.velocity((Entity)(Object)this,requested);}
    @WrapMethod(method="setPosRaw")
    private void chorus$position(double x,double y,double z,Operation<Void> original){
        var actor=(Entity)(Object)this;
        if(MinecraftMotionConstraints.mask(actor)==0){original.call(x,y,z);return;}
        var actual=MinecraftMotionConstraints.position(actor,new Vec3(x,y,z));original.call(actual.x,actual.y,actual.z);
    }
    @WrapMethod(method="teleportSetPosition(Lnet/minecraft/world/entity/PositionMoveRotation;Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V")
    private void chorus$teleportPosition(PositionMoveRotation current,PositionMoveRotation destination,Set<Relative> relative,Operation<Void> original){
        MinecraftMotionConstraints.teleport((Entity)(Object)this,()->original.call(current,destination,relative));
    }
    @WrapMethod(method="teleportTo(DDD)V")
    private void chorus$legacyTeleport(double x,double y,double z,Operation<Void> original){
        var actor=(Entity)(Object)this;MinecraftMotionConstraints.teleport(actor,()->original.call(x,y,z));MinecraftMotionConstraints.syncTeleport(actor);
    }
    @WrapMethod(method="teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;")
    private Entity chorus$teleport(TeleportTransition transition,Operation<Entity> original){
        var result=original.call(transition);if(result!=null)MinecraftMotionConstraints.syncTeleport(result);return result;
    }
    @Inject(method="startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",at=@At("HEAD"),cancellable=true)
    private void chorus$ride(Entity vehicle,boolean force,boolean sendEvent,CallbackInfoReturnable<Boolean> cir){
        if(MinecraftMotionConstraints.mask((Entity)(Object)this)!=0)cir.setReturnValue(false);
    }
}
