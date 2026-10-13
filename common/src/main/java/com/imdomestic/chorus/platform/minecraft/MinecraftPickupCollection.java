package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.object.PickupCollection;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

final class MinecraftPickupCollection {
    private MinecraftPickupCollection() {}
    static LivingEntity collector(ServerLevel level, String id) {
        try {
            return level.getEntity(UUID.fromString(id)) instanceof LivingEntity living && living.isAlive() && !living.isRemoved() && !living.isSpectator() ? living : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    static PickupCollection.Result select(ServerLevel level, PickupCollection.Query query) {
        var collector = collector(level, query.collector());
        if (collector == null) return new PickupCollection.Result(query, PickupCollection.Outcome.MISSING_COLLECTOR, List.of());
        var runtime = MinecraftEffectRuntime.installed(level).orElseThrow();
        var objects = new ArrayList<EffectObject>();
        for (var entity : level.getAllEntities()) if (entity instanceof EffectObject object && object.remoteCandidate(runtime, query, collector)) objects.add(object);
        objects.sort(Comparator.comparingDouble((EffectObject object) -> object.position().distanceTo(collector.position())).thenComparing(object -> object.getUUID().toString()));
        return new PickupCollection.Result(query, PickupCollection.Outcome.FOUND,
                objects.stream().map(object -> new PickupCollection.Entry(object.getUUID().toString(), object.pickup().orElseThrow())).toList());
    }
    static PickupCollection.Claimed claim(ServerLevel level, PickupCollection.Claim claim) {
        UUID id;
        try { id = UUID.fromString(claim.entry().entity()); }
        catch (IllegalArgumentException invalid) { return new PickupCollection.Claimed(claim, PickupCollection.ClaimOutcome.UNAVAILABLE, Optional.empty()); }
        if (level.getEntity(id) instanceof EffectObject object) return object.claim(claim);
        return new PickupCollection.Claimed(claim, PickupCollection.ClaimOutcome.UNAVAILABLE, Optional.empty());
    }
}
