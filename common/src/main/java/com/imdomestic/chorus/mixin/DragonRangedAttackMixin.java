package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftNativeActions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.*;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DragonStrafePlayerPhase.class)
abstract class DragonRangedAttackMixin extends AbstractDragonPhaseInstance {
    @Shadow private int fireballCharge;
    @Shadow private Path currentPath;
    @Shadow private LivingEntity attackTarget;
    protected DragonRangedAttackMixin(EnderDragon dragon){super(dragon);}
    @Inject(method="doServerTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;isSilent()Z"),cancellable=true)
    private void chorus$ranged(ServerLevel level,CallbackInfo ci){
        if(MinecraftNativeActions.ranged(dragon,attackTarget,"minecraft:dragon_fireball"))return;
        // Finish this attempted strafe like vanilla, without creating the fireball or its launch sound.
        fireballCharge=0;if(currentPath!=null)while(!currentPath.isDone())currentPath.advance();
        dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);ci.cancel();
    }
}
