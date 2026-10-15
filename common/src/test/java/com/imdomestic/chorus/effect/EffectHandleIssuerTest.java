package com.imdomestic.chorus.effect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.buff.BuffStore;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.projectile.*;
import com.imdomestic.chorus.effect.resource.EffectHandleRequests;
import com.imdomestic.chorus.effect.resource.ResourceAuthority;
import com.imdomestic.chorus.effect.resource.ResourceReadView;
import com.imdomestic.chorus.effect.target.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Issuer qualification addresses a ledger without replacing its original business identity. */
class EffectHandleIssuerTest {
    static final String A = "test:a", B = "test:b";
    static final long DUE = 1_000_000;
    static final BuffInstance.Origin ORIGIN = new BuffInstance.Origin("player", "source", "weapon", "");
    static final Map<String, String> LEGACY_IDS = Map.of(
            "chorus:effect/combat/damage_tallies/handle_v2", "chorus:effect/combat/damage_tallies/handle",
            "chorus:effect/combat/damage_groups/handle_v2", "chorus:effect/combat/damage_groups/handle",
            "chorus:effect/combat/damage_groups/handle_v3", "chorus:effect/combat/damage_groups/handle",
            "chorus:effect/projectile/shot_groups/handle_v2", "chorus:effect/projectile/shot_groups/handle",
            "chorus:effect/target/entity_attachments/handle_v2", "chorus:effect/target/entity_attachments/handle");

    static DamageTallies.Handle tally() { return new DamageTallies.Handle("tally", 0, DUE); }
    static DamageGroups.Handle damageGroup() { return new DamageGroups.Handle("damage-group", "player", 0, DUE); }
    static ShotGroups.Handle shot() { return new ShotGroups.Handle("shot", ORIGIN, 2, 0, DUE); }
    static EntityAttachments.Handle attachment() {
        return EntityAttachmentTest.handle("attachment", 2,
                EntityAttachmentTest.policy(EntityAttachments.DeathPolicy.FOLLOW, EntityAttachments.RemovalPolicy.WAIT));
    }
    static DamageReceipt receipt(String id) {
        return new DamageReceipt(id, DamageReceipt.Outcome.APPLIED, 0, 0, 4, Optional.empty(), false);
    }
    static DamageCommand damage() {
        return new DamageCommand("target", ORIGIN, 4, "minecraft:generic", Set.of(), Set.of(), false);
    }

    @Test void tallyQualificationPreservesEveryOriginalIdentityField() {
        var legacy = tally(); var qualified = legacy.issuedBy(A);
        assertTrue(legacy.issuer().isEmpty()); assertEquals(Optional.of(A), qualified.issuer());
        assertEquals(qualified, qualified.issuedBy(B)); assertNotEquals(legacy, qualified);
        assertTrue(legacy.matches(qualified)); assertTrue(qualified.matches(legacy));
        assertTrue(qualified.matches(legacy.issuedBy(A))); assertFalse(qualified.matches(legacy.issuedBy(B)));
        assertDoesNotThrow(() -> legacy.requireIssuer(B)); assertDoesNotThrow(() -> qualified.requireIssuer(A));
        assertThrows(IllegalArgumentException.class, () -> qualified.requireIssuer(B));
        assertThrows(IllegalArgumentException.class, () -> legacy.issuedBy(" "));
        for (var other : List.of(new DamageTallies.Handle("other", 0, DUE),
                new DamageTallies.Handle(legacy.id(), 1, DUE), new DamageTallies.Handle(legacy.id(), 0, DUE + 1))) {
            assertFalse(qualified.matches(other)); assertFalse(other.matches(qualified));
        }
    }

