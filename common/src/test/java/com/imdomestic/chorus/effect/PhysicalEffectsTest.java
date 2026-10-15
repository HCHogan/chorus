package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;
import static com.imdomestic.chorus.effect.persistence.PhysicalEffects.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.EffectProgram;
import com.imdomestic.chorus.effect.object.*;
import com.imdomestic.chorus.effect.persistence.PhysicalEffects;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PhysicalEffectsTest {
    static final String DOMAIN = "test:physical", CATALOGUE = "test:catalogue", VERSION = "physical-test";
    static final String PROJECTILE = "00000000-0000-0000-0000-000000000001", PICKUP = "00000000-0000-0000-0000-000000000002";
    static final String CONSTRUCT = "00000000-0000-0000-0000-000000000003", RETIRED = "00000000-0000-0000-0000-000000000004";
    static final String OWNER = "00000000-0000-0000-0000-000000000005", TARGET = "00000000-0000-0000-0000-000000000006";
    static final TimelineIdentity TIMELINE = new TimelineIdentity("00000000-0000-0000-0000-000000000007");
    static final String DIGEST = "0123456789abcdef".repeat(4);
    static final WorldPosition POSITION = new WorldPosition(DOMAIN, -17, 50, 18);
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin(OWNER, "source", "weapon", "ability", Set.of("test:captured"));
    record Fixture(EffectSession.Checkpoint checkpoint, String catalogue, long gameTime, Directory directory) {}
    static Fixture fixture() {
        var program = new EffectProgram(VERSION, List.of(), List.of(), List.of());
        var state = EffectState.empty().withBuffs(new BuffStore(1_000_000, 1, Map.of()));
        var checkpoint = new EffectSession.Checkpoint(program, new TimelineEngine.Checkpoint<>(VERSION, state, 1_000_000, 1, 1), false, Optional.of(TIMELINE));
        var active = new TreeMap<String, Snapshot>();
        for (var value : List.of(snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, projectile()), snapshot(identity(PICKUP, Kind.PICKUP), 0, pickup()),
                snapshot(identity(CONSTRUCT, Kind.CONSTRUCT), 0, construct()))) active.put(value.identity().entity(), value);
        return new Fixture(checkpoint, CATALOGUE, 20, new Directory(active, Map.of(RETIRED, new Tombstone(identity(RETIRED, Kind.PICKUP), 3, "collected"))));
    }
    static Identity identity(String entity, Kind kind) { return new Identity(entity, kind, DOMAIN, TIMELINE, CATALOGUE); }
    static Snapshot snapshot(Identity identity, long revision, Payload payload) { return new Snapshot(identity, revision, 20, 1_000_000, -2, 1, DIGEST, payload); }
    static EffectContinuations.Pending pending() {
        var source = new EffectSource("source", "test:source", OWNER, ORIGIN, Set.of("test:source_tag"));
        return new EffectContinuations.Pending("captured", "test:contact", VERSION, source,
                new RuleEngine.Event(1, 1, Optional.empty(), 9_000_000, new RuleEngine.Signal("test:launch", new EffectEvent(OWNER, "", ORIGIN, Set.of(), Map.of()))),
                Map.of(), Optional.empty());
    }
    static Projectile projectile() {
        var collision = new ProjectileFlight.Collision(new ProjectileFlight.Limit(2), new ProjectileFlight.Limit(3), new ProjectileFlight.Limit(2), .75, new ProjectileFlight.Limit(5));
        var policy = new ProjectileTracking.Policy(20, 90, 180, TargetQuery.Anchor.BODY, TargetQuery.Relation.ANY, false, false);
        var parameters = new ProjectileFlight.Parameters(500, 10, .99, 5_000_000, collision, Optional.of(policy));
        var launch = new ProjectileFlight.Launch(Optional.of(POSITION), Optional.of(new WorldDirection(DOMAIN, 1, 0, 0)), parameters, OWNER, pending(), "impact");
        return new Projectile(launch, 100_000, 4, new ProjectileFlight.Progress(2, 1, 1, Map.of(TARGET, 1L), false), Set.of(TARGET), Optional.of(TARGET), "player");
    }
    static Pickup pickup() {
        return new Pickup(new WorldPickup.Spawn(Optional.of(POSITION), "test:orb", OWNER, ORIGIN,
                new WorldPickup.Parameters(5_000_000, .5, Optional.of(new WorldPickup.Attraction("test:attraction", 10, 5))), pending(), "contact"), 900_000, false);
    }
    static Construct construct() {
        return new Construct(new WorldConstruct.Spawn(Optional.of(POSITION), "test:turret", ORIGIN,
                new WorldConstruct.Parameters(100, .5, 1, 5_000_000), Set.of("test:construct"), VERSION), 900_000, 5_900_000, true, Optional.empty());
    }
    static Projectile copy(Projectile original, long age, double traveled, ProjectileFlight.Progress progress, Set<String> inside, Optional<String> tracking) {
        return new Projectile(original.launch(), age, traveled, progress, inside, tracking, original.launchOwnerKind());
    }
    @Test void allKindsValidateWithoutReinterpretingHistoricalCauseOrForeignIssuerClock() {
        var fixture = fixture();
        assertDoesNotThrow(() -> PhysicalEffects.validate(fixture.directory(), Map.of(DOMAIN, fixture.checkpoint()), Map.of(DOMAIN, CATALOGUE), Map.of(DOMAIN, fixture.gameTime())));
        assertTrue(projectile().launch().continuation().cause().timeMicros() > fixture.checkpoint().timeline().timeMicros());
        assertEquals(Optional.of(TARGET), projectile().trackingTarget(), "redirect=false permits the just-hit target to remain selected until the next tick");
    }
    @Test void admissionReplacementAndRetirementUseExactMonotonicIdentityWithoutResurrection() {
        var start = snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, projectile()); var initial = Directory.EMPTY.admit(start);
        var value = projectile(); var advanced = copy(value, 150_000, 6, value.progress().contact(value.launch().parameters().collision(), ProjectileFlight.End.ENTITY, Optional.of(TARGET), false), Set.of(TARGET), Optional.empty());
        var next = snapshot(start.identity(), 1, advanced); var progressed = initial.replace(next);
        var tombstone = new Tombstone(start.identity(), 2, "caught"); var retired = progressed.retire(tombstone);
        assertSame(retired, retired.retire(tombstone)); assertTrue(retired.active().isEmpty()); assertEquals(tombstone, retired.retired().get(PROJECTILE));
        assertThrows(IllegalArgumentException.class, () -> retired.admit(start));
        assertThrows(IllegalArgumentException.class, () -> retired.replace(next));
        assertThrows(IllegalArgumentException.class, () -> retired.retire(new Tombstone(start.identity(), 2, "expired")));
        assertEquals(value, initial.active().get(PROJECTILE).payload());
        assertThrows(UnsupportedOperationException.class, () -> progressed.active().clear());
    }
    @Test void skippedRevisionDuplicateAdmissionChangedIdentityAndOverflowCannotReplaceOrRetire() {
        var start = snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, projectile()); var directory = Directory.EMPTY.admit(start);
        assertThrows(IllegalArgumentException.class, () -> directory.admit(start));
        for (long revision : List.of(0L, 2L)) {
            assertThrows(IllegalArgumentException.class, () -> directory.replace(snapshot(start.identity(), revision, projectile())));
        }
        var foreign = new Identity(PROJECTILE, Kind.PROJECTILE, DOMAIN, TimelineIdentity.fresh(), CATALOGUE);
        assertThrows(IllegalArgumentException.class, () -> directory.replace(snapshot(foreign, 1, projectile())));
        assertThrows(IllegalArgumentException.class, () -> directory.retire(new Tombstone(foreign, 1, "removed")));
        assertThrows(IllegalArgumentException.class, () -> directory.retire(new Tombstone(start.identity(), 2, "removed")));
        var maximum = new Directory(Map.of(PROJECTILE, snapshot(start.identity(), Long.MAX_VALUE, projectile())), Map.of());
        assertThrows(ArithmeticException.class, () -> maximum.replace(snapshot(start.identity(), Long.MAX_VALUE, projectile())));
        assertThrows(ArithmeticException.class, () -> maximum.retire(new Tombstone(start.identity(), Long.MAX_VALUE, "removed")));
    }
    @Test void progressCannotRewindTravelAgeHitsOrRewriteLaunch() {
        var value = projectile(); var directory = Directory.EMPTY.admit(snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, value));
        var rewind = List.of(copy(value, 50_000, 4, value.progress(), value.inside(), value.trackingTarget()),
                copy(value, 100_000, 3, value.progress(), value.inside(), value.trackingTarget()),
                copy(value, 100_000, 4, ProjectileFlight.Progress.EMPTY, Set.of(), Optional.empty()));
        for (var next : rewind) assertThrows(IllegalArgumentException.class, () -> directory.replace(snapshot(identity(PROJECTILE, Kind.PROJECTILE), 1, next)));
        var launch = value.launch();
        var rewritten = new Projectile(new ProjectileFlight.Launch(launch.position(), launch.direction(), launch.parameters(), "", launch.continuation(), launch.impactSlot()),
                value.ageMicros(), value.traveled(), value.progress(), value.inside(), value.trackingTarget(), value.launchOwnerKind());
        assertThrows(IllegalArgumentException.class, () -> directory.replace(snapshot(identity(PROJECTILE, Kind.PROJECTILE), 1, rewritten)));
    }
    @Test void liveProjectileRequiresReachableCountersAndInsideHistory() {
        var value = projectile();
        assertThrows(IllegalArgumentException.class, () -> copy(value, 100_001, 4, value.progress(), value.inside(), value.trackingTarget()));
        assertThrows(IllegalArgumentException.class, () -> copy(value, 0, 4, value.progress(), value.inside(), value.trackingTarget()));
        assertThrows(IllegalArgumentException.class, () -> copy(value, 100_000, Double.NaN, value.progress(), value.inside(), value.trackingTarget()));
        assertThrows(IllegalArgumentException.class, () -> copy(value, 100_000, 4, value.progress(), Set.of(OWNER), value.trackingTarget()));
        for (var progress : List.of(new ProjectileFlight.Progress(3, 1, 1, Map.of(TARGET, 1L), false),
                new ProjectileFlight.Progress(4, 0, 4, Map.of(TARGET, 2L, OWNER, 2L), false), value.progress().abandon())) {
            var changed = copy(value, 100_000, 4, progress, value.inside(), value.trackingTarget());
            assertThrows(IllegalArgumentException.class, () -> snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, changed));
        }
        assertThrows(IllegalArgumentException.class, () -> snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0,
                copy(value, 5_000_000, 4, value.progress(), value.inside(), value.trackingTarget())));
    }
    @Test void zeroRangeCanExistBeforeItsFirstTickButCannotRemainActiveAfterTravel() {
        var base = projectile().launch(); var parameters = new ProjectileFlight.Parameters(0, 0, 1, 1_000_000, ProjectileFlight.Collision.STOP,
                Optional.empty(), Optional.empty(), OptionalDouble.of(0), Optional.empty());
        var launch = new ProjectileFlight.Launch(base.position(), base.direction(), parameters, "", pending(), "impact");
        assertDoesNotThrow(() -> snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, new Projectile(launch, 0, 0, ProjectileFlight.Progress.EMPTY, Set.of(), Optional.empty(), "unavailable")));
        assertThrows(IllegalArgumentException.class, () -> snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, new Projectile(launch, 50_000, 0, ProjectileFlight.Progress.EMPTY, Set.of(), Optional.empty(), "unavailable")));
        assertThrows(IllegalArgumentException.class, () -> new Projectile(launch, 0, 0, ProjectileFlight.Progress.EMPTY, Set.of(), Optional.of(TARGET), "unavailable"));
    }
    @Test void destinationPreservesCapturedLifeAndOnlyItsOwnTarget() {
        var base = projectile().launch();
        var life = new EntityAnchor.Reference(TARGET, "saved-life", DOMAIN);
        var destination = new ProjectileDestination(TARGET, 90, 1, TargetQuery.Anchor.BODY, false, Optional.empty(), Optional.of(life));
        var parameters = new ProjectileFlight.Parameters(20, 0, 1, 1_000_000, ProjectileFlight.Collision.STOP, Optional.empty(), Optional.of(destination));
        var launch = new ProjectileFlight.Launch(base.position(), base.direction(), parameters, OWNER, pending(), "impact");
        var body = new Projectile(launch, 50_000, 1, ProjectileFlight.Progress.EMPTY, Set.of(), Optional.of(TARGET), "player");
        assertEquals(life, body.launch().parameters().destination().orElseThrow().reference().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new Projectile(launch, 50_000, 1, ProjectileFlight.Progress.EMPTY, Set.of(), Optional.of(OWNER), "player"));
    }
    @Test void pickupClaimAndConstructEndCannotProduceAnActiveSnapshot() {
        var pickup = pickup(); var construct = construct();
        assertThrows(IllegalArgumentException.class, () -> snapshot(identity(PICKUP, Kind.PICKUP), 0, new Pickup(pickup.spawn(), pickup.createdMicros(), true)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(identity(PICKUP, Kind.PICKUP), 0, new Pickup(pickup.spawn(), 1_000_001, false)));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(identity(PICKUP, Kind.PICKUP), 0, 20, 5_900_000, -2, 1, DIGEST, pickup));
        assertThrows(IllegalArgumentException.class, () -> snapshot(identity(CONSTRUCT, Kind.CONSTRUCT), 0, new Construct(construct.spawn(), construct.createdAt(), construct.expiresAt(), false, Optional.empty())));
        var ended = WorldConstruct.ended(construct.spawn(), CONSTRUCT, WorldConstruct.End.DESTROYED, "killed", 100_000);
        assertThrows(IllegalArgumentException.class, () -> snapshot(identity(CONSTRUCT, Kind.CONSTRUCT), 0, new Construct(construct.spawn(), construct.createdAt(), construct.expiresAt(), true, Optional.of(ended))));
        assertThrows(IllegalArgumentException.class, () -> new Construct(construct.spawn(), construct.createdAt(), construct.expiresAt() + 1, true, Optional.empty()));
        assertThrows(ArithmeticException.class, () -> new Construct(construct.spawn(), Long.MAX_VALUE - 1, Long.MAX_VALUE, true, Optional.empty()));
    }
    @Test void mismatchedTypeDomainKeyDigestOrGlobalIdentityIsRejected() {
        var snapshot = snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, projectile());
        assertThrows(IllegalArgumentException.class, () -> snapshot(identity(PROJECTILE, Kind.PICKUP), 0, projectile()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(new Identity(PROJECTILE, Kind.PROJECTILE, "test:other", TIMELINE, CATALOGUE), 0, projectile()));
        assertThrows(IllegalArgumentException.class, () -> new Directory(Map.of(PICKUP, snapshot), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Directory(Map.of(PROJECTILE, snapshot), Map.of(PROJECTILE, new Tombstone(snapshot.identity(), 1, "removed"))));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(snapshot.identity(), 0, 20, 1_000_000, 0, 0, DIGEST.toUpperCase(Locale.ROOT), projectile()));
        assertThrows(IllegalArgumentException.class, () -> identity("1-1-1-1-1", Kind.PROJECTILE));
    }
    @Test void archiveBindingRejectsAbsentOrChangedTimelineCatalogueDomainAndFutureClocks() {
        var fixture = fixture(); var saved = fixture.checkpoint();
        for (var identity : List.of(Optional.<TimelineIdentity>empty(), Optional.of(TimelineIdentity.fresh()))) {
            var foreign = new EffectSession.Checkpoint(saved.program(), saved.timeline(), false, identity);
            assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.validate(fixture.directory(), Map.of(DOMAIN, foreign), Map.of(DOMAIN, CATALOGUE), Map.of(DOMAIN, 20L)));
        }
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.validate(fixture.directory(), Map.of(DOMAIN, saved), Map.of(DOMAIN, "test:other"), Map.of(DOMAIN, 20L)));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.validate(fixture.directory(), Map.of(DOMAIN, saved), Map.of(DOMAIN, CATALOGUE), Map.of(DOMAIN, 19L)));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.validate(fixture.directory(), Map.of(), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.validate(fixture.directory(), Map.of(DOMAIN, saved), Map.of(), Map.of(DOMAIN, 20L)));
        var future = new Snapshot(identity(PROJECTILE, Kind.PROJECTILE), 0, 20, 1_000_001, 0, 0, DIGEST, projectile());
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.validate(Directory.EMPTY.admit(future), Map.of(DOMAIN, saved), Map.of(DOMAIN, CATALOGUE), Map.of(DOMAIN, 20L)));
    }
    @Test void successorCannotEraseAnExistingManifestOrItsRetirementWatermarks() {
        var previous = fixture().directory();
        assertDoesNotThrow(() -> PhysicalEffects.requireSuccessor(previous, previous));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, Directory.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, new Directory(previous.active(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, new Directory(Map.of(), previous.retired())));
        var old = previous.retired().get(RETIRED);
        for (var changed : List.of(new Tombstone(old.identity(), old.revision() + 1, old.reason()),
                new Tombstone(old.identity(), old.revision(), "another-reason"),
                new Tombstone(new Identity(RETIRED, Kind.PICKUP, DOMAIN, TimelineIdentity.fresh(), CATALOGUE), old.revision(), old.reason())))
            assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, new Directory(previous.active(), Map.of(RETIRED, changed))));
        var resurrected = new TreeMap<>(previous.active()); resurrected.put(RETIRED, snapshot(identity(RETIRED, Kind.PICKUP), 9, pickup()));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, new Directory(resurrected, Map.of())));
    }
    @Test void successorAllowsSeveralRevisionsAndNewEffectsThatAlreadyEndedBetweenSaves() {
        var initial = snapshot(identity(PROJECTILE, Kind.PROJECTILE), 2, projectile());
        var previous = new Directory(Map.of(PROJECTILE, initial), Map.of()); var body = projectile();
        var progress = body.progress().contact(body.launch().parameters().collision(), ProjectileFlight.End.ENTITY, Optional.of(TARGET), false);
        var continued = copy(body, 150_000, 8, progress, Set.of(TARGET), Optional.empty());
        var current = new Snapshot(initial.identity(), 17, 25, 1_100_000, 3, -2, "a".repeat(64), continued);
        var next = new Directory(Map.of(PROJECTILE, current, PICKUP, snapshot(identity(PICKUP, Kind.PICKUP), 5, pickup())),
                Map.of(CONSTRUCT, new Tombstone(identity(CONSTRUCT, Kind.CONSTRUCT), 13, "destroyed")));
        assertDoesNotThrow(() -> PhysicalEffects.requireSuccessor(previous, next));
        var retired = new Directory(Map.of(), Map.of(PROJECTILE, new Tombstone(initial.identity(), 30, "caught")));
        assertDoesNotThrow(() -> PhysicalEffects.requireSuccessor(previous, retired));
        assertDoesNotThrow(() -> PhysicalEffects.requireSuccessor(Directory.EMPTY, next));
    }
    @Test void successorRequiresExactSnapshotEqualityWhenRevisionDoesNotAdvance() {
        var old = snapshot(identity(PROJECTILE, Kind.PROJECTILE), 3, projectile()); var previous = new Directory(Map.of(PROJECTILE, old), Map.of());
        var changes = List.of(new Snapshot(old.identity(), 3, 21, old.capturedMicros(), old.chunkX(), old.chunkZ(), old.nativeDigest(), old.payload()),
                new Snapshot(old.identity(), 3, 20, 1_000_001, old.chunkX(), old.chunkZ(), old.nativeDigest(), old.payload()),
                new Snapshot(old.identity(), 3, 20, old.capturedMicros(), 5, old.chunkZ(), old.nativeDigest(), old.payload()),
                new Snapshot(old.identity(), 3, 20, old.capturedMicros(), old.chunkX(), old.chunkZ(), "b".repeat(64), old.payload()),
                snapshot(old.identity(), 3, copy(projectile(), 150_000, 5, projectile().progress(), Set.of(), Optional.empty())));
        for (var changed : changes)
            assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, new Directory(Map.of(PROJECTILE, changed), Map.of())));
    }
    @Test void successorCannotHideRewoundProgressIdentityOrClocksBehindHigherRevision() {
        var old = snapshot(identity(PROJECTILE, Kind.PROJECTILE), 7, projectile()); var previous = new Directory(Map.of(PROJECTILE, old), Map.of());
        var body = projectile();
        var launch = body.launch();
        var rewritten = new Projectile(new ProjectileFlight.Launch(launch.position(), launch.direction(), launch.parameters(), "", launch.continuation(), launch.impactSlot()),
                body.ageMicros(), body.traveled(), body.progress(), body.inside(), body.trackingTarget(), body.launchOwnerKind());
        var changes = List.of(snapshot(old.identity(), 6, body),
                snapshot(new Identity(PROJECTILE, Kind.PROJECTILE, DOMAIN, TimelineIdentity.fresh(), CATALOGUE), 12, body),
                new Snapshot(old.identity(), 12, 19, old.capturedMicros(), old.chunkX(), old.chunkZ(), old.nativeDigest(), body),
                new Snapshot(old.identity(), 12, 20, 999_999, old.chunkX(), old.chunkZ(), old.nativeDigest(), body),
                snapshot(old.identity(), 12, copy(body, 50_000, 4, body.progress(), body.inside(), body.trackingTarget())),
                snapshot(old.identity(), 12, copy(body, 150_000, 3, body.progress(), body.inside(), body.trackingTarget())),
                snapshot(old.identity(), 12, copy(body, 150_000, 5, ProjectileFlight.Progress.EMPTY, Set.of(), Optional.empty())),
                snapshot(old.identity(), 12, rewritten));
        for (var changed : changes)
            assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, new Directory(Map.of(PROJECTILE, changed), Map.of())));
        for (var changed : List.of(new Tombstone(old.identity(), 7, "caught"),
                new Tombstone(new Identity(PROJECTILE, Kind.PROJECTILE, DOMAIN, TimelineIdentity.fresh(), CATALOGUE), 12, "caught")))
            assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(previous, new Directory(Map.of(), Map.of(PROJECTILE, changed))));
    }
    @Test void successorPreservesOriginalPickupAndConstructClocksAcrossSaveIntervals() {
        var initial = fixture().directory(); var pickup = pickup(); var construct = construct();
        for (var changed : List.of(snapshot(identity(PICKUP, Kind.PICKUP), 100, new Pickup(pickup.spawn(), pickup.createdMicros() + 1, false)),
                snapshot(identity(CONSTRUCT, Kind.CONSTRUCT), 100, new Construct(construct.spawn(), construct.createdAt() + 1, construct.expiresAt() + 1, true, Optional.empty())))) {
            var next = new TreeMap<>(initial.active()); next.put(changed.identity().entity(), changed);
            assertThrows(IllegalArgumentException.class, () -> PhysicalEffects.requireSuccessor(initial, new Directory(next, initial.retired())));
        }
    }
}
