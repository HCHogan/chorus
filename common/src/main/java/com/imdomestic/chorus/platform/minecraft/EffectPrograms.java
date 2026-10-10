package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.data.ProgramCatalogue;
import com.imdomestic.chorus.effect.data.ProgramModule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Server-side reloadable catalogue. Existing runtimes retain their immutable compiled program. */
public final class EffectPrograms {
    private EffectPrograms() {}
    public static final ResourceKey<Registry<LoadedProgram>> KEY = ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("chorus", "effect_program"));
    /** Called before publication, after every module in the reload snapshot has been decoded. */
    public static void validate(RegistryAccess.Frozen registries) {
        registries.lookup(KEY).ifPresent(registry -> {
            var sources = new java.util.TreeMap<String, ProgramModule>();
            registry.listElements().forEach(holder -> sources.put(holder.key().identifier().toString(), holder.value().source()));
            var compiled = ProgramCatalogue.compile(sources);
            registry.listElements().forEach(holder -> holder.value().prepare(Optional.ofNullable(compiled.get(holder.key().identifier().toString()))));
        });
    }
    public static Optional<CompiledEffects> find(MinecraftServer server, Identifier id) {
        return server.reloadableRegistries().lookup().lookup(KEY).flatMap(registry -> registry.get(ResourceKey.create(KEY, id))).flatMap(holder -> holder.value().compiled());
    }
    public static List<Identifier> ids(MinecraftServer server) {
        return server.reloadableRegistries().lookup().lookup(KEY).map(registry -> registry.listElements().filter(holder -> holder.value().compiled().isPresent()).map(holder -> holder.key().identifier()).sorted().toList()).orElse(List.of());
    }
    public static MinecraftEffectRuntime install(ServerLevel level, Identifier id, EffectState.Mode mode) {
        var program = find(level.getServer(), id).orElseThrow(() -> new IllegalArgumentException("Unknown Chorus effect program: " + id));
        var world = new MinecraftWorldActions(level, value -> living(level, value), command -> {
            var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(command.damageType())));
            var owner = living(level, command.source().owner());
            return new DamageSource(type, null, owner);
        }, (target, check) -> true, cue -> {
            throw new IllegalArgumentException("No cue binding installed for " + cue.cue());
        });
        return MinecraftEffectRuntime.install(level, program, EffectState.empty().withMode(mode).withRandom(new com.imdomestic.chorus.effect.random.RandomState(new java.security.SecureRandom().nextLong(), 0)),
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world, MinecraftEffectRuntime::nativeSource);
    }
    private static LivingEntity living(ServerLevel level, String id) {
        try { return level.getEntity(UUID.fromString(id)) instanceof LivingEntity entity ? entity : null; }
        catch (IllegalArgumentException invalidId) { return null; }
    }
}
