package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerMotionConstraintMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;
    @Shadow private int aboveGroundTickCount;
    @WrapMethod(method="handlePlayerPositionChange")
    private void chorus$position(double x,double y,double z,float yaw,float pitch,boolean onGround,boolean horizontalCollision,Operation<Void> original){
        MinecraftMovementInput.mask(player);var requested=new Vec3(x,y,z);var constrained=MinecraftMotionConstraints.position(player,requested);
        // Vanilla still validates free axes, collisions, speed and state. Never teleport directly to client coordinates.
        original.call(constrained.x,constrained.y,constrained.z,yaw,pitch,onGround,horizontalCollision);
        if(!constrained.equals(requested))player.connection.teleport(player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());
    }
    @WrapMethod(method="teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V")
    private void chorus$teleport(PositionMoveRotation destination,Set<Relative> relatives,Operation<Void> original){
        original.call(destination,relatives);
        // Send the anchor AFTER the vanilla teleport packet, which may contain relative coordinates.
        MinecraftMotionConstraints.syncTeleport(player);
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void chorus$floating(CallbackInfo ci){
        MinecraftMovementInput.mask(player);
        if((MinecraftMotionConstraints.mask(player)&MinecraftMovementInput.VERTICAL)!=0){clientIsFloating=false;aboveGroundTickCount=0;}
    }
}
