package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class KineticTremorsTest {
    private static final String PERK = "chorus_d2:kinetic_tremors", COUNTER = PERK + "_hits", COOLDOWN = PERK + "_cooldown";
    private static EffectSource weapon(String owner, String weapon, String archetype, boolean enhanced) {
        var tags = new HashSet<>(Set.of("chorus_d2:" + archetype)); if (enhanced) tags.add("chorus:enhanced");
        return new EffectSource(weapon, PERK, owner, new BuffInstance.Origin(owner, weapon, weapon, ""), tags);
    }
    private static final class Harness {
        final EffectSession session; final CompiledEffects program;
        final List<DamageCommand> damages = new ArrayList<>(); final List<Double> losses = new ArrayList<>();
        final List<Long> times = new ArrayList<>(); final List<TargetQuery> queries = new ArrayList<>();
        Optional<WorldPosition> position = Optional.of(new WorldPosition("test:world", 1, 2, 3));
        List<String> targets = List.of("nearby"); int captures;
        Harness(EffectState.Mode mode, EffectSource... sources) throws Exception { this(load("kinetic_tremors"), mode, sources); }
        Harness(CompiledEffects program, EffectState.Mode mode, EffectSource... sources) {
            this.program = program; var initial = EffectState.empty().withMode(mode); for (var source : sources) initial = initial.withSource(source);
            session = new EffectSession(engine(program), initial, request -> switch (request.command()) {
                case PositionQuery query -> { captures++; yield new PositionQuery.Result(query, position); }
                case TargetQuery query -> {
                    queries.add(query); yield new TargetQuery.Result(query, TargetQuery.Outcome.AVAILABLE,
                            targets.stream().sorted().map(id -> new TargetQuery.Target(id, 0)).toList());
                }
                case DamageCommand damage -> {
                    damages.add(damage); times.add(state().buffs().timeMicros());
                    double loss = program.outgoing(state(), damage, damage.amount()).orElseThrow().output().value(); losses.add(loss);
                    yield new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, loss, Optional.empty(), false);
                }
                default -> throw new AssertionError(request.command());
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        Optional<BuffInstance> buff(String buff, String victim, EffectSource source) { return state().buffs().active(new BuffInstance.Key(victim, buff, source.origin().weapon())); }
        double count(String victim, EffectSource source) { return buff(COUNTER, victim, source).map(b -> b.components().numbers().get("hits")).orElse(0.0); }
        void hit(long time, String id, String victim, EffectSource source, String rank) { hit(time, id, victim, source, rank, true, DamageReceipt.Outcome.APPLIED); }
        void hit(long time, String id, String victim, EffectSource source, String rank, boolean direct, DamageReceipt.Outcome outcome) {
            var tags = new HashSet<String>(); if (direct) tags.add("chorus:direct_weapon_hit"); if (!rank.isBlank()) tags.add("chorus:target_rank/" + rank);
            var damage = new DamageCommand(victim, source.origin(), 1, "test:physical", tags, Set.of(), false);
            var receipt = new DamageReceipt(id, outcome, 0, 0, outcome == DamageReceipt.Outcome.APPLIED ? 1 : 0, Optional.empty(), false);
            session.observe(time, DamageFacts.from(damage, receipt)); assertTrue(session.state().idle());
        }
        void advance(long time) { session.observe(time, List.of()); assertTrue(session.state().idle()); }
        void burst(EffectSource source, String rank, int hits) { for (int i = 0; i < hits; i++) hit(0, "hit/" + i, "target", source, rank); }
    }
    @Test void allListedWeaponClassesUseTheirNormalAndEnhancedThresholds() throws Exception {
        var thresholds = Map.ofEntries(Map.entry("smg", new int[]{14,13}), Map.entry("auto_rifle", new int[]{12,11}),
                Map.entry("pulse_rifle", new int[]{11,10}), Map.entry("non_burst_sidearm", new int[]{8,7}),
                Map.entry("hand_cannon_180", new int[]{6,5}), Map.entry("scout_other", new int[]{6,5}),
                Map.entry("scout_120", new int[]{5,4}), Map.entry("scout_150", new int[]{5,4}), Map.entry("hand_cannon_140", new int[]{5,4}),
                Map.entry("micro_missile_sidearm", new int[]{4,3}), Map.entry("bow", new int[]{3,2}), Map.entry("sniper_rifle", new int[]{3,2}));
        for (var entry : thresholds.entrySet()) for (int enhanced = 0; enhanced < 2; enhanced++) {
            var source = weapon("player", "weapon", entry.getKey(), enhanced == 1); var h = new Harness(EffectState.Mode.PVE, source); int threshold = entry.getValue()[enhanced];
            for (int i = 1; i < threshold; i++) { h.hit(0, "hit/" + i, "target", source, "minor"); assertEquals(i, h.count("target", source)); assertEquals(0, h.captures); }
            h.hit(0, "hit/last", "target", source, "minor"); assertEquals(0, h.count("target", source)); assertEquals(1, h.captures);
            assertEquals(4_250_000, h.buff(COOLDOWN, "target", source).orElseThrow().deadline());
            h.advance(2_250_000); assertEquals(List.of(250_000L, 1_250_000L, 2_250_000L), h.times); assertEquals(List.of(9.0, 9.0, 9.0), h.losses);
        }
    }
    @Test void directHitsRefreshOnlyOnceAndCounterPersistsThroughStowUntilTheExactTimeout() throws Exception {
        var source = weapon("player", "weapon", "auto_rifle", false); var h = new Harness(EffectState.Mode.PVE, source);
        h.hit(0, "a", "target", source, "minor"); h.hit(1_000_000, "b", "target", source, "minor");
        h.session.start(2_000_000, new RuleEngine.Signal("chorus:weapon_stowed", event(source)));
        assertEquals(2, h.count("target", source)); h.hit(3_900_000, "b", "target", source, "minor");
        assertEquals(4_000_000, h.buff(COUNTER, "target", source).orElseThrow().deadline());
        h.hit(3_999_999, "c", "target", source, "minor"); assertEquals(3, h.count("target", source));
        h.hit(6_999_999, "d", "target", source, "minor"); assertEquals(1, h.count("target", source));
        assertEquals(Set.of("d"), h.buff(COUNTER, "target", source).orElseThrow().components().sets().get("seen_damage"));
    }
    @Test void countersAndCooldownsAreIsolatedByTargetAndWeaponInstance() throws Exception {
        var a = weapon("player", "a", "bow", false); var b = weapon("other", "b", "bow", true); var h = new Harness(EffectState.Mode.PVE, a, b);
        h.hit(0, "a1", "target", a, "minor"); h.hit(0, "a2", "target", a, "minor");
        h.hit(0, "other-target", "second", a, "minor"); h.hit(0, "b1", "target", b, "minor");
        assertEquals(2, h.count("target", a)); assertEquals(1, h.count("second", a)); assertEquals(1, h.count("target", b));
        h.hit(0, "b2", "target", b, "minor"); assertTrue(h.buff(COOLDOWN, "target", b).isPresent()); assertTrue(h.buff(COOLDOWN, "target", a).isEmpty());
        h.hit(0, "a3", "target", a, "minor"); assertEquals(2, h.captures); assertEquals(1, h.count("second", a));
        h.advance(250_000); assertEquals(Set.of("a", "b"), h.damages.stream().map(d -> d.source().weapon()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void cooldownBlocksCountingThroughTwoSecondsAfterTheLastWaveWithoutBeingExtended() throws Exception {
        var source = weapon("player", "weapon", "bow", false); var h = new Harness(EffectState.Mode.PVE, source); h.burst(source, "minor", 3);
        for (long time : List.of(0L, 250_000L, 1_250_000L, 2_250_000L, 4_249_999L)) {
            h.hit(time, "ignored/" + time, "target", source, "minor"); assertEquals(0, h.count("target", source));
            assertEquals(4_250_000, h.buff(COOLDOWN, "target", source).orElseThrow().deadline());
        }
        assertEquals(3, h.damages.size()); h.hit(4_250_000, "next", "target", source, "minor");
        assertEquals(1, h.count("target", source)); assertTrue(h.buff(COOLDOWN, "target", source).isEmpty());
    }
    @Test void immuneBlockedIndirectAndUnclassifiedHitsCannotAdvanceTheCounter() throws Exception {
        var source = weapon("player", "weapon", "bow", false); var unknown = weapon("other", "unknown", "unknown", false);
        var h = new Harness(EffectState.Mode.PVE, source, unknown);
        h.hit(0, "immune", "target", source, "minor", true, DamageReceipt.Outcome.IMMUNE);
        h.hit(0, "blocked", "target", source, "minor", true, DamageReceipt.Outcome.BLOCKED);
        h.hit(0, "cancelled", "target", source, "minor", true, DamageReceipt.Outcome.CANCELLED);
        h.hit(0, "indirect", "target", source, "minor", false, DamageReceipt.Outcome.APPLIED);
        h.hit(0, "missing-rank", "target", source, ""); h.hit(0, "unknown-weapon", "target", unknown, "minor");
        assertTrue(h.state().buffs().instances().isEmpty());
        h.burst(source, "minor", 3); h.targets = List.of("target"); h.advance(2_250_000);
        assertEquals(3, h.damages.size()); assertEquals(1, h.captures); // Wave facts do not re-count as direct hits.
        assertTrue(h.damages.stream().allMatch(d -> d.tags().containsAll(Set.of("chorus:weapon_damage", "chorus:kinetic_damage"))
                && !d.tags().contains("chorus:direct_weapon_hit") && d.killTags().contains("chorus:weapon_kill")));
    }
    @Test void launchRankAndMaximumDamageAreFrozenAcrossWavesWhilePvpHasItsOwnBase() throws Exception {
        for (var mode : EffectState.Mode.values()) for (String rank : List.of("minor", "elite", "miniboss", "boss", "player")) {
            var source = weapon("player", "weapon", "bow", true); var h = new Harness(mode, source); h.burst(source, rank, 2);
            var place = h.position.orElseThrow(); h.position = Optional.empty();
            h.session.start(0, SourceChange.remove(source.instance())); h.advance(2_250_000);
            double expected = mode == EffectState.Mode.PVP ? 2 : Set.of("miniboss", "boss").contains(rank) ? 9 * 1.333 : 9;
            for (double amount : h.losses) assertEquals(expected, amount, 1e-10);
            assertEquals(3, h.damages.size()); assertTrue(h.damages.stream().allMatch(d -> d.snapshot().isPresent()));
            assertTrue(h.queries.stream().allMatch(q -> q.radius() == 6 && q.center().equals(new TargetQuery.PositionCenter(place))));
        }
    }
    @Test void missingActivationPositionConsumesTheAttemptWithoutInventingABlast() throws Exception {
        var source = weapon("player", "weapon", "bow", true); var h = new Harness(EffectState.Mode.PVE, source); h.position = Optional.empty();
        h.burst(source, "minor", 2); h.advance(250_000);
        assertEquals(1, h.captures); assertTrue(h.state().timers().isEmpty()); assertTrue(h.state().buffs().instances().isEmpty()); assertTrue(h.damages.isEmpty());
    }
    @Test void selectedSourceModifierRemainsInCapturedAttackAfterUnequippingAndDoesNotMergeAgain() throws Exception {
        var data = json("kinetic_tremors"); data.getAsJsonArray("bundles").get(0).getAsJsonObject().add("modifiers", JsonParser.parseString("""
                [{"id":"test_empower","profile":"chorus_d2:weapon_damage","stage":"empowering","group":"empowering","op":"multiply",
                  "stacking_key":"test:empower","value":{"type":"chorus:constant","value":0.2,"unit":"delta"},
                  "if":{"type":"chorus:source_tag","tag":"test:empowered"},"reference":"Synthetic mechanism test","confidence":"assumed"}]
                """));
        var source = weapon("player", "weapon", "bow", false); var tags = new HashSet<>(source.tags()); tags.add("test:empowered");
        source = new EffectSource(source.instance(), source.bundle(), source.holder(), source.origin(), tags);
        var h = new Harness(compile(data), EffectState.Mode.PVE, source); h.burst(source, "minor", 3);
        h.advance(250_000); h.session.start(250_000, SourceChange.remove(source.instance())); h.advance(2_250_000);
        assertEquals(3, h.losses.size()); h.losses.forEach(value -> assertEquals(10.8, value, 1e-10));
    }
    @Test void fullProgramRoundTripsAndAmbiguousArchetypesFailBeforeCreatingCounterState() throws Exception {
        var p = load("kinetic_tremors"); var encoded = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p.program()).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        var source = weapon("player", "weapon", "bow", false);
        source = new EffectSource(source.instance(), source.bundle(), source.holder(), source.origin(), Set.of("chorus_d2:bow", "chorus_d2:smg"));
        var h = new Harness(EffectState.Mode.PVE, source); var ambiguous = source;
        assertThrows(IllegalStateException.class, () -> h.hit(0, "bad", "target", ambiguous, "minor"));
        assertTrue(h.state().buffs().instances().isEmpty()); assertTrue(h.session.state().engine().failure().isPresent());
    }
}
