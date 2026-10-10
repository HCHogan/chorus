package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.util.Iterator;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EnderDragon.class)
abstract class DragonMeleeAttackMixin {
    // Reject each target before its entire contact branch, including wing push and enchantment effects.
    @WrapOperation(method={"knockBack","hurt(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V"},at=@At(value="INVOKE",target="Ljava/util/Iterator;next()Ljava/lang/Object;"))
    private Object chorus$melee(Iterator<?> iterator,Operation<Object> original){
        var target=original.call(iterator);
        return target instanceof LivingEntity victim&&!MinecraftNativeActions.melee((EnderDragon)(Object)this,victim,"minecraft:dragon_contact")?null:target;
    }
}