    @Test void damageGroupQualificationPreservesOwnerAndOriginalDeadline() {
        var legacy = damageGroup(); var qualified = legacy.issuedBy(A);
        assertTrue(legacy.issuer().isEmpty()); assertEquals(Optional.of(A), qualified.issuer());
        assertEquals(qualified, qualified.issuedBy(B)); assertNotEquals(legacy, qualified);
        assertTrue(legacy.matches(qualified)); assertTrue(qualified.matches(legacy));
        assertTrue(qualified.matches(legacy.issuedBy(A))); assertFalse(qualified.matches(legacy.issuedBy(B)));
        assertDoesNotThrow(() -> legacy.requireIssuer(B)); assertDoesNotThrow(() -> qualified.requireIssuer(A));
        assertThrows(IllegalArgumentException.class, () -> qualified.requireIssuer(B));
        assertThrows(IllegalArgumentException.class, () -> legacy.issuedBy(" "));
        assertEquals(legacy.timerId(), qualified.timerId());
        for (var other : List.of(new DamageGroups.Handle("other", "player", 0, DUE),
                new DamageGroups.Handle(legacy.id(), "other", 0, DUE), new DamageGroups.Handle(legacy.id(), "player", 1, DUE),
                new DamageGroups.Handle(legacy.id(), "player", 0, DUE + 1))) {
            assertFalse(qualified.matches(other)); assertFalse(other.matches(qualified));
        }
    }

    @Test void shotQualificationPreservesOriginPelletsAndOriginalDeadline() {
        var legacy = shot(); var qualified = legacy.issuedBy(A);
        assertTrue(legacy.issuer().isEmpty()); assertEquals(Optional.of(A), qualified.issuer());
        assertEquals(qualified, qualified.issuedBy(B)); assertNotEquals(legacy, qualified);
        assertTrue(legacy.matches(qualified)); assertTrue(qualified.matches(legacy));
        assertTrue(qualified.matches(legacy.issuedBy(A))); assertFalse(qualified.matches(legacy.issuedBy(B)));
        assertDoesNotThrow(() -> legacy.requireIssuer(B)); assertDoesNotThrow(() -> qualified.requireIssuer(A));
        assertThrows(IllegalArgumentException.class, () -> qualified.requireIssuer(B));
        assertThrows(IllegalArgumentException.class, () -> legacy.issuedBy(" "));
        assertEquals(legacy.timerId(), qualified.timerId());
        for (var other : List.of(new ShotGroups.Handle("other", ORIGIN, 2, 0, DUE),
                new ShotGroups.Handle(legacy.id(), new BuffInstance.Origin("player", "other", "weapon", ""), 2, 0, DUE),
                new ShotGroups.Handle(legacy.id(), ORIGIN, 3, 0, DUE), new ShotGroups.Handle(legacy.id(), ORIGIN, 2, 1, DUE),
                new ShotGroups.Handle(legacy.id(), ORIGIN, 2, 0, DUE + 1))) {
            assertFalse(qualified.matches(other)); assertFalse(other.matches(qualified));
        }
    }

    @Test void attachmentQualificationPreservesLifeContactAndPolicies() {
        var legacy = attachment(); var qualified = legacy.issuedBy(A);
        assertTrue(legacy.issuer().isEmpty()); assertEquals(Optional.of(A), qualified.issuer());
        assertEquals(qualified, qualified.issuedBy(B)); assertNotEquals(legacy, qualified);
        assertTrue(legacy.matches(qualified)); assertTrue(qualified.matches(legacy));
        assertTrue(qualified.matches(legacy.issuedBy(A))); assertFalse(qualified.matches(legacy.issuedBy(B)));
        assertDoesNotThrow(() -> legacy.requireIssuer(B)); assertDoesNotThrow(() -> qualified.requireIssuer(A));
        assertThrows(IllegalArgumentException.class, () -> qualified.requireIssuer(B));
        assertThrows(IllegalArgumentException.class, () -> legacy.issuedBy(" "));
        assertEquals(legacy.query(), qualified.query());
        for (var other : List.of(
                new EntityAttachments.Handle("other", 0, DUE, legacy.initial(), legacy.contact(), legacy.policy()),
                new EntityAttachments.Handle(legacy.id(), 1, DUE, legacy.initial(), legacy.contact(), legacy.policy()),
                new EntityAttachments.Handle(legacy.id(), 0, DUE + 1, legacy.initial(), legacy.contact(), legacy.policy()),
                new EntityAttachments.Handle(legacy.id(), 0, DUE, EntityAttachmentTest.anchor(3, true), legacy.contact(), legacy.policy()),
                new EntityAttachments.Handle(legacy.id(), 0, DUE, legacy.initial(), EntityAttachmentTest.point(3), legacy.policy()),
                new EntityAttachments.Handle(legacy.id(), 0, DUE, legacy.initial(), legacy.contact(),
                        EntityAttachmentTest.policy(EntityAttachments.DeathPolicy.END, EntityAttachments.RemovalPolicy.WAIT)))) {
            assertFalse(qualified.matches(other)); assertFalse(other.matches(qualified));
        }
    }

