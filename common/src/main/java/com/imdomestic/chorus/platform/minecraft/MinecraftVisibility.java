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
        return trace(level, from, to, false);
    }
    /** Closed segment contacts, without vanilla's forward sample that classifies near-face hits as embedded. */
    public static Optional<BlockHitResult> projectile(ServerLevel level, WorldPosition from, WorldPosition to) {
        return trace(level, from, to, true);
    }
    private static Optional<BlockHitResult> trace(ServerLevel level, WorldPosition from, WorldPosition to, boolean projectile) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Visibility observation requires server thread");
        String dimension = level.dimension().identifier().toString();
        if (!from.dimension().equals(dimension) || !to.dimension().equals(dimension)) return Optional.empty();
        if (!loaded(level, from) || !loaded(level, to)) return Optional.empty();
        var blocks = new BlockGetter() {
            boolean missing;
            @Override public BlockState getBlockState(BlockPos pos) {
                var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) { missing = true; return Blocks.BEDROCK.defaultBlockState(); }
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
                var contact = contact(local.move(pos), c.getFrom(), c.getTo(), pos);
                if (contact != null && (best == null || c.getFrom().distanceToSqr(contact.getLocation()) < c.getFrom().distanceToSqr(best.getLocation()))) best = contact;
            }
            return best;
        }, c -> BlockHitResult.miss(c.getTo(), Direction.UP, BlockPos.containing(c.getTo()))) : blocks.clip(context);
        return blocks.missing ? Optional.empty() : Optional.of(hit);
    }
    private static BlockHitResult contact(AABB box, Vec3 from, Vec3 to, BlockPos pos) {
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
}
