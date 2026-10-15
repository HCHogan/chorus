package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Creation fixes the fact identity; restoring a ledger must not issue a different identity. */
class ShotGroupBeginIdentityTest {
    private static final String A = "test:a", B = "test:b";
    private static final EffectSource SOURCE = EffectTestSupport.source("test:shot");
    private static CompiledEffects program() {
        return EffectTestSupport.compile(JsonParser.parseString("""
            {"version":"shot-begin-identity","bundles":[{"id":"test:shot","rules":[{
              "id":"begin","on":"test:start","do":[{"action":{"type":"chorus:begin_shot",
                "pellets":{"type":"chorus:constant","value":2,"unit":"count"},
                "lifetime":{"type":"chorus:constant","value":1,"unit":"second"}},"as":"shot"}]}]}]}
            """).getAsJsonObject());
    }
    private static RuleEngine.Signal start() {
        return new RuleEngine.Signal("test:start", new EffectEvent("player", "", SOURCE.origin(), Set.of(), Map.of()));
    }
    private static ShotGroups.Handle handle(EffectSession session) {
        var groups = session.state().engine().domain().shotGroups();
        assertEquals(1, groups.size());
        return groups.values().iterator().next().handle();
    }
    private static final class Fixture {
        final CompiledEffects program = program().withResourceRouting();
        final Map<String, EffectSession> sessions = new TreeMap<>();
        ResourceSessionAuthority host;
        int operations;
        Fixture() {
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>();
            for (String domain : List.of(A, B)) {
                var session = new EffectSession(program, EffectCheckpointTest.clock(), 1,
                        EffectState.empty().withSource(SOURCE), request -> execute(domain, request));
                sessions.put(domain, session);
                bindings.put(domain, new ResourceSessionAuthority.Binding(program, session));
            }
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings);
            sessions.values().forEach(session -> session.start(0, start()));
        }
        RuleEngine.ActionResult execute(String domain, RuleEngine.WorldRequest request) {
            assertInstanceOf(ResourceRequests.Request.class, request.command());
            operations++;
            var receipt = host.execute(domain, request);
            var published = new TreeMap<String, Object>();
            sessions.forEach((key, session) -> published.put(key, session.state()));
            assertSame(receipt, host.execute(domain, request));
            sessions.forEach((key, session) -> assertSame(published.get(key), session.state()));
            return receipt;
        }
        void restore() {
            var saved = host.checkpoint();
            var encoded = ResourceCheckpointTest.archive(saved).encode();
            var decoded = CheckpointArchive.decode(JsonParser.parseString(encoded.toString()));
            var policies = new TreeMap<String, ResourceSessionAuthority.RestoreBinding>();
            for (String domain : List.of(A, B)) policies.put(domain, new ResourceSessionAuthority.RestoreBinding(
                    program, EffectCheckpointTest.clock(), 1, request -> execute(domain, request)));
            var restored = ResourceSessionAuthority.restore(decoded.resourceCheckpoint().orElseThrow(), policies);
            host = restored.authority(); sessions.clear();
            restored.bindings().forEach((domain, binding) -> sessions.put(domain, binding.session()));
            assertEquals(saved, host.checkpoint());
        }
    }

    @Test void coordinatedDslDistinguishesSameOperationAndOwnerInDifferentIssuingWorlds() {
        var fixture = new Fixture();
        var a = handle(fixture.sessions.get(A)); var b = handle(fixture.sessions.get(B));
        assertEquals(a.id(), b.id()); assertEquals(a.origin(), b.origin());
        assertEquals(Optional.of(A), a.issuer()); assertEquals(Optional.of(B), b.issuer());
        assertEquals(ShotGroups.ReferenceFormat.ISSUED_V1, a.referenceFormat());
        assertEquals(ShotGroups.ReferenceFormat.ISSUED_V1, b.referenceFormat());
        assertNotEquals(a.reference(), b.reference());
        assertTrue(fixture.operations > 0);
    }

    @Test void jointArchiveRestoresExactLedgerAndExpiryIdentitiesWithoutReissuingBegin() {
        var fixture = new Fixture();
        var a = handle(fixture.sessions.get(A)); var b = handle(fixture.sessions.get(B));
        int operations = fixture.operations;
        fixture.restore();
        assertEquals(operations, fixture.operations);
        assertEquals(a, handle(fixture.sessions.get(A))); assertEquals(b, handle(fixture.sessions.get(B)));
        for (String domain : List.of(A, B)) {
            var state = fixture.sessions.get(domain).state().engine().domain();
            var group = handle(fixture.sessions.get(domain));
            assertEquals(group, state.timers().get(group.timerId()).signal().payload());
            var clock = new com.imdomestic.chorus.effect.buff.BuffStore(group.dueAt(),
                    state.buffs().nextGeneration(), state.buffs().instances());
            // The timeline consumes the due timer before dispatching its expiry fact.
            var expired = ShotGroups.expire(state.cancel(group.timerId()).withBuffs(clock), group);
            assertTrue(expired.state().shotGroups().isEmpty());
            var summary = (ShotGroups.Summary) expired.emitted().getFirst().payload();
            assertEquals(group.reference(), summary.event().references().get("shot"));
        }
    }

    @Test void standaloneDslPreservesItsExistingLocalReferenceConvention() {
        var session = new EffectSession(program(), EffectCheckpointTest.clock(), 1,
                EffectState.empty().withSource(SOURCE), request -> { throw new AssertionError(request); });
        session.start(0, start());
        var group = handle(session);
        assertTrue(group.issuer().isEmpty());
        assertEquals(ShotGroups.ReferenceFormat.LEGACY, group.referenceFormat());
        assertEquals(group.id(), group.reference());
    }
}