    @Test void tallyLegacyAliasReadsRecordsAndClosesWithoutErasingTheCanonicalIssuer() {
        var legacy = tally(); var qualified = legacy.issuedBy(A);
        var initial = DamageTallies.begin(EffectState.empty(), qualified).state();
        assertEquals(DamageTallies.Summary.ZERO, DamageTallies.read(initial, legacy).numbers());
        var recorded = DamageTallies.record(initial, legacy, receipt("hit"));
        assertEquals(qualified, recorded.state().damageTallies().get(legacy.id()).handle());
        assertTrue(((DamageTallies.Result) recorded.result()).changed());
        assertEquals(4, DamageTallies.read(recorded.state(), legacy).numbers().healthLoss());
        var repeated = DamageTallies.record(recorded.state(), legacy, receipt("hit"));
        assertFalse(((DamageTallies.Result) repeated.result()).changed()); assertEquals(recorded.state(), repeated.state());
        var legacyLedger = DamageTallies.begin(EffectState.empty(), legacy).state();
        assertEquals(legacy, DamageTallies.record(legacyLedger, qualified, receipt("legacy-hit"))
                .state().damageTallies().get(legacy.id()).handle());
        for (var wrong : List.of(legacy.issuedBy(B), new DamageTallies.Handle(legacy.id(), 0, DUE + 1))) {
            assertThrows(IllegalArgumentException.class, () -> DamageTallies.read(recorded.state(), wrong));
            assertThrows(IllegalArgumentException.class, () -> DamageTallies.record(recorded.state(), wrong, receipt("other")));
            assertThrows(IllegalArgumentException.class, () -> DamageTallies.close(recorded.state(), wrong));
        }
        var closed = DamageTallies.close(recorded.state(), legacy);
        assertEquals(1, ((DamageTallies.Result) closed.result()).numbers().attempts());
        assertTrue(closed.state().damageTallies().isEmpty());
    }

    @Test void damageGroupLegacyAliasRetainsSharedEntitlementAndClosesTheOriginalTimer() throws Exception {
        var h = new DamageGroupTest.Harness(); h.run("arm");
        var buff = h.state().buffs().instances().values().iterator().next();
        var legacy = damageGroup(); var qualified = legacy.issuedBy(A);
        var begun = DamageGroups.begin(h.state(), qualified).state(); var timer = begun.timers().get(qualified.timerId());
        assertEquals(qualified, DamageGroups.require(begun, legacy).handle());
        var command = damage().withGroup(legacy).withConsumptions(List.of(new BuffConsumption.Candidate(
                buff.key(), buff.generation(), BuffConsumption.When.HIT, 1, Optional.of(buff))));
        var consumed = BuffConsumption.finish(begun, command, receipt("hit"));
        var group = DamageGroups.forCommand(consumed.state(), command).orElseThrow();
        assertEquals(qualified, group.handle()); assertEquals(Map.of(buff.key(), buff), group.grants());
        assertTrue(consumed.state().buffs().instances().isEmpty());
        assertEquals(timer, consumed.state().timers().get(qualified.timerId()));
        assertEquals(qualified, timer.signal().payload());
        for (var wrong : List.of(legacy.issuedBy(B), new DamageGroups.Handle(legacy.id(), "player", 0, DUE + 1))) {
            assertThrows(IllegalArgumentException.class, () -> DamageGroups.require(consumed.state(), wrong));
            assertThrows(IllegalArgumentException.class, () -> DamageGroups.close(consumed.state(), wrong));
        }
        var closed = DamageGroups.close(consumed.state(), legacy).state();
        assertTrue(closed.damageGroups().isEmpty()); assertFalse(closed.timers().containsKey(qualified.timerId()));
    }

