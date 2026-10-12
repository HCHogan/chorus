package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** One instantaneous, server-observed closed ray segment. It does not spawn a flying entity. */
public final class Hitscan {
    private Hitscan() {}
    public enum Outcome { ENTITY, BLOCK, RANGE, UNLOADED, MISSING_POSITION, MISSING_DIRECTION, WRONG_DIMENSION, EXPIRED_SHOT }
    public record Query(Optional<WorldPosition> position, Optional<WorldDirection> direction, double range,
            double inflation, String owner, String emitter, Optional<ShotGroups.Member> member) implements RuleEngine.WorldCommand {
        public Query {
            Objects.requireNonNull(position); Objects.requireNonNull(direction); Objects.requireNonNull(member);
            Numbers.finite(range, "ray range"); Numbers.nonnegative(inflation, "ray target inflation");
            if (range <= 0 || range > 256 || inflation > 4 || owner == null || owner.isBlank() || emitter == null || emitter.isBlank())
                throw new IllegalArgumentException("Invalid ray geometry or identity");
            if (member.filter(m -> !m.shot().origin().owner().equals(owner)).isPresent()) throw new IllegalArgumentException("Ray owner differs from shot");
        }
    }
    public record Prepared(Query query) implements RuleEngine.ActionResult {}
    /** The shared contact is an aggregation adapter; RANGE is exposed as range, not a flight expiry. */
    public record Result(Query query, Outcome outcome, Optional<ProjectileFlight.Impact> contact) implements PositionResult, Targets.Collection {
        public Result {
            Objects.requireNonNull(query); Objects.requireNonNull(outcome); Objects.requireNonNull(contact);
            boolean observed = switch (outcome) { case ENTITY, BLOCK, RANGE, UNLOADED -> true; default -> false; };
            if (observed != contact.isPresent()) throw new IllegalArgumentException("Ray outcome differs from contact");
            if (contact.isPresent()) {
                var c = contact.orElseThrow(); var start = query.position().orElseThrow(); var direction = query.direction().orElseThrow();
                var expected = switch (outcome) { case ENTITY -> ProjectileFlight.End.ENTITY; case BLOCK -> ProjectileFlight.End.BLOCK;
                    case RANGE -> ProjectileFlight.End.EXPIRED; case UNLOADED -> ProjectileFlight.End.UNLOADED; default -> throw new AssertionError(); };
                if (c.end() != expected || !c.member().equals(query.member()) || !c.terminal() || c.sequence() != 1 || c.bounces() != 0 || c.ageMicros() != 0
                        || !start.dimension().equals(c.point().dimension()) || !start.dimension().equals(direction.dimension()))
                    throw new IllegalArgumentException("Invalid instantaneous ray contact");
                double x = c.point().x()-start.x(), y = c.point().y()-start.y(), z = c.point().z()-start.z();
                double along = x*direction.x()+y*direction.y()+z*direction.z();
                if (along < -1e-7 || along > query.range()+1e-7 || Math.hypot(Math.hypot(x-along*direction.x(), y-along*direction.y()), z-along*direction.z()) > 1e-7
                        || outcome == Outcome.RANGE && Math.abs(along-query.range()) > 1e-7)
                    throw new IllegalArgumentException("Contact is outside requested ray");
                if (outcome == Outcome.ENTITY && (c.geometry().isEmpty() || Double.compare(c.observedGeometry().inflation(),query.inflation()) != 0
                        || c.target().filter(id -> id.equals(query.owner()) || id.equals(query.emitter())).isPresent()))
                    throw new IllegalArgumentException("Ray needs observed target geometry and cannot hit its emitter");
            }
        }
        @Override public Optional<WorldPosition> position() { return contact.map(ProjectileFlight.Impact::point); }
        @Override public List<Targets.Identity> targets() { return contact.map(ProjectileFlight.Impact::targets).orElse(List.of()); }
    }
}
