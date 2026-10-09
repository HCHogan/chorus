package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceChangeTest {
    private static final class Harness {
        final List<HealingCommand> heals = new ArrayList<>();
        final EffectSession session;
        Harness(CompiledEffects program) {
            session = new EffectSession(engine(program), EffectState.empty(), request -> {
                var command = (HealingCommand) request.command(); heals.add(command);
                return new HealingReceipt("source/" + heals.size(), command, HealingReceipt.Outcome.APPLIED, command.amount(), command.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
    }
    @Test void attachOnlyInitializesTheBoundInstanceAndEqualRebindingIsANoop() throws Exception {
        var program = load("source_attachment"); var test = new Harness(program); var first = source("test:attachment");
        var second = new EffectSource("second", first.bundle(), first.holder(), first.origin(), Set.of());
        test.session.start(0, SourceChange.bind(first)); test.session.start(0, SourceChange.bind(second)); test.session.start(0, SourceChange.bind(second));
        assertEquals(2, test.heals.size()); assertEquals(2, test.state().sources().size()); assertEquals(2, test.state().timers().size());
        var changed = new EffectSource(second.instance(), second.bundle(), second.holder(), second.origin(), Set.of("test:changed"));
        test.session.start(0, SourceChange.bind(changed)); assertEquals(3, test.heals.size()); assertEquals(2, test.state().timers().size());
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
    }
    @Test void detachmentSettlesElapsedRecoveryAndCancelsSourceTimerWithoutErasingIndependentBuffs() throws Exception {
        var test = new Harness(load("source_attachment")); var source = source("test:attachment");
        test.session.start(0, SourceChange.bind(source)); test.session.start(70_001, SourceChange.remove(source.instance()));
        assertEquals(1 + .070001 * 2, test.heals.stream().mapToDouble(HealingCommand::amount).sum(), 1e-12);
        assertTrue(test.state().sources().isEmpty()); assertTrue(test.state().timers().isEmpty()); assertEquals(1, test.state().buffs().instances().size());
        test.session.start(900_000, SourceChange.remove(source.instance())); assertEquals(3, test.heals.size());
        test.session.start(1_000_000, new RuleEngine.Signal("test:noop", RuleEngine.Empty.INSTANCE)); assertTrue(test.state().buffs().instances().isEmpty());
    }
    @Test void missingOrBuffOnlyBundlesCannotBecomeStaticSources() throws Exception {
        var program = load("restoration");
        assertThrows(IllegalArgumentException.class, () -> program.validateSource(source("test:missing")));
        assertThrows(IllegalArgumentException.class, () -> program.validateSource(source("chorus_d2:restoration_active")));
        assertThrows(IllegalArgumentException.class, () -> new SourceChange("different", Optional.of(source("test:attachment"))));
        var data = json("restoration"); var rule = json("source_attachment").getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).deepCopy();
        var rules = new com.google.gson.JsonArray(); rules.add(rule);
        data.getAsJsonArray("bundles").get(1).getAsJsonObject().add("rules", rules);
        assertThrows(IllegalStateException.class, () -> compile(data));
    }
    @Test void singleSourceReplacementAndRemovalRunCleanupWithTheOldTagsExactlyOnce() throws Exception {
        var test = new Harness(load("equipment")); var normal = source("test:gear_perk");
        var enhanced = new EffectSource(normal.instance(), normal.bundle(), normal.holder(), normal.origin(), Set.of("chorus:enhanced"));
        test.session.start(0, SourceChange.bind(normal)); test.session.start(50_000, SourceChange.bind(enhanced));
        assertEquals(List.of(1.0, 10.0, 2.0), test.heals.stream().map(HealingCommand::amount).toList());
        test.session.start(70_000, SourceChange.remove(enhanced.instance())); test.session.start(80_000, SourceChange.remove(enhanced.instance()));
        test.session.observe(200_000, List.of());
        assertEquals(List.of(1.0, 10.0, 2.0, 20.0), test.heals.stream().map(HealingCommand::amount).toList());
        assertTrue(test.state().sources().isEmpty()); assertTrue(test.state().timers().isEmpty());
    }
}