    @Test void oldAndQualifiedGroupMembersShareOneReservationInEitherDirection() throws Exception {
        var program = ConsumptionReservationsTest.program();
        var legacy = damageGroup(); var qualified = legacy.issuedBy(A);
        var state = DamageGroups.begin(ConsumptionReservationsTest.armed(program, 3), qualified).state();
        var oldCommand = program.prepareDamage(state, ConsumptionReservationsTest.attack().withGroup(legacy));
        var view = new ResourceReadView(new ResourceAuthority.Worlds(ResourceAuthority.Directory.EMPTY, Map.of(A, state)), A, program.program());
        var newCommand = program.prepareDamage(state, ConsumptionReservationsTest.attack().withGroup(qualified), Optional.of(view));
        assertEquals(1, oldCommand.consumptions().size());
        assertTrue(oldCommand.consumptions().getFirst().shared().isPresent());
        assertEquals(3, ConsumptionReservationsTest.count(ConsumptionReservations.view(state, oldCommand, "incoming-old",
                List.of(new ConsumptionReservations.Claim("pending-qualified", newCommand)))));
        assertEquals(3, ConsumptionReservationsTest.count(ConsumptionReservations.view(state, newCommand, "incoming-qualified",
                List.of(new ConsumptionReservations.Claim("pending-old", oldCommand)))));
        var claims = List.of(new ConsumptionReservations.Claim("pending-old", oldCommand),
                new ConsumptionReservations.Claim("pending-qualified", newCommand));
        var independent = new DamageGroups.Handle("independent", "player", 0, DUE).issuedBy(A);
        var withIndependent = DamageGroups.begin(state, independent).state();
        assertEquals(2, ConsumptionReservationsTest.count(ConsumptionReservations.view(withIndependent,
                ConsumptionReservationsTest.attack().withGroup(independent), "independent", claims)));
        assertEquals(3, ConsumptionReservationsTest.count(state));
        var confirmed = BuffConsumption.finish(state, oldCommand, receipt("confirmed")).state();
        assertEquals(qualified, confirmed.damageGroups().get(legacy.id()).handle());
        assertEquals(2, ConsumptionReservationsTest.count(ConsumptionReservations.view(confirmed,
                ConsumptionReservationsTest.attack(), "after-confirmation", claims)));
    }

    @Test void shotLegacyMemberCountsAndClosesContactsWithoutErasingIssuerOrReplayingProgress() {
        var legacy = shot(); var qualified = legacy.issuedBy(A); var member = new ShotGroups.Member(legacy, 0);
        var initial = ShotGroups.begin(EffectState.empty(), qualified).state();
        var timer = initial.timers().get(qualified.timerId());
        assertTrue(ShotGroups.active(initial, member));
        var reserved = ShotGroups.reserve(initial, member);
        assertEquals(qualified, reserved.shotGroups().get(legacy.id()).handle());
        assertThrows(IllegalArgumentException.class, () -> ShotGroups.reserve(reserved, member));
        var contact = new ProjectileFlight.Impact(ProjectileFlight.End.ENTITY, EntityAttachmentTest.point(2), Optional.of("target"),
                0, 0, 0, 0, 1, 0, 1, 1, true, Optional.of(member))
                .withGeometry(new ProjectileFlight.Geometry(EntityAttachmentTest.point(2), 1, 2, 1, 0));
        var query = new Hitscan.Query(Optional.of(EntityAttachmentTest.point(1)), Optional.of(new WorldDirection("world", 1, 0, 0)),
                10, 0, ORIGIN.owner(), ORIGIN.owner(), Optional.of(member));
        var opened = ShotGroups.traced(reserved, new Hitscan.Result(query, Hitscan.Outcome.ENTITY, Optional.of(contact))).state();
        ShotGroups.validateDamage(opened, contact, damage());
        var counted = ShotGroups.damage(opened, contact, damage(), receipt("hit"));
        assertEquals(qualified, counted.shotGroups().get(legacy.id()).handle());
        var progress = ShotGroups.progress(opened, counted, member);
        assertEquals(1, progress.size());
        assertEquals(qualified, ((ShotGroups.Progress) progress.getFirst().payload()).after().shot());
        var repeated = ShotGroups.damage(counted, contact, damage(), receipt("hit"));
        assertEquals(counted, repeated); assertTrue(ShotGroups.progress(counted, repeated, member).isEmpty());
        assertEquals(timer, counted.timers().get(qualified.timerId()));
        for (var wrong : List.of(legacy.issuedBy(B), new ShotGroups.Handle(legacy.id(), ORIGIN, 3, 0, DUE))) {
            assertThrows(IllegalArgumentException.class, () -> ShotGroups.active(counted, new ShotGroups.Member(wrong, 0)));
            assertThrows(IllegalArgumentException.class, () -> ShotGroups.close(counted, contact.withMember(Optional.of(new ShotGroups.Member(wrong, 0)))));
        }
        var closed = ShotGroups.close(counted, contact).state();
        assertEquals(qualified, closed.shotGroups().get(legacy.id()).handle());
        assertEquals(ShotGroups.Phase.CLOSED, closed.shotGroups().get(legacy.id()).pellets().get(0).phase());
        assertFalse(ShotGroups.accepts(closed, contact));
        assertThrows(IllegalArgumentException.class, () -> ShotGroups.close(closed, contact));
    }

