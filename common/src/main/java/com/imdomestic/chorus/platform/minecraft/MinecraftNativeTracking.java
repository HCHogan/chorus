package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.core.codec.DataCodecs;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

/** Native homing qualification; steering, collision and damage remain owned by Minecraft. */
public final class MinecraftNativeTracking {
    private MinecraftNativeTracking() {}
    public interface OwnerListener { void chorus$trackingOwnerChanged(); }
    public enum Outcome { UNMANAGED, ALLOWED, RESTRICTED, FAILED }
    public enum OwnerKind { PLAYER, MOB, OTHER, UNAVAILABLE }
    public record Credit(Optional<UUID> owner, OwnerKind kind, Set<String> tags) {
        public static final Credit EMPTY = new Credit(Optional.empty(), OwnerKind.UNAVAILABLE, Set.of());
        public static final Codec<Credit> CODEC = RecordCodecBuilder.<Credit>create(i -> i.group(
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Credit::owner),
                DataCodecs.enumeration(OwnerKind.class).fieldOf("kind").forGetter(Credit::kind),
                Codec.STRING.listOf().xmap(Set::copyOf, v -> v.stream().sorted().toList()).fieldOf("tags").forGetter(Credit::tags)
        ).apply(i, Credit::new)).validate(c -> (c.owner().isPresent() || c.kind() == OwnerKind.UNAVAILABLE)
                && (c.kind() != OwnerKind.UNAVAILABLE || c.tags().isEmpty())
                ? DataResult.success(c) : DataResult.error(() -> "Tracking credit requires an owner for known classifications and no tags for unavailable owners"));
        public Credit { Objects.requireNonNull(owner); Objects.requireNonNull(kind); tags = Set.copyOf(tags); }
        public boolean matches(EntityReference<Entity> reference) {
            return owner.equals(reference == null ? Optional.empty() : Optional.of(reference.getUUID()));
        }
        public static Credit capture(EntityReference<Entity> reference, Level level) {
            if (reference == null) return EMPTY;
            Entity entity = EntityReference.getEntity(reference, level);
            if (entity == null) return new Credit(Optional.of(reference.getUUID()), OwnerKind.UNAVAILABLE, Set.of());
            var tags = new HashSet<>(entity.entityTags());
            entity.getType().builtInRegistryHolder().tags().forEach(t -> tags.add(t.location().toString()));
            OwnerKind kind = entity instanceof Player ? OwnerKind.PLAYER : entity instanceof Mob ? OwnerKind.MOB : OwnerKind.OTHER;
            if (kind == OwnerKind.PLAYER) tags.add("chorus:guardian");
            if (kind == OwnerKind.MOB) tags.add("chorus:combatant");
            return new Credit(Optional.of(reference.getUUID()), kind, tags);
        }
        public Credit reconcile(EntityReference<Entity> reference, Level level) {
            return matches(reference) && kind != OwnerKind.UNAVAILABLE ? this : capture(reference, level);
        }
    }
    public static Outcome authorize(Projectile projectile, Credit credit, LivingEntity target, boolean acquire, long ageTicks) {
        if (!(projectile.level() instanceof ServerLevel level)) return Outcome.UNMANAGED;
        var installed = MinecraftEffectRuntime.installed(level);
        if (installed.isEmpty() || !installed.get().program().hasActionGates(ActionGate.Kind.PROJECTILE_TRACKING)) return Outcome.UNMANAGED;
        var runtime = installed.get(); runtime.prepare();
        if (runtime.failure().isPresent()) return Outcome.FAILED;
        String owner = credit.owner().map(UUID::toString).orElse("");
        String projectileId = projectile.getUUID().toString();
        var input = new EffectEvent(owner, target.getUUID().toString(), new BuffInstance.Origin(owner, projectileId, "", "", credit.tags()),
                Set.of("chorus:projectile_tracking", "chorus:native_projectile_tracking"),
                Map.of("tracking_age", new Measure(ageTicks / 20.0, Unit.SECOND),
                        "tracking_distance", new Measure(projectile.position().distanceTo(target.getBoundingBox().getCenter()), Unit.METER)),
                Map.of("tracking_redirect", false), Map.of("projectile", projectileId, "emitter", "",
                        "tracking_owner_kind", credit.kind().name().toLowerCase(Locale.ROOT),
                        "native_projectile", BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString()));
        boolean allowed = runtime.authorizeProjectileTracking(input, target, acquire ? ActionGate.Phase.START : ActionGate.Phase.CONTINUE, List.of(owner));
        return runtime.failure().isPresent() ? Outcome.FAILED : allowed ? Outcome.ALLOWED : Outcome.RESTRICTED;
    }
}
