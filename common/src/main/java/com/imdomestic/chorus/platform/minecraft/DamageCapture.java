package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.DamageBasis;
import com.imdomestic.chorus.effect.combat.ShieldDamage;
import com.imdomestic.chorus.stat.CalculationProfile;
import com.imdomestic.chorus.stat.Numbers;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** World-side scratch space. Immutable observations cross into the pure engine only after the outer hurt returns. */
public final class DamageCapture {
    private DamageCapture() {}
    public record Observed(DamageCommand command, DamageReceipt receipt) {}
    public interface Observer {
        void prepare();
        DamageCommand describe(LivingEntity target, DamageSource source, float amount);
        default DamageCommand begin(String id, DamageCommand command) { return command; }
        default DamageCommand revise(String id, DamageCommand command) { return command; }
        default DamageCommand begin(String id, DamageCommand command, LivingEntity actualTarget, DamageSource actualSource) {
            return begin(id, command);
        }
        default DamageCommand revise(String id, DamageCommand command, LivingEntity actualTarget, DamageSource actualSource) {
            return revise(id, command);
        }
        default Optional<List<com.imdomestic.chorus.rule.RuleEngine.Signal>> finished(String id, DamageCommand command, DamageReceipt receipt, boolean managed) { return Optional.empty(); }
        /** An actually completed native call, also delivered before failure flush if post-hurt observation failed. */
        default void actual(DamageCommand command, DamageReceipt receipt, boolean managed) {}
        default void abandoned(String id) {}
        default void confirmedDeath(String deathId, LivingEntity target) {}
        default Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observeBuffs(DamageCommand command) { return Optional.empty(); }
        default Optional<com.imdomestic.chorus.effect.target.EntityObservation> observeEntities(DamageCommand command, LivingEntity target, DamageSource source) { return Optional.empty(); }
        Optional<CalculationProfile.Result> outgoing(DamageCommand command, double amount);
        Optional<CalculationProfile.Result> defense(DamageCommand command, double amount);
        default Optional<CalculationProfile.Result> health(DamageCommand command, double amount) { return Optional.empty(); }
        ShieldDamage.Planned shields(DamageCommand command, double amount, DamageBasis basis);
        void committed(List<Observed> observations);
        void failed(Throwable error, List<Observed> committedBeforeFailure);
    }
    private static final Map<ServerLevel, Observer> OBSERVERS = new ConcurrentHashMap<>();
    private static final String NATIVE_PREFIX = "native/" + UUID.randomUUID() + "/";
    private static final AtomicLong NATIVE_IDS = new AtomicLong();
    private static final ThreadLocal<ArrayDeque<Request>> REQUESTS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Call>> CALLS = ThreadLocal.withInitial(ArrayDeque::new);