    @Test void shotLegacyExpiryUsesTheOriginalClockAndPublishesOneCanonicalSummary() {
        var legacy = shot(); var qualified = legacy.issuedBy(A);
        var initial = ShotGroups.begin(EffectState.empty(), qualified).state();
        assertEquals(DUE, initial.timers().get(legacy.timerId()).dueAt());
        assertEquals(qualified, initial.timers().get(legacy.timerId()).signal().payload());
        assertThrows(IllegalArgumentException.class, () -> ShotGroups.expire(initial, legacy));
        var due = initial.cancel(legacy.timerId()).withBuffs(new BuffStore(DUE, 1, Map.of()));
        assertFalse(ShotGroups.active(due, new ShotGroups.Member(legacy, 0)));
        assertThrows(IllegalArgumentException.class, () -> ShotGroups.expire(due, legacy.issuedBy(B)));
        var expired = ShotGroups.expire(due, legacy);
        assertTrue(expired.state().shotGroups().isEmpty()); assertTrue(expired.state().timers().isEmpty());
        assertEquals(1, expired.emitted().size()); assertEquals(ShotGroups.RESOLVED, expired.emitted().getFirst().type());
        var summary = (ShotGroups.Summary) expired.emitted().getFirst().payload();
        assertEquals(qualified, summary.shot()); assertFalse(summary.complete());
        assertTrue(ShotGroups.expire(expired.state(), legacy).emitted().isEmpty());
    }

    @Test void attachmentLegacyAliasKeepsCanonicalQueriesAndCannotBypassTheIssuedRevision() {
        var legacy = attachment(); var qualified = legacy.issuedBy(A);
        var initial = EntityAttachments.begin(EffectState.empty(), qualified).state();
        var query = EntityAttachmentTest.query(initial, legacy);
        assertEquals(qualified, query.expected().handle()); assertEquals(0, query.expected().revision());
        var observed = EntityAttachments.resolved(initial, query, EntityAttachmentTest.observed(query, 10, true));
        var current = observed.state().attachments().get(legacy.id());
        assertEquals(qualified, current.handle()); assertEquals(1, current.revision());
        assertEquals(EntityAttachmentTest.point(11), ((EntityAttachments.Resolution) observed.result()).position().orElseThrow());

        var old = query.expected();
        var aliasQuery = new EntityAttachments.Query(new EntityAttachments.Entry(legacy, old.phase(), old.last(), old.revision(), old.reason()));
        var stale = EntityAttachments.resolved(observed.state(), aliasQuery, EntityAttachmentTest.observed(aliasQuery, 99, true));
        assertEquals(observed.state(), stale.state());
        assertEquals(EntityAttachments.Outcome.MISSING, ((EntityAttachments.Resolution) stale.result()).outcome());
        assertTrue(((EntityAttachments.Resolution) stale.result()).position().isEmpty());
        assertEquals(EntityAttachmentTest.anchor(10, true), stale.state().attachments().get(legacy.id()).last());

        var fresh = EntityAttachmentTest.query(stale.state(), legacy);
        assertEquals(qualified, fresh.expected().handle()); assertEquals(1, fresh.expected().revision());
        var next = EntityAttachments.resolved(stale.state(), fresh, EntityAttachmentTest.observed(fresh, 20, true));
        assertEquals(qualified, next.state().attachments().get(legacy.id()).handle());
        assertEquals(2, next.state().attachments().get(legacy.id()).revision());
        for (var wrong : List.of(legacy.issuedBy(B), new EntityAttachments.Handle(legacy.id(), 0, DUE + 1,
                legacy.initial(), legacy.contact(), legacy.policy()))) {
            assertThrows(IllegalArgumentException.class, () -> EntityAttachments.resolve(next.state(), wrong));
            assertThrows(IllegalArgumentException.class, () -> EntityAttachments.close(next.state(), wrong));
        }
        assertTrue(EntityAttachments.close(next.state(), legacy).state().attachments().isEmpty());
    }

