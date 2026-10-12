package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.WorldPosition;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;

/** Reads only loaded terrain/entities; a wall wins an equal-distance entity contact. */
public final class MinecraftHitscan {
    private MinecraftHitscan() {}
    public static Hitscan.Result trace(ServerLevel level, Hitscan.Query q) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Hitscan requires server thread");
        if (q.member().isPresent() && !ShotGroups.active(MinecraftEffectRuntime.installed(level).orElseThrow().state().engine().domain(), q.member().orElseThrow()))
            return missing(q,Hitscan.Outcome.EXPIRED_SHOT);
        if (q.position().isEmpty()) return missing(q,Hitscan.Outcome.MISSING_POSITION);
        if (q.direction().isEmpty()) return missing(q,Hitscan.Outcome.MISSING_DIRECTION);
        var start = q.position().orElseThrow(); var direction = q.direction().orElseThrow();
        if (!start.dimension().equals(level.dimension().identifier().toString()) || !start.dimension().equals(direction.dimension())) return missing(q,Hitscan.Outcome.WRONG_DIMENSION);
        var from = new Vec3(start.x(),start.y(),start.z()); var to = from.add(direction.x()*q.range(),direction.y()*q.range(),direction.z()*q.range());
        var path = MinecraftVisibility.ray(level,start,point(level,to));
        if (path.isEmpty()) return observed(q,Hitscan.Outcome.UNLOADED,from,null,null,level);
        var terrain = path.orElseThrow(); var end = terrain.hit().getLocation();
        LivingEntity selected = null; Vec3 hit = null; double nearest = from.distanceToSqr(end);
        var bounds = new AABB(from,end).inflate(q.inflation()+1e-7);
        for (var target : level.getEntitiesOfClass(LivingEntity.class,bounds,e -> e.isAlive() && !e.isRemoved() && !e.isSpectator()
                && !e.getUUID().toString().equals(q.owner()) && !e.getUUID().toString().equals(q.emitter()))) {
            var box = target.getBoundingBox().inflate(q.inflation());
            var contact = MinecraftVisibility.contact(box,from,end,BlockPos.containing(from));
            if (contact == null) continue;
            double distance = from.distanceToSqr(contact.getLocation());
            boolean endpoint = terrain.hit().getType() == HitResult.Type.MISS && !terrain.unavailable();
            if (distance < nearest || distance == nearest && (selected != null ? target.getUUID().toString().compareTo(selected.getUUID().toString()) < 0 : endpoint)) {
                selected = target; hit = contact.getLocation(); nearest = distance;
            }
        }
        if (selected != null) return observed(q,Hitscan.Outcome.ENTITY,hit,selected,null,level);
        return observed(q,terrain.unavailable() ? Hitscan.Outcome.UNLOADED : terrain.hit().getType() == HitResult.Type.BLOCK ? Hitscan.Outcome.BLOCK : Hitscan.Outcome.RANGE,end,null,terrain.hit(),level);
    }
    private static Hitscan.Result missing(Hitscan.Query q, Hitscan.Outcome outcome) { return new Hitscan.Result(q,outcome,Optional.empty()); }
    private static WorldPosition point(ServerLevel level,Vec3 p) { return new WorldPosition(level.dimension().identifier().toString(),p.x,p.y,p.z); }
    private static Hitscan.Result observed(Hitscan.Query q,Hitscan.Outcome outcome,Vec3 p,LivingEntity target,BlockHitResult block,ServerLevel level) {
        var end = switch(outcome) { case ENTITY -> ProjectileFlight.End.ENTITY; case BLOCK -> ProjectileFlight.End.BLOCK; case RANGE -> ProjectileFlight.End.EXPIRED; case UNLOADED -> ProjectileFlight.End.UNLOADED; default -> throw new AssertionError(); };
        var face = outcome == Hitscan.Outcome.BLOCK ? block.getDirection() : null;
        var contact = new ProjectileFlight.Impact(end,point(level,p),Optional.ofNullable(target).map(e -> e.getUUID().toString()),
                face == null ? 0 : face.getStepX(),face == null ? 0 : face.getStepY(),face == null ? 0 : face.getStepZ(),0).withMember(q.member());
        if (target != null) {
            var box = target.getBoundingBox();
            contact = contact.withGeometry(new ProjectileFlight.Geometry(new WorldPosition(level.dimension().identifier().toString(),box.minX,box.minY,box.minZ),box.getXsize(),box.getYsize(),box.getZsize(),q.inflation()));
        }
        return new Hitscan.Result(q,outcome,Optional.of(contact));
    }
}
