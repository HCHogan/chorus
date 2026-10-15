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
    private Map<String,Restoration> restoring;
    private record Restoration(MinecraftEffectRuntime.Checkpoint saved,RestorePolicy policy,CompiledEffects program,EffectSession session) {}
    private boolean closing,closed;
    private RuntimeException installationFailure;
    public MinecraftResourceRuntime(MinecraftServer server) {
        this.server=Objects.requireNonNull(server);thread();MinecraftCheckpointBoundary.changingRuntime(server);LIVE.add(this);
    }
    private void thread() { if(!server.isSameThread())throw new IllegalStateException("Native resource host belongs to the server thread"); }
    private void active() {
        thread();if(closed||closing||installationFailure!=null)throw new IllegalStateException("Native resource host is closed or failed",installationFailure);
    }
    private void ready(){active();if(restoring!=null)throw new IllegalStateException("Not every restored native dimension is connected");}
    void enteringRuntime(){thread();if(!closing)ready();}
    private void changing(){ready();MinecraftCheckpointBoundary.enteringResources(server);}
    private static String domain(ServerLevel level) { return level.dimension().identifier().toString(); }
    private void level(ServerLevel level) { active();if(level.getServer()!=server)throw new IllegalArgumentException("Resource runtime belongs to another server"); }
    public MinecraftEffectRuntime install(ServerLevel level,CompiledEffects program,EffectState initial,EffectClock clock,
            Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world,MinecraftEffectRuntime.NativeSource source) {
        changing();level(level);MinecraftCheckpointBoundary.changingRuntime(server);String domain=domain(level);
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
        changing();level(owner);runtime(owner);return authority.claim(holder,domain(owner));
    }
    /** Only resource accounts move. Entity, equipment, actor and ability configuration migration is separate. */
    public void transfer(String holder,ServerLevel destination) {
        changing();level(destination);runtime(destination);authority.commit(authority.prepareTransfer(holder,domain(destination)));
    }
    public ResourceAuthority.Granted grant(ResourceState.Key account,double requested,double scaled) {
        changing();return authority.grant(account,requested,scaled);
    }
    public ResourceAuthority.Worlds snapshot() { ready();return authority.snapshot(); }
    public ResourceSessionAuthority.Drained drain(int boundaries) { changing();return authority.drain(boundaries); }
    ResourceSessionAuthority.Checkpoint settledResources(){ready();return authority.checkpoint();}
    public MinecraftEffectRuntime runtime(ServerLevel level) {
        level(level);var runtime=runtimes.get(domain(level));if(runtime==null)throw new IllegalArgumentException("Dimension is not in this resource host");return runtime;
    }
    ResourceReadView view(MinecraftEffectRuntime runtime,ServerLevel level,RuleEngine.WorldRequest request) {
        if(runtime(level)!=runtime)throw new IllegalArgumentException("Foreign native resource runtime");
        return request==null?authority.readView(domain(level)):authority.readView(domain(level),request);
    }
    RuleEngine.ActionResult execute(MinecraftEffectRuntime runtime,ServerLevel level,RuleEngine.WorldRequest request) {
        changing();if(runtime(level)!=runtime)throw new IllegalArgumentException("Foreign native resource runtime");return authority.execute(domain(level),request);
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
        changing();runtimes.values().forEach(MinecraftEffectRuntime::checkpoint);
        var resources=authority.checkpoint();var dimensions=new TreeMap<String,MinecraftEffectRuntime.Checkpoint>();
        runtimes.forEach((domain,runtime)->dimensions.put(domain,runtime.settledCheckpoint()));
        return new Checkpoint(resources,dimensions);
    }
    public record RestoreBinding(ServerLevel level,CompiledEffects program,EffectClock clock,
            Function<RuleEngine.WorldRequest,RuleEngine.ActionResult> world,MinecraftEffectRuntime.NativeSource source) {
        public RestoreBinding { Objects.requireNonNull(level);Objects.requireNonNull(program);Objects.requireNonNull(clock);Objects.requireNonNull(world);Objects.requireNonNull(source); }
    }
    /** World executors are connected only when the corresponding native level has been constructed. */
    public record RestorePolicy(CompiledEffects program,EffectClock clock,
            Function<ServerLevel,Function<RuleEngine.WorldRequest,RuleEngine.ActionResult>> world,MinecraftEffectRuntime.NativeSource source){
        public RestorePolicy{Objects.requireNonNull(program);Objects.requireNonNull(clock);Objects.requireNonNull(world);Objects.requireNonNull(source);}
    }
    /** Restores every core session before the first native dimension or entity is loaded. */
    public static MinecraftResourceRuntime prepareRestore(MinecraftServer server,Checkpoint saved,Map<String,RestorePolicy> policies){
        policies=Map.copyOf(policies);
        if(!saved.dimensions().keySet().equals(policies.keySet()))throw new IllegalArgumentException("Native resource restore domain set differs");
        var host=new MinecraftResourceRuntime(server);
        try{
            var plans=new TreeMap<String,Restoration>();var bindings=new TreeMap<String,ResourceSessionAuthority.Binding>();
            for(var entry:new TreeMap<>(policies).entrySet()){
                var domain=entry.getKey();var policy=entry.getValue();var checkpoint=saved.dimensions().get(domain);var compiled=policy.program().withResourceRouting();
                var session=EffectSession.restore(compiled,policy.clock(),256,checkpoint.effects(),request->{
                    host.ready();var runtime=host.runtimes.get(domain);
                    if(runtime==null)throw new IllegalStateException("Native resource dimension is not connected: "+domain);return runtime.executeWorld(request);
                });
                plans.put(domain,new Restoration(checkpoint,policy,compiled,session));bindings.put(domain,new ResourceSessionAuthority.Binding(compiled,session));
            }
            host.authority=new ResourceSessionAuthority(saved.resources().directory(),bindings);host.restoring=Map.copyOf(plans);return host;
        }catch(RuntimeException failure){host.installationFailure=failure;try{host.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Safe before other levels exist: only immutable resource queries may run until finishRestore. */
    public MinecraftEffectRuntime restoreLevel(ServerLevel level){
        level(level);MinecraftCheckpointBoundary.changingRuntime(server);String domain=domain(level);
        if(restoring==null||!restoring.containsKey(domain))throw new IllegalArgumentException("Dimension is not awaiting native restoration");
        if(runtimes.containsKey(domain)||MinecraftEffectRuntime.installed(level).isPresent())throw new IllegalStateException("Restored dimension already has a runtime");
        try{
            var plan=restoring.get(domain);
            var runtime=MinecraftEffectRuntime.connectRestored(level,plan.program(),plan.saved(),plan.policy().clock(),plan.policy().world().apply(level),plan.policy().source(),this,plan.session());
            runtimes.put(domain,runtime);MinecraftEffectRuntime.attachCoordinated(runtime);return runtime;
        }catch(RuntimeException failure){installationFailure=failure;throw failure;}
    }
    public void finishRestore(){
        active();if(restoring==null||!runtimes.keySet().equals(restoring.keySet()))throw new IllegalStateException("Native resource restoration is incomplete");
        restoring=null;
    }
    /** The corresponding physical world must already match every saved dimension and tick. No world writes are replayed. */
    public static MinecraftResourceRuntime restore(MinecraftServer server,Checkpoint saved,Map<String,RestoreBinding> policies) {
        policies=Map.copyOf(policies);
        if(!saved.dimensions().keySet().equals(policies.keySet()))throw new IllegalArgumentException("Native resource restore domain set differs");
        var plans=new TreeMap<String,RestorePolicy>();
        policies.forEach((domain,policy)->plans.put(domain,new RestorePolicy(policy.program(),policy.clock(),level->{
            if(level!=policy.level())throw new IllegalArgumentException("Wrong restored native level");return policy.world();
        },policy.source())));
        var host=prepareRestore(server,saved,plans);
        try {
            for(var entry:new TreeMap<>(policies).entrySet()){
                var policy=entry.getValue();host.level(policy.level());
                if(!entry.getKey().equals(domain(policy.level()))||MinecraftEffectRuntime.installed(policy.level()).isPresent())throw new IllegalArgumentException("Native restore dimension is unavailable");
                host.restoreLevel(policy.level());
            }
            host.finishRestore();return host;
        }catch(RuntimeException failure){host.installationFailure=failure;try{host.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    boolean closing() { return closing; }
    static void stop(MinecraftServer server) {
        for(var host:List.copyOf(LIVE))if(host.server==server)host.close();
    }
    @Override public void close() {
        thread();if(closed)return;MinecraftCheckpointBoundary.enteringResources(server);
        if(closing)throw new IllegalStateException("Reentrant native resource close");
        runtimes.values().forEach(MinecraftEffectRuntime::requireResourceDetach);
        closing=true;RuntimeException failure=null;
        try {
            for(var runtime:runtimes.values())try{runtime.close();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }finally{closed=true;closing=false;LIVE.remove(this);EffectPrograms.closed(this);}
        if(failure!=null)throw failure;
    }
}
