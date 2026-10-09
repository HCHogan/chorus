package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.DamageCapture;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
abstract class PlayerDamageMixin {
    @WrapMethod(method = "hurtServer")
    private boolean chorus$capture(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        return DamageCapture.hurt((Player) (Object) this, source, amount, 2, scaled -> original.call(level, source, scaled));
    }
    @WrapMethod(method = "isInvulnerableTo")
    private boolean chorus$immunity(ServerLevel level, DamageSource source, Operation<Boolean> original) {
        boolean immune = original.call(level, source);
        if (immune) DamageCapture.immune((Player) (Object) this, source);
        return immune;
    }
    @ModifyExpressionValue(method = "hurtServer", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;invulnerable:Z"))
    private boolean chorus$abilityImmunity(boolean immune, ServerLevel level, DamageSource source, float amount) {
        if (immune && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) DamageCapture.immune((Player) (Object) this, source);
        return immune;
    }
}
