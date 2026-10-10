package com.imdomestic.chorus.effect.object;

import com.imdomestic.chorus.effect.EffectContinuations;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** One logical pickup, with an explicit recipient and a detached, version-pinned collection body. */
public final class WorldPickup {
    public static final String CONTACT = "chorus:internal/pickup_contact", COLLECTED = "chorus:pickup";
    private WorldPickup() {}
    public record Attraction(String profile, double radius, double speed) {
        public Attraction {
            Objects.requireNonNull(profile); Numbers.nonnegative(radius, "pickup attraction radius"); Numbers.nonnegative(speed, "pickup speed");
            if (profile.isBlank() || speed > 2000) throw new IllegalArgumentException("Invalid pickup attraction policy");
        }
    }
    public record Parameters(long lifetimeMicros, double radius, Optional<Attraction> attraction) {
        public Parameters {
            Numbers.nonnegative(radius, "pickup collection radius"); Objects.requireNonNull(attraction);
            if (lifetimeMicros <= 0 || lifetimeMicros == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid pickup lifetime");
        }
    }
    public record Spawn(Optional<WorldPosition> position, String kind, String recipient, BuffInstance.Origin origin,
            Parameters parameters, EffectContinuations.Pending continuation, String contactSlot) implements RuleEngine.WorldCommand {
        public Spawn {
            Objects.requireNonNull(position); Objects.requireNonNull(kind); Objects.requireNonNull(recipient); Objects.requireNonNull(origin);
            Objects.requireNonNull(parameters); Objects.requireNonNull(continuation); Objects.requireNonNull(contactSlot);
            if (kind.isBlank() || contactSlot.isBlank() || continuation.bindings().containsKey(contactSlot) || continuation.owner().isPresent())
                throw new IllegalArgumentException("Pickup needs a kind, fresh contact slot and detached continuation");
        }
        public RuleEngine.Signal finish(Contact contact) {
            if (!position.orElseThrow().dimension().equals(contact.point().dimension())) throw new IllegalArgumentException("Pickup changed dimension");
            if (contact.end() == End.COLLECTED ? contact.ageMicros() >= parameters.lifetimeMicros()
                    || !contact.collector().orElseThrow().equals(recipient) : contact.ageMicros() < parameters.lifetimeMicros())
                throw new IllegalArgumentException("Pickup contact contradicts lifetime or recipient");
            var bindings = new HashMap<>(continuation.bindings()); bindings.put(contactSlot, contact);
            return new RuleEngine.Signal(CONTACT, new EffectContinuations.Pending(continuation.id(), continuation.definition(), continuation.version(),
                    continuation.scope(), continuation.cause(), bindings, Optional.empty()));
        }
    }
    public enum Outcome { SPAWNED, MISSING_POSITION, MISSING_RECIPIENT, WRONG_DIMENSION, UNLOADED, REJECTED }
    public record Receipt(Spawn spawn, Outcome outcome, Optional<String> entity) implements RuleEngine.ActionResult {
        public Receipt {
            Objects.requireNonNull(spawn); Objects.requireNonNull(outcome); Objects.requireNonNull(entity);
            if ((outcome == Outcome.SPAWNED) != entity.isPresent() || entity.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Invalid pickup spawn receipt");
        }
    }
    public enum End { COLLECTED, EXPIRED }
    /** Expiry has no collector. Both results retain the terminal position for explicit follow-up effects. */
    public record Contact(End end, WorldPosition point, Optional<String> collector, long ageMicros) implements PositionResult, Targets.Collection {
        public Contact {
            Objects.requireNonNull(end); Objects.requireNonNull(point); Objects.requireNonNull(collector);
            if (ageMicros < 0 || (end == End.COLLECTED) != collector.isPresent() || collector.filter(String::isBlank).isPresent()) throw new IllegalArgumentException("Invalid pickup contact");
        }
        @Override public Optional<WorldPosition> position() { return Optional.of(point); }
        @Override public List<Targets.Identity> targets() { return collector.map(id -> List.of(new Targets.Identity(id))).orElse(List.of()); }
        public RuleEngine.Signal fact(String id, String kind, BuffInstance.Origin origin) {
            String who = collector.orElseThrow();
            return new RuleEngine.Signal(COLLECTED, new EffectEvent(who, who, origin, Set.of(kind),
                    Map.of("count", new Measure(1, Unit.COUNT), "age", new Measure(ageMicros / 1_000_000.0, Unit.SECOND)),
                    Map.of(), Map.of("pickup_id", id, "pickup_kind", kind, "collector", who)));
        }
    }
}
