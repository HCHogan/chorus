package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.object.WorldConstruct;
import com.imdomestic.chorus.effect.object.WorldPickup;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.Numbers;
import java.util.*;

/** Persistent physical identities and Chorus-owned progress. Native admission and successful entity writes are host proofs. */
public final class PhysicalEffects {
    private PhysicalEffects() {}
    public enum Kind { PROJECTILE, PICKUP, CONSTRUCT }

    public record Identity(String entity, Kind kind, String domain, TimelineIdentity timeline, String catalogue) {
        public Identity {
            uuid(entity); Objects.requireNonNull(kind); identifier(domain); Objects.requireNonNull(timeline); identifier(catalogue);
        }
    }
    public sealed interface Payload permits Projectile, Pickup, Construct {
        Kind kind();
        String dimension();
        String version();
        void requireActive(long capturedMicros);
    }
    /** UUIDs retain collision semantics; no life identity or target eligibility is inferred during decoding. */
    public record Projectile(ProjectileFlight.Launch launch, long ageMicros, double traveled,
            ProjectileFlight.Progress progress, Set<String> inside, Optional<String> trackingTarget, String launchOwnerKind) implements Payload {
        public Projectile {
            Objects.requireNonNull(launch); Objects.requireNonNull(progress); inside = Set.copyOf(inside); Objects.requireNonNull(trackingTarget);
            if (launch.position().isEmpty() || launch.direction().isEmpty()
                    || !launch.position().orElseThrow().dimension().equals(launch.direction().orElseThrow().dimension()))
                throw new IllegalArgumentException("Physical projectile requires its original launch geometry");
            if (ageMicros < 0 || ageMicros % 50_000 != 0) throw new IllegalArgumentException("Invalid physical projectile age");
            Numbers.nonnegative(traveled, "physical projectile traveled distance");
            if (ageMicros == 0 && (traveled != 0 || progress.bounces() != 0 || progress.entityContacts() != 0))
                throw new IllegalArgumentException("Unticked projectile has physical travel or collision history");
            progress.hits().keySet().forEach(PhysicalEffects::uuid); inside.forEach(PhysicalEffects::uuid); trackingTarget.ifPresent(PhysicalEffects::uuid);
            if (!progress.hits().keySet().containsAll(inside)) throw new IllegalArgumentException("Inside set contains an unobserved collision");
            if (!Set.of("player", "mob", "other", "unavailable").contains(launchOwnerKind))
                throw new IllegalArgumentException("Unknown launch owner classification");
            var parameters = launch.parameters();
            if (trackingTarget.isPresent() && parameters.tracking().isEmpty() && parameters.destination().isEmpty())
                throw new IllegalArgumentException("Ballistic projectile has a tracking target");
            if (trackingTarget.isPresent() && parameters.destination().isPresent()
                    && !trackingTarget.orElseThrow().equals(parameters.destination().orElseThrow().target()))
                throw new IllegalArgumentException("Tracking target differs from explicit destination");
            var collision = parameters.collision();
            requireWithin(progress.bounces(), collision.blockBounces(), "bounce count");
            progress.hits().values().forEach(count -> requireWithin(count, collision.hitsPerTarget(), "target contacts"));
        }
        @Override public Kind kind() { return Kind.PROJECTILE; }
        @Override public String dimension() { return launch.position().orElseThrow().dimension(); }
        @Override public String version() { return launch.continuation().version(); }
        @Override public void requireActive(long capturedMicros) {
            if (progress.terminal() || ageMicros >= launch.parameters().lifetimeMicros()
                    || progress.sequence() - progress.bounces() != progress.entityContacts())
                throw new IllegalArgumentException("Projectile is not at a live settled physical boundary");
            requireWithin(progress.entityContacts(), launch.parameters().collision().entityPierces(), "continued entity contacts");
            requireWithin(progress.sequence(), launch.parameters().collision().totalContinuations(), "continued contacts");
            launch.parameters().range().ifPresent(range -> {
                // A freshly launched zero-range projectile has not observed its first physical tick yet.
                if (traveled > range || traveled == range && (ageMicros != 0 || progress.sequence() != 0))
                    throw new IllegalArgumentException("Live projectile already exhausted its range");
            });
        }
    }
    public record Pickup(WorldPickup.Spawn spawn, long createdMicros, boolean consumed) implements Payload {
        public Pickup {
            Objects.requireNonNull(spawn); nonnegative(createdMicros, "pickup creation time");
            if (spawn.position().isEmpty()) throw new IllegalArgumentException("Physical pickup has no spawn position");
            uuid(spawn.recipient());
        }
        @Override public Kind kind() { return Kind.PICKUP; }
        @Override public String dimension() { return spawn.position().orElseThrow().dimension(); }
        @Override public String version() { return spawn.continuation().version(); }
        @Override public void requireActive(long capturedMicros) {
            if (consumed || capturedMicros < createdMicros || capturedMicros - createdMicros >= spawn.parameters().lifetimeMicros())
                throw new IllegalArgumentException("Pickup is consumed, expired or not yet created");
        }
    }
    /** Health, absorption, attributes and the LivingEntity life remain in the native entity body, not a partial Mob copy. */
    public record Construct(WorldConstruct.Spawn spawn, long createdAt, long expiresAt, boolean admitted,
            Optional<RuleEngine.Signal> ended) implements Payload {
        public Construct {
            Objects.requireNonNull(spawn); Objects.requireNonNull(ended); nonnegative(createdAt, "construct creation time");
            if (spawn.position().isEmpty() || expiresAt != Math.addExact(createdAt, spawn.parameters().lifetimeMicros()))
                throw new IllegalArgumentException("Construct lifetime differs from its original spawn");
            ended.ifPresent(signal -> {
                if (!admitted || !signal.type().equals(WorldConstruct.ENDED) || !(signal.payload() instanceof EffectEvent event)
                        || !event.source().equals(spawn.origin()) || !event.actor().equals(spawn.origin().owner())
                        || !spawn.kind().equals(event.references().get("construct_kind"))
                        || !event.victim().equals(event.references().get("construct")))
                    throw new IllegalArgumentException("Construct end evidence differs from its spawn");
            });
        }
        @Override public Kind kind() { return Kind.CONSTRUCT; }
        @Override public String dimension() { return spawn.position().orElseThrow().dimension(); }
        @Override public String version() { return spawn.version(); }
        @Override public void requireActive(long capturedMicros) {
            if (!admitted || ended.isPresent() || capturedMicros < createdAt || capturedMicros >= expiresAt)
                throw new IllegalArgumentException("Construct is not live and admitted at capture");
        }
    }
    /** nativeDigest names the host's typed-NBT SHA-256. Its syntax alone does not prove any native bytes or world write. */
    public record Snapshot(Identity identity, long revision, long capturedGameTime, long capturedMicros,
            int chunkX, int chunkZ, String nativeDigest, Payload payload) {
        public Snapshot {
            Objects.requireNonNull(identity); Objects.requireNonNull(payload); nonnegative(revision, "physical revision");
            nonnegative(capturedGameTime, "physical game time"); nonnegative(capturedMicros, "physical logical time");
            if (nativeDigest == null || !nativeDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid native body digest");
            if (identity.kind() != payload.kind() || !identity.domain().equals(payload.dimension()))
                throw new IllegalArgumentException("Physical identity differs from its payload");
            payload.requireActive(capturedMicros);
        }
    }
    /** A retirement watermark carries no launch/spawn body and grants no permission to restore an entity. */
    public record Tombstone(Identity identity, long revision, String reason) {
        public Tombstone {
            Objects.requireNonNull(identity);
            if (revision <= 0 || reason == null || reason.isBlank()) throw new IllegalArgumentException("Invalid physical retirement");
        }
    }
    public record Directory(Map<String, Snapshot> active, Map<String, Tombstone> retired) {
        public static final Directory EMPTY = new Directory(Map.of(), Map.of());
        public Directory {
            active = Collections.unmodifiableMap(new TreeMap<>(active)); retired = Collections.unmodifiableMap(new TreeMap<>(retired));
            for (var entry : active.entrySet())
                if (!entry.getKey().equals(entry.getValue().identity().entity()) || retired.containsKey(entry.getKey()))
                    throw new IllegalArgumentException("Duplicate or mismatched active physical identity");
            for (var entry : retired.entrySet())
                if (!entry.getKey().equals(entry.getValue().identity().entity())) throw new IllegalArgumentException("Mismatched retired physical identity");
        }
        public Directory admit(Snapshot snapshot) {
            var id = snapshot.identity().entity();
            if (snapshot.revision() != 0 || active.containsKey(id) || retired.containsKey(id))
                throw new IllegalArgumentException("Physical admission requires a new identity at revision zero");
            var next = new TreeMap<>(active); next.put(id, snapshot); return new Directory(next, retired);
        }
        public Directory replace(Snapshot snapshot) {
            var id = snapshot.identity().entity(); var previous = active.get(id);
            if (previous == null || !previous.identity().equals(snapshot.identity()) || snapshot.revision() != Math.addExact(previous.revision(), 1)
                    || snapshot.capturedGameTime() < previous.capturedGameTime() || snapshot.capturedMicros() < previous.capturedMicros())
                throw new IllegalArgumentException("Physical replacement changed identity, clock or revision sequence");
            requireProgress(previous.payload(), snapshot.payload());
            var next = new TreeMap<>(active); next.put(id, snapshot); return new Directory(next, retired);
        }
        public Directory retire(Tombstone tombstone) {
            var id = tombstone.identity().entity(); var oldRetired = retired.get(id);
            if (oldRetired != null) {
                if (!oldRetired.equals(tombstone)) throw new IllegalArgumentException("Physical retirement differs from its committed watermark");
                return this;
            }
            var previous = active.get(id);
            if (previous == null || !previous.identity().equals(tombstone.identity()) || tombstone.revision() != Math.addExact(previous.revision(), 1))
                throw new IllegalArgumentException("Physical retirement requires the next revision of a live identity");
            var nextActive = new TreeMap<>(active); nextActive.remove(id);
            var nextRetired = new TreeMap<>(retired); nextRetired.put(id, tombstone); return new Directory(nextActive, nextRetired);
        }
    }
    /**
     * A later save may span many physical revisions, but may neither forget a known UUID nor rewrite its history.
     * This compares persisted manifests; it does not authorize entity writes or garbage collection of retirement watermarks.
     */
    public static void requireSuccessor(Directory previous, Directory next) {
        Objects.requireNonNull(previous); Objects.requireNonNull(next);
        for (var entry : previous.retired().entrySet())
            if (!entry.getValue().equals(next.retired().get(entry.getKey())))
                throw new IllegalArgumentException("Physical successor lost or rewrote a retirement watermark");
        for (var old : previous.active().values()) {
            String id = old.identity().entity(); var retired = next.retired().get(id);
            if (retired != null) {
                if (!old.identity().equals(retired.identity()) || retired.revision() <= old.revision())
                    throw new IllegalArgumentException("Physical successor retirement changed identity or did not advance revision");
                continue;
            }
            var current = next.active().get(id);
            if (current == null || !old.identity().equals(current.identity()) || current.revision() < old.revision()
                    || current.capturedGameTime() < old.capturedGameTime() || current.capturedMicros() < old.capturedMicros())
                throw new IllegalArgumentException("Physical successor lost an active identity or rewound its revision or clock");
            if (current.revision() == old.revision() && !current.equals(old))
                throw new IllegalArgumentException("Unchanged physical revision has different snapshot contents");
            requireProgress(old.payload(), current.payload());
        }
    }
    /** Matches every record to its saved timeline; older archives without persistent identities cannot authorize native restoration. */
    public static void validate(Directory directory, Map<String, EffectSession.Checkpoint> domains,
            Map<String, String> catalogues, Map<String, Long> gameTimes) {
        Objects.requireNonNull(directory); Objects.requireNonNull(domains); Objects.requireNonNull(catalogues); Objects.requireNonNull(gameTimes);
        if (!domains.keySet().equals(catalogues.keySet()) || !domains.keySet().equals(gameTimes.keySet()))
            throw new IllegalArgumentException("Physical validation metadata differs from saved domains");
        gameTimes.values().forEach(time -> nonnegative(time, "saved game time"));
        for (var snapshot : directory.active().values()) {
            var checkpoint = requireIdentity(snapshot.identity(), domains, catalogues);
            if (snapshot.capturedGameTime() > gameTimes.get(snapshot.identity().domain())
                    || snapshot.capturedMicros() > checkpoint.timeline().timeMicros()
                    || !snapshot.payload().version().equals(checkpoint.program().version()))
                throw new IllegalArgumentException("Physical snapshot is newer than its world or belongs to another catalogue version");
            snapshot.payload().requireActive(snapshot.capturedMicros());
        }
        for (var tombstone : directory.retired().values()) requireIdentity(tombstone.identity(), domains, catalogues);
    }
    private static EffectSession.Checkpoint requireIdentity(Identity identity, Map<String, EffectSession.Checkpoint> domains, Map<String, String> catalogues) {
        var checkpoint = domains.get(identity.domain());
        if (checkpoint == null || !identity.catalogue().equals(catalogues.get(identity.domain()))
                || !checkpoint.identity().equals(Optional.of(identity.timeline())))
            throw new IllegalArgumentException("Physical record has no matching saved catalogue and timeline");
        return checkpoint;
    }
    private static void requireProgress(Payload before, Payload after) {
        boolean valid = switch (before) {
            case Projectile old -> after instanceof Projectile next && old.launch().equals(next.launch())
                    && old.launchOwnerKind().equals(next.launchOwnerKind()) && old.ageMicros() <= next.ageMicros() && old.traveled() <= next.traveled()
                    && old.progress().sequence() <= next.progress().sequence() && old.progress().bounces() <= next.progress().bounces()
                    && old.progress().entityContacts() <= next.progress().entityContacts()
                    && old.progress().hits().entrySet().stream().allMatch(hit -> next.progress().hits().getOrDefault(hit.getKey(), 0L) >= hit.getValue());
            case Pickup old -> after instanceof Pickup next && old.spawn().equals(next.spawn()) && old.createdMicros() == next.createdMicros();
            case Construct old -> after instanceof Construct next && old.spawn().equals(next.spawn())
                    && old.createdAt() == next.createdAt() && old.expiresAt() == next.expiresAt();
        };
        if (!valid) throw new IllegalArgumentException("Physical progress rewound or changed original launch/spawn");
    }
    private static void requireWithin(long count, ProjectileFlight.Limit limit, String name) {
        if (limit.maximum().isPresent() && count > limit.maximum().getAsInt()) throw new IllegalArgumentException("Physical " + name + " exceeds captured policy");
    }
    private static void nonnegative(long value, String name) { if (value < 0) throw new IllegalArgumentException("Negative " + name); }
    private static void uuid(String value) {
        Objects.requireNonNull(value);
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Expected canonical physical UUID");
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Expected canonical physical namespaced identifier");
    }
}
