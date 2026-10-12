package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftEntityRemoval;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Capture the actual first transition, even if later native callbacks throw or immediately re-enable the object. */
@Mixin(Entity.class)
abstract class EntityRemovalMixin {
    @WrapOperation(method = "setRemoved", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;removalReason:Lnet/minecraft/world/entity/Entity$RemovalReason;", opcode = Opcodes.PUTFIELD))
    private void chorus$removed(Entity entity, Entity.RemovalReason reason, Operation<Void> original) {
        var pending = MinecraftEntityRemoval.begin(entity, reason);
        original.call(entity, reason);
        pending.ifPresent(MinecraftEntityRemoval.Pending::committed);
    }
}