    public static void install(ServerLevel level, Observer observer) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Observers belong to the server thread");
        if (OBSERVERS.putIfAbsent(level, Objects.requireNonNull(observer)) != null) throw new IllegalStateException("Level already has a damage observer");
    }
    public static void remove(ServerLevel level, Observer observer) { OBSERVERS.remove(level, observer); }
    public static void clear(MinecraftServer server) { OBSERVERS.keySet().removeIf(level -> level.getServer() == server); }
    private static Observer observer(LivingEntity entity) {
        return entity.level() instanceof ServerLevel level ? OBSERVERS.get(level) : null;
    }
    static boolean hasObserver(LivingEntity entity) { return observer(entity) != null; }
    /** An outer native call with no matching coordinator cannot share this host's atomic publication. */
    static boolean nestedOutside(MinecraftResourceRuntime host) {
        Objects.requireNonNull(host);
        return CALLS.get().stream().anyMatch(call -> !(call.observer instanceof MinecraftEffectRuntime runtime)
                || runtime.resourceHost() != host);
    }

    public static final class Request implements AutoCloseable {
        private final Call call;
        private final boolean managed;
        private final DamageCommand description;
        private boolean entered;
        private Request(String id, LivingEntity target, DamageSource source, boolean nonLethal, DamageCommand description) {
            this.call = new Call(id, target, source, nonLethal); this.description = description; this.managed = description != null; REQUESTS.get().push(this);
        }
        public DamageReceipt receipt(boolean accepted) { return call.receipt(accepted); }
        @Override public void close() {
            if (REQUESTS.get().peek() != this) throw new IllegalStateException("Damage request scopes closed out of order");
            REQUESTS.get().pop(); if (REQUESTS.get().isEmpty()) REQUESTS.remove();
        }
    }
    public static Request request(String id, LivingEntity target, DamageSource source, boolean nonLethal, DamageCommand description) {
        if (id.isBlank()) throw new IllegalArgumentException("Missing damage ID");
        return new Request(id, target, source, nonLethal, description);
    }
    private record Delivery(Observer observer, Observed fact, boolean publish) {}
    private static final class Boundary {
        final List<Delivery> committed = new ArrayList<>();
        final LinkedHashSet<Observer> observers = new LinkedHashSet<>();
        Throwable failure;
        void flush() {
            var deliveries = List.copyOf(committed); var recipients = List.copyOf(observers);
            committed.clear(); observers.clear();
            Throwable notificationFailure = null;
            for (var observer : recipients) {
                var facts = deliveries.stream().filter(delivery -> delivery.observer() == observer && (failure != null || delivery.publish()))
                        .map(Delivery::fact).toList();
                try {
                    if (failure != null) observer.failed(failure, facts);
                    else observer.committed(facts);
                } catch (RuntimeException | Error error) {
                    if (failure == null) failure = error;
                    if (notificationFailure == null) notificationFailure = error;
                    else suppress(notificationFailure, error);
                }
            }
            if (notificationFailure instanceof RuntimeException error) throw error;
            if (notificationFailure instanceof Error error) throw error;
        }
    }
    private static void suppress(Throwable original, Throwable secondary) {
        if (original != secondary) original.addSuppressed(secondary);
    }
    /** A rejected entrance owns no Scope, so it must release its reservation and outer boundary itself. */
    private static void rejected(Call call, boolean outermost, Throwable error) {
        if (call.boundary.failure == null) call.boundary.failure = error;
        try { if (call.observer != null) call.observer.abandoned(call.id); }
        catch (RuntimeException | Error abandoned) { suppress(error, abandoned); }
        if (outermost) {
            CALLS.remove();
            try { call.boundary.flush(); }
            catch (RuntimeException | Error notification) { suppress(error, notification); }
        }
    }
    private static final class Call {
        final String id;
        final LivingEntity target;
        DamageSource source;
        final boolean nonLethal;
        double healthLoss, absorptionLoss;
        boolean immune, blocked, dead;
        boolean shieldsProcessed, healthProcessed, defenseBlocked, healthBlocked;
        List<ShieldDamage.LayerHit> shields = List.of();
        Optional<CalculationProfile.Result> outgoing = Optional.empty(), defense = Optional.empty(), health = Optional.empty();
        Optional<String> protection = Optional.empty();
        int layer;
        Boundary boundary;
        Observer observer;
        DamageCommand command;
        boolean publish;
        boolean managedOrigin;
        Optional<List<com.imdomestic.chorus.rule.RuleEngine.Signal>> consumptionFacts = Optional.empty();
        Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observedBuffs = Optional.empty();
        Optional<com.imdomestic.chorus.effect.target.EntityObservation> observedEntities = Optional.empty();
        Call(String id, LivingEntity target, DamageSource source, boolean nonLethal) {
            this.id = id; this.target = target; this.source = source; this.nonLethal = nonLethal;
        }
        DamageReceipt receipt(boolean accepted) {
            double shieldLoss = shields.stream().mapToDouble(hit -> hit.trace().capacityLoss()).sum();
            boolean shieldBlocked = defenseBlocked || healthBlocked || shields.stream().anyMatch(hit -> hit.trace().blocked());
            var outcome = shieldLoss + healthLoss + absorptionLoss > 0 || dead || protection.isPresent() || accepted && !shieldBlocked
                    ? DamageReceipt.Outcome.APPLIED : immune ? DamageReceipt.Outcome.IMMUNE
                    : blocked || shieldBlocked ? DamageReceipt.Outcome.BLOCKED : DamageReceipt.Outcome.CANCELLED;
            return new DamageReceipt(id, outcome, shieldLoss, absorptionLoss, healthLoss,
                    dead ? Optional.of(id + "/death") : Optional.empty(), protection.isPresent(), protection, shields, outgoing, defense, consumptionFacts, observedBuffs, observedEntities, health);
        }
    }
    public static final class Scope implements AutoCloseable {
        private final Call call;
        private final boolean owner;
        private final int previousLayer;
        private boolean completed, accepted;
        private Scope(Call call, boolean owner, int previousLayer) { this.call = call; this.owner = owner; this.previousLayer = previousLayer; }
        public void complete(boolean accepted) { this.completed = true; this.accepted = accepted; }
        public void failed(Throwable error) { if (call.boundary.failure == null) call.boundary.failure = error; }
        @Override public void close() {
            if (!owner) { call.layer = previousLayer; return; }
            if (CALLS.get().peek() != call) throw new IllegalStateException("Damage calls closed out of order");
            Throwable closingFailure = null;
            try {
                if (call.observer != null) {
                    if (completed) {
                        call.observedBuffs = call.observer.observeBuffs(call.command);
                        call.observedEntities = call.observer.observeEntities(call.command, call.target, call.source);
                        call.consumptionFacts = call.observer.finished(call.id, call.command, call.receipt(accepted), call.managedOrigin);
                    }
                    else call.observer.abandoned(call.id);
                }
            } catch (RuntimeException | Error error) {
                closingFailure = error; failed(error);
                try { if (call.observer != null) call.observer.abandoned(call.id); }
                catch (RuntimeException | Error abandoned) { suppress(error, abandoned); }
            } finally {
                CALLS.get().pop();
                boolean outermost = CALLS.get().isEmpty();
                if (outermost) CALLS.remove();
                if (completed && call.observer != null) try {
                    var receipt = call.receipt(accepted);
                    call.boundary.committed.add(new Delivery(call.observer, new Observed(call.command, receipt), call.publish));
                    call.observer.actual(call.command, receipt, call.managedOrigin);
                } catch (RuntimeException | Error error) {
                    failed(error);
                    if (closingFailure == null) closingFailure = error; else suppress(closingFailure, error);
                }
                // Success leaves the managed primary to Action.complete. Failure still needs its actual native receipt,
                // including when observing entities or finalizing consumption failed after the HP write.
                if (outermost) try { call.boundary.flush(); }
                catch (RuntimeException | Error error) {
                    if (closingFailure == null) closingFailure = error; else suppress(closingFailure, error);
                }
            }
            if (closingFailure instanceof RuntimeException error) throw error;
            if (closingFailure instanceof Error error) throw error;
        }
    }

    /** Layers 3/2/1 are ServerPlayer/Player/LivingEntity; delegation joins, native reentry creates a new call. */
    public static Scope enter(LivingEntity target, DamageSource source, float amount, int layer) {
        var current = CALLS.get().peek();
        if (current != null && current.target == target && layer < current.layer) {
            int previous = current.layer; current.layer = layer;
            if (current.source != source) {
                var previousSource = current.source;
                current.source = source;
                try {
                    if (current.observer != null && !current.managedOrigin)
                        current.command = Objects.requireNonNull(current.observer.revise(current.id,
                                current.observer.describe(target, source, amount), target, source));
                } catch (RuntimeException | Error error) {
                    current.source = previousSource; current.layer = previous;
                    if (current.boundary.failure == null) current.boundary.failure = error;
                    throw error;
                }
            }
            return new Scope(current, false, previous);
        }
        var request = REQUESTS.get().peek();
        var observer = observer(target);
        if (request == null && observer == null && current == null) return null;
        boolean ownedRequest = request != null && !request.entered && request.call.target == target;
        Call call = ownedRequest ? request.call : new Call(NATIVE_PREFIX + NATIVE_IDS.incrementAndGet(), target, source, false);
        if (ownedRequest) request.entered = true;
        call.source = source; call.layer = layer; call.observer = observer;
        call.boundary = current == null ? new Boundary() : current.boundary;
        call.publish = observer != null && !(ownedRequest && request.managed);
        call.managedOrigin = ownedRequest && request.managed;
        if (observer != null) {
            call.boundary.observers.add(observer);
            try {
                if (current == null && (request == null || !request.managed)) observer.prepare();
                call.command = Objects.requireNonNull(observer.begin(call.id,
                        call.managedOrigin ? request.description : observer.describe(target, source, amount), target, source));
            } catch (RuntimeException | Error error) {
                rejected(call, current == null, error); throw error;
            }
        }
        CALLS.get().push(call); return new Scope(call, true, 0);
    }
    public static boolean hurt(LivingEntity target, DamageSource source, float amount, int layer, Function<Float, Boolean> original) {
        try (var scope = enter(target, source, amount, layer)) {
            try {
                if (scope != null && scope.owner && scope.call.observer != null) {
                    scope.call.outgoing = scope.call.observer.outgoing(scope.call.command, amount);
                    if (scope.call.outgoing.isPresent()) amount = nativeAmount(scope.call.outgoing.orElseThrow().output().value());
                }
                boolean accepted = original.apply(amount);
                if (scope != null) scope.complete(accepted);
                return accepted;
            } catch (RuntimeException | Error error) {
                if (scope != null) scope.failed(error);
                throw error;
            }
        }
    }
    private static Call current(LivingEntity target) {
        var call = CALLS.get().peek(); return call != null && call.target == target ? call : null;
    }
    /** Called only after native immunity, cancellation, item blocking and cooldown gates, before armor. */
    public static float shield(LivingEntity target, float amount) {
        var call = current(target);
        if (call == null || call.observer == null || call.shieldsProcessed) return amount;
        call.shieldsProcessed = true;
        // A flat ADD/REPLACE/curve in a defense profile must not resurrect fully item-blocked damage.
        if (amount <= 0) return amount;
        call.defense = call.observer.defense(call.command, amount);
        if (call.defense.isPresent()) {
            float modified = nativeAmount(call.defense.orElseThrow().output().value());
            call.defenseBlocked = amount > 0 && modified == 0;
            amount = modified;
        }
        var plan = call.observer.shields(call.command, amount, new DamageBasis(call.outgoing, call.defense));
        call.shields = plan.layers();
        return (float) plan.budget().toVanilla();
    }
    /** Called after native armor, magic and absorption, before combat tracking and the HP write. */
    public static float healthDamage(LivingEntity target, float amount) {
        var call = current(target);
        if (call == null || call.observer == null || call.healthProcessed) return amount;
        call.healthProcessed = true;
        if (amount <= 0) return amount;
        call.health = call.observer.health(call.command, amount);
        if (call.health.isEmpty()) return amount;
        float modified = nativeAmount(call.health.orElseThrow().output().value());
        call.healthBlocked = modified == 0;
        return modified;
    }
    private static float nativeAmount(double amount) {
        Numbers.nonnegative(amount, "native damage");
        if (amount > Float.MAX_VALUE) throw new IllegalArgumentException("Damage exceeds the native float range");
        return (float) amount;
    }
    public static float limitHealth(LivingEntity target, float health) {
        var call = current(target);
        return call != null && call.nonLethal && health < target.getHealth()
                ? Math.max(health, Math.min(1, target.getHealth())) : health;
    }
    public static void health(LivingEntity target, float before, float after) {
        var call = current(target); if (call != null) call.healthLoss += Math.max(0, (double) before - after);
    }
    public static void absorption(LivingEntity target, float before, float after) {
        var call = current(target); if (call != null) call.absorptionLoss += Math.max(0, (double) before - after);
    }
    public static void immune(LivingEntity target, DamageSource source) {
        var call = current(target);
        var request = REQUESTS.get().peek();
        if (request != null && !request.entered && request.call.target == target) call = request.call;
        if (call != null && call.source == source) call.immune = true;
    }
    public static void fireImmune(LivingEntity target) { var call = current(target); if (call != null) call.immune = true; }
    public static void blocked(LivingEntity target) { var call = current(target); if (call != null) call.blocked = true; }
    public static void death(LivingEntity target, DamageSource source) {
        var call = current(target);
        if (call != null && call.source == source && !call.dead) {
            call.dead = true;
            if (call.observer != null) call.observer.confirmedDeath(call.id + "/death", target);
        }
    }
    public static void protectedBy(LivingEntity target, String source) {
        var call = current(target); if (call != null) call.protection = Optional.of(source);
    }
}
