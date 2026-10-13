package com.imdomestic.chorus.effect.motion;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;

/** Historical physical landing evidence. It does not confer any particular ability's completion or credit. */
public final class GroundContact {
    private GroundContact() {}
    public static final String LANDED = "chorus:landed";
    public record Landed(EntityAnchor.Reference reference, String landingId, double requestedY, double movedY,
            EntityObservation observation) implements EffectEvent.Carrier {
        public Landed {
            Objects.requireNonNull(reference); Objects.requireNonNull(observation);
            if (landingId == null || landingId.isBlank() || !Double.isFinite(requestedY) || requestedY >= 0
                    || !Double.isFinite(movedY))
                throw new IllegalArgumentException("Landing needs a downward movement and receipt identity");
            if (!observation.entities().keySet().equals(Set.of(reference.target())) || observation.require(reference.target()).isEmpty()
                    || observation.positions().size() != TargetQuery.Anchor.values().length)
                throw new IllegalArgumentException("Landing must observe its entity and all anchors");
            for (var anchor : TargetQuery.Anchor.values()) {
                var point = observation.requirePosition(reference.target(), anchor).orElseThrow(() -> new IllegalArgumentException("Missing landing anchor"));
                if (!point.dimension().equals(reference.dimension())) throw new IllegalArgumentException("Landing belongs to another world");
            }
        }
        @Override public EffectEvent event() {
            return new EffectEvent(reference.target(), reference.target(), new BuffInstance.Origin(reference.target(), "chorus:ground_contact", "", ""),
                    Set.of(LANDED), Map.of("requested_y", new Measure(requestedY, Unit.METER), "moved_y", new Measure(movedY, Unit.METER)),
                    Map.of("ground_contact", true), Map.of("landing_id", landingId, "entity_life", reference.life(), "entity_dimension", reference.dimension()))
                    .withObservedEntities(Optional.of(observation));
        }
        public RuleEngine.Signal signal() { return new RuleEngine.Signal(LANDED, this); }
        public EntityAnchor.Result anchor(TargetQuery.Anchor anchor) {
            return new EntityAnchor.Result(new EntityAnchor.Query(reference.target(), anchor, Optional.of(reference)), EntityAnchor.Outcome.AVAILABLE,
                    Optional.of(new EntityAnchor.Observation(reference, observation.requirePosition(reference.target(), anchor).orElseThrow(), anchor,
                            observation.require(reference.target()).orElseThrow().alive())));
        }
    }
}
