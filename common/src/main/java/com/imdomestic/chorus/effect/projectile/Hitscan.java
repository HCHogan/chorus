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
    public interface Observation extends PositionResult, Targets.Collection {
        Query query(); Outcome outcome(); Optional<ProjectileFlight.Impact> contact();
        default double segmentDistance() { var a=query().position().orElseThrow();var b=position().orElseThrow();return Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z()); }
        default double distance() { return segmentDistance(); }
        @Override default Optional<WorldPosition> position() { return contact().map(ProjectileFlight.Impact::point); }
        @Override default List<Targets.Identity> targets() { return contact().map(ProjectileFlight.Impact::targets).orElse(List.of()); }
    }
    /** Additional distinct entities after the first; does not permit penetrating terrain. */
    public record PiercingQuery(Query ray, int pierces) implements RuleEngine.WorldCommand {
        public PiercingQuery { Objects.requireNonNull(ray); if (pierces < 0) throw new IllegalArgumentException("Negative ray penetrations"); }
    }
    public interface PreparedObservation extends RuleEngine.ActionResult { RuleEngine.WorldCommand command(); }
    public interface PathObservation extends RuleEngine.ActionResult {
        RuleEngine.WorldCommand command(); List<? extends Observation> observations();
    }
    public record PreparedPath(PiercingQuery query) implements PreparedObservation {
        @Override public RuleEngine.WorldCommand command() { return query; }
    }
    public record PathContact(Result observed, long sequence, boolean terminal) implements Observation {
        public PathContact {
            Objects.requireNonNull(observed);
            if (sequence < 1 || !terminal && observed.outcome()!=Outcome.ENTITY || observed.contact().isEmpty() && sequence!=1)
                throw new IllegalArgumentException("Invalid ray path contact");
        }
        @Override public Query query() { return observed.query(); }
        @Override public Outcome outcome() { return observed.outcome(); }
        @Override public Optional<ProjectileFlight.Impact> contact() {
            return observed.contact().map(c -> new ProjectileFlight.Impact(c.end(),c.point(),c.target(),c.normalX(),c.normalY(),c.normalZ(),0,
                    sequence,0,c.end()==ProjectileFlight.End.ENTITY?sequence:sequence-1,c.end()==ProjectileFlight.End.ENTITY?1:0,terminal,c.member(),c.geometry()));
        }
    }
    /** Geometry is captured once. Later writes cannot reorder or add contacts to this instantaneous path. */
    public record PathResult(PiercingQuery query, List<PathContact> contacts) implements PathObservation {
        @Override public RuleEngine.WorldCommand command() { return query; }
        @Override public List<PathContact> observations() { return contacts; }
        public PathResult {
            Objects.requireNonNull(query); contacts=List.copyOf(contacts);
            if(contacts.isEmpty() || contacts.size()>(long)query.pierces()+2) throw new IllegalArgumentException("Invalid ray path length");
            var targets=new HashSet<String>(); double previous=-1;String previousId="";
            for(int i=0;i<contacts.size();i++) {
                var c=contacts.get(i);boolean last=i==contacts.size()-1;
                if(!c.query().equals(query.ray()) || c.sequence()!=i+1L || c.terminal()!=last || !last&&c.outcome()!=Outcome.ENTITY)
                    throw new IllegalArgumentException("Unordered or mismatched ray path");
                if(c.outcome()==Outcome.ENTITY) {
                    String id=c.targets().getFirst().entity(); if(!targets.add(id))throw new IllegalArgumentException("Ray revisited a penetrated target");
                    if(last && contacts.size()!=(long)query.pierces()+1)throw new IllegalArgumentException("Ray stopped before its penetration budget");
                } else if(last && contacts.size()>(long)query.pierces()+1)throw new IllegalArgumentException("Ray continued after its final entity");
                if(c.position().isPresent()) {
                    var a=query.ray().position().orElseThrow();var b=c.position().orElseThrow();double distance=Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z());
                    String id=c.targets().isEmpty()?"":c.targets().getFirst().entity();
                    if(distance<previous || distance==previous && c.outcome()==Outcome.ENTITY && id.compareTo(previousId)<0)throw new IllegalArgumentException("Ray contacts are not ordered by distance and identity");
                    previous=distance;previousId=id;
                } else if(contacts.size()!=1)throw new IllegalArgumentException("Unavailable ray has multiple contacts");
            }
        }
    }
    /** The shared contact is an aggregation adapter; RANGE is exposed as range, not a flight expiry. */
    public record Result(Query query, Outcome outcome, Optional<ProjectileFlight.Impact> contact) implements Observation {
        public Result { validateObservation(query,outcome,contact,false); }
        @Override public Optional<WorldPosition> position() { return contact.map(ProjectileFlight.Impact::point); }
        @Override public List<Targets.Identity> targets() { return contact.map(ProjectileFlight.Impact::targets).orElse(List.of()); }
    }
    static void validateObservation(Query query, Outcome outcome, Optional<ProjectileFlight.Impact> contact, boolean allowOrigin) {
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
                    || !allowOrigin && c.target().filter(id -> id.equals(query.owner()) || id.equals(query.emitter())).isPresent()))
                throw new IllegalArgumentException("Ray needs observed target geometry and cannot hit its emitter");
        }
    }
}
