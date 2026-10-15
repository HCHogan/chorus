package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Explicit native host for a group of coordinated dimensions. Default catalogue startup remains separate. */
public final class MinecraftResourceRuntime implements AutoCloseable {
    private static final Set<MinecraftResourceRuntime> LIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final MinecraftServer server;
    private ResourceSessionAuthority authority=new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY,Map.of());
    private final Map<String,MinecraftEffectRuntime> runtimes=new TreeMap<>();
    private boolean closing,closed;
    private RuntimeException installationFailure;
    public MinecraftResourceRuntime(MinecraftServer server) {
        this.server=Objects.requireNonNull(server);thread();MinecraftCheckpointBoundary.changingRuntime(server);LIVE.add(this);
    }
    private void thread() { if(!server.isSameThread())throw new IllegalStateException("Native resource host belongs to the server thread"); }
    private void active() {
        thread();if(closed||closing||installationFailure!=null)throw new IllegalStateException("Native resource host is closed or failed",installationFailure);
    }
    private static String domain(ServerLevel level) { return level.dimension().identifier().toString(); }
    private void level(ServerLevel level) { active();if(level.getServer()!=server)throw new IllegalArgumentException("Resource runtime belongs to another server"); }
    public MinecraftEffectRuntime install(ServerLevel level,CompiledEffects program,EffectState initial,EffectClock clock,
            Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world,MinecraftEffectRuntime.NativeSource source) {
        level(level);MinecraftCheckpointBoundary.changingRuntime(server);String domain=domain(level);
        if(runtimes.containsKey(domain)||MinecraftEffectRuntime.installed(level).isPresent())throw new IllegalStateException("Dimension already has a rule runtime");
        var compiled=program.withResourceRouting();
        var runtime=MinecraftEffectRuntime.coordinated(level,compiled,initial,clock,world,source,this,Optional.empty());
        authority.enlist(Map.of(domain,new ResourceSessionAuthority.Binding(compiled,runtime.resourceSession())));
        runtimes.put(domain,runtime);
        try { MinecraftEffectRuntime.attachCoordinated(runtime);return runtime; }
        catch(RuntimeException failure){installationFailure=failure;throw failure;}
    }
    /** The holder must be registered before its first routed resource write; physical ownership is not inferred. */
    public ResourceAuthority.Partition claim(String holder,ServerLevel owner) {
        level(owner);runtime(owner);return authority.claim(holder,domain(owner));
    }
    /** Only resource accounts move. Entity, equipment, actor and ability configuration migration is separate. */
    public void transfer(String holder,ServerLevel destination) {
        level(destination);runtime(destination);authority.commit(authority.prepareTransfer(holder,domain(destination)));
    }
    public ResourceAuthority.Granted grant(ResourceState.Key account,double requested,double scaled) {
        active();return authority.grant(account,requested,scaled);
    }
    public ResourceAuthority.Worlds snapshot() { active();return authority.snapshot(); }
    public ResourceSessionAuthority.Drained drain(int boundaries) { active();return authority.drain(boundaries); }
    public MinecraftEffectRuntime runtime(ServerLevel level) {
        level(level);var runtime=runtimes.get(domain(level));if(runtime==null)throw new IllegalArgumentException("Dimension is not in this resource host");return runtime;
    }
    ResourceReadView view(MinecraftEffectRuntime runtime,ServerLevel level,RuleEngine.WorldRequest request) {
        if(runtime(level)!=runtime)throw new IllegalArgumentException("Foreign native resource runtime");
        return request==null?authority.readView(domain(level)):authority.readView(domain(level),request);
    }
    RuleEngine.ActionResult execute(MinecraftEffectRuntime runtime,ServerLevel level,RuleEngine.WorldRequest request) {
        if(runtime(level)!=runtime)throw new IllegalArgumentException("Foreign native resource runtime");return authority.execute(domain(level),request);
    }
    public record Checkpoint(ResourceSessionAuthority.Checkpoint resources,Map<String,MinecraftEffectRuntime.Checkpoint> dimensions) {
        public Checkpoint {
            Objects.requireNonNull(resources);dimensions=Collections.unmodifiableMap(new TreeMap<>(dimensions));
            if(!resources.domains().keySet().equals(dimensions.keySet()))throw new IllegalArgumentException("Native resource checkpoint domain set differs");
            dimensions.forEach((domain,saved)->{
                if(!domain.equals(saved.dimension())||!saved.effects().equals(resources.domains().get(domain)))throw new IllegalArgumentException("Native resource checkpoint state differs");
            });
        }
    }
    /** Pins both native metadata and all resource sessions. This does not save or restore Minecraft files. */
    public Checkpoint checkpoint() {
        active();runtimes.values().forEach(MinecraftEffectRuntime::checkpoint);
        var resources=authority.checkpoint();var dimensions=new TreeMap<String,MinecraftEffectRuntime.Checkpoint>();
        runtimes.forEach((domain,runtime)->dimensions.put(domain,runtime.settledCheckpoint()));
        return new Checkpoint(resources,dimensions);
    }
    public record RestoreBinding(ServerLevel level,CompiledEffects program,EffectClock clock,
            Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world,MinecraftEffectRuntime.NativeSource source) {
        public RestoreBinding { Objects.requireNonNull(level);Objects.requireNonNull(program);Objects.requireNonNull(clock);Objects.requireNonNull(world);Objects.requireNonNull(source); }
    }
    /** The corresponding physical world must already match every saved dimension and tick. No world writes are replayed. */
    public static MinecraftResourceRuntime restore(MinecraftServer server,Checkpoint saved,Map<String,RestoreBinding> policies) {
        policies=Map.copyOf(policies);
        if(!saved.dimensions().keySet().equals(policies.keySet()))throw new IllegalArgumentException("Native resource restore domain set differs");
        var host=new MinecraftResourceRuntime(server);
        try {
            var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();
            for(var entry:new TreeMap<>(policies).entrySet()){
                var policy=entry.getValue();host.level(policy.level());
                if(!entry.getKey().equals(domain(policy.level()))||MinecraftEffectRuntime.installed(policy.level()).isPresent())throw new IllegalArgumentException("Native restore dimension is unavailable");
                var compiled=policy.program().withResourceRouting();var checkpoint=saved.dimensions().get(entry.getKey());
                var runtime=MinecraftEffectRuntime.coordinated(policy.level(),compiled,checkpoint.effects().timeline().domain(),policy.clock(),policy.world(),policy.source(),host,Optional.of(checkpoint));
                bindings.put(entry.getKey(),new ResourceSessionAuthority.Binding(compiled,runtime.resourceSession()));host.runtimes.put(entry.getKey(),runtime);
            }
            host.authority=new ResourceSessionAuthority(saved.resources().directory(),bindings);
            host.runtimes.values().forEach(MinecraftEffectRuntime::attachCoordinated);return host;
        }catch(RuntimeException failure){host.installationFailure=failure;try{host.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    boolean closing() { return closing; }
    static void stop(MinecraftServer server) {
        for(var host:List.copyOf(LIVE))if(host.server==server)host.close();
    }
    @Override public void close() {
        thread();if(closed)return;
        if(closing)throw new IllegalStateException("Reentrant native resource close");
        runtimes.values().forEach(MinecraftEffectRuntime::requireResourceDetach);
        closing=true;RuntimeException failure=null;
        try {
            for(var runtime:runtimes.values())try{runtime.close();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }finally{closed=true;closing=false;LIVE.remove(this);}
        if(failure!=null)throw failure;
    }
}
