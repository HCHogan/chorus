package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Include overrides before their animations, status application, cooldowns and other pre-super work. */
@Mixin(targets={"net.minecraft.world.entity.Mob","net.minecraft.world.entity.animal.bee.Bee",
        "net.minecraft.world.entity.animal.golem.IronGolem","net.minecraft.world.entity.animal.panda.Panda",
        "net.minecraft.world.entity.monster.Creeper","net.minecraft.world.entity.monster.Ravager",
        "net.minecraft.world.entity.monster.Zoglin","net.minecraft.world.entity.monster.creaking.Creaking",
        "net.minecraft.world.entity.monster.hoglin.Hoglin","net.minecraft.world.entity.monster.skeleton.WitherSkeleton",
        "net.minecraft.world.entity.monster.spider.CaveSpider","net.minecraft.world.entity.monster.warden.Warden",
        "net.minecraft.world.entity.monster.zombie.Husk","net.minecraft.world.entity.monster.zombie.Zombie"})
abstract class MobMeleeAttackMixin {
    @Inject(method="doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",at=@At("HEAD"),cancellable=true)
    private void chorus$melee(ServerLevel level,Entity victim,CallbackInfoReturnable<Boolean> cir){
        var actor=(Mob)(Object)this;
        // Vanilla Guardian uses this helper for the physical component of its ranged beam, not a melee attack.
        if(!(actor instanceof Guardian)&&!MinecraftNativeActions.melee(actor,victim,"minecraft:mob_melee"))cir.setReturnValue(false);
    }
}
