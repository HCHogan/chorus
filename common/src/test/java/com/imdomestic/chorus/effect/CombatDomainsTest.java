package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CombatDomainsTest {
    static final String A = "test:a", B = "test:b", C = "test:c", HOLDER = "player", VICTIM = "target";
    static final EffectSource SOURCE = source("test:control");
    static final DamageGroups.Handle LEGACY = new DamageGroups.Handle("same-id", HOLDER, 0, 1_000_000);

    static final class Fixture {
        final CompiledEffects program;
        final ResourceAuthority.Worlds worlds;
        Fixture() throws Exception { this(B, true); }
        Fixture(String owner, boolean managed) throws Exception {
            var data = json("damage_group");
            data.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("max_stacks", 3);
            program = compile(data);
            var domains = new TreeMap<String, EffectState>();
            for (String domain : List.of(A, B, C)) {
                var state = EffectState.empty().withSource(SOURCE);
                state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:next_hit"), HOLDER, HOLDER, SOURCE.origin(), 3, 1, 3_000_000).store());
                domains.put(domain, DamageGroups.begin(state, LEGACY.issuedBy(domain)).state());
            }
            var directory = ResourceAuthority.Directory.EMPTY;
            if (managed) {
                directory = ResourceAuthority.claim(directory, HOLDER, owner, program, domains.get(owner));
                directory = ResourceAuthority.claim(directory, VICTIM, C, program, domains.get(C));
            }
            worlds = new ResourceAuthority.Worlds(directory, domains);
        }
        DamageCommand shared(String issuer) { return command(buff(worlds, B), BuffConsumption.When.HIT, Optional.of(LEGACY.issuedBy(issuer))); }
    }

    static DamageCommand attack() {
        return new DamageCommand(VICTIM, SOURCE.origin(), 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false);
    }
    static DamageCommand command(BuffInstance buff, BuffConsumption.When when, Optional<DamageGroups.Handle> group) {
        var command = group.map(attack()::withGroup).orElseGet(CombatDomainsTest::attack);
        return command.withConsumptions(List.of(new BuffConsumption.Candidate(buff.key(), buff.generation(), when, 1, group.map(_ -> buff))));
    }
    static BuffInstance buff(ResourceAuthority.Worlds worlds, String domain) {
        return worlds.domain(domain).buffs().instances().values().iterator().next();
    }
    static int count(ResourceAuthority.Worlds worlds, String domain) {
        return worlds.domain(domain).buffs().instances().values().stream().mapToInt(BuffInstance::count).sum();
    }
    static DamageGroups.Group group(ResourceAuthority.Worlds worlds, String domain) { return worlds.domain(domain).damageGroups().get(LEGACY.id()); }
    static DamageReceipt receipt(String id) { return receipt(id, DamageReceipt.Outcome.APPLIED, 0, 1); }
    static DamageReceipt receipt(String id, DamageReceipt.Outcome outcome, double absorption, double health) {
        return new DamageReceipt(id, outcome, 0, absorption, health, Optional.empty(), false);
    }
    @Test void receiptObservesTheOwnersBuffBeforeConsumptionUsingItsOwnClock() throws Exception {
        var f = new Fixture();
        var worlds = at(at(f.worlds, B, 100_000), C, 200_000);
        var current = buff(worlds, B);
        var command = command(current, BuffConsumption.When.HIT, Optional.empty());
        var result = CombatDomains.finish(worlds, C, command, receipt("observed"));
        var observation = result.receipt().observedBuffs().orElseThrow();
        assertEquals(200_000, observation.timeMicros());
        assertEquals(3, observation.require(HOLDER).getFirst().stacks());
        assertEquals(current.generation(), observation.require(HOLDER).getFirst().generation());
        assertEquals(2, count(result.worlds(), B));
        assertEquals(List.of(), observation.require(VICTIM));
        var expired = at(worlds, B, 3_000_001);
        assertEquals(List.of(), CombatDomains.observe(expired, C, command, receipt("later"))
                .observedBuffs().orElseThrow().require(HOLDER));
    }
    @Test void existingNativeObservationsAndSettledReceiptsRemainUnchanged() throws Exception {
        var f = new Fixture(); var command = f.shared(A);
        var captured = new BuffObservation(17, Map.of(HOLDER, List.of()));
        var raw = receipt("native").withObservedBuffs(captured);
        assertSame(raw, CombatDomains.observe(f.worlds, C, command, raw));
        var result = CombatDomains.finish(f.worlds, C, command, raw);
        assertSame(captured, result.receipt().observedBuffs().orElseThrow());
        var settled = receipt("already-settled").withConsumptionFacts(List.of());
        assertSame(settled, CombatDomains.observe(f.worlds, C, command, settled));
        assertSame(settled, CombatDomains.finish(f.worlds, C, command, settled).receipt());
    }
    static ResourceAuthority.Worlds replace(ResourceAuthority.Worlds worlds, String domain, EffectState state) {
        return ResourceAuthority.publish(worlds, domain, worlds.domain(domain), state);
    }
    static ResourceAuthority.Worlds at(ResourceAuthority.Worlds worlds, String domain, long time) {
        var state = worlds.domain(domain);
        for (var timer : List.copyOf(state.timers().values())) if (timer.dueAt() <= time) state = state.cancel(timer.id());
        var store = state.buffs();
        return replace(worlds, domain, state.withBuffs(new BuffStore(time, store.nextGeneration(), store.instances())));
    }
    static void assertOwnerFacts(CombatDomains.Finished result, String owner) {
        assertEquals(Set.of(owner), result.factsByDomain().keySet());
        assertFalse(result.factsByDomain().get(owner).isEmpty());
        for (var signal : result.factsByDomain().get(owner)) {
            var change = assertInstanceOf(Buffs.Change.class, signal.payload());
            assertEquals(HOLDER, change.instance().key().holder());
            assertEquals(Buffs.Reason.CONSUMED, change.reason());
        }
        assertEquals(Optional.of(List.of()), result.receipt().consumptionFacts());
    }

    @Test void issuerOwnerAndVictimUseThreeDomainsWithoutTouchingSameIdNeighbors() throws Exception {
        var f = new Fixture(); var command = f.shared(A); var before = f.worlds;
        var finished = CombatDomains.finish(before, C, command, receipt("hit")); var after = finished.worlds();
        assertEquals(List.of(3, 2, 3), List.of(count(after, A), count(after, B), count(after, C)));
        assertEquals(Map.of(buff(before, B).key(), buff(before, B)), group(after, A).grants());
        assertEquals(group(before, B), group(after, B)); assertSame(before.domain(C), after.domain(C));
        assertEquals(before.domain(A).withDamageGroup(group(after, A)), after.domain(A));
        assertEquals(before.domain(B).withBuffs(after.domain(B).buffs()), after.domain(B));
        assertSame(before.directory(), after.directory()); assertEquals(3, count(before, B));
        assertTrue(group(before, A).grants().isEmpty()); assertOwnerFacts(finished, B);
    }

    @Test void settledReceiptCannotConsumeAgainOrRegenerateAnyDomainFacts() throws Exception {
        var f = new Fixture(); var command = command(buff(f.worlds, B), BuffConsumption.When.HIT, Optional.empty());
        var first = CombatDomains.finish(f.worlds, C, command, receipt("independent"));
        var replay = CombatDomains.finish(first.worlds(), C, command, first.receipt());
        assertSame(first.worlds(), replay.worlds()); assertSame(first.receipt(), replay.receipt());
        assertEquals(2, count(replay.worlds(), B)); assertTrue(replay.factsByDomain().isEmpty());
    }

    @Test void sharedMembersConsumeOnceAndRetainTheOriginalEligibility() throws Exception {
        var f = new Fixture(); var command = f.shared(A);
        var first = CombatDomains.finish(f.worlds, C, command, receipt("first"));
        var second = CombatDomains.finish(first.worlds(), B, command, receipt("second"));
        assertEquals(first.worlds(), second.worlds()); assertEquals(2, count(second.worlds(), B));
        assertEquals(3, group(second.worlds(), A).grants().get(buff(f.worlds, B).key()).count());
        assertTrue(second.receipt().consumptionSettled()); assertTrue(second.factsByDomain().isEmpty());
        assertOwnerFacts(first, B);
    }

    @Test void cancellationImmunityAndEffectiveDamageRespectTheCapturedConsumptionPolicy() throws Exception {
        var f = new Fixture();
        for (var when : BuffConsumption.When.values()) for (var outcome : DamageReceipt.Outcome.values()) {
            var command = command(buff(f.worlds, B), when, Optional.of(LEGACY.issuedBy(A)));
            var zero = CombatDomains.finish(f.worlds, C, command, receipt("zero", outcome, 0, 0));
            boolean consumes = when == BuffConsumption.When.HIT && outcome != DamageReceipt.Outcome.CANCELLED && outcome != DamageReceipt.Outcome.FAILED;
            assertEquals(consumes ? 2 : 3, count(zero.worlds(), B), when + "/" + outcome);
            assertEquals(consumes ? 1 : 0, group(zero.worlds(), A).grants().size());
            if (consumes) assertOwnerFacts(zero, B); else assertTrue(zero.factsByDomain().isEmpty());
        }
        var effective = command(buff(f.worlds, B), BuffConsumption.When.EFFECTIVE_DAMAGE, Optional.of(LEGACY.issuedBy(A)));
        for (var hit : List.of(receipt("health", DamageReceipt.Outcome.APPLIED, 0, 1), receipt("absorption", DamageReceipt.Outcome.APPLIED, 1, 0))) {
            var finished = CombatDomains.finish(f.worlds, C, effective, hit);
            assertEquals(2, count(finished.worlds(), B)); assertOwnerFacts(finished, B);
        }
    }

    @Test void anOldAttackRetainsItsGrantWithoutChargingANewGenerationOrPausedBuff() throws Exception {
        var f = new Fixture(); var old = buff(f.worlds, B); var command = f.shared(A);
        var state = f.worlds.domain(B); var emptied = Buffs.consume(state.buffs(), old.key(), 3).store();
        var refreshed = Buffs.grant(emptied, f.program.buff("test:next_hit"), HOLDER, HOLDER, SOURCE.origin(), 3, 1, 3_000_000).store();
        var fresh = replace(f.worlds, B, state.withBuffs(refreshed)); assertNotEquals(old.generation(), buff(fresh, B).generation());
        var finished = CombatDomains.finish(fresh, C, command, receipt("old"));
        assertEquals(3, count(finished.worlds(), B)); assertEquals(old, group(finished.worlds(), A).grants().get(old.key()));
        assertTrue(finished.factsByDomain().isEmpty());
        assertSame(fresh, CombatDomains.view(fresh, C, attack(), "new", List.of(new CombatDomains.Claim(A, "old", command))));
        var paused = new BuffInstance(old.generation(), old.key(), old.definition(), old.origin(), old.stacks(), old.nextStackId(),
                old.tier(), old.originalDurationMicros(), old.longestDurationMicros(), OptionalLong.of(0), old.components(), old.quantity());
        var pausedWorlds = replace(f.worlds, B, state.withBuffs(new BuffStore(0, state.buffs().nextGeneration(), Map.of(old.key(), paused))));
        var pausedHit = CombatDomains.finish(pausedWorlds, C, command, receipt("paused"));
        assertEquals(3, count(pausedHit.worlds(), B)); assertEquals(old, group(pausedHit.worlds(), A).grants().get(old.key()));
        assertTrue(pausedHit.factsByDomain().isEmpty());
        assertSame(pausedWorlds, CombatDomains.view(pausedWorlds, C, attack(), "new", List.of(new CombatDomains.Claim(A, "old", command))));
    }

    @Test void aliasesResolveOnlyAgainstTheirExplicitCallerAndNeverSearchOtherDomains() throws Exception {
        var f = new Fixture();
        assertEquals(A, CombatDomains.group(f.worlds, C, LEGACY.issuedBy(A)).domain());
        assertEquals(C, CombatDomains.group(f.worlds, C, LEGACY).domain());
        var legacyState = f.worlds.domain(A).withDamageGroup(new DamageGroups.Group(LEGACY, Map.of()));
        var legacyWorlds = replace(f.worlds, A, legacyState);
        var resolved = CombatDomains.group(legacyWorlds, C, LEGACY.issuedBy(A));
        assertEquals(A, resolved.domain()); assertEquals(LEGACY, resolved.value().handle());
        assertEquals(LEGACY.issuedBy(A), resolved.value().handle().issuedBy(resolved.domain()));
        var missing = replace(legacyWorlds, C, DamageGroups.close(legacyWorlds.domain(C), LEGACY.issuedBy(C)).state());
        assertThrows(IllegalArgumentException.class, () -> CombatDomains.group(missing, C, LEGACY));
        assertThrows(IllegalArgumentException.class, () -> CombatDomains.group(missing, C, LEGACY.issuedBy("test:missing")));
        assertEquals(A, CombatDomains.group(missing, C, LEGACY.issuedBy(A)).domain());
    }

    @Test void groupLifetimeUsesIssuerClockEvenWhenOwnerAndCallerAreFarAhead() throws Exception {
        var f = new Fixture(); var command = f.shared(A);
        var differentClocks = at(at(f.worlds, B, 2_000_000), C, 2_000_000);
        assertEquals(A, CombatDomains.group(differentClocks, C, LEGACY.issuedBy(A)).domain());
        var finished = CombatDomains.finish(differentClocks, C, command, receipt("live-issuer"));
        assertEquals(2, count(finished.worlds(), B)); assertOwnerFacts(finished, B);
        assertTrue(finished.factsByDomain().get(B).stream().allMatch(signal -> ((Buffs.Change) signal.payload()).timeMicros() == 2_000_000));
        var expired = at(differentClocks, A, LEGACY.dueAt());
        assertThrows(IllegalArgumentException.class, () -> CombatDomains.group(expired, C, LEGACY.issuedBy(A)));
        assertThrows(IllegalArgumentException.class, () -> CombatDomains.finish(expired, C, command, receipt("expired")));
        assertEquals(3, count(expired, B)); assertTrue(group(expired, A).grants().isEmpty());
        for (var outcome : List.of(DamageReceipt.Outcome.CANCELLED, DamageReceipt.Outcome.FAILED)) {
            var ignored = CombatDomains.finish(expired, C, command, receipt("cancelled-after-expiry", outcome, 0, 0));
            assertSame(expired, ignored.worlds()); assertTrue(ignored.factsByDomain().isEmpty());
        }
    }

    @Test void nestedSameIdsSkipOnlyTheCurrentRuntimeAndReservationsRemainReadOnly() throws Exception {
        var f = new Fixture(); var command = command(buff(f.worlds, B), BuffConsumption.When.HIT, Optional.empty());
        var claims = List.of(new CombatDomains.Claim(A, "same-operation", command), new CombatDomains.Claim(C, "same-operation", command));
        var view = CombatDomains.view(f.worlds, C, attack(), "same-operation", claims);
        assertEquals(2, count(view, B)); assertEquals(3, count(f.worlds, B)); assertSame(f.worlds.directory(), view.directory());
        assertSame(f.worlds.domain(A), view.domain(A)); assertSame(f.worlds.domain(C), view.domain(C));
        assertEquals(f.worlds.domain(B).withBuffs(view.domain(B).buffs()), view.domain(B));
        assertSame(f.worlds, CombatDomains.view(f.worlds, C, attack(), "same-operation", List.of(claims.getLast())));
        assertSame(f.worlds, CombatDomains.view(f.worlds, C, attack(), "same-operation", List.of(claims.getLast(), claims.getLast())));
        var ownConflict = new CombatDomains.Claim(C, "same-operation", attack());
        assertThrows(IllegalArgumentException.class, () -> CombatDomains.view(f.worlds, C, attack(), "same-operation", List.of(claims.getLast(), ownConflict)));
        assertEquals(2, count(CombatDomains.view(f.worlds, C, attack(), "child", List.of(claims.getFirst(), claims.getFirst())), B));
        var conflict = new CombatDomains.Claim(A, "same-operation", attack());
        assertThrows(IllegalArgumentException.class, () -> CombatDomains.view(f.worlds, C, attack(), "child", List.of(claims.getFirst(), conflict)));
        var otherOwner = new DamageCommand(VICTIM, new BuffInstance.Origin("other", "", "", ""), 10, "minecraft:generic", Set.of(), Set.of(), false);
        assertSame(f.worlds, CombatDomains.view(f.worlds, C, otherOwner, "different", claims));
    }

    @Test void sharedReservationsNormalizeLegacyAliasesButKeepDifferentIssuersSeparate() throws Exception {
        var f = new Fixture(); var explicit = f.shared(A);
        var legacy = command(buff(f.worlds, B), BuffConsumption.When.HIT, Optional.of(LEGACY));
        var aliases = List.of(new CombatDomains.Claim(C, "explicit", explicit), new CombatDomains.Claim(A, "legacy", legacy));
        assertEquals(2, count(CombatDomains.view(f.worlds, C, attack(), "independent", aliases), B));
        assertSame(f.worlds, CombatDomains.view(f.worlds, C, explicit, "next-member", aliases));
        var differentIssuers = List.of(aliases.getLast(), new CombatDomains.Claim(B, "legacy", legacy));
        assertEquals(1, count(CombatDomains.view(f.worlds, C, attack(), "independent", differentIssuers), B));
        assertEquals(2, count(CombatDomains.view(f.worlds, C, explicit, "next-member", differentIssuers), B));
        assertTrue(group(f.worlds, A).grants().isEmpty()); assertTrue(group(f.worlds, B).grants().isEmpty());
    }

    @Test void confirmedChildLeavesOnlyItsKnownResultWhileParentOutcomeRemainsUnknown() throws Exception {
        var f = new Fixture(); var command = f.shared(A);
        var unknownParent = List.of(new CombatDomains.Claim(A, "parent", command));
        assertEquals(2, count(CombatDomains.view(f.worlds, C, attack(), "independent", unknownParent), B));
        var child = CombatDomains.finish(f.worlds, C, command, receipt("confirmed-child"));
        assertEquals(2, count(child.worlds(), B)); assertOwnerFacts(child, B);
        var view = CombatDomains.view(child.worlds(), C, attack(), "independent", unknownParent);
        assertSame(child.worlds(), view); assertEquals(1, group(view, A).grants().size());
        var independent = command(buff(f.worlds, B), BuffConsumption.When.HIT, Optional.empty());
        var unknownIndependent = new CombatDomains.Claim(A, "unknown-independent-parent", independent);
        var reserved = CombatDomains.view(child.worlds(), C, attack(), "other", List.of(unknownParent.getFirst(), unknownIndependent));
        assertEquals(1, count(reserved, B)); assertEquals(2, count(child.worlds(), B));
        assertEquals(group(child.worlds(), A), group(reserved, A)); assertEquals(1, group(child.worlds(), A).grants().size());
        assertEquals(child.worlds(), CombatDomains.finish(child.worlds(), C, command, child.receipt()).worlds());
        assertEquals(3, count(f.worlds, B));
    }

    @Test void anUnmanagedHolderFallsBackToCallerWithoutScanningIdenticalBuffCopies() throws Exception {
        var f = new Fixture(B, false); var command = command(buff(f.worlds, C), BuffConsumption.When.HIT, Optional.of(LEGACY.issuedBy(A)));
        var finished = CombatDomains.finish(f.worlds, C, command, receipt("unmanaged"));
        assertEquals(List.of(3, 3, 2), List.of(count(finished.worlds(), A), count(finished.worlds(), B), count(finished.worlds(), C)));
        assertEquals(1, group(finished.worlds(), A).grants().size()); assertOwnerFacts(finished, C);
        var view = CombatDomains.view(f.worlds, C, attack(), "child", List.of(new CombatDomains.Claim(A, "parent", command)));
        assertEquals(List.of(2, 3, 3), List.of(count(view, A), count(view, B), count(view, C)));
        assertSame(f.worlds.domain(C), view.domain(C));
    }

    @Test void coincidentIssuerOwnerAndCallerMergeBothWritesWithoutLosingEither() throws Exception {
        for (String owner : List.of(A, B, C)) for (String caller : List.of(A, B, C)) {
            var f = new Fixture(owner, true);
            var command = command(buff(f.worlds, owner), BuffConsumption.When.HIT, Optional.of(LEGACY.issuedBy(A)));
            var finished = CombatDomains.finish(f.worlds, caller, command, receipt(owner + "/" + caller));
            assertEquals(1, group(finished.worlds(), A).grants().size()); assertOwnerFacts(finished, owner);
            for (String domain : List.of(A, B, C)) {
                assertEquals(domain.equals(owner) ? 2 : 3, count(finished.worlds(), domain), owner + "/" + caller + "/" + domain);
                if (!domain.equals(A)) assertEquals(group(f.worlds, domain), group(finished.worlds(), domain));
            }
        }
    }
}
