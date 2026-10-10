package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientPacketListener.class)
abstract class ClientMotionConstraintMixin {
    @WrapMethod(method="setValuesFromPositionPacket")
    private static boolean chorus$serverTeleport(PositionMoveRotation change,Set<Relative> relatives,Entity entity,boolean interpolate,Operation<Boolean> original){
        if(MinecraftMotionConstraints.mask(entity)==0)return original.call(change,relatives,entity,interpolate);
        var state=(MinecraftMovementInput.Synced)entity;state.chorus$beginMotionTeleport();
        // A constrained entity takes an authoritative teleport immediately, before accepting the next anchor snapshot.
        try{return original.call(change,relatives,entity,false);}finally{state.chorus$endMotionTeleport();}
    }
}
