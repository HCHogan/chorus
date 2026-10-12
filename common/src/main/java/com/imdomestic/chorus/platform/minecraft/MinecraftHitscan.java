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
    public static Hitscan.Result trace(ServerLevel level, Hitscan.Query q) { return traceAll(level,q,1).getFirst(); }
    public static Hitscan.PathResult trace(ServerLevel level, Hitscan.PiercingQuery q) {
        var observed=traceAll(level,q.ray(),(long)q.pierces()+1);var contacts=new ArrayList<Hitscan.PathContact>();
        for(int i=0;i<observed.size();i++)contacts.add(new Hitscan.PathContact(observed.get(i),i+1L,i==observed.size()-1));
        return new Hitscan.PathResult(q,contacts);
    }
    public static RicochetHitscan.Result trace(ServerLevel level,RicochetHitscan.Query request) {
        var segment=request.ray();var contacts=new ArrayList<RicochetHitscan.Contact>();var seen=new HashMap<String,Long>();
        long entities=0,bounces=0;double prior=0;
        while(true) {
            var excluded=new HashSet<String>();seen.forEach((id,count)->{if(count>=request.hitsPerTarget())excluded.add(id);});
            var observed=observe(level,segment,1L+request.pierces()-entities,excluded,request.hitOriginAfterBounce() && bounces>0);
            for(var raw:observed) {
                boolean terminal=true;
                if(raw.outcome()==Hitscan.Outcome.ENTITY) { entities++;seen.merge(raw.impact().orElseThrow().target().orElseThrow(),1L,Long::sum);terminal=entities==1L+request.pierces(); }
                else if(raw.outcome()==Hitscan.Outcome.BLOCK && bounces<request.bounces() && RicochetHitscan.canReflect(segment,raw.impact().orElseThrow(),raw.embedded())) { bounces++;terminal=false; }
                var contact=new RicochetHitscan.Contact(segment,raw.outcome(),raw.impact(),contacts.size()+1L,bounces,entities,terminal,prior,raw.embedded(),raw.impact().flatMap(ProjectileFlight.Impact::target).map(seen::get).orElse(0L));contacts.add(contact);
                if(terminal)return new RicochetHitscan.Result(request,contacts);
                if(raw.outcome()==Hitscan.Outcome.BLOCK) { prior+=contact.segmentDistance();segment=RicochetHitscan.reflected(segment,raw.impact().orElseThrow());break; }
            }
        }
    }
    private record Contact(LivingEntity entity,Vec3 point,double distance) {}
    private record Observed(Hitscan.Outcome outcome,Optional<ProjectileFlight.Impact> impact,boolean embedded) {}
    private static List<Hitscan.Result> traceAll(ServerLevel level,Hitscan.Query q,long maximumEntities) {
        return observe(level,q,maximumEntities,Set.of(),false).stream().map(r->new Hitscan.Result(q,r.outcome(),r.impact())).toList();
    }
    private static List<Observed> observe(ServerLevel level,Hitscan.Query q,long maximumEntities,Set<String> excluded,boolean hitOrigin) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Hitscan requires server thread");
        if (q.member().isPresent() && !ShotGroups.active(MinecraftEffectRuntime.installed(level).orElseThrow().state().engine().domain(), q.member().orElseThrow()))
            return List.of(missing(Hitscan.Outcome.EXPIRED_SHOT));
        if (q.position().isEmpty()) return List.of(missing(Hitscan.Outcome.MISSING_POSITION));
        if (q.direction().isEmpty()) return List.of(missing(Hitscan.Outcome.MISSING_DIRECTION));
        var start = q.position().orElseThrow(); var direction = q.direction().orElseThrow();
        if (!start.dimension().equals(level.dimension().identifier().toString()) || !start.dimension().equals(direction.dimension())) return List.of(missing(Hitscan.Outcome.WRONG_DIMENSION));
        var from = new Vec3(start.x(),start.y(),start.z()); var to = from.add(direction.x()*q.range(),direction.y()*q.range(),direction.z()*q.range());
        var path = MinecraftVisibility.ray(level,start,point(level,to));
        if (path.isEmpty()) return List.of(observed(q,Hitscan.Outcome.UNLOADED,from,null,null,level));
        var terrain = path.orElseThrow(); var end = terrain.hit().getLocation();
        var candidates = new ArrayList<Contact>(); double boundary = from.distanceToSqr(end);
        var bounds = new AABB(from,end).inflate(q.inflation()+1e-7);
        for (var target : level.getEntitiesOfClass(LivingEntity.class,bounds,e -> e.isAlive() && !e.isRemoved() && !e.isSpectator()
                && !excluded.contains(e.getUUID().toString())
                && (hitOrigin || !e.getUUID().toString().equals(q.owner()) && !e.getUUID().toString().equals(q.emitter())))) {
            var box = target.getBoundingBox().inflate(q.inflation());
            var contact = MinecraftVisibility.contact(box,from,end,BlockPos.containing(from));
            if (contact == null) continue;
            double distance = from.distanceToSqr(contact.getLocation());
            boolean endpoint = terrain.hit().getType() == HitResult.Type.MISS && !terrain.unavailable();
            if (distance < boundary || distance == boundary && endpoint) candidates.add(new Contact(target,contact.getLocation(),distance));
        }
        candidates.sort(Comparator.comparingDouble(Contact::distance).thenComparing(c->c.entity().getUUID().toString()));
        var results=new ArrayList<Observed>();
        for(var c:candidates) {
            results.add(observed(q,Hitscan.Outcome.ENTITY,c.point(),c.entity(),null,level));
            if(results.size()==maximumEntities)return List.copyOf(results);
        }
        results.add(observed(q,terrain.unavailable() ? Hitscan.Outcome.UNLOADED : terrain.hit().getType() == HitResult.Type.BLOCK ? Hitscan.Outcome.BLOCK : Hitscan.Outcome.RANGE,end,null,terrain.hit(),level));
        return List.copyOf(results);
    }

    private static Observed missing(Hitscan.Outcome outcome) { return new Observed(outcome,Optional.empty(),false); }
    private static WorldPosition point(ServerLevel level,Vec3 p) { return new WorldPosition(level.dimension().identifier().toString(),p.x,p.y,p.z); }
    private static Observed observed(Hitscan.Query q,Hitscan.Outcome outcome,Vec3 p,LivingEntity target,BlockHitResult block,ServerLevel level) {
        var end = switch(outcome) { case ENTITY -> ProjectileFlight.End.ENTITY; case BLOCK -> ProjectileFlight.End.BLOCK; case RANGE -> ProjectileFlight.End.EXPIRED; case UNLOADED -> ProjectileFlight.End.UNLOADED; default -> throw new AssertionError(); };
        var face = outcome == Hitscan.Outcome.BLOCK ? block.getDirection() : null;
        var contact = new ProjectileFlight.Impact(end,point(level,p),Optional.ofNullable(target).map(e -> e.getUUID().toString()),
                face == null ? 0 : face.getStepX(),face == null ? 0 : face.getStepY(),face == null ? 0 : face.getStepZ(),0).withMember(q.member());
        if (target != null) {
            var box = target.getBoundingBox();
            contact = contact.withGeometry(new ProjectileFlight.Geometry(new WorldPosition(level.dimension().identifier().toString(),box.minX,box.minY,box.minZ),box.getXsize(),box.getYsize(),box.getZsize(),q.inflation()));
        }
        return new Observed(outcome,Optional.of(contact),outcome==Hitscan.Outcome.BLOCK && block.isInside());
    }
}
