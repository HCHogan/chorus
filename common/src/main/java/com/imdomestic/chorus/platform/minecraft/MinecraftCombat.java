package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffObservation;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.target.EntityAttachments;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.CalculationProfile;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;

/** One native callback boundary, with immutable shadows and one joint publication for every participating domain. */
public final class MinecraftCombat {
    private static final ThreadLocal<MinecraftCombat> ACTIVE = new ThreadLocal<>();
    private final MinecraftResourceRuntime host;
    private ResourceSessionAuthority.CombatBoundary ticket;
    private ResourceAuthority.Worlds shadow;
    private World world;
    private record World(MinecraftEffectRuntime runtime, RuleEngine.WorldRequest request) {}
    private record Identity(String domain, String id) {}
    private record LifeKey(boolean target, String domain, String holder) {}
    private record Known(CombatDomains.Claim claim, DamageCapture.Observed observation, boolean deferred, String completionDomain) {}
    private record Actual(DamageCapture.Observed observation, boolean managed) {}
    private final Map<Identity, CombatDomains.Claim> claims = new LinkedHashMap<>();
    private final Deque<Identity> stack = new ArrayDeque<>();
    private final Map<Identity, Known> known = new LinkedHashMap<>();
    private final Map<Identity, Actual> actuals = new LinkedHashMap<>();
    private final Map<Identity, ResourceSessionAuthority.NativeShot> shotBindings = new LinkedHashMap<>();
    private final Map<Identity, Map<String, List<RuleEngine.Signal>>> shotFacts = new LinkedHashMap<>();
    private final Set<Identity> emittedDeferred = new HashSet<>();
    private final Map<String, List<RuleEngine.Signal>> facts = new TreeMap<>();
    private final Map<String, List<ShieldDamage.Write>> shields = new TreeMap<>();
    private final Map<LifeKey, Life> lives = new LinkedHashMap<>();
    private Optional<Failure> failure = Optional.empty();

