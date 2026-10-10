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
        default Optional<List<com.imdomestic.chorus.rule.RuleEngine.Signal>> finished(String id, DamageCommand command, DamageReceipt receipt, boolean managed) { return Optional.empty(); }
        default void abandoned(String id) {}
        default Optional<com.imdomestic.chorus.effect.buff.BuffObservation> observeBuffs(DamageCommand command) { return Optional.empty(); }
        default Optional<com.imdomestic.chorus.effect.target.EntityObservation> observeEntities(DamageCommand command, LivingEntity target, DamageSource source) { return Optional.empty(); }
        Optional<CalculationProfile.Result> outgoing(DamageCommand command, double amount);
        Optional<CalculationProfile.Result> defense(DamageCommand command, double amount);
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
    private record Delivery(Observer observer, Observed fact) {}
    private static final class Boundary {
        final List<Delivery> committed = new ArrayList<>();
        final LinkedHashSet<Observer> observers = new LinkedHashSet<>();
        Throwable failure;
        void flush() {
            for (var observer : observers) {
                var facts = committed.stream().filter(delivery -> delivery.observer() == observer).map(Delivery::fact).toList();
                if (failure != null) observer.failed(failure, facts);
                else observer.committed(facts);
            }
        }
    }
    private static final class Call {
        final String id;
        final LivingEntity target;
        DamageSource source;
        final boolean nonLethal;
        double healthLoss, absorptionLoss;
        boolean immune, blocked, dead;
        boolean shieldsProcessed, defenseBlocked;
        List<ShieldDamage.LayerHit> shields = List.of();
        Optional<CalculationProfile.Result> outgoing = Optional.empty(), defense = Optional.empty();
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
            boolean shieldBlocked = defenseBlocked || shields.stream().anyMatch(hit -> hit.trace().blocked());
            var outcome = shieldLoss + healthLoss + absorptionLoss > 0 || dead || protection.isPresent() || accepted && !shieldBlocked
                    ? DamageReceipt.Outcome.APPLIED : immune ? DamageReceipt.Outcome.IMMUNE
                    : blocked || shieldBlocked ? DamageReceipt.Outcome.BLOCKED : DamageReceipt.Outcome.CANCELLED;
            return new DamageReceipt(id, outcome, shieldLoss, absorptionLoss, healthLoss,
                    dead ? Optional.of(id + "/death") : Optional.empty(), protection.isPresent(), protection, shields, outgoing, defense, consumptionFacts, observedBuffs, observedEntities);
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
                failed(error);
                if (call.observer != null) call.observer.abandoned(call.id);
                throw error;
            } finally {
                CALLS.get().pop();
                boolean outermost = CALLS.get().isEmpty();
                if (outermost) CALLS.remove();
                if (completed && call.publish) call.boundary.committed.add(new Delivery(call.observer, new Observed(call.command, call.receipt(accepted))));
                // The managed primary hit is published by Action.complete, never a second time by this observer.
                if (outermost) call.boundary.flush();
            }
        }
    }

    /** Layers 3/2/1 are ServerPlayer/Player/LivingEntity; delegation joins, native reentry creates a new call. */
    public static Scope enter(LivingEntity target, DamageSource source, float amount, int layer) {
        var current = CALLS.get().peek();
        if (current != null && current.target == target && layer < current.layer) {
            int previous = current.layer; current.layer = layer;
            if (current.source != source) {
                current.source = source;
                if (current.observer != null && !current.managedOrigin) current.command = current.observer.revise(current.id, current.observer.describe(target, source, amount));
            }
            return new Scope(current, false, previous);
        }
        var request = REQUESTS.get().peek();
        var observer = observer(target);
        if (request == null && observer == null && current == null) return null;
        if (current == null && observer != null && (request == null || !request.managed)) observer.prepare();
        boolean ownedRequest = request != null && !request.entered && request.call.target == target;
        Call call = ownedRequest ? request.call : new Call(NATIVE_PREFIX + NATIVE_IDS.incrementAndGet(), target, source, false);
        if (ownedRequest) request.entered = true;
        call.source = source; call.layer = layer; call.observer = observer;
        call.boundary = current == null ? new Boundary() : current.boundary;
        call.publish = observer != null && !(ownedRequest && request.managed);
        call.managedOrigin = ownedRequest && request.managed;
        if (observer != null) {
            call.command = observer.begin(call.id, call.managedOrigin ? request.description : observer.describe(target, source, amount));
            call.boundary.observers.add(observer);
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
        var call = current(target); if (call != null && call.source == source) call.dead = true;
    }
    public static void protectedBy(LivingEntity target, String source) {
        var call = current(target); if (call != null) call.protection = Optional.of(source);
    }
}
