package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftGroundContact;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
abstract class EntityGroundContactMixin {
    @WrapMethod(method = "move")
    private void chorus$landed(MoverType type, Vec3 requested, Operation<Void> original) {
        var pending = MinecraftGroundContact.begin((Entity)(Object)this, requested);
        original.call(type, requested);
        pending.ifPresent(MinecraftGroundContact.Move::moved);
    }
}
