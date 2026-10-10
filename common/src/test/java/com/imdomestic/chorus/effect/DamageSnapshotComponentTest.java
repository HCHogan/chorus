package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DamageSnapshotComponentTest {
    static final EffectSource SOURCE = source("test:storage");
    static final String BUFF = "test:stored_attack";
    static CompiledEffects program() throws Exception { return link("damage_snapshot", "stored_damage_snapshot"); }
    static class Harness {
        final CompiledEffects program = program(); final EffectSession session;
        final List<DamageCommand> commands = new ArrayList<>(); final List<Double> dealt = new ArrayList<>();
        boolean fail;
        Harness() throws Exception {
            var state = EffectState.empty().withSource(SOURCE).withSource(new EffectSource("attack", "test:attack", "player", SOURCE.origin(), Set.of()))
                    .withResource(new ResourceState(new ResourceState.Key("player", "test:power"), .3, 1, 0));
            state = state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:empower"), "player", "player", SOURCE.origin(), 1, 1, 100_000).store());
            session = new EffectSession(engine(program), state, this::execute);
        }
        RuleEngine.ActionResult execute(RuleEngine.WorldRequest request) {
            var command = (DamageCommand) request.command(); commands.add(command);
            if (fail) throw new IllegalStateException("unknown after world dispatch");
            double outgoing = program.outgoing(state(), command, command.amount()).orElseThrow().output().value();
            double damage = program.defense(state(), command, outgoing).orElseThrow().output().value(); dealt.add(damage);
            return new DamageReceipt("hit/" + commands.size(), DamageReceipt.Outcome.APPLIED, 0, 0, damage, Optional.empty(), false);
        }
        EffectState state() { return session.state().engine().domain(); }
        void send(long time, String name) { session.start(time, new RuleEngine.Signal("test:snapshot_" + name, event(SOURCE))); healthy(); }
        void healthy() { assertTrue(session.state().idle()); assertTrue(session.state().engine().failure().isEmpty(), session.state().engine().failure().toString()); }
        void until(long time) { session.observe(time, List.of()); healthy(); }
        BuffInstance instance() { return buff(session.state(), BUFF, "target"); }
        DamageSnapshot saved(String component) { return instance().components().damageSnapshots().get(component).orElseThrow(); }
        void detach() { session.start(state().buffs().timeMicros(), SourceChange.remove("attack")); session.start(state().buffs().timeMicros(), SourceChange.remove(SOURCE.instance())); healthy(); }
    }
    @Test void typedSnapshotComponentsAreImmutableAndSurviveEveryOtherComponentUpdate() throws Exception {
        var h = new Harness(); h.send(0, "init"); var initial = h.instance().components(); h.send(0, "save"); var snapshot = h.saved("attack");
        var updated = h.instance().components().number("n", BuffComponents.Update.ADD, 1).remember("seen", "event").reference("ref", "entity")
                .targets("members", Targets.Identities.of(List.of("member"))).position("place", Optional.of(new WorldPosition("test:dimension", 1, 2, 3)));
        assertEquals(snapshot, updated.damageSnapshots().get("attack").orElseThrow()); assertTrue(initial.damageSnapshots().get("attack").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> updated.damageSnapshots().clear());
        assertThrows(IllegalArgumentException.class, () -> updated.damageSnapshot("undeclared", Optional.of(snapshot)));
        assertThrows(NullPointerException.class, () -> new DamageSnapshot.Stored(null));
        assertThrows(IllegalArgumentException.class, () -> new BuffSchema(Map.of(), Set.of(), Set.of(), Set.of(), Set.of("same"), Set.of("same")));
    }
    @Test void emptySnapshotsCanBeGuardedAndCopiedAsClearButCannotInventDamageOrBaseValues() throws Exception {
        var h = new Harness(); h.send(0, "init"); h.send(0, "fire"); assertTrue(h.commands.isEmpty());
        var empty = new DamageSnapshot.Stored(Optional.empty()); assertTrue(ResultShape.STORED_DAMAGE_SNAPSHOT.flag("missing", empty));
        assertThrows(IllegalStateException.class, () -> ResultShape.STORED_DAMAGE_SNAPSHOT.read("base_damage", empty));
        assertThrows(IllegalStateException.class, () -> ResultShape.STORED_DAMAGE_SNAPSHOT.snapshot(empty));
        h.send(0, "save"); h.send(0, "copy"); h.send(0, "clear"); h.send(0, "fire"); assertTrue(h.commands.isEmpty());
        h.send(0, "fire_copy"); assertEquals(List.of(12.0), h.dealt); assertTrue(h.instance().components().damageSnapshots().get("attack").isEmpty());
    }
    @Test void laterIndependentBuffEventUsesFrozenSourceAndLiveVictimAfterExpiryAndSourceDetach() throws Exception {
        var h = new Harness(); h.send(0, "init"); h.send(0, "save"); var stored = h.saved("attack");
        h.send(200_000, "mark"); h.detach();
        h.session.start(200_000, new RuleEngine.Signal("test:stored_fire", event(SOURCE))); h.healthy();
        assertEquals(20.25, h.dealt.getFirst(), 1e-9); assertEquals(stored, h.commands.getFirst().snapshot().orElseThrow());
        assertEquals(SOURCE.origin(), h.commands.getFirst().source()); assertEquals(Set.of("test:weapon_kill"), h.commands.getFirst().killTags());
        assertEquals("test-snapshot-v1", stored.profile().version());
        assertThrows(IllegalArgumentException.class, () -> h.program.outgoing(h.state().withMode(EffectState.Mode.PVP), stored.command("target"), 10));
    }
    @Test void refreshAndCopyPreserveCapturedValuesWhileExplicitOverwriteAndNewGenerationAreIndependent() throws Exception {
        var h = new Harness(); h.send(0, "init"); h.send(0, "save"); h.send(0, "copy"); var old = h.saved("attack"); long generation = h.instance().generation();
        h.send(200_000, "init"); assertEquals(generation, h.instance().generation()); assertEquals(old, h.saved("attack"));
        h.send(200_000, "save"); assertNotEquals(old, h.saved("attack")); assertEquals(old, h.saved("copy"));
        h.send(200_000, "replace"); assertNotEquals(generation, h.instance().generation());
        assertEquals(10, h.dealt.getLast(), 1e-9, "ended rule reads replaced generation's last snapshot");
        assertTrue(h.instance().components().damageSnapshots().values().stream().allMatch(Optional::isEmpty));
    }
    @Test void detachedReadKeepsTheExactValueAcrossOverwriteRemovalAndRebinding() throws Exception {
        var h = new Harness(); h.send(0, "init"); h.send(0, "save"); var old = h.saved("attack");
        h.until(200_000); h.send(200_000, "delay"); h.send(200_000, "save"); var replacement = h.saved("attack");
        h.send(200_000, "remove"); assertEquals(replacement, h.commands.getFirst().snapshot().orElseThrow());
        h.detach(); h.until(300_000); assertEquals(old, h.commands.getLast().snapshot().orElseThrow());
        assertEquals(List.of(10.0, 12.0), h.dealt);
    }
    @Test void unknownWorldResultKeepsTheStoredSnapshotAndNeverReplaysItsDamage() throws Exception {
        var h = new Harness(); h.send(0, "init"); h.send(0, "save"); var saved = h.saved("attack"); h.fail = true;
        assertThrows(IllegalStateException.class, () -> h.session.start(0, new RuleEngine.Signal("test:snapshot_fire", event(SOURCE))));
        assertFalse(h.session.state().idle()); assertEquals(saved, h.saved("attack")); assertEquals(1, h.commands.size());
        assertThrows(IllegalStateException.class, () -> h.session.observe(100_000, List.of())); assertEquals(1, h.commands.size());
    }
    @Test void declarationAndBindingKindsAreCheckedAndCompiledProgramsRoundTrip() throws Exception {
        var p = program(); assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow().program());
        var schema = p.buff(BUFF).components(); assertThrows(IllegalArgumentException.class, () -> schema.requireDamageSnapshot("place"));
        var v = new Validation(Map.of(BUFF, p.buff(BUFF)), Map.of("wrong", ResultShape.POSITION), false);
        assertThrows(IllegalArgumentException.class, () -> new Action.WriteDamageSnapshot(BUFF, Evaluation.Target.SELF, "attack", "wrong").validate(v));
        assertThrows(IllegalArgumentException.class, () -> new Action.WriteDamageSnapshot(BUFF, Evaluation.Target.SELF, "attack", "later").validate(v));
        assertThrows(IllegalArgumentException.class, () -> new Action.ReadDamageSnapshot(BUFF, Evaluation.Target.SELF, "unknown").validate(v));
        assertThrows(IllegalArgumentException.class, ResultShape.STORED_DAMAGE_SNAPSHOT::requireTarget);
        assertTrue(EffectCodecs.ACTION.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"type\":\"chorus:read_damage_snapshot\",\"buff\":\"test:stored_attack\",\"component\":\"attack\",\"typo\":true}")).error().isPresent());
    }
}
