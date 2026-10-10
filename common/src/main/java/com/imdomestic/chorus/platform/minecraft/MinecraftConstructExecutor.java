package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.object.WorldConstruct;
import static com.imdomestic.chorus.effect.object.WorldConstruct.Outcome.*;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;

final class MinecraftConstructExecutor {
    private MinecraftConstructExecutor() {}
    static WorldConstruct.Receipt spawn(ServerLevel level, WorldConstruct.Spawn command) {
        if (command.position().isEmpty()) return WorldConstruct.Receipt.rejected(command, MISSING_POSITION);
        var at = command.position().orElseThrow(); var p = command.parameters();
        if (!at.dimension().equals(level.dimension().identifier().toString())) return WorldConstruct.Receipt.rejected(command, WRONG_DIMENSION);
        // Explicit native representation/query limits, not gameplay constants or event-chain limits.
        if (p.width() > 16 || p.height() > 16 || (float) p.width() <= 0 || (float) p.height() <= 0
                || (float) p.health() <= 0 || !Float.isFinite((float) p.health())
                || Attributes.MAX_HEALTH.value().sanitizeValue(p.health()) != p.health())
            return WorldConstruct.Receipt.rejected(command, UNSUPPORTED_PARAMETERS);
        if (Math.abs(at.x()) > 3e7 || Math.abs(at.z()) > 3e7 || Math.abs(at.y()) > 2e7)
            return WorldConstruct.Receipt.rejected(command, OUT_OF_BOUNDS);
        var box = net.minecraft.world.entity.EntityDimensions.scalable((float) p.width(), (float) p.height()).makeBoundingBox(at.x(), at.y(), at.z());
        if (box.getXsize() <= 0 || box.getYsize() <= 0 || box.getZsize() <= 0) return WorldConstruct.Receipt.rejected(command, UNSUPPORTED_PARAMETERS);
        var query = box.inflate(1);
        for (int x = Mth.floor(query.minX) >> 4; x <= Mth.floor(query.maxX) >> 4; x++)
            for (int z = Mth.floor(query.minZ) >> 4; z <= Mth.floor(query.maxZ) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return WorldConstruct.Receipt.rejected(command, UNLOADED);
        if (!level.getWorldBorder().isWithinBounds(box)) return WorldConstruct.Receipt.rejected(command, OUT_OF_BOUNDS);
        var runtime = MinecraftEffectRuntime.installed(level).orElseThrow(() -> new IllegalStateException("Construct needs a rule runtime"));
        if (runtime.failure().isPresent() || !runtime.program().program().version().equals(command.version())) throw new IllegalStateException("Construct runtime mismatch");
        var entity = new EffectConstruct(com.imdomestic.chorus.registry.ChorusEntities.EFFECT_CONSTRUCT.get(), level);
        try {
            entity.initialize(command, runtime);
            if (!level.noCollision(entity, entity.getBoundingBox())) return reject(entity, command, OBSTRUCTED);
            if (!level.addFreshEntity(entity) || entity.isRemoved()) return reject(entity, command, REJECTED);
            return new WorldConstruct.Receipt(command, SPAWNED, Optional.of(entity.getUUID().toString()));
        } catch (RuntimeException error) { entity.discard(); throw error; }
    }
    private static WorldConstruct.Receipt reject(EffectConstruct entity, WorldConstruct.Spawn command, WorldConstruct.Outcome outcome) {
        entity.discard(); return WorldConstruct.Receipt.rejected(command, outcome);
    }
}
