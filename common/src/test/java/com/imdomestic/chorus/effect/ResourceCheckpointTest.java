package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.ResourceAuthorityTest.*;
import static com.imdomestic.chorus.effect.ResourceRoutingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceCheckpointTest {
    @TempDir Path directory;
    static EffectSession session(CompiledEffects program, EffectState state) {
        return ResourceSessionAuthorityTest.session(program, state);
    }
    static EffectState state(EffectSession session) { return session.state().engine().domain(); }
    static Map<String, ResourceSessionAuthority.RestoreBinding> policies(ResourceSessionAuthority.Checkpoint saved) {
        var result = new TreeMap<String, ResourceSessionAuthority.RestoreBinding>();
        saved.domains().forEach((domain, checkpoint) -> {
            var program = new CompiledEffects(checkpoint.program());
            if (checkpoint.resourceRouting()) program = program.withResourceRouting();
            result.put(domain, new ResourceSessionAuthority.RestoreBinding(program, EffectCheckpointTest.clock(), 1,
                    _ -> { throw new AssertionError("Restoration must not replay world actions"); }));
        });
        return result;
    }
    static List<CheckpointArchive.Entry> entries(ResourceSessionAuthority.Checkpoint saved) {
        return saved.domains().entrySet().stream().map(entry -> new CheckpointArchive.Entry("test:catalogue", entry.getKey(),
                entry.getValue().timeline().timeMicros() / 50_000, entry.getValue(), Set.of(), Set.of(), Set.of())).toList();
    }
    static CheckpointArchive archive(ResourceSessionAuthority.Checkpoint saved) { return CheckpointArchive.coordinated(entries(saved), saved); }
    static ResourceSessionAuthority.Checkpoint migrated() {
        var f = ResourceSessionAuthorityTest.fixture(initial(0), empty(50_000));
        f.host().commit(f.host().prepareTransfer(HOLDER, B)); return f.host().checkpoint();
    }
    static EffectSession.Checkpoint replace(EffectSession.Checkpoint saved, EffectState state) {
        var t = saved.timeline();
        return new EffectSession.Checkpoint(saved.program(), new TimelineEngine.Checkpoint<>(t.version(), state,
                state.buffs().timeMicros(), t.nextEvent(), t.nextFrame()), saved.resourceRouting());
    }
    @Test void capturePublishesEverySettledDomainWithoutAdvancingClocksOrEventCounters() {
        var f = ResourceSessionAuthorityTest.fixture(initial(0), empty(50_000));
        f.host().claim("empty-holder", B); var old = f.host().checkpoint();
        f.a().observe(1_000_000, List.of()); f.b().observe(550_000, List.of());
        var a = f.a().state(); var b = f.b().state(); var saved = f.host().checkpoint();
        assertEquals(Set.of(A, B), saved.domains().keySet()); assertEquals(f.a().checkpoint(), saved.domains().get(A));
        assertEquals(f.b().checkpoint(), saved.domains().get(B)); assertSame(a, f.a().state()); assertSame(b, f.b().state());
        assertEquals(.65, saved.directory().owner(ENERGY).account(ENERGY.resource()).value(), 1e-12);
        assertEquals(550_000, saved.directory().owner("empty-holder").timeMicros());
        assertEquals(.4, old.directory().owner(ENERGY).account(ENERGY.resource()).value());
        assertEquals(old.directory().owner(HOLDER).revision() + 1, saved.directory().owner(HOLDER).revision());
        assertEquals(saved, f.host().checkpoint());
        assertThrows(UnsupportedOperationException.class, () -> saved.domains().clear());
    }
    @Test void jointRestorePreservesMigratedBalancesIssuerRefundClaimsAndIndependentClocks() {
        var paid = ResourceAuthorityTest.paid(); var program = program().withResourceRouting();
        var a = session(program, paid.worlds().domain(A)); var b = session(program, paid.worlds().domain(B));
        var host = new ResourceSessionAuthority(paid.worlds().directory(), Map.of(A, new ResourceSessionAuthority.Binding(program, a), B, new ResourceSessionAuthority.Binding(program, b)));
        host.commit(host.prepareTransfer(HOLDER, B)); host.refund(A, paid.handle(), .6);
        var saved = host.checkpoint(); var decoded = CheckpointArchive.decode(archive(saved).encode()).resourceCheckpoint().orElseThrow();
        var restored = ResourceSessionAuthority.restore(decoded, policies(decoded)); var next = restored.authority();
        var left = restored.bindings().get(A).session(); var right = restored.bindings().get(B).session();
        assertNotSame(a, left); assertNotSame(b, right); assertEquals(saved, next.checkpoint());
        next.refund(A, paid.handle(), 1); next.refund(A, paid.handle(), 1);
        assertEquals(2, state(right).resources().get(ENERGY).value()); assertTrue(state(left).resources().isEmpty());
        next.commit(next.prepareTransfer(HOLDER, A)); next.refund(A, paid.handle(), 1);
        assertEquals(2, state(left).resources().get(ENERGY).value()); assertTrue(state(right).resources().isEmpty());
        left.observe(1_000_000, List.of()); right.observe(51_000_000, List.of());
        assertEquals(2, state(left).resources().get(ENERGY).value(), "full account must not regain an already claimed refund");
        assertEquals(1, state(left).retainedCosts().get(paid.handle().id()).receipt().refundClaimed());
        assertEquals(saved, host.checkpoint(), "restored sessions must not mutate the original host");
    }
    @Test void mixedModesEmptyPartitionsAndUnmanagedDomainsSurviveRestoreWithoutInventingEnrollment() {
        var local = program(); var coordinated = local.withResourceRouting();
        var a = session(coordinated, empty(0)); var b = session(local, initial(7)); var c = session(local, empty(9));
        var host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, new ResourceSessionAuthority.Binding(coordinated, a),
                B, new ResourceSessionAuthority.Binding(local, b), C, new ResourceSessionAuthority.Binding(local, c)));
        host.claim("empty-holder", A); var saved = host.checkpoint(); var restored = ResourceSessionAuthority.restore(saved, policies(saved));
        assertEquals(saved, restored.authority().checkpoint()); assertTrue(restored.bindings().get(A).session().resourceRouting());
        assertFalse(restored.bindings().get(B).session().resourceRouting()); assertEquals(Set.of("empty-holder"), saved.directory().holders().keySet());
        restored.authority().commit(restored.authority().prepareTransfer("empty-holder", C));
        assertEquals(C, restored.authority().checkpoint().directory().owner("empty-holder").lease().domain());
        assertEquals(4, state(restored.bindings().get(B).session()).resources().size());
    }
    @Test void restoreRequiresTheExactDomainSetFullCatalogueAndCompilationMode() {
        var saved = migrated(); var expected = policies(saved);
        var missing = new TreeMap<>(expected); missing.remove(A);
        assertThrows(IllegalArgumentException.class, () -> ResourceSessionAuthority.restore(saved, missing));
        var extra = new TreeMap<>(expected); extra.put(C, expected.get(A));
        assertThrows(IllegalArgumentException.class, () -> ResourceSessionAuthority.restore(saved, extra));
        var wrongMode = new TreeMap<>(expected); var p = expected.get(A);
        wrongMode.put(A, new ResourceSessionAuthority.RestoreBinding(p.program().withResourceRouting(), p.clock(), 1, p.world()));
        assertThrows(IllegalArgumentException.class, () -> ResourceSessionAuthority.restore(saved, wrongMode));
        var json = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program().program()).getOrThrow().getAsJsonObject();
        json.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("base_rate", 2);
        var other = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, json).getOrThrow();
        var wrongProgram = new TreeMap<>(expected); wrongProgram.put(A, new ResourceSessionAuthority.RestoreBinding(other, p.clock(), 1, p.world()));
        assertEquals(other.program().version(), p.program().program().version());
        assertThrows(IllegalArgumentException.class, () -> ResourceSessionAuthority.restore(saved, wrongProgram));
        assertEquals(saved, ResourceSessionAuthority.restore(saved, expected).authority().checkpoint());
    }
    @Test void directoryAndSessionsMustAgreeOnEveryOwnerBalanceClockAndCatalogue() {
        var saved = migrated(); var original = ResourceSessionAuthorityTest.fixture(initial(0), empty(50_000)).host().checkpoint();
        assertThrows(IllegalStateException.class, () -> new ResourceSessionAuthority.Checkpoint(original.directory(), saved.domains()));
        var absent = new TreeMap<>(saved.domains()); absent.remove(B);
        assertThrows(IllegalArgumentException.class, () -> new ResourceSessionAuthority.Checkpoint(saved.directory(), absent));
        var replica = new TreeMap<>(saved.domains()); replica.put(A, original.domains().get(A));
        assertThrows(IllegalStateException.class, () -> new ResourceSessionAuthority.Checkpoint(saved.directory(), replica));
        var balance = new TreeMap<>(saved.domains()); var owner = saved.domains().get(B);
        balance.put(B, replace(owner, account(owner.timeline().domain(), HOLDER, "energy", 1, 3)));
        assertThrows(IllegalStateException.class, () -> new ResourceSessionAuthority.Checkpoint(saved.directory(), balance));
        var time = new TreeMap<>(saved.domains()); var t = owner.timeline();
        time.put(B, new EffectSession.Checkpoint(owner.program(), new TimelineEngine.Checkpoint<>(t.version(), t.domain(), t.timeMicros() + 1, t.nextEvent(), t.nextFrame())));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSessionAuthority.Checkpoint(saved.directory(), time));
        var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, owner.program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("base_rate", 2);
        var changed = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow(); var catalogue = new TreeMap<>(saved.domains());
        catalogue.put(B, new EffectSession.Checkpoint(changed.program(), t));
        assertThrows(IllegalArgumentException.class, () -> new ResourceSessionAuthority.Checkpoint(saved.directory(), catalogue));
    }
    @Test void pendingInboxRejectsCheckpointWithoutDrainingOrChangingAnyParticipant() {
        var f = new Fixture(data(rule("go", array(pool("grant_resource", "requested", charge(.1))))));
        f.start("go"); assertTrue(f.sessions.get(B).inboxPending());
        var a = f.sessions.get(A).state(); var b = f.sessions.get(B).state();
        assertThrows(IllegalStateException.class, f.host::checkpoint);
        assertSame(a, f.sessions.get(A).state()); assertSame(b, f.sessions.get(B).state()); assertTrue(f.sessions.get(B).inboxPending());
        assertFalse(f.host.drain(10).pending()); assertEquals(.5, f.host.checkpoint().directory().owner(ENERGY).account("test:energy").value());
    }
    @Test void activeWorldCallbacksAndUnknownOutcomesCannotBeSavedOrReplayed() {
        var f = new Fixture(data(rule("go", array(action("play_cue", "cue", new JsonPrimitive("test:cue"))))));
        var calls = new AtomicInteger(); f.external = _ -> {
            calls.incrementAndGet(); assertThrows(IllegalStateException.class, f.host::checkpoint);
            throw new IllegalStateException("actual world outcome unknown");
        };
        assertThrows(IllegalStateException.class, () -> f.start("go")); var a = f.sessions.get(A).state();
        assertThrows(IllegalStateException.class, f.host::checkpoint); assertSame(a, f.sessions.get(A).state()); assertEquals(1, calls.get());
    }
    @Test void ownerThreadAndAllTimelineIdentitiesAreCheckedAroundCapture() throws Exception {
        var program = program(); var b = session(program, empty(0)); var armed = new AtomicBoolean();
        var clock = new EffectClock((_, _) -> {
            if (armed.getAndSet(false)) b.observe(0, List.of());
            return new EffectClock.Rate(0, List.of());
        });
        var state = account(empty(0), "unmanaged", "custom", 1, 1);
        var a = new EffectSession(program, clock, 1, state, _ -> { throw new AssertionError(); });
        var host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, new ResourceSessionAuthority.Binding(program, a), B, new ResourceSessionAuthority.Binding(program, b)));
        armed.set(true); assertThrows(IllegalStateException.class, host::checkpoint);
        var stable = host.checkpoint(); var error = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { host.checkpoint(); } catch (Throwable failure) { error.set(failure); } });
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, error.get()); assertEquals(stable, host.checkpoint());
    }
    @Test void archiveAttachesExactlyTheCapturedDimensionsIncludingUnmanagedDomains() {
        var saved = migrated(); var entries = new ArrayList<>(entries(saved)); var expected = archive(saved);
        Collections.reverse(entries); assertEquals(expected, CheckpointArchive.coordinated(entries, saved));
        assertEquals(4, expected.encode().get("format").getAsInt()); assertEquals(expected, CheckpointArchive.decode(expected.encode()));
        assertEquals(saved, CheckpointArchive.decode(expected.encode()).resourceCheckpoint().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.coordinated(entries.subList(0, 1), saved));
        var duplicate = new ArrayList<>(entries); duplicate.add(entries.getFirst());
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.coordinated(duplicate, saved));
        var entry = entries.getFirst(); var t = entry.effects().timeline();
        var advancedSequence = new EffectSession.Checkpoint(entry.effects().program(), new TimelineEngine.Checkpoint<>(t.version(), t.domain(), t.timeMicros(), t.nextEvent() + 1, t.nextFrame()));
        entries.set(0, new CheckpointArchive.Entry(entry.program(), entry.dimension(), entry.gameTime(), advancedSequence, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.coordinated(entries, saved));
    }
    @Test void archiveVersionsCannotDropTheDirectoryOrHideItsPresenceAsLegacyData() {
        var program = program().withResourceRouting(); var a = session(program, empty(0));
        var host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(A, new ResourceSessionAuthority.Binding(program, a)));
        var saved = host.checkpoint(); var encoded = archive(saved).encode();
        assertThrows(IllegalArgumentException.class, () -> new CheckpointArchive(entries(saved)));
        var dropped = encoded.deepCopy(); dropped.remove("resource_directory");
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(dropped));
        dropped.addProperty("format", 3); assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(dropped));
        var disguised = encoded.deepCopy(); disguised.addProperty("format", 3);
        assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(disguised));
        for (var version : List.of(new JsonPrimitive("4"), new JsonPrimitive(4.5), new JsonPrimitive(5))) {
            var invalid = encoded.deepCopy(); invalid.add("format", version); assertThrows(IllegalArgumentException.class, () -> CheckpointArchive.decode(invalid));
        }
        var empty = new ResourceSessionAuthority.Checkpoint(ResourceAuthority.Directory.EMPTY, Map.of());
        assertEquals(empty, CheckpointArchive.decode(archive(empty).encode()).resourceCheckpoint().orElseThrow());
        assertTrue(new CheckpointArchive(List.of()).resourceCheckpoint().isEmpty());
    }
    @Test void committedJointSaveRequiresExplicitHostSupportBeforeInvalidatingItsFile() throws Exception {
        var file = directory.resolve("world.json"); var store = new WorldCheckpointStore(file); var saved = migrated();
        var save = store.beginCoordinated().prepare(archive(saved), Set.of("world", "players"));
        save.confirm("world"); assertThrows(IllegalStateException.class, save::commit);
        assertThrows(IllegalStateException.class, store::beginCoordinated);
        save.confirm("players"); save.commit(); var committed = Files.readString(file);
        assertThrows(IllegalStateException.class, store::begin); assertEquals(committed, Files.readString(file));
        var run = new WorldCheckpointStore(file).beginCoordinated();
        assertThrows(IllegalStateException.class, store::beginCoordinated);
        var recovered = run.recovered().orElseThrow().resourceCheckpoint().orElseThrow();
        var restored = ResourceSessionAuthority.restore(recovered, policies(recovered));
        restored.bindings().get(B).session().observe(1_050_000, List.of());
        assertEquals(.65, restored.authority().checkpoint().directory().owner(ENERGY).account("test:energy").value());
        restored.authority().commit(restored.authority().prepareTransfer(HOLDER, A)); var next = restored.authority().checkpoint();
        var second = run.prepare(archive(next), Set.of("world")); second.confirm("world"); second.commit();
        assertEquals(next, new WorldCheckpointStore(file).beginCoordinated().recovered().orElseThrow().resourceCheckpoint().orElseThrow());
    }
    @Test void corruptJointDirectoryNeverFallsBackToLocalRecoveryOrRewritesTheEvidence() throws Exception {
        var file = directory.resolve("world.json"); var store = new WorldCheckpointStore(file);
        var save = store.beginCoordinated().prepare(archive(migrated()), Set.of("world")); save.confirm("world"); save.commit();
        var data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        data.getAsJsonObject("archive").getAsJsonObject("resource_directory").getAsJsonArray("holders").get(0).getAsJsonObject().addProperty("time_micros", "99");
        Files.writeString(file, data.toString()); var before = Files.readString(file);
        assertThrows(RuntimeException.class, store::beginCoordinated); assertThrows(RuntimeException.class, store::begin);
        assertEquals(before, Files.readString(file));
    }
    @Test void individuallyValidDirectoryFromBeforeTransferCannotBeSplicedIntoCommittedDomains() throws Exception {
        var file = directory.resolve("world.json"); var store = new WorldCheckpointStore(file);
        var f = ResourceSessionAuthorityTest.fixture(initial(0), empty(50_000)); var old = f.host().checkpoint();
        f.host().commit(f.host().prepareTransfer(HOLDER, B)); var moved = f.host().checkpoint();
        var save = store.beginCoordinated().prepare(archive(moved), Set.of("world")); save.confirm("world"); save.commit();
        var data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        data.getAsJsonObject("archive").add("resource_directory", ResourceAuthorityCodec.encode(old.directory()));
        assertEquals(old.directory(), ResourceAuthorityCodec.decode(data.getAsJsonObject("archive").get("resource_directory")));
        Files.writeString(file, data.toString()); var before = Files.readString(file);
        assertThrows(IllegalStateException.class, store::beginCoordinated); assertEquals(before, Files.readString(file));
    }
}
