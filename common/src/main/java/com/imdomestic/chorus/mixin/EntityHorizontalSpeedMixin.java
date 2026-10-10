package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftHorizontalSpeed;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityHorizontalSpeedMixin {
    @WrapMethod(method="move")
    private void chorus$move(MoverType type,Vec3 movement,Operation<Void> original){MinecraftHorizontalSpeed.move((Entity)(Object)this,movement,limited->original.call(type,limited));}
    @ModifyVariable(method="setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",at=@At("HEAD"),argsOnly=true)
    private Vec3 chorus$velocity(Vec3 requested){return MinecraftHorizontalSpeed.velocity((Entity)(Object)this,requested);}
    @WrapMethod(method="setPosRaw")
    private void chorus$position(double x,double y,double z,Operation<Void> original){MinecraftHorizontalSpeed.rawPosition((Entity)(Object)this,new Vec3(x,y,z),limited->original.call(limited.x,limited.y,limited.z));}
    @Inject(method="startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",at=@At("HEAD"),cancellable=true)
    private void chorus$ride(Entity vehicle,boolean force,boolean sendEvent,CallbackInfoReturnable<Boolean> cir){if(MinecraftHorizontalSpeed.speed((Entity)(Object)this).isPresent())cir.setReturnValue(false);}
}
