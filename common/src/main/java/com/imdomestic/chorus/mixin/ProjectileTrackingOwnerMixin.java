package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeTracking;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
abstract class ProjectileTrackingOwnerMixin {
    @Inject(method = "setOwner(Lnet/minecraft/world/entity/EntityReference;)V", at = @At("RETURN"))
    private void chorus$owner(EntityReference<Entity> owner, CallbackInfo ci) {
        if ((Object) this instanceof MinecraftNativeTracking.OwnerListener listener) listener.chorus$trackingOwnerChanged();
    }
}
