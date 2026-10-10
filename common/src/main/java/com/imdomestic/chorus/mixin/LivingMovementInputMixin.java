package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LivingEntity.class)
abstract class LivingMovementInputMixin implements MinecraftMovementInput.Synced {
    @Unique private int chorus$movementMask;
    @Unique private Object chorus$movementOwner;
    @Override public int chorus$movementRestrictions(){return chorus$movementMask;}
    @Override public void chorus$movementRestrictions(Object owner,int value){
        if(value!=0||chorus$movementOwner==owner){
            chorus$movementOwner=value==0?null:owner;
            if(chorus$movementMask!=value){chorus$movementMask=value;com.imdomestic.chorus.network.MovementInputNetwork.changed((LivingEntity)(Object)this);}
        }
    }
    @ModifyExpressionValue(method="aiStep",at=@At(value="FIELD",target="Lnet/minecraft/world/entity/LivingEntity;jumping:Z",opcode=org.objectweb.asm.Opcodes.GETFIELD))
    private boolean chorus$jumpIntent(boolean original){return original&&!MinecraftMovementInput.blocked((LivingEntity)(Object)this,MinecraftMovementInput.JUMP);}
}
