package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.data.ProgramCatalogue;
import com.imdomestic.chorus.effect.data.ProgramModule;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.resource.ResourceSessionAuthority;
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
    // The default Java host/clock is part of restoration semantics, not implied by equal DSL content.
    private static final java.util.Map<MinecraftEffectRuntime, Identifier> DEFAULT_HOSTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<MinecraftServer,MinecraftResourceRuntime> RESOURCE_HOSTS=java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static final java.util.Map<MinecraftServer,java.util.Map<String,Identifier>> RESTORING=java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static MinecraftEffectRuntime catalogueHost(Identifier id, MinecraftEffectRuntime runtime) { DEFAULT_HOSTS.put(runtime, id); return runtime; }
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
        return catalogueHost(id, MinecraftEffectRuntime.install(level, program, EffectState.empty().withMode(mode).withRandom(new com.imdomestic.chorus.effect.random.RandomState(new java.security.SecureRandom().nextLong(), 0)),
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), world(level), MinecraftEffectRuntime::nativeSource));
    }
    /** Catalogue-backed coordinated host. Explicit ownership enrollment remains the caller's responsibility. */
    public static MinecraftEffectRuntime installCoordinated(ServerLevel level,Identifier id,EffectState.Mode mode){
        var server=level.getServer();if(!server.isSameThread())throw new IllegalStateException("Catalogue installation requires the server thread");
        var program=find(server,id).orElseThrow(()->new IllegalArgumentException("Unknown Chorus effect program: "+id));
        var host=RESOURCE_HOSTS.get(server);boolean created=host==null;
        if(created){host=new MinecraftResourceRuntime(server);RESOURCE_HOSTS.put(server,host);}
        try{return catalogueHost(id,host.install(level,program,EffectState.empty().withMode(mode).withRandom(new com.imdomestic.chorus.effect.random.RandomState(new java.security.SecureRandom().nextLong(),0)),
                new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),world(level),MinecraftEffectRuntime::nativeSource));}
        catch(RuntimeException failure){if(created)try{host.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public static Optional<MinecraftResourceRuntime> resourceRuntime(MinecraftServer server){return Optional.ofNullable(RESOURCE_HOSTS.get(server));}
    static void closed(MinecraftResourceRuntime host){
        synchronized(RESOURCE_HOSTS){var servers=RESOURCE_HOSTS.entrySet().stream().filter(e->e.getValue()==host).map(java.util.Map.Entry::getKey).toList();
            servers.forEach(server->{RESOURCE_HOSTS.remove(server);RESTORING.remove(server);});}
    }
    /** Core sessions are restored together before createLevels; native bindings follow constructor callbacks. */
    static void prepareResources(MinecraftServer server,CheckpointArchive archive){
        if(archive.resourceDirectory().isEmpty())return;
        if(RESOURCE_HOSTS.containsKey(server))throw new IllegalStateException("Catalogue resource host already exists");
        var dimensions=new java.util.TreeMap<String,MinecraftEffectRuntime.Checkpoint>();
        var effects=new java.util.TreeMap<String,EffectSession.Checkpoint>();var names=new java.util.TreeMap<String,Identifier>();
        var policies=new java.util.TreeMap<String,MinecraftResourceRuntime.RestorePolicy>();
        for(var entry:archive.dimensions())if(entry.effects().resourceRouting()){
            var id=Identifier.parse(entry.program());var program=find(server,id).orElseThrow(()->new IllegalArgumentException("Unknown saved catalogue: "+id));
            dimensions.put(entry.dimension(),nativeCheckpoint(entry));effects.put(entry.dimension(),entry.effects());names.put(entry.dimension(),id);
            policies.put(entry.dimension(),new MinecraftResourceRuntime.RestorePolicy(program,new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),EffectPrograms::world,MinecraftEffectRuntime::nativeSource));
        }
        // Native local-mode sessions cannot own routed partitions; old local archives still restore separately.
        var resources=new ResourceSessionAuthority.Checkpoint(archive.resourceDirectory().orElseThrow(),effects);
        var host=MinecraftResourceRuntime.prepareRestore(server,new MinecraftResourceRuntime.Checkpoint(resources,dimensions),policies);
        RESOURCE_HOSTS.put(server,host);RESTORING.put(server,java.util.Map.copyOf(names));
    }
    static MinecraftEffectRuntime restoreResourceLevel(ServerLevel level){
        var names=RESTORING.get(level.getServer());var id=names==null?null:names.get(level.dimension().identifier().toString());
        if(id==null)throw new IllegalStateException("Native dimension has no prepared catalogue resource session");
        return catalogueHost(id,RESOURCE_HOSTS.get(level.getServer()).restoreLevel(level));
    }
    static void finishResources(MinecraftServer server){
        if(RESTORING.containsKey(server)){RESOURCE_HOSTS.get(server).finishRestore();RESTORING.remove(server);}
    }
    private static MinecraftEffectRuntime.Checkpoint nativeCheckpoint(CheckpointArchive.Entry saved){
        return new MinecraftEffectRuntime.Checkpoint(saved.dimension(),saved.gameTime(),saved.effects(),saved.equipmentOwners(),saved.actorOwners(),saved.abilityOwners());
    }
    public static MinecraftEffectRuntime restore(ServerLevel level, Identifier id, MinecraftEffectRuntime.Checkpoint checkpoint) {
        var program = find(level.getServer(), id).orElseThrow(() -> new IllegalArgumentException("Unknown Chorus effect program: " + id));
        return catalogueHost(id, MinecraftEffectRuntime.restore(level, program, checkpoint, new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())),
                world(level), MinecraftEffectRuntime::nativeSource));
    }
    /** Capture a catalogue-backed runtime with the metadata required by the host archive. */
    public static com.imdomestic.chorus.effect.persistence.CheckpointArchive.Entry checkpoint(ServerLevel level, Identifier id) {
        var runtime = MinecraftEffectRuntime.installed(level).orElseThrow(() -> new IllegalStateException("No Chorus runtime in this dimension"));
        if (!id.equals(catalogueId(level, runtime))) throw new IllegalArgumentException("Runtime is not the named default catalogue host");
        var saved = runtime.checkpoint();
        return new com.imdomestic.chorus.effect.persistence.CheckpointArchive.Entry(id.toString(), saved.dimension(), saved.gameTime(), saved.effects(), saved.equipmentOwners(), saved.actorOwners(), saved.abilityOwners());
    }
    static Identifier catalogueId(ServerLevel level, MinecraftEffectRuntime runtime) {
        var id = DEFAULT_HOSTS.get(runtime);
        if (id == null) throw new IllegalArgumentException("Runtime is not a default catalogue host");
        var current = find(level.getServer(), id).orElseThrow(() -> new IllegalArgumentException("Unknown Chorus effect program: " + id));
        if (!runtime.program().program().equals(current.program())) throw new IllegalArgumentException("Runtime differs from the named catalogue entry");
        return id;
    }
    public static MinecraftEffectRuntime restore(ServerLevel level, com.imdomestic.chorus.effect.persistence.CheckpointArchive.Entry saved) {
        return restore(level, Identifier.parse(saved.program()), nativeCheckpoint(saved));
    }
    private static MinecraftWorldActions world(ServerLevel level) {
        return new MinecraftWorldActions(level, value -> living(level, value), command -> {
            var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(command.damageType())));
            var owner = living(level, command.source().owner());
            return new DamageSource(type, null, owner);
        }, (target, check) -> true, cue -> {
            throw new IllegalArgumentException("No cue binding installed for " + cue.cue());
        });
    }
    private static LivingEntity living(ServerLevel level, String id) {
        try { return level.getEntity(UUID.fromString(id)) instanceof LivingEntity entity ? entity : null; }
        catch (IllegalArgumentException invalidId) { return null; }
    }
}
