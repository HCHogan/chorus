package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.DamageCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge reads the container again after each reduction; changing the method argument alone has no effect. */
@Mixin({LivingEntity.class, Player.class})
abstract class ShieldContainerMixin {
    @WrapOperation(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/damagesource/DamageContainer;getNewDamage()F", ordinal = 0))
    private float chorus$shields(DamageContainer container, Operation<Float> original) {
        float incoming = original.call(container);
        float remaining = DamageCapture.shield((LivingEntity) (Object) this, incoming);
        if (remaining != incoming) container.setNewDamage(remaining);
        return remaining;
    }
}