    @Test void versionedHandlesAndNestedLedgerEvidenceRoundTripAndMigrateExactOldShapes() {
        var codec = EffectCheckpointCodec.DATA;
        assertHandleWire(tally().issuedBy(A), tally(), DamageTallies.Handle.class, "chorus:effect/combat/damage_tallies/handle_v2");
        assertHandleWire(damageGroup().issuedBy(A), damageGroup(), DamageGroups.Handle.class, "chorus:effect/combat/damage_groups/handle_v3");
        assertHandleWire(shot().issuedBy(A), shot(), ShotGroups.Handle.class, "chorus:effect/projectile/shot_groups/handle_v2");
        assertHandleWire(attachment().issuedBy(A), attachment(), EntityAttachments.Handle.class, "chorus:effect/target/entity_attachments/handle_v2");

        var current = ledgers(true); var legacy = ledgers(false);
        assertEquals(current, codec.decode(codec.encode(current), EffectState.class));
        assertEquals(current, codec.decode(damageGroupsV2(codec.encode(current)), EffectState.class));
        assertEquals(legacy, codec.decode(legacyHandles(codec.encode(current)), EffectState.class));
        var member = new ShotGroups.Member(shot().issuedBy(A), 1);
        assertEquals(member, codec.decode(codec.encode(member), ShotGroups.Member.class));
        assertEquals(new ShotGroups.Member(shot(), 1), codec.decode(legacyHandles(codec.encode(member)), ShotGroups.Member.class));
        var query = EntityAttachmentTest.query(current, attachment());
        assertEquals(query, codec.decode(codec.encode(query), EntityAttachments.Query.class));
        assertEquals(EntityAttachmentTest.query(legacy, attachment()),
                codec.decode(legacyHandles(codec.encode(query)), EntityAttachments.Query.class));
    }

    @Test void oldDamageGroupWireShapesKeepTheirExactFieldsAndLegacyReferences() {
        var codec = EffectCheckpointCodec.DATA;
        var oldFields = List.of("id:java.lang.String", "owner:java.lang.String", "startedAt:long", "dueAt:long");
        assertEquals(oldFields, codec.schema().get("chorus:effect/combat/damage_groups/handle"));
        var qualifiedFields = new ArrayList<>(oldFields); qualifiedFields.add("issuer:java.util.Optional<java.lang.String>");
        assertEquals(qualifiedFields, codec.schema().get("chorus:effect/combat/damage_groups/handle_v2"));
        var currentFields = new ArrayList<>(qualifiedFields); currentFields.add("referenceFormat:com.imdomestic.chorus.effect.combat.DamageGroups$ReferenceFormat");
        assertEquals(currentFields, codec.schema().get("chorus:effect/combat/damage_groups/handle_v3"));
        assertEquals(List.of("LEGACY", "ISSUED_V1"), codec.schema().get("chorus:effect/combat/damage_groups/reference_format"));
        for (var original : List.of(damageGroup(), damageGroup().issuedBy(A))) {
            var v3 = codec.encode(original);
            for (var wire : List.of(legacyHandles(v3), damageGroupsV2(v3))) {
                var object = wire.getAsJsonObject(); var v1 = object.get("$").getAsString().endsWith("/handle");
                assertEquals(v1 ? Set.of("id", "owner", "startedAt", "dueAt") : Set.of("id", "owner", "startedAt", "dueAt", "issuer"), object.getAsJsonObject("v").keySet());
                var restored = codec.decode(wire, DamageGroups.Handle.class);
                assertEquals(v1 ? damageGroup() : original, restored);
                assertEquals(DamageGroups.ReferenceFormat.LEGACY, restored.referenceFormat());
                assertEquals("group/6:player/damage-group", restored.reference());
                var moved = restored.issuedBy(A).issuedBy(B);
                assertEquals(Optional.of(A), moved.issuer()); assertEquals(restored.reference(), moved.reference());
                var savedAgain = codec.encode(moved);
                assertEquals("chorus:effect/combat/damage_groups/handle_v3", savedAgain.getAsJsonObject().get("$").getAsString());
                assertEquals(moved, codec.decode(savedAgain, DamageGroups.Handle.class));
                assertEquals(restored.reference(), codec.decode(savedAgain, DamageGroups.Handle.class).reference());
                var extra = object.deepCopy(); extra.getAsJsonObject("v").add("referenceFormat", codec.encode(DamageGroups.ReferenceFormat.ISSUED_V1));
                assertThrows(IllegalArgumentException.class, () -> codec.decode(extra, DamageGroups.Handle.class));
                for (String field : List.copyOf(object.getAsJsonObject("v").keySet())) {
                    var missing = object.deepCopy(); missing.getAsJsonObject("v").remove(field);
                    assertThrows(IllegalArgumentException.class, () -> codec.decode(missing, DamageGroups.Handle.class), field);
                }
            }
        }
    }

