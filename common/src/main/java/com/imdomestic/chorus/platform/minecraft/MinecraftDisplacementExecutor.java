package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.target.WorldPosition;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.*;

/** Bounded, loaded-only collision query followed by an explicit authoritative relocation and anchor update. */
final class MinecraftDisplacementExecutor {
    private MinecraftDisplacementExecutor() {}
    private static final double MAX_STEP=64,MAX_QUERY_CELLS=65_536;
    static Displacement.Receipt execute(ServerLevel level,LivingEntity target,Displacement.Command command){
        if(target==null||target.isRemoved()||target.level()!=level)return reject(command,Displacement.Outcome.MISSING_TARGET);
        if(!target.isAlive())return reject(command,Displacement.Outcome.DEAD);
        if(target.isSpectator())return reject(command,Displacement.Outcome.SPECTATOR);
        if(target.isPassenger())return reject(command,Displacement.Outcome.PASSENGER);
        if(target.isVehicle())return reject(command,Displacement.Outcome.VEHICLE);
        if(target.isSleeping())return reject(command,Displacement.Outcome.SLEEPING);
        if(target.noPhysics)return reject(command,Displacement.Outcome.NO_PHYSICS);
        if(command.direction().isEmpty())return reject(command,Displacement.Outcome.MISSING_DIRECTION);
        if(!command.direction().get().dimension().equals(level.dimension().identifier().toString()))return reject(command,Displacement.Outcome.WRONG_DIMENSION);
        var requested=command.requested().orElseThrow();if(requested.length()>MAX_STEP)return reject(command,Displacement.Outcome.QUERY_TOO_LARGE);
        var before=target.position();var delta=new Vec3(requested.x(),requested.y(),requested.z());var proposed=before.add(delta);
        if(!proposed.isFinite()||Math.abs(proposed.x)>3e7||Math.abs(proposed.y)>2e7||Math.abs(proposed.z)>3e7)return reject(command,Displacement.Outcome.OUT_OF_BOUNDS);
        var box=target.getBoundingBox();var query=box.expandTowards(delta).inflate(1);
        double cells=(Math.ceil(query.maxX)-Math.floor(query.minX)+1)*(Math.ceil(query.maxY)-Math.floor(query.minY)+1)*(Math.ceil(query.maxZ)-Math.floor(query.minZ)+1);
        if(!Double.isFinite(cells)||cells>MAX_QUERY_CELLS)return reject(command,Displacement.Outcome.QUERY_TOO_LARGE);
        for(int x=Mth.floor(query.minX)>>4;x<=Mth.floor(query.maxX)>>4;x++)for(int z=Mth.floor(query.minZ)>>4;z<=Mth.floor(query.maxZ)>>4;z++)
            if(level.getChunkSource().getChunkNow(x,z)==null)return reject(command,Displacement.Outcome.UNLOADED);
        if(!level.getWorldBorder().isWithinBounds(box))return reject(command,Displacement.Outcome.OUT_OF_BOUNDS);
        var colliders=new ArrayList<>(level.getEntityCollisions(target,query));colliders.add(level.getWorldBorder().getCollisionShape());
        // No step-up, noclip, unstuck teleport or ray-only clearance: use the recipient's full collision box.
        var resolved=Entity.collideBoundingBox(target,delta,box,level,colliders);var destination=before.add(resolved);
        if(!destination.equals(before))target.teleportTo(destination.x,destination.y,destination.z);
        var change=new Displacement.Change(point(level,before),point(target.level().dimension().identifier().toString(),target.position()),new Displacement.Offset(resolved.x,resolved.y,resolved.z));
        return new Displacement.Receipt(command,change.changed()?Displacement.Outcome.APPLIED:Displacement.Outcome.UNCHANGED,Optional.of(change));
    }
    private static Displacement.Receipt reject(Displacement.Command command,Displacement.Outcome outcome){return Displacement.Receipt.rejected(command,outcome);}
    private static WorldPosition point(ServerLevel level,Vec3 p){return point(level.dimension().identifier().toString(),p);}
    private static WorldPosition point(String dimension,Vec3 p){return new WorldPosition(dimension,p.x,p.y,p.z);}
}