    /** Diagnostic evidence only. An unknown callback never becomes a successful checkpoint or replay permission. */
    public record Failure(String message, ResourceAuthority.Worlds before, ResourceAuthority.Worlds observed,
            List<CombatDomains.Claim> pending, Map<String, List<DamageCapture.Observed>> completed,
            Map<String, List<RuleEngine.Signal>> facts, boolean published, List<ShotDamageAccounting.Settled> shots) {
        public Failure {
            Objects.requireNonNull(message); Objects.requireNonNull(before); Objects.requireNonNull(observed);
            pending = List.copyOf(pending); completed = immutableLists(completed); facts = immutableLists(facts); shots=List.copyOf(shots);
        }
        public Failure(String message, ResourceAuthority.Worlds before, ResourceAuthority.Worlds observed,
                List<CombatDomains.Claim> pending, Map<String, List<DamageCapture.Observed>> completed,
                Map<String, List<RuleEngine.Signal>> facts, boolean published) {
            this(message,before,observed,pending,completed,facts,published,List.of());
        }
    }
    private static <T> Map<String, List<T>> immutableLists(Map<String, List<T>> values) {
        var copied = new TreeMap<String, List<T>>(); values.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copied);
    }
    MinecraftCombat(MinecraftResourceRuntime host) { this.host = host; }
    Optional<Failure> failure() { return failure; }
    boolean active() { return ticket != null; }
    boolean worldActive() { return world != null; }
    static void uncoordinatedEntry(){
        var active=ACTIVE.get();
        if(active!=null){
            var error=new IllegalStateException("Nested native work belongs to an uncoordinated runtime");
            active.ticket.violated(error);throw error;
        }
    }
    void changing() {
        var active = ACTIVE.get();
        if (active != null) {
            var error = new IllegalStateException("Native combat has frozen world/runtime mutations");
            active.ticket.violated(error); throw error;
        }
    }
    void entering() {
        var active = ACTIVE.get();
        if (active != null && active != this) {
            var error = new IllegalStateException("Nested native combat belongs to a different resource host");
            active.ticket.violated(error); throw error;
        }
        if (failure.isPresent()) throw new IllegalStateException("Native combat requires explicit recovery");
    }
    void enterWorld(MinecraftEffectRuntime runtime, RuleEngine.WorldRequest request) {
        entering();
        if (world != null || active()) throw new IllegalStateException("Reentrant native world callback");
        world = new World(runtime, request);
    }
    void leaveWorld() { world = null; }
    private static String domain(MinecraftEffectRuntime runtime) { return runtime.level().dimension().identifier().toString(); }
    private void capture(MinecraftEffectRuntime physical) {
        entering();
        if (ticket != null) return;
        if(DamageCapture.nestedOutside(host))throw new IllegalStateException("Native combat cannot join another host's damage boundary");
        String caller = world == null ? domain(physical) : domain(world.runtime());
        ticket = host.combatAuthority().beginCombat(caller, world == null ? Optional.empty() : Optional.of(world.request()));
        shadow = ticket.worlds(); ACTIVE.set(this);
    }
    ResourceReadView view(MinecraftEffectRuntime runtime) {
        if (ticket == null) throw new IllegalStateException("No active native combat snapshot");
        return ticket.view(shadow, domain(runtime));
    }
    private void current() {
        if (ticket == null || ACTIVE.get() != this) throw new IllegalStateException("Native combat lost its host boundary");
        host.combatAuthority().validateCombat(ticket);
        lives.values().forEach(Life::current);
    }
    private record Life(ServerLevel level, UUID id, Entity body, String life) {
        static Life capture(ServerLevel level, UUID id, Entity body) {
            if (body != null && (body.level() != level || !body.getUUID().equals(id)))
                throw new IllegalArgumentException("Combat entity is outside its physical binding");
            return new Life(level, id, body, body instanceof LivingEntity living ? MinecraftEntityLife.get(living).id() : "");
        }
        void current() {
            var indexed = level.getEntity(id);
            if (body == null) {
                if (indexed != null) throw new IllegalStateException("Previously absent combat owner acquired a body");
            } else if (body.level() != level || !id.equals(body.getUUID()) || body instanceof LivingEntity living && !life.equals(MinecraftEntityLife.get(living).id())
                    || body.getRemovalReason()==Entity.RemovalReason.CHANGED_DIMENSION
                    || indexed != body && !(indexed == null && body.isRemoved())) {
                throw new IllegalStateException("Combat entity life or physical binding changed: " + id + " in " + level.dimension().identifier()
                        + ", indexed=" + (indexed==body?"same":indexed==null?"absent":"replacement") + ", removed=" + body.getRemovalReason());
            }
        }
    }
    private void pin(LifeKey key, Life value) {
        var old = lives.putIfAbsent(key, value);
        if (old != null && !old.equals(value)) throw new IllegalStateException("Combat participant changed within its boundary");
        value.current();
    }
    private void pin(MinecraftEffectRuntime runtime, DamageCommand command, LivingEntity target) {
        String physical = domain(runtime);
        var targetLease = ticket.placement().characters().get(command.target());
        if (targetLease != null && !targetLease.domain().equals(physical))
            throw new IllegalStateException("Native target differs from its character placement");
        pin(new LifeKey(true,physical,command.target()), Life.capture(runtime.level(), target.getUUID(), target));
        String holder = command.source().owner();
        UUID id;
        try { id = UUID.fromString(holder); } catch (IllegalArgumentException ignored) { return; }
        String ownerDomain = view(runtime).characterDomain(holder);
        var ownerKey=new LifeKey(false,ownerDomain,holder);
        var previous=lives.get(ownerKey);
        if(previous!=null){previous.current();return;}
        var level = host.combatRuntime(ownerDomain).level();
        var indexed = level.getEntity(id);
        pin(ownerKey, Life.capture(level, id, indexed));
    }
    DamageCommand begin(MinecraftEffectRuntime runtime, String id, DamageCommand command, LivingEntity target, boolean revision,
            Optional<RuleEngine.WorldRequest> managedRequest) {
        capture(runtime); current();
        var key = new Identity(domain(runtime), id);
        if (revision ? !key.equals(stack.peek()) : claims.containsKey(key) || known.containsKey(key))
            throw new IllegalStateException("Native combat reservation identity is not current");
        if (!revision && world != null && managedRequest.orElse(null) == world.request()) {
            host.combatAuthority().bindCombatShot(ticket, world.request(), key.domain(), id, command)
                    .ifPresent(binding -> shotBindings.put(key,binding));
        }
        pin(runtime, command, target);
        var raw = CombatDomains.capture(shadow, ticket.placement(), key.domain(), id, command);
        var other = claims.entrySet().stream().filter(entry -> !entry.getKey().equals(key)).map(Map.Entry::getValue).toList();
        var overlay = CombatDomains.view(shadow, ticket.placement(), raw, other);
        var attack = ticket.view(overlay, key.domain());
        var prepared = runtime.program().prepareDamage(attack.state(), command, Optional.of(attack));
        var claim = CombatDomains.capture(shadow, ticket.placement(), key.domain(), id, prepared);
        current(); claims.put(key, claim); if (!revision) stack.push(key);
        return prepared;
    }
    private CombatDomains.Claim claim(MinecraftEffectRuntime runtime, DamageCommand command) {
        var key = stack.peek();
        if (key == null || !key.domain().equals(domain(runtime))) throw new IllegalStateException("Native combat query is outside its current hit");
        var claim = claims.get(key);
        if (claim == null || !claim.command().equals(command)) throw new IllegalStateException("Native combat query changed its prepared command");
        return claim;
    }
    private ResourceReadView attack(MinecraftEffectRuntime runtime, DamageCommand command) {
        var claim = claim(runtime, command);
        return ticket.view(CombatDomains.view(shadow, ticket.placement(), claim, List.copyOf(claims.values())), domain(runtime));
    }
    Optional<CalculationProfile.Result> outgoing(MinecraftEffectRuntime runtime, DamageCommand command, double amount) {
        current(); return runtime.program().outgoing(attack(runtime, command), command, amount);
    }
    Optional<CalculationProfile.Result> defense(MinecraftEffectRuntime runtime, DamageCommand command, double amount) {
        current(); claim(runtime, command); return runtime.program().defense(view(runtime), command, amount);
    }
    Optional<CalculationProfile.Result> health(MinecraftEffectRuntime runtime, DamageCommand command, double amount) {
        current(); claim(runtime, command); return runtime.program().health(view(runtime), command, amount);
    }
    ShieldDamage.Planned shields(MinecraftEffectRuntime runtime, DamageCommand command, double amount, DamageBasis basis) {
        current();
        var committed = view(runtime).buffView(command.target());
        var plan = runtime.program().shields(committed, attack(runtime, command), command, amount, basis);
        replace(committed.domain(), plan.commit().apply(committed.state()));
        shields.computeIfAbsent(committed.domain(), _ -> new ArrayList<>()).addAll(plan.commit().writes());
        return plan;
    }
    private void replace(String domain, EffectState state) {
        var domains = new TreeMap<>(shadow.domains()); domains.put(domain, state);
        shadow = new ResourceAuthority.Worlds(shadow.directory(), domains, shadow.timelines());
    }
    BuffObservation observeBuffs(MinecraftEffectRuntime runtime, DamageCommand command) {
        current(); var claim = claim(runtime, command);
        var holders = new TreeMap<String, List<BuffObservation.Entry>>();
        for (String holder : List.of(command.source().owner(), command.target())) if (!holder.isBlank())
            holders.put(holder, BuffObservation.capture(shadow.domain(claim.buff(holder).domain()).buffs(), holder).require(holder));
        return new BuffObservation(shadow.domain(domain(runtime)).buffs().timeMicros(), holders);
    }
    List<RuleEngine.Signal> finished(MinecraftEffectRuntime runtime, String id, DamageCommand command, DamageReceipt receipt, boolean managed) {
        current(); var claim = claim(runtime, command); var key = new Identity(domain(runtime), id);
        if (!key.equals(stack.peek())) throw new IllegalStateException("Native combat receipts completed out of order");
        var shotEmitted = accountShot(key,receipt);
        var result = CombatDomains.finish(shadow, ticket.placement(), claim, receipt); shadow = result.worlds();
        String completionDomain=managed&&world!=null?domain(world.runtime()):key.domain();
        var emitted=merged(result.factsByDomain(),shotEmitted);
        var local = emitted.getOrDefault(completionDomain, List.of());
        var observation = new DamageCapture.Observed(command, result.receipt().withConsumptionFacts(local));
        known.put(key, new Known(claim, observation, managed,completionDomain));
        if (!managed) add(key.domain(), damageFacts(key,observation));
        emitted.forEach((domain, signals) -> { if (!managed || !domain.equals(completionDomain)) add(domain, signals); });
        claims.remove(key); stack.pop(); return local;
    }
    private Map<String,List<RuleEngine.Signal>> accountShot(Identity key,DamageReceipt receipt) {
        var binding=shotBindings.get(key);if(binding==null)return Map.of();
        var applied=host.combatAuthority().settleCombatShot(ticket,binding,shadow,key.domain(),receipt);shadow=applied.worlds();
        if(!applied.facts().isEmpty())shotFacts.put(key,ShotFacts.route(ticket.placement(),applied.settled().pin().issuer(),applied.facts()));
        return shotFacts.getOrDefault(key,Map.of());
    }
    private List<RuleEngine.Signal> damageFacts(Identity key,DamageCapture.Observed observation) {
        var binding=shotBindings.get(key);
        return DamageFacts.from(observation.command(),observation.receipt(),binding==null?Map.of():binding.attribution());
    }
    private static Map<String,List<RuleEngine.Signal>> merged(Map<String,List<RuleEngine.Signal>> first,Map<String,List<RuleEngine.Signal>> second) {
        var result=new TreeMap<String,List<RuleEngine.Signal>>();
        first.forEach((domain,values)->result.put(domain,new ArrayList<>(values)));
        second.forEach((domain,values)->result.computeIfAbsent(domain,_->new ArrayList<>()).addAll(values));
        return immutableLists(result);
    }
    void abandoned(MinecraftEffectRuntime runtime, String id) {
        var key = new Identity(domain(runtime), id);
        if (key.equals(stack.peek())) stack.pop(); // Unknown claims remain reserved and appear in failure evidence.
    }
    void actual(MinecraftEffectRuntime runtime,DamageCommand command,DamageReceipt receipt,boolean managed){
        if(active())actuals.put(new Identity(domain(runtime),receipt.damageId()),new Actual(new DamageCapture.Observed(command,receipt),managed));
    }
    private void add(String domain, List<RuleEngine.Signal> emitted) {
        if (!emitted.isEmpty()) facts.computeIfAbsent(domain, _ -> new ArrayList<>()).addAll(emitted);
    }
    private void deferred(Optional<RuleEngine.ActionResult> actual) {
        for (var knownEntry : known.entrySet()) if (knownEntry.getValue().deferred()) {
            var entry=knownEntry.getValue();
            boolean actionCompletes = world != null && (world.request().command() instanceof DamageCommand
                    || world.request().command() instanceof com.imdomestic.chorus.effect.target.EntityAnchor.GuardedDamage)
                    && actual.filter(entry.observation().receipt()::equals).isPresent();
            if (!actionCompletes && emittedDeferred.add(knownEntry.getKey())) {
                add(entry.completionDomain(), damageFacts(knownEntry.getKey(),entry.observation()));
                entry.observation().receipt().consumptionFacts().ifPresent(value -> add(entry.completionDomain(), value));
            }
        }
    }
    private void lifecycle() {
        host.combatRuntimes().forEach((domain, runtime) -> {
            var emitted = runtime.takeNativeLifecycleFacts(); add(domain, emitted);
            var commit = EntityAttachments.commit(RuleEngine.Empty.INSTANCE, emitted);
            if (commit instanceof EntityAttachments.Commit changes) replace(domain, changes.apply(shadow.domain(domain)));
        });
    }
    RuleEngine.ActionResult completeWorld(MinecraftEffectRuntime runtime, RuleEngine.ActionResult actual, List<RuleEngine.Signal> additional) {
        if (world == null || world.runtime() != runtime) throw new IllegalStateException("Native combat lost its world callback");
        if (!active()) return actual;
        var value = actual instanceof RuleEngine.WorldReceipt envelope ? envelope.value() : actual;
        current();
        if (!stack.isEmpty() || !claims.isEmpty()) throw new IllegalStateException("World callback left unfinished native damage");
        add(domain(runtime), additional); deferred(Optional.of(value)); lifecycle();
        host.combatAuthority().commitCombat(ticket, shadow, Optional.of(value), immutableLists(facts));
        clear(); return actual;
    }
    void committed() {
        if (!active() || world != null) return;
        try {
            current();
            if (!stack.isEmpty() || !claims.isEmpty()) throw new IllegalStateException("Native boundary left unfinished damage");
            deferred(Optional.empty()); lifecycle();
            host.combatAuthority().commitCombat(ticket, shadow, Optional.empty(), immutableLists(facts));
        } catch (RuntimeException | Error error) { failed(error); throw error; }
        var before=ticket.worlds();var committed=shadow;var emitted=immutableLists(facts);var written=immutableLists(shields);var shotEvidence=ticket.shotEvidence();
        var observations=new TreeMap<String,List<DamageCapture.Observed>>();
        known.values().forEach(value->observations.computeIfAbsent(value.claim().domain(),_->new ArrayList<>()).add(value.observation()));
        clear();
        try{host.drain(Math.multiplyExact(Math.max(1, host.combatRuntimes().size()), 256));}
        catch(RuntimeException|Error error){
            if(failure.isEmpty()){
                var evidence=new Failure(error.getClass().getSimpleName()+": "+error.getMessage(),before,committed,List.of(),observations,emitted,true,shotEvidence);
                failure=Optional.of(evidence);host.failCombat(error,evidence,written);
            }
            throw error;
        }
    }
    void failed(Throwable error) {
        if (!active() || failure.isPresent()) return;
        actuals.forEach((key,actual)->{
            if(known.containsKey(key))return;
            var claim=claims.get(key);if(claim==null)return;
            var observation=actual.observation();String completionDomain=actual.managed()&&world!=null?domain(world.runtime()):key.domain();
            Map<String,List<RuleEngine.Signal>> shotEmitted=Map.of();
            try{
                shotEmitted=accountShot(key,observation.receipt());
                var result=CombatDomains.finish(shadow,ticket.placement(),claim,observation.receipt());shadow=result.worlds();
                var emitted=merged(result.factsByDomain(),shotEmitted);
                var settled=new DamageCapture.Observed(observation.command(),result.receipt().withConsumptionFacts(emitted.getOrDefault(completionDomain,List.of())));
                known.put(key,new Known(claim,settled,false,completionDomain));
                add(completionDomain,damageFacts(key,settled));
                emitted.forEach(this::add);claims.remove(key);
            }catch(RuntimeException|Error secondary){
                if(secondary!=error)error.addSuppressed(secondary);
                known.put(key,new Known(claim,observation,false,completionDomain));
                add(completionDomain,damageFacts(key,observation));
                observation.receipt().consumptionFacts().ifPresent(emitted->add(completionDomain,emitted));
                shotEmitted.forEach(this::add);
            }
        });
        var before = ticket.worlds();
        boolean published = false;
        try {
            deferred(Optional.empty()); lifecycle();
            host.combatAuthority().retainCombat(ticket, shadow, immutableLists(facts)); published = true;
        } catch (RuntimeException | Error secondary) { if (secondary != error) error.addSuppressed(secondary); }
        var observations = new TreeMap<String, List<DamageCapture.Observed>>();
        known.values().forEach(value -> observations.computeIfAbsent(value.claim().domain(), _ -> new ArrayList<>()).add(value.observation()));
        failure = Optional.of(new Failure(error.getClass().getSimpleName() + ": " + error.getMessage(), before, shadow,
                List.copyOf(claims.values()), observations, facts, published,ticket.shotEvidence()));
        if (!published) try { host.combatAuthority().abandonCombat(ticket); }
        catch (RuntimeException | Error secondary) { if (secondary != error) error.addSuppressed(secondary); }
        var evidence = failure.orElseThrow(); var committedShields = immutableLists(shields);
        clear(); host.failCombat(error, evidence, committedShields);
    }
    void failed(MinecraftEffectRuntime runtime,Throwable error,List<DamageCapture.Observed> observations){
        if(!active()||failure.isPresent())return;
        for(var observation:observations){
            var key=new Identity(domain(runtime),observation.receipt().damageId());
            actuals.putIfAbsent(key,new Actual(observation,false));
        }
        failed(error);
    }
    private void clear() {
        if (ACTIVE.get() == this) ACTIVE.remove();
        ticket = null; shadow = null; claims.clear(); stack.clear(); known.clear(); actuals.clear(); shotBindings.clear();shotFacts.clear();emittedDeferred.clear(); facts.clear(); shields.clear(); lives.clear();
    }
}
