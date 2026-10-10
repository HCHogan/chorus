package com.imdomestic.chorus.platform.minecraft;

import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

/** Anchors belong to the host projection, not to a second client-side rules interpreter. */
public final class MinecraftMotionConstraints {
    private MinecraftMotionConstraints() {}
    public static int mask(Entity actor){return actor instanceof LivingEntity&&actor instanceof MinecraftMovementInput.Synced state&&!state.chorus$motionTeleporting()?state.chorus$movementRestrictions()&MinecraftMovementInput.MOTION:0;}
    public static Vec3 velocity(Entity actor,Vec3 requested){
        int mask=mask(actor);return mask==0?requested:new Vec3((mask&MinecraftMovementInput.HORIZONTAL)!=0?0:requested.x,(mask&MinecraftMovementInput.VERTICAL)!=0?0:requested.y,(mask&MinecraftMovementInput.HORIZONTAL)!=0?0:requested.z);
    }
    public static Vec3 position(Entity actor,Vec3 requested){
        int mask=mask(actor);if(mask==0)return requested;var anchor=((MinecraftMovementInput.Synced)actor).chorus$movementAnchor();
        return new Vec3((mask&MinecraftMovementInput.HORIZONTAL)!=0?anchor.x:requested.x,(mask&MinecraftMovementInput.VERTICAL)!=0?anchor.y:requested.y,(mask&MinecraftMovementInput.HORIZONTAL)!=0?anchor.z:requested.z);
    }
    /** Explicit host teleports may relocate an anchored entity; ordinary setPos/move cannot. */
    public static void teleport(Entity actor,Runnable operation){
        if(!(actor instanceof MinecraftMovementInput.Synced state)){operation.run();return;}
        state.chorus$beginMotionTeleport();try{operation.run();}finally{state.chorus$endMotionTeleport();}
    }
    public static void syncTeleport(Entity actor){if((mask(actor)&MinecraftMovementInput.MOTION)!=0)com.imdomestic.chorus.network.MovementInputNetwork.changed((LivingEntity)actor);}
}
