package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.target.WorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Collision-shape ray test without requesting or loading chunks. Unknown terrain is not visible. */
public final class MinecraftVisibility {
    private MinecraftVisibility() {}
    private static boolean loaded(ServerLevel level, WorldPosition point) {
        var block = BlockPos.containing(point.x(), point.y(), point.z());
        return level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4) != null;
    }
    public static boolean visible(ServerLevel level, WorldPosition from, WorldPosition to) {
        return trace(level, from, to).filter(hit -> hit.getType() == HitResult.Type.MISS).isPresent();
    }
    /** Empty means unknown terrain or another dimension, not an unobstructed path. */
    public static Optional<BlockHitResult> trace(ServerLevel level, WorldPosition from, WorldPosition to) {
        return path(level, from, to, false, false).filter(p -> !p.unavailable()).map(ProjectilePath::hit);
    }
    /** The available prefix ends at this hit; an unavailable endpoint is not a physical wall. */
    public record ProjectilePath(BlockHitResult hit, boolean unavailable) {}
    /** Closed segment contacts, without vanilla's forward sample that classifies near-face hits as embedded. */
    public static Optional<ProjectilePath> projectile(ServerLevel level, WorldPosition from, WorldPosition to) {
        return path(level, from, to, true, true);
    }
    public static Optional<ProjectilePath> ray(ServerLevel level, WorldPosition from, WorldPosition to) {
        return path(level, from, to, true, false);
    }
    private static Optional<ProjectilePath> path(ServerLevel level, WorldPosition from, WorldPosition to, boolean projectile, boolean simulated) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Visibility observation requires server thread");
        String dimension = level.dimension().identifier().toString();
        if (!from.dimension().equals(dimension) || !to.dimension().equals(dimension)) return Optional.empty();
        if (!loaded(level, from) || !projectile && !loaded(level, to)) return Optional.empty();
        if (simulated && !level.isPositionEntityTicking(BlockPos.containing(from.x(), from.y(), from.z()))) return Optional.empty();
        var blocks = new BlockGetter() {
            boolean missing;
            @Override public BlockState getBlockState(BlockPos pos) {
                var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null || simulated && !level.isPositionEntityTicking(pos)) { missing = true; return Blocks.BEDROCK.defaultBlockState(); }
                return chunk.getBlockState(pos);
            }
            @Override public FluidState getFluidState(BlockPos pos) {
                var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) { missing = true; return Fluids.EMPTY.defaultFluidState(); }
                return chunk.getFluidState(pos);
            }
            @Override public BlockEntity getBlockEntity(BlockPos pos) {
                var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) { missing = true; return null; }
                return chunk.getBlockEntity(pos);
            }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public int getMinY() { return level.getMinY(); }
        };
        var context = new ClipContext(new Vec3(from.x(), from.y(), from.z()), new Vec3(to.x(), to.y(), to.z()),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        var hit = projectile ? BlockGetter.traverseBlocks(context.getFrom(), context.getTo(), context, (c, pos) -> {
            var shape = c.getBlockShape(blocks.getBlockState(pos), blocks, pos); BlockHitResult best = null;
            for (var local : shape.toAabbs()) {
                var contact = blockContact(local.move(pos), c.getFrom(), c.getTo(), pos, () -> {
                    // A rounded grid-corner entry can select an internal seam. Read only already loaded
                    // neighbouring shapes, without changing the unavailable-prefix observation.
                    boolean prefixMissing=blocks.missing;
                    try {
                    var neighbours = new ArrayList<AABB>();
                    for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) {
                        var at=pos.offset(x,y,z);var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                        if(chunk==null || simulated && !level.isPositionEntityTicking(at))continue;
                        for(var other:c.getBlockShape(chunk.getBlockState(at),blocks,at).toAabbs())neighbours.add(other.move(at));
                    }
                    return neighbours;
                    } finally { blocks.missing=prefixMissing; }
                });
                if (contact != null && (best == null || c.getFrom().distanceToSqr(contact.getLocation()) < c.getFrom().distanceToSqr(best.getLocation()))) best = contact;
            }
            return best;
        }, c -> BlockHitResult.miss(c.getTo(), Direction.UP, BlockPos.containing(c.getTo()))) : blocks.clip(context);
        return Optional.of(new ProjectilePath(hit, blocks.missing));
    }
    static BlockHitResult contact(AABB box, Vec3 from, Vec3 to, BlockPos pos) {
        var delta = to.subtract(from);
        if (from.x > box.minX && from.x < box.maxX && from.y > box.minY && from.y < box.maxY && from.z > box.minZ && from.z < box.maxZ)
            return new BlockHitResult(from, Direction.getApproximateNearest(delta.x, delta.y, delta.z).getOpposite(), pos, true);
        double[] start = {from.x, from.y, from.z}, motion = {delta.x, delta.y, delta.z};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        Direction[] low = {Direction.WEST, Direction.DOWN, Direction.NORTH}, high = {Direction.EAST, Direction.UP, Direction.SOUTH};
        double enter = Double.NEGATIVE_INFINITY, leave = Double.POSITIVE_INFINITY; Direction face = null;
        for (int axis = 0; axis < 3; axis++) {
            if (motion[axis] == 0) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) return null; // Include seams between adjacent shape boxes.
                continue;
            }
            double near = ((motion[axis] > 0 ? min[axis] : max[axis]) - start[axis]) / motion[axis];
            double far = ((motion[axis] > 0 ? max[axis] : min[axis]) - start[axis]) / motion[axis];
            if (near > enter) { enter = near; face = motion[axis] > 0 ? low[axis] : high[axis]; }
            leave = Math.min(leave, far);
        }
        if (face == null || enter < 0 || enter > 1 || leave <= 0 || enter > leave) return null;
        return new BlockHitResult(from.add(delta.scale(enter)), face, pos, false);
    }
    /** Preserve the contact point; only resolve an ambiguous corner normal against the solid union. */
    static BlockHitResult blockContact(AABB box,Vec3 from,Vec3 to,BlockPos pos,Supplier<List<AABB>> neighbours) {
        var hit=contact(box,from,to,pos);if(hit==null || hit.isInside())return hit;
        double[] start={from.x,from.y,from.z},end={to.x,to.y,to.z},point={hit.getLocation().x,hit.getLocation().y,hit.getLocation().z};
        double[] min={box.minX,box.minY,box.minZ},max={box.maxX,box.maxY,box.maxZ};
        Direction[] low={Direction.WEST,Direction.DOWN,Direction.NORTH},high={Direction.EAST,Direction.UP,Direction.SOUTH};
        var candidates=new ArrayList<Direction>();
        for(int axis=0;axis<3;axis++) {
            double motion=end[axis]-start[axis];if(motion==0)continue;
            double plane=motion>0?min[axis]:max[axis];
            double error=2*Math.max(Math.ulp(plane),Math.max(Math.ulp(start[axis]),Math.ulp(end[axis])));
            if(Math.abs(point[axis]-plane)<=error)candidates.add(motion>0?low[axis]:high[axis]);
        }
        if(candidates.size()<2)return hit;
        var shapes=neighbours.get();
        if(!covered(hit.getDirection(),point,min,max,shapes))return hit;
        for(var face:candidates)if(!covered(face,point,min,max,shapes))return new BlockHitResult(hit.getLocation(),face,pos,false);
        return hit;
    }
    private static boolean covered(Direction face,double[] point,double[] min,double[] max,List<AABB> shapes) {
        int axis=switch(face.getAxis()){case X->0;case Y->1;case Z->2;};boolean negative=face.getAxisDirection()==Direction.AxisDirection.NEGATIVE;
        double plane=negative?min[axis]:max[axis];var probe=point.clone();probe[axis]=plane;
        // Sample the interior of this face, not the edge of a diagonally touching solid.
        for(int a=0;a<3;a++)if(a!=axis){double center=(min[a]+max[a])*.5;probe[a]=Math.nextAfter(Math.clamp(probe[a],min[a],max[a]),center);}
        for(var shape:shapes){double[] lo={shape.minX,shape.minY,shape.minZ},hi={shape.maxX,shape.maxY,shape.maxZ};
            if(negative?lo[axis]>=plane || hi[axis]<plane:hi[axis]<=plane || lo[axis]>plane)continue;
            boolean contains=true;for(int a=0;a<3;a++)if(a!=axis && (probe[a]<lo[a] || probe[a]>hi[a]))contains=false;
            if(contains)return true;
        }return false;
    }
}
