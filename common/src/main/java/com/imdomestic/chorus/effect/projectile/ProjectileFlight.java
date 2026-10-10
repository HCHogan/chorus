package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.effect.EffectContinuations;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Immutable launch and contact observations. Each contact body is independent of its former source. */
public final class ProjectileFlight {
    public static final String EVENT = "chorus:internal/projectile_impact";
    private ProjectileFlight() {}
    public record Limit(OptionalInt maximum) {
        public static final Limit UNLIMITED = new Limit(OptionalInt.empty());
        public Limit { Objects.requireNonNull(maximum); if (maximum.isPresent() && maximum.getAsInt() < 0) throw new IllegalArgumentException("Negative collision limit"); }
        public Limit(int maximum) { this(OptionalInt.of(maximum)); }
        public boolean allows(long completed) { return maximum.isEmpty() || completed < maximum.getAsInt(); }
    }
    public record Collision(Limit blockBounces, Limit entityPierces, Limit hitsPerTarget, double restitution) {
        public static final Collision STOP = new Collision(new Limit(0), new Limit(0), new Limit(1), 1);
        public Collision {
            Objects.requireNonNull(blockBounces); Objects.requireNonNull(entityPierces); Objects.requireNonNull(hitsPerTarget);
            Numbers.nonnegative(restitution, "bounce restitution");
            if (!hitsPerTarget.allows(0) || restitution > 1) throw new IllegalArgumentException("Invalid projectile collision policy");
        }
    }
    public record Parameters(double speed, double gravity, double drag, long lifetimeMicros, Collision collision, Optional<ProjectileTracking.Policy> tracking) {
        public Parameters(double speed, double gravity, double drag, long lifetimeMicros) { this(speed, gravity, drag, lifetimeMicros, Collision.STOP); }
        public Parameters(double speed, double gravity, double drag, long lifetimeMicros, Collision collision) { this(speed, gravity, drag, lifetimeMicros, collision, Optional.empty()); }
        public Parameters {
            Objects.requireNonNull(collision); Objects.requireNonNull(tracking);
            Numbers.nonnegative(speed, "projectile speed"); Numbers.nonnegative(gravity, "projectile gravity"); Numbers.nonnegative(drag, "projectile drag");
            if (speed > 2000 || gravity > 2000 || drag > 1 || lifetimeMicros <= 0 || lifetimeMicros == Long.MAX_VALUE)
                throw new IllegalArgumentException("Projectile parameters exceed host limits");
        }
    }
    public record Launch(Optional<WorldPosition> position, Optional<WorldDirection> direction, Parameters parameters,
            String owner, EffectContinuations.Pending continuation, String impactSlot, Optional<ShotGroups.Member> member) implements RuleEngine.WorldCommand {
        public Launch(Optional<WorldPosition> position, Optional<WorldDirection> direction, Parameters parameters,
                String owner, EffectContinuations.Pending continuation, String impactSlot) { this(position, direction, parameters, owner, continuation, impactSlot, Optional.empty()); }
        public Launch {
            Objects.requireNonNull(position); Objects.requireNonNull(direction); Objects.requireNonNull(parameters); Objects.requireNonNull(owner);
            Objects.requireNonNull(continuation); Objects.requireNonNull(impactSlot); Objects.requireNonNull(member);
            if (member.filter(m -> !m.shot().origin().owner().equals(owner)).isPresent()) throw new IllegalArgumentException("Pellet owner differs from shot");
            if (impactSlot.isBlank() || continuation.bindings().containsKey(impactSlot) || continuation.owner().isPresent()) throw new IllegalArgumentException("Projectile needs a fresh contact slot and detached continuation");
        }
        public RuleEngine.Signal finish(Impact impact) {
            if (!position.orElseThrow().dimension().equals(impact.point().dimension())) throw new IllegalArgumentException("Projectile impact changed dimension");
            var bindings = new HashMap<>(continuation.bindings()); bindings.put(impactSlot, impact.withMember(member));
            return new RuleEngine.Signal(EVENT, new EffectContinuations.Pending(continuation.id() + "/contact/" + impact.sequence(), continuation.definition(), continuation.version(), continuation.scope(), continuation.cause(), bindings, Optional.empty()));
        }
    }
    public enum Outcome { LAUNCHED, MISSING_POSITION, MISSING_DIRECTION, WRONG_DIMENSION, UNLOADED, REJECTED, EXPIRED_SHOT }
    public record Receipt(Launch launch, Outcome outcome, Optional<String> entity) implements RuleEngine.ActionResult {
        public Receipt {
            Objects.requireNonNull(launch); Objects.requireNonNull(outcome); Objects.requireNonNull(entity);
            if ((outcome == Outcome.LAUNCHED) != entity.isPresent() || entity.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Invalid projectile launch receipt");
        }
    }
    public enum End { ENTITY, BLOCK, EXPIRED, UNLOADED }
    /** Contact limits are content policy, not a restriction on the engine's event cycles. */
    public record Progress(long sequence, long bounces, long entityContacts, Map<String, Long> hits, boolean terminal) {
        public static final Progress EMPTY = new Progress(0, 0, 0, Map.of(), false);
        public Progress {
            hits = Map.copyOf(hits);
            if (sequence < 0 || bounces < 0 || entityContacts < 0 || bounces > sequence || entityContacts > sequence
                    || hits.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() <= 0 || e.getValue() > entityContacts)) throw new IllegalArgumentException("Invalid projectile progress");
            long counted = 0;
            for (long count : hits.values()) {
                if (count > entityContacts - counted) throw new IllegalArgumentException("Contact counts exceed total");
                counted += count;
            }
            if (counted != entityContacts || bounces > sequence - entityContacts) throw new IllegalArgumentException("Contradictory projectile progress");
        }
        public Progress abandon() { return terminal ? this : new Progress(sequence, bounces, entityContacts, hits, true); }
        public boolean canHit(String target, Collision policy) { return !terminal && policy.hitsPerTarget().allows(hits.getOrDefault(target, 0L)); }
        public Progress contact(Collision policy, End end, Optional<String> target, boolean embedded) {
            if (terminal) throw new IllegalStateException("Consumed projectile cannot observe another contact");
            if ((end == End.ENTITY) != target.isPresent()) throw new IllegalArgumentException("Contact target does not match kind");
            long nextSequence = Math.addExact(sequence, 1), nextBounces = bounces, nextContacts = entityContacts;
            var nextHits = new HashMap<>(hits); boolean done = true;
            if (end == End.ENTITY) {
                String id = target.orElseThrow(); if (!canHit(id, policy)) throw new IllegalArgumentException("Target contact limit exhausted");
                nextContacts = Math.addExact(entityContacts, 1); nextHits.put(id, Math.addExact(hits.getOrDefault(id, 0L), 1));
                done = !policy.entityPierces().allows(entityContacts);
            } else if (end == End.BLOCK && !embedded && policy.blockBounces().allows(bounces)) { nextBounces = Math.addExact(bounces, 1); done = false; }
            return new Progress(nextSequence, nextBounces, nextContacts, nextHits, done);
        }
    }
    /** Only ENTITY contains a living target; all outcomes have an exact observed position. */
    public record Impact(End end, WorldPosition point, Optional<String> target, double normalX, double normalY, double normalZ,
            long ageMicros, long sequence, long bounces, long entityContacts, long targetContacts, boolean terminal, Optional<ShotGroups.Member> member) implements PositionResult, Targets.Collection {
        public Impact(End end, WorldPosition point, Optional<String> target, double normalX, double normalY, double normalZ,
                long ageMicros, long sequence, long bounces, long entityContacts, long targetContacts, boolean terminal) {
            this(end, point, target, normalX, normalY, normalZ, ageMicros, sequence, bounces, entityContacts, targetContacts, terminal, Optional.empty());
        }
        public Impact withMember(Optional<ShotGroups.Member> value) {
            return new Impact(end, point, target, normalX, normalY, normalZ, ageMicros, sequence, bounces, entityContacts, targetContacts, terminal, value);
        }
        public Impact(End end, WorldPosition point, Optional<String> target, double normalX, double normalY, double normalZ, long ageMicros) {
            this(end, point, target, normalX, normalY, normalZ, ageMicros, 1, 0, end == End.ENTITY ? 1 : 0, end == End.ENTITY ? 1 : 0, true);
        }
        public Impact {
            Objects.requireNonNull(end); Objects.requireNonNull(point); Objects.requireNonNull(target); Objects.requireNonNull(member);
            Numbers.finite(normalX, "normal x"); Numbers.finite(normalY, "normal y"); Numbers.finite(normalZ, "normal z");
            if (ageMicros < 0 || (end == End.ENTITY) != target.isPresent() || target.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Invalid projectile impact");
            double normal = Math.hypot(Math.hypot(normalX, normalY), normalZ);
            if (end == End.BLOCK ? Math.abs(normal - 1) > 1e-9 : normal != 0) throw new IllegalArgumentException("Only block impacts have a unit face normal");
            if (sequence < 1 || bounces < 0 || entityContacts < 0 || targetContacts < 0 || bounces > sequence || entityContacts > sequence || targetContacts > entityContacts
                    || (end == End.ENTITY) != (targetContacts > 0) || !terminal && end != End.ENTITY && end != End.BLOCK
                    || !terminal && end == End.BLOCK && bounces == 0) throw new IllegalArgumentException("Contradictory projectile contact counters");
        }
        @Override public Optional<WorldPosition> position() { return Optional.of(point); }
        @Override public List<Targets.Identity> targets() { return target.map(id -> List.of(new Targets.Identity(id))).orElse(List.of()); }
    }
}
