package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import com.imdomestic.chorus.platform.minecraft.MinecraftMotionConstraints;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LivingEntity.class)
abstract class LivingMovementInputMixin implements MinecraftMovementInput.Synced {
    @Unique private int chorus$movementMask;
    @Unique private Object chorus$movementOwner;
    @Unique private Vec3 chorus$movementAnchor=Vec3.ZERO;
    @Unique private int chorus$motionTeleportDepth;
    @Override public int chorus$movementRestrictions(){return chorus$movementMask;}
    @Override public Vec3 chorus$movementAnchor(){return chorus$movementAnchor==null?Vec3.ZERO:chorus$movementAnchor;}
    @Override public void chorus$movementRestrictions(Object owner,int value){
        var actor=(LivingEntity)(Object)this;int added=value&~chorus$movementMask;
        if((added&MinecraftMovementInput.MOTION)!=0&&actor.isPassenger())actor.stopRiding();
        Vec3 old=chorus$movementAnchor(),now=actor.position();
        var anchor=new Vec3((value&MinecraftMovementInput.HORIZONTAL)==0?0:(added&MinecraftMovementInput.HORIZONTAL)!=0?now.x:old.x,
                (value&MinecraftMovementInput.VERTICAL)==0?0:(added&MinecraftMovementInput.VERTICAL)!=0?now.y:old.y,
                (value&MinecraftMovementInput.HORIZONTAL)==0?0:(added&MinecraftMovementInput.HORIZONTAL)!=0?now.z:old.z);
        chorus$movementProjection(owner,value,anchor);
    }
    @Override public void chorus$movementProjection(Object owner,int value,Vec3 anchor){
        if(value!=0||chorus$movementOwner==owner){
            chorus$movementOwner=value==0?null:owner;
            boolean changed=chorus$movementMask!=value||!chorus$movementAnchor().equals(anchor);
            chorus$movementMask=value;chorus$movementAnchor=anchor;
            var actor=(LivingEntity)(Object)this;
            if((value&MinecraftMovementInput.MOTION)!=0){actor.setPos(MinecraftMotionConstraints.position(actor,actor.position()));actor.setDeltaMovement(actor.getDeltaMovement());}
            if(changed)com.imdomestic.chorus.network.MovementInputNetwork.changed(actor);
        }
    }
    @Override public boolean chorus$motionTeleporting(){return chorus$motionTeleportDepth>0;}
    @Override public void chorus$beginMotionTeleport(){chorus$motionTeleportDepth++;}
    @Override public void chorus$endMotionTeleport(){
        if(--chorus$motionTeleportDepth<0)throw new IllegalStateException("Unbalanced motion teleport");
        if(chorus$motionTeleportDepth==0){
            var actor=(LivingEntity)(Object)this;var pos=actor.position();
            chorus$movementAnchor=new Vec3((chorus$movementMask&MinecraftMovementInput.HORIZONTAL)!=0?pos.x:0,(chorus$movementMask&MinecraftMovementInput.VERTICAL)!=0?pos.y:0,(chorus$movementMask&MinecraftMovementInput.HORIZONTAL)!=0?pos.z:0);
            actor.setDeltaMovement(actor.getDeltaMovement());
        }
    }
    @ModifyExpressionValue(method="aiStep",at=@At(value="FIELD",target="Lnet/minecraft/world/entity/LivingEntity;jumping:Z",opcode=org.objectweb.asm.Opcodes.GETFIELD))
    private boolean chorus$jumpIntent(boolean original){return original&&!MinecraftMovementInput.blocked((LivingEntity)(Object)this,MinecraftMovementInput.JUMP);}
    @ModifyReturnValue(method="maxUpStep",at=@At("RETURN"))
    private float chorus$stepHeight(float original){return (MinecraftMotionConstraints.mask((LivingEntity)(Object)this)&MinecraftMovementInput.VERTICAL)!=0?0:original;}
}
