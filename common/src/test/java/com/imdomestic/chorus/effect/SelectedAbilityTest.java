package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectedAbilityTest {
    static final EffectSource SOURCE = source("test:selected");
    static class Harness {
        final CompiledEffects program; EffectSession session; final List<HealingCommand> heals = new ArrayList<>();
        Harness() throws Exception {
            program = load("selected_ability"); session = new EffectSession(program, EffectCheckpointTest.clock(), 256, EffectState.empty().withSource(SOURCE), this::world);
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest q) {
            var command = (HealingCommand) q.command(); heals.add(command);
            return new HealingReceipt(q.id().toString(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void choose(String holder, String slot, String id) {
            var before = state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots());
            if (id.isEmpty()) slots.remove(slot); else slots.put(slot, id);
            session.start(now(), new AbilityChange(holder, before, new AbilityLoadout(slots)).signal());
        }
        void signal(String kind) { session.start(now(), new RuleEngine.Signal("test:" + kind, new EffectEvent("enemy", "enemy", new BuffInstance.Origin("enemy", "attack", "", "test:arc"), Set.of("test:solar"), Map.of()))); }
        DamageCommand attack() { return new DamageCommand("enemy", SOURCE.origin(), 10, "minecraft:generic", Set.of(), Set.of(), false, Optional.of("test:damage")); }
        void restore() { session = EffectSession.restore(program, EffectCheckpointTest.clock(), 256, EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())), this::world); }
    }
    @Test void onlyCommittedSelectionInTheRequestedSlotCountsAndSourceTagsCannotSpoofIt() throws Exception {
        var h = new Harness(); h.signal("probe"); assertTrue(h.heals.isEmpty());
        h.session.start(0, SourceChange.bind(new EffectSource("spoof", "test:spoof", "player", SOURCE.origin(), Set.of("test:solar"))));
        h.choose("player", "test:grenade", "test:solar_grenade"); h.choose("enemy", "test:super", "test:solar"); h.signal("probe"); assertTrue(h.heals.isEmpty());
        h.choose("player", "test:super", "test:solar"); h.signal("probe"); assertEquals(1, h.heals.size());
        h.choose("player", "test:super", "test:arc"); h.signal("probe"); assertEquals(1, h.heals.size());
        h.choose("player", "test:super", ""); h.signal("probe"); assertEquals(1, h.heals.size());
    }
    @Test void castReplacementDoesNotChangeTheBaseSelectionReadInsideTheResolvedAbility() throws Exception {
        var h = new Harness(); h.choose("player", "test:super", "test:solar");
        h.session.start(0, SourceChange.bind(new EffectSource("override", "test:override", "player", SOURCE.origin(), Set.of())));
        h.session.start(0, new AbilityUse.Request("player", "test:super", "cast", new EffectEvent("player", "player", SOURCE.origin(), Set.of(), Map.of())).signal());
        assertEquals(2, h.heals.getLast().amount()); assertEquals("test:arc", h.heals.getLast().source().ability());
        assertEquals("test:solar", h.program.selectedAbility(h.state(), "player", "test:super").orElseThrow().id());
    }
    @Test void attackCaptureFreezesSourceSelectionButReadsVictimSelectionAtImpact() throws Exception {
        var h = new Harness(); h.choose("player", "test:super", "test:solar"); var shot = h.program.captureDamage(h.state(), h.attack());
        h.choose("player", "test:super", "test:arc"); h.choose("enemy", "test:super", "test:arc");
        assertEquals(60, h.program.outgoing(h.state(), shot.command("enemy"), 10).orElseThrow().output().value(), 1e-9);
        h.choose("enemy", "test:super", "test:solar"); assertEquals(20, h.program.outgoing(h.state(), shot.command("enemy"), 10).orElseThrow().output().value(), 1e-9);
        assertEquals(10, h.program.outgoing(h.state(), h.attack(), 10).orElseThrow().output().value(), 1e-9);
        var encoded = EffectCheckpointCodec.DATA.encode(shot); assertEquals(shot, EffectCheckpointCodec.DATA.decode(encoded, DamageSnapshot.class));
    }
    @Test void delayedActionsReadNewSelectionsAfterDomainRestoreAndRetainTheirOldCredit() throws Exception {
        var h = new Harness(); h.choose("player", "test:super", "test:arc"); h.signal("delay"); h.restore();
        h.session.observe(50_000, List.of()); h.choose("player", "test:super", "test:solar"); h.session.observe(100_000, List.of());
        assertEquals(4, h.heals.getLast().amount()); assertEquals(SOURCE.origin(), h.heals.getLast().source());
        h.signal("delay"); h.choose("player", "test:super", ""); h.restore(); h.session.observe(200_000, List.of()); assertEquals(1, h.heals.size());
    }
    @Test void malformedIdentifiersUnknownFieldsAndUnboundTargetsAreRejected() throws Exception {
        for (String fault : List.of("slot", "tag", "extra", "binding")) {
            var d = json("selected_ability"); var c = d.getAsJsonArray("bundles").get(4).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("if");
            if (fault.equals("slot") || fault.equals("tag")) c.addProperty(fault, "bad id");
            if (fault.equals("extra")) c.addProperty("resolved", true);
            if (fault.equals("binding")) { var target = new JsonObject(); target.addProperty("binding", "missing"); c.add("target", target); }
            assertThrows(RuntimeException.class, () -> compile(d), fault);
        }
        var h = new Harness(); var data = json("selected_ability");
        data.getAsJsonArray("bundles").get(4).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("if").addProperty("slot", "test:absent");
        assertNotNull(compile(data)); assertTrue(h.program.selectedAbility(h.state(), "player", "test:absent").isEmpty());
        assertEquals(h.program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.program.program()).getOrThrow()).getOrThrow());
    }
}
