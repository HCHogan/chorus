package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConsumptionReservationsTest {
    static final EffectSource SOURCE = source("test:control");
    static DamageCommand attack() { return new DamageCommand("target", SOURCE.origin(), 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false, Optional.of("test:melee")); }
    static CompiledEffects program() throws Exception {
        var d = json("damage_group"); d.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").addProperty("max_stacks", 3); return compile(d);
    }
    static EffectState armed(CompiledEffects p, int stacks) {
        var s = EffectState.empty().withSource(SOURCE);
        return s.withBuffs(Buffs.grant(s.buffs(), p.buff("test:next_hit"), "player", "player", SOURCE.origin(), stacks, 1, 3_000_000).store());
    }
    static int count(EffectState s) { return s.buffs().instances().values().stream().mapToInt(BuffInstance::count).sum(); }
    @Test void pendingHitReservesOnlyItsCostAndOwnQueryKeepsTheOriginalEligibility() throws Exception {
        var p = program(); var s = armed(p, 3); var first = p.prepareDamage(s, attack());
        var open = List.of(new ConsumptionReservations.Claim("first", first));
        var child = ConsumptionReservations.view(s, attack(), "child", open);
        assertEquals(2, count(child)); assertEquals(3, count(s)); assertEquals(s, ConsumptionReservations.view(s, first, "first", open));
        var second = p.prepareDamage(child, attack()); var both = List.of(open.getFirst(), new ConsumptionReservations.Claim("second", second));
        assertEquals(1, count(ConsumptionReservations.view(s, attack(), "third", both)));
    }
    @Test void otherOwnersAndNewGenerationsDoNotPayForAStaleReservation() throws Exception {
        var p = program(); var s = armed(p, 1); var old = p.prepareDamage(s, attack()); var claims = List.of(new ConsumptionReservations.Claim("old", old));
        var other = new DamageCommand("target", new BuffInstance.Origin("other", "", "", ""), 10, "minecraft:generic", Set.of(), Set.of(), false);
        assertEquals(s, ConsumptionReservations.view(s, other, "new", claims));
        var empty = BuffConsumption.finish(s, old, receipt("old")).state();
        var fresh = empty.withBuffs(Buffs.grant(empty.buffs(), p.buff("test:next_hit"), "player", "player", SOURCE.origin(), 1, 1, 3_000_000).store());
        assertEquals(fresh, ConsumptionReservations.view(fresh, attack(), "new", claims));
    }
    @Test void pendingSharedMembersReserveOneCostAndConfirmedMembershipIsNotReservedAgain() throws Exception {
        var p = program(); var s = armed(p, 3); var group = new DamageGroups.Handle("shared", "player", 0, 1_000_000); s = DamageGroups.begin(s, group).state();
        var command = p.prepareDamage(s, attack().withGroup(group));
        var claims = List.of(new ConsumptionReservations.Claim("parent", command), new ConsumptionReservations.Claim("child", command));
        assertEquals(3, count(ConsumptionReservations.view(s, command, "next-member", claims)));
        assertEquals(2, count(ConsumptionReservations.view(s, attack(), "independent", claims)));
        var confirmed = BuffConsumption.finish(s, command, receipt("child")).state();
        assertEquals(2, count(ConsumptionReservations.view(confirmed, attack(), "independent", claims)));
    }
    @Test void confirmedCombatCommitIsAtomicAndRejectsUnrelatedOrStaleState() throws Exception {
        var p = program(); var before = armed(p, 2); var command = p.prepareDamage(before, attack());
        var after = BuffConsumption.finish(before, command, receipt("hit")).state(); var commit = new CombatCommit(before, after);
        assertEquals(after, commit.apply(before)); assertTrue(commit.changed()); assertThrows(IllegalStateException.class, () -> commit.apply(after));
        assertThrows(IllegalArgumentException.class, () -> new CombatCommit(before, after.withMode(EffectState.Mode.PVP)));
        assertThrows(IllegalArgumentException.class, () -> new CombatCommit(before, after.withoutSource(SOURCE.instance())));
        assertEquals(before, new CombatCommit(before, before).apply(before));
    }
    @Test void externallySettledReceiptCannotConsumeASecondCharge() throws Exception {
        var p = program(); var before = armed(p, 3); var command = p.prepareDamage(before, attack());
        var consumed = BuffConsumption.finish(before, command, receipt("hit"));
        var marked = new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false, Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.of(consumed.emitted()));
        assertTrue(marked.consumptionSettled()); assertEquals(2, count(consumed.state()));
        assertEquals(consumed.state(), BuffConsumption.finish(consumed.state(), command, marked).state());
        assertTrue(BuffConsumption.finish(consumed.state(), command, marked).emitted().isEmpty());
    }
    @Test void reservedAttackBuffDoesNotHideItsDefensiveShieldOnSelfDamage() throws Exception {
        var data = json("damage_group"); var buff = data.getAsJsonArray("buffs").get(0).getAsJsonObject();
        buff.getAsJsonObject("definition").add("components", com.google.gson.JsonParser.parseString("{\"numbers\":{\"capacity\":{\"initial\":30,\"unit\":\"damage\"}}}"));
        buff.add("shield", com.google.gson.JsonParser.parseString("{\"capacity\":\"capacity\"}"));
        var p = compile(data); var before = armed(p, 1); var parent = p.prepareDamage(before, attack());
        var self = new DamageCommand("player", SOURCE.origin(), 10, "minecraft:generic", Set.of("test:melee"), Set.of(), false, Optional.of("test:melee"));
        var available = ConsumptionReservations.view(before, self, "child", List.of(new ConsumptionReservations.Claim("parent", parent)));
        assertEquals(0, count(available)); assertEquals(10, p.outgoing(available, self, 10).orElseThrow().output().value());
        var shield = p.shields(before, self, 10, DamageBasis.EMPTY, available);
        assertEquals(1, shield.layers().size()); assertEquals(0, shield.budget().toVanilla());
        assertEquals(20, shield.commit().apply(before).buffs().instances().values().iterator().next().components().numbers().get("capacity"));
    }
    private static DamageReceipt receipt(String id) { return new DamageReceipt(id, DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false); }
}
