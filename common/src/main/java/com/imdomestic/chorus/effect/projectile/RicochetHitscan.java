package com.imdomestic.chorus.effect.projectile;

import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** A frozen polyline, with one shared travel budget and explicit origin collision policy. */
public final class RicochetHitscan {
    private RicochetHitscan() {}
    public record Query(Hitscan.Query ray, int pierces, int bounces, int hitsPerTarget, boolean hitOriginAfterBounce) implements RuleEngine.WorldCommand {
        public Query(Hitscan.Query ray,int pierces,int bounces,boolean hitOriginAfterBounce) { this(ray,pierces,bounces,1,hitOriginAfterBounce); }
        public Query { Objects.requireNonNull(ray); if(pierces<0 || bounces<0 || hitsPerTarget<1)throw new IllegalArgumentException("Negative ray continuations"); }
    }
    public record Prepared(Query query) implements Hitscan.PreparedObservation {
        public Prepared { Objects.requireNonNull(query); }
        @Override public RuleEngine.WorldCommand command() { return query; }
    }
    /** observed is a local, single-segment contact; cumulative counters are projected only when consumed. */
    public record Contact(Hitscan.Query query, Hitscan.Outcome outcome, Optional<ProjectileFlight.Impact> observed,
            long sequence, long bounces, long entityContacts, boolean terminal, double priorDistance, boolean embedded, long targetContacts) implements Hitscan.Observation {
        public Contact(Hitscan.Query query,Hitscan.Outcome outcome,Optional<ProjectileFlight.Impact> observed,long sequence,long bounces,long entityContacts,boolean terminal,double priorDistance,boolean embedded) {
            this(query,outcome,observed,sequence,bounces,entityContacts,terminal,priorDistance,embedded,outcome==Hitscan.Outcome.ENTITY?1:0);
        }
        public Contact {
            Hitscan.validateObservation(query,outcome,observed,true);Numbers.nonnegative(priorDistance,"previous ray distance");
            if(sequence<1 || bounces<0 || entityContacts<0 || bounces>sequence || entityContacts>sequence-bounces || priorDistance>256
                    || embedded && (outcome!=Hitscan.Outcome.BLOCK || !terminal)
                    || !terminal && outcome!=Hitscan.Outcome.ENTITY && outcome!=Hitscan.Outcome.BLOCK
                    || (outcome==Hitscan.Outcome.ENTITY ? targetContacts<1 || targetContacts>entityContacts : targetContacts!=0)
                    || observed.isEmpty() && (sequence!=1 || bounces!=0 || entityContacts!=0 || priorDistance!=0 || !terminal))
                throw new IllegalArgumentException("Invalid reflected contact counters");
        }
        @Override public Optional<ProjectileFlight.Impact> contact() {
            return observed.map(c->new ProjectileFlight.Impact(c.end(),c.point(),c.target(),c.normalX(),c.normalY(),c.normalZ(),0,
                    sequence,bounces,entityContacts,targetContacts,terminal,c.member(),c.geometry()));
        }
        @Override public double distance() { return priorDistance+segmentDistance(); }
    }
    public static boolean canReflect(Hitscan.Query segment, ProjectileFlight.Impact hit, boolean embedded) {
        if(embedded || hit.end()!=ProjectileFlight.End.BLOCK)return false;
        double normal=Math.abs(hit.normalX())+Math.abs(hit.normalY())+Math.abs(hit.normalZ());
        if(normal!=1 || Math.max(Math.max(Math.abs(hit.normalX()),Math.abs(hit.normalY())),Math.abs(hit.normalZ()))!=1)
            throw new IllegalArgumentException("Reflection needs a collision face normal");
        var d=segment.direction().orElseThrow();var from=segment.position().orElseThrow();var to=hit.point();
        double length=Math.hypot(Math.hypot(to.x()-from.x(),to.y()-from.y()),to.z()-from.z());
        return length<segment.range() && d.x()*hit.normalX()+d.y()*hit.normalY()+d.z()*hit.normalZ()<0;
    }
    public static Hitscan.Query reflected(Hitscan.Query segment, ProjectileFlight.Impact hit) {
        if(!canReflect(segment,hit,false))throw new IllegalArgumentException("Contact cannot reflect");
        var d=segment.direction().orElseThrow();double dot=d.x()*hit.normalX()+d.y()*hit.normalY()+d.z()*hit.normalZ();
        var direction=new WorldDirection(d.dimension(),d.x()-2*dot*hit.normalX(),d.y()-2*dot*hit.normalY(),d.z()-2*dot*hit.normalZ());
        var a=segment.position().orElseThrow();var b=hit.point();double distance=Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z());
        return new Hitscan.Query(Optional.of(b),Optional.of(direction),segment.range()-distance,segment.inflation(),segment.owner(),segment.emitter(),segment.member());
    }
    public record Result(Query query, List<Contact> contacts) implements Hitscan.PathObservation {
        public Result {
            Objects.requireNonNull(query);contacts=List.copyOf(contacts);
            if(contacts.isEmpty() || contacts.size()>(long)query.pierces()+query.bounces()+2)throw new IllegalArgumentException("Invalid reflected path length");
            var segment=query.ray();var targets=new HashMap<String,Long>();var segmentTargets=new HashSet<String>();double prior=0,previous=-1;String previousId="";long bounces=0,entities=0;
            for(int i=0;i<contacts.size();i++) {
                var c=contacts.get(i);boolean last=i==contacts.size()-1;
                if(!c.query().equals(segment) || c.sequence()!=i+1L || c.terminal()!=last || Math.abs(c.priorDistance()-prior)>1e-7)
                    throw new IllegalArgumentException("Disconnected reflected path");
                if(c.observed().isEmpty()) { if(contacts.size()!=1)throw new IllegalArgumentException("Unavailable reflected path has later contacts");continue; }
                var hit=c.observed().orElseThrow();double distance=c.segmentDistance();String target=hit.target().orElse("");
                if(distance<previous || distance==previous && c.outcome()==Hitscan.Outcome.ENTITY && target.compareTo(previousId)<0 || c.distance()>query.ray().range()+1e-7)
                    throw new IllegalArgumentException("Unordered reflected contacts or exceeded travel budget");
                if(c.outcome()==Hitscan.Outcome.ENTITY) {
                    long visits=targets.merge(target,1L,Long::sum);
                    if(!segmentTargets.add(target) || visits>query.hitsPerTarget() || c.targetContacts()!=visits || (target.equals(segment.owner()) || target.equals(segment.emitter())) && (!query.hitOriginAfterBounce() || bounces==0))
                        throw new IllegalArgumentException("Invalid target visits or forbidden origin contact");
                    entities++;
                    if(c.terminal()!=(entities==1L+query.pierces()) || entities>1L+query.pierces())throw new IllegalArgumentException("Invalid reflected penetration budget");
                } else if(c.outcome()==Hitscan.Outcome.BLOCK) {
                    boolean reflect=bounces<query.bounces() && canReflect(segment,hit,c.embedded());
                    if(c.terminal()==reflect)throw new IllegalArgumentException("Invalid reflection termination");
                    if(reflect)bounces++;
                } else if(!c.terminal())throw new IllegalArgumentException("Nonterminal reflected boundary");
                if(c.bounces()!=bounces || c.entityContacts()!=entities)throw new IllegalArgumentException("Reflected counters differ from path");
                if(c.outcome()==Hitscan.Outcome.BLOCK && !c.terminal()) {
                    prior+=distance;segment=reflected(segment,hit);segmentTargets.clear();previous=-1;previousId="";
                } else { previous=distance;previousId=target; }
            }
        }
        @Override public RuleEngine.WorldCommand command() { return query; }
        @Override public List<Contact> observations() { return contacts; }
    }
}
