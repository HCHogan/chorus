package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeakenTest {
    static final String BUFF = "chorus_d2:weaken";
    static Map<String, Measure> parameters(double duration, double guardianDuration, double strength, double guardianStrength) {
        return Map.of("combatant_duration", new Measure(duration, Unit.SECOND), "guardian_duration", new Measure(guardianDuration, Unit.SECOND), "combatant_strength", new Measure(strength, Unit.DELTA), "guardian_strength", new Measure(guardianStrength, Unit.DELTA));
    }
    static EffectSource source(String id, String owner, double duration, double guardianDuration, double strength, double guardianStrength) {
        return new EffectSource(id, BUFF + "_application", owner, new BuffInstance.Origin(owner, id, "weapon", "ability"), Set.of(), parameters(duration, guardianDuration, strength, guardianStrength));
    }
    static final class Harness {
        final CompiledEffects p; EffectSession session; final Map<String, EntityQuery.View> entities = new HashMap<>(); final List<StatusResult.Check> checks = new ArrayList<>(); StatusResult.Decision decision = StatusResult.Decision.ALLOWED; boolean unknown;
        Harness(boolean guardian, EffectState.Mode mode) throws Exception {
            p = link("weaken", "weaken_inputs", "strand_defense", "combat_damage", "movement_attributes"); entities.put("target", FreezeTest.view(guardian)); entities.put("other", FreezeTest.view(guardian));
            session = new EffectSession(p, EffectCheckpointTest.clock(), 1, EffectState.empty().withMode(mode), this::world);
        }
        RuleEngine.ActionResult world(RuleEngine.WorldRequest request) {
            return switch (request.command()) {
                case EntityQuery q -> new EntityQuery.Result(q, Optional.ofNullable(entities.get(q.target())));
                case StatusResult.Check q -> { checks.add(q); if (unknown) throw new IllegalStateException("unknown Weaken authorization"); yield new StatusResult.Checked(q, decision); }
                default -> throw new AssertionError(request.command());
            };
        }
        EffectState state() { return session.state().engine().domain(); }
        long now() { return state().buffs().timeMicros(); }
        void until(long time) { session.observe(time, List.of()); }
        void bind(EffectSource s) { session.start(now(), SourceChange.bind(s)); }
        void event(EffectSource s, String target, String type) { session.start(now(), new RuleEngine.Signal("chorus_d2:" + type, new EffectEvent(s.holder(), target, s.origin(), Set.of(), Map.of(), Map.of(), Map.of("source_instance", s.instance(), "bundle", s.bundle())))); }
        void apply(EffectSource s) { bind(s); event(s, "target", "apply_weaken"); }
        Optional<BuffInstance> buff(String target) { return state().buffs().instances().values().stream().filter(b -> b.definition().id().equals(BUFF) && b.key().holder().equals(target)).findFirst(); }
        double damage(String target) { return p.defense(state(), new DamageCommand(target, new BuffInstance.Origin("attacker", "attack", "gun", ""), 100, "minecraft:generic", Set.of(), Set.of(), false), 100).orElseThrow().output().value(); }
        double speed() { return p.nativeAttributes(state(), "target").stream().filter(v -> v.binding().attribute().equals("minecraft:movement_speed")).findFirst().orElseThrow().amount(); }
        void restore() { var checkpoint = EffectCheckpointCodec.decode(EffectCheckpointCodec.encode(session.checkpoint())); session = EffectSession.restore(new CompiledEffects(checkpoint.program()), EffectCheckpointTest.clock(), 256, checkpoint, this::world); }
    }
    static EffectSource normal() { return source("normal", "caster", 6, 2.5, .15, .075); }
    @Test void actualRecipientClassificationSelectsKnownNormalStrengthAndDurationIndependentlyOfWorldMode() throws Exception {
        for (boolean player : List.of(false, true)) for (var mode : EffectState.Mode.values()) {
            var h = new Harness(player, mode); h.apply(normal()); assertEquals(player ? 107.5 : 115, h.damage("target"), 1e-10); assertEquals(player ? 2_500_000 : 6_000_000, h.buff("target").orElseThrow().deadline()); assertEquals(-.2, h.speed(), 1e-12); assertEquals(100, h.damage("other"));
        }
    }
    @Test void allGrantorsShareOneNonstackingRecipientStatusAndShorterRefreshDoesNotShortenIt() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVE); h.apply(normal()); var first = h.buff("target").orElseThrow(); h.until(1_000_000); h.apply(source("second", "ally", 1, 1, .15, .075));
        assertEquals(1, h.state().buffs().instances().size()); assertEquals(115, h.damage("target"), 1e-10); assertEquals(first.generation(), h.buff("target").orElseThrow().generation()); assertEquals(first.origin(), h.buff("target").orElseThrow().origin()); assertEquals(6_000_000, h.buff("target").orElseThrow().deadline());
    }
    @Test void weakerApplicationsCanExtendRefreshableStrongWeakenWithoutReducingItsStrength() throws Exception {
        for (boolean player : List.of(false, true)) {
            var h = new Harness(player, EffectState.Mode.PVE); h.apply(source("strong", "first", 2, 2, .3, .5)); h.until(1_000_000); h.apply(normal());
            assertEquals(player ? 150 : 130, h.damage("target"), 1e-10); assertEquals(player ? 3_500_000 : 7_000_000, h.buff("target").orElseThrow().deadline());
            h.until(h.buff("target").orElseThrow().deadline()); h.apply(normal()); assertEquals(player ? 107.5 : 115, h.damage("target"), 1e-10);
        }
    }
    @Test void strongerSeparateGlobalDebuffWinsWhileIndependentResistanceMultipliesAfterIt() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVE); h.apply(normal());
        h.bind(new EffectSource("strong_other", "test:strong_debuff", "target", new BuffInstance.Origin("target", "other", "", ""), Set.of())); assertEquals(130, h.damage("target"), 1e-10);
        h.bind(new EffectSource("dr", "test:independent_resistance", "target", new BuffInstance.Origin("target", "dr", "", ""), Set.of())); assertEquals(65, h.damage("target"), 1e-10);
        h.session.start(h.now(), SourceChange.remove("strong_other")); assertEquals(57.5, h.damage("target"), 1e-10);
    }
    @Test void deniedApplicationCannotChangeAnExistingStrengthDeadlineOrOwnership() throws Exception {
        for (var decision : StatusResult.Decision.values()) if (decision != StatusResult.Decision.ALLOWED) {
            var h = new Harness(false, EffectState.Mode.PVE); h.apply(normal()); var before = h.buff("target"); h.decision = decision; h.until(1_000_000); h.apply(source("denied", "other", 20, 20, .5, .5)); assertEquals(before, h.buff("target")); assertEquals(115, h.damage("target"), 1e-10);
        }
    }
    @Test void invalidParametersAndMissingOrDeadTargetsNeverReachStatusAuthorization() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVE); h.apply(source("zero", "caster", 0, 2, .15, .075)); h.apply(source("negative", "caster", 6, 2, -.15, .075)); assertTrue(h.checks.isEmpty());
        h.entities.remove("target"); h.apply(normal()); assertTrue(h.checks.isEmpty()); h.entities.put("target", new EntityQuery.View(false, false, 0, 1000, 0)); h.apply(normal()); assertTrue(h.checks.isEmpty() && h.buff("target").isEmpty());
    }
    @Test void expiryCleanseAndConfirmedDeathRemoveBothPenaltiesWhileDetachingTheGiverDoesNot() throws Exception {
        for (String reason : List.of("expiry", "cleanse", "death")) {
            var h = new Harness(false, EffectState.Mode.PVE); var s = normal(); h.apply(s); h.session.start(0, SourceChange.remove(s.instance())); assertEquals(115, h.damage("target"), 1e-10);
            if (reason.equals("expiry")) h.until(6_000_000);
            else if (reason.equals("cleanse")) { h.bind(s); h.event(s, "target", "clear_weaken"); }
            else h.session.start(0, new RuleEngine.Signal("chorus:death", new EffectEvent("attacker", "target", new BuffInstance.Origin("attacker", "attack", "", ""), Set.of(), Map.of())));
            assertTrue(h.buff("target").isEmpty()); assertEquals(100, h.damage("target")); assertEquals(0, h.speed());
        }
    }
    @Test void checkpointKeepsRetainedStrengthAndDeadlineAndUnknownAuthorizationNeverInventsStatus() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVE); h.apply(source("strong", "caster", 6, 2, .3, .5)); h.until(1_000_000); h.apply(normal()); h.restore(); assertEquals(130, h.damage("target"), 1e-10); h.until(7_000_000); assertTrue(h.buff("target").isEmpty());
        var failed = new Harness(false, EffectState.Mode.PVE); failed.unknown = true; assertThrows(IllegalStateException.class, () -> failed.apply(normal())); assertTrue(failed.buff("target").isEmpty()); assertThrows(IllegalStateException.class, () -> failed.until(1_000_000)); assertEquals(1, failed.checks.size());
    }
    @Test void sharedDefinitionRequiresExplicitSourceParametersAndRoundtripsWithoutSpecialJavaTypes() throws Exception {
        var h = new Harness(false, EffectState.Mode.PVE); var s = normal(); assertThrows(IllegalArgumentException.class, () -> h.p.validateSource(new EffectSource(s.instance(), s.bundle(), s.holder(), s.origin(), Set.of())));
        assertEquals(h.p.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, h.p.program()).getOrThrow()).getOrThrow());
    }
}