    @Test void newDamageGroupReferencesRoundTripWithoutAliasingMigratedLegacyIdentity() {
        var codec = EffectCheckpointCodec.DATA; var old = damageGroup().issuedBy(A);
        var fresh = DamageGroups.Handle.issued(old.id(), old.owner(), old.startedAt(), old.dueAt(), A);
        assertEquals(DamageGroups.ReferenceFormat.ISSUED_V1, fresh.referenceFormat());
        assertNotEquals(old.reference(), fresh.reference()); assertFalse(old.matches(fresh)); assertFalse(fresh.matches(old));
        assertEquals(fresh, codec.decode(codec.encode(fresh), DamageGroups.Handle.class));
        assertEquals(old, codec.decode(damageGroupsV2(codec.encode(fresh)), DamageGroups.Handle.class));
        assertEquals(damageGroup(), codec.decode(legacyHandles(codec.encode(fresh)), DamageGroups.Handle.class));
        var unknownFormat = codec.encode(fresh).getAsJsonObject();
        unknownFormat.getAsJsonObject("v").getAsJsonObject("referenceFormat").addProperty("v", "FUTURE");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknownFormat, DamageGroups.Handle.class));
        var absentFormat = codec.encode(fresh).getAsJsonObject(); absentFormat.getAsJsonObject("v").remove("referenceFormat");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(absentFormat, DamageGroups.Handle.class));
    }

    @Test void nestedVersionTwoGroupsUpgradeLedgerTimerCommandsAndProtocolEvidenceTogether() {
        var codec = EffectCheckpointCodec.DATA; var old = damageGroup().issuedBy(A);
        var fresh = DamageGroups.Handle.issued(old.id(), old.owner(), old.startedAt(), old.dueAt(), A);
        var currentState = DamageGroups.begin(EffectState.empty(), fresh).state();
        var legacyState = DamageGroups.begin(EffectState.empty(), old).state();
        assertEquals(legacyState, codec.decode(damageGroupsV2(codec.encode(currentState)), EffectState.class));
        var currentBegin = new EffectHandleRequests.GroupBegin(A, fresh); var oldBegin = new EffectHandleRequests.GroupBegin(A, old);
        var currentClose = new EffectHandleRequests.GroupClose(A, fresh); var oldClose = new EffectHandleRequests.GroupClose(A, old);
        var current = List.of(damage().withGroup(fresh), currentBegin, currentClose,
                new EffectHandleRequests.GroupStarted(currentBegin, fresh), new EffectHandleRequests.GroupClosed(currentClose));
        var legacy = List.of(damage().withGroup(old), oldBegin, oldClose,
                new EffectHandleRequests.GroupStarted(oldBegin, old), new EffectHandleRequests.GroupClosed(oldClose));
        for (int i = 0; i < current.size(); i++) {
            var restored = codec.decode(damageGroupsV2(codec.encode(current.get(i))), current.get(i).getClass());
            assertEquals(legacy.get(i), restored); assertEquals(restored, codec.decode(codec.encode(restored), restored.getClass()));
        }
        assertEquals(currentState, codec.decode(codec.encode(currentState), EffectState.class));
    }

    @Test void tallyProtocolValuesKeepTheirQualifiedHandlesAcrossCodecRoundTrips() {
        var codec = EffectCheckpointCodec.DATA; var qualified = tally().issuedBy(A);
        var begin = new EffectHandleRequests.TallyBegin(A, qualified);
        var record = new EffectHandleRequests.TallyRecord(A, qualified, receipt("recorded"));
        var read = new EffectHandleRequests.TallyRead(A, qualified);
        var close = new EffectHandleRequests.TallyClose(A, qualified);
        var started = new EffectHandleRequests.TallyStarted(begin, qualified);
        var result = new EffectHandleRequests.TallyResult(read, new DamageTallies.Result(Optional.of(DamageTallies.Summary.ZERO), false));
        for (var value : List.of(begin, record, read, close, started, result))
            assertEquals(value, codec.decode(codec.encode(value), value.getClass()));
        assertThrows(IllegalArgumentException.class, () -> new EffectHandleRequests.TallyBegin(A, tally()));
        assertThrows(IllegalArgumentException.class, () -> new EffectHandleRequests.TallyBegin(B, qualified));
        assertThrows(IllegalArgumentException.class, () -> new EffectHandleRequests.TallyRecord(B, qualified, receipt("wrong")));
        assertThrows(IllegalArgumentException.class, () -> new EffectHandleRequests.TallyRead(B, qualified));
        assertThrows(IllegalArgumentException.class, () -> new EffectHandleRequests.TallyClose(B, qualified));
        assertThrows(IllegalArgumentException.class, () -> new EffectHandleRequests.TallyStarted(begin, tally()));
        assertThrows(IllegalArgumentException.class, () -> new EffectHandleRequests.TallyResult(read,
                new DamageTallies.Result(Optional.of(DamageTallies.Summary.ZERO), true)));
    }

    static EffectState ledgers(boolean qualified) {
        var state = DamageTallies.begin(EffectState.empty(), qualified ? tally().issuedBy(A) : tally()).state();
        state = DamageTallies.record(state, tally(), receipt("stored-hit")).state();
        state = DamageGroups.begin(state, qualified ? damageGroup().issuedBy(A) : damageGroup()).state();
        state = ShotGroups.begin(state, qualified ? shot().issuedBy(A) : shot()).state();
        state = ShotGroups.reserve(state, new ShotGroups.Member(shot(), 0));
        return EntityAttachments.begin(state, qualified ? attachment().issuedBy(A) : attachment()).state();
    }

    static <T> void assertHandleWire(T current, T legacy, Class<T> type, String storageId) {
        var codec = EffectCheckpointCodec.DATA; var wire = codec.encode(current);
        assertEquals(storageId, wire.getAsJsonObject().get("$").getAsString());
        assertEquals(current, codec.decode(wire, type));
        assertEquals(legacy, codec.decode(legacyHandles(wire), type));
    }

    /** Downgrade only the four known Handle shapes, including their occurrences in typed evidence. */
    static JsonElement legacyHandles(JsonElement encoded) {
        var copy = encoded.deepCopy(); eraseQualification(copy); return copy;
    }
    static void eraseQualification(JsonElement value) {
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(EffectHandleIssuerTest::eraseQualification); return; }
        if (!value.isJsonObject()) return;
        var object = value.getAsJsonObject(); var type = object.get("$");
        if (type != null && LEGACY_IDS.containsKey(type.getAsString())) {
            object.addProperty("$", LEGACY_IDS.get(type.getAsString())); object.getAsJsonObject("v").remove("issuer");
            object.getAsJsonObject("v").remove("referenceFormat");
        }
        object.entrySet().forEach(entry -> eraseQualification(entry.getValue()));
    }

    /** Exact previous five-field group shape, preserving issuer even inside timers and protocol values. */
    static JsonElement damageGroupsV2(JsonElement encoded) {
        var copy = encoded.deepCopy(); eraseReferenceFormat(copy); return copy;
    }
    static void eraseReferenceFormat(JsonElement value) {
        if (value.isJsonArray()) { value.getAsJsonArray().forEach(EffectHandleIssuerTest::eraseReferenceFormat); return; }
        if (!value.isJsonObject()) return;
        var object = value.getAsJsonObject(); var type = object.get("$");
        if (type != null && type.getAsString().equals("chorus:effect/combat/damage_groups/handle_v3")) {
            object.addProperty("$", "chorus:effect/combat/damage_groups/handle_v2"); object.getAsJsonObject("v").remove("referenceFormat");
        }
        object.entrySet().forEach(entry -> eraseReferenceFormat(entry.getValue()));
    }
}
