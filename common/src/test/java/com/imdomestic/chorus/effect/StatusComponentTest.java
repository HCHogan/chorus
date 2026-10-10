package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static com.imdomestic.chorus.rule.RuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.stat.Measure;
import com.imdomestic.chorus.stat.Unit;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatusComponentTest {
    private static final String SLICE = "chorus_d2:slice", SEVER = "chorus_d2:sever", ACCUMULATOR = "test:accumulator";
    private static EffectEvent hit(EffectSource source, String target, String id, double loss, boolean apply, boolean lethal, boolean immune) {
        var command = new DamageCommand(target, source.origin(), 10000, "test:physical", apply ? Set.of("test:apply") : Set.of(), Set.of(), false);
        var receipt = new DamageReceipt(id, immune ? DamageReceipt.Outcome.IMMUNE : DamageReceipt.Outcome.APPLIED,
                0, 0, loss, lethal ? Optional.of("death-" + id) : Optional.empty(), false);
        return (EffectEvent) DamageFacts.from(command, receipt).getFirst().payload();
    }
    private static StatusResult.Checked allowed(WorldRequest request) {
        return new StatusResult.Checked(assertInstanceOf(StatusResult.Check.class, request.command()), StatusResult.Decision.ALLOWED);
    }

    @Test void sliceConsumesOnlyAfterStatusCommitAndDoesNotRecreateTheLastCharge() throws Exception {
        var compiled = link("combat_damage", "strand_defense", "continuity", "slice"); var engine = engine(compiled); var source = source(SLICE);
        var active = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "chorus:class_ability_used", event(source));
        assertEquals(5, buff(active.state(), SLICE, "player").count());
        var stowed = send(engine, active.state(), 0, "chorus:weapon_stowed", event(source));
        assertEquals(active.state().engine().domain().buffs(), stowed.state().engine().domain().buffs());
        var state = stowed.state();
        for (int i = 1; i <= 5; i++) {
            String target = "target-" + i;
            var waiting = send(engine, state, i * 1_000_000L, "chorus:hit", hit(source, target, "hit-" + i, 0, false, false, true));
            assertEquals(6 - i, buff(waiting.state(), SLICE, "player").count());
            assertTrue(waiting.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.key().holder().equals(target)));
            var checked = allowed(waiting.actions().getFirst());
            assertEquals(10_000_000, checked.request().duration());
            var resumed = complete(engine, waiting, checked);
            assertEquals(i, resumed.state().engine().domain().buffs().instances().values().stream().filter(b -> b.definition().id().equals(SEVER)).count());
            assertEquals(resumed.state(), engine.transition(resumed.state(), new Completed(waiting.actions().getFirst().id(), checked)).state());
            if (i < 5) {
                assertEquals(5 - i, buff(resumed.state(), SLICE, "player").count());
                assertEquals((i + 8) * 1_000_000L, buff(resumed.state(), SLICE, "player").stacks().getFirst().expiresAt());
                var repeat = send(engine, resumed.state(), i * 1_000_000L, "chorus:hit", hit(source, "target-" + i, "repeat-" + i, 0, false, false, true));
                assertTrue(repeat.actions().isEmpty()); assertEquals(5 - i, buff(repeat.state(), SLICE, "player").count());
                state = repeat.state();
            } else {
                assertTrue(resumed.state().engine().domain().buffs().instances().values().stream().noneMatch(b -> b.definition().id().equals(SLICE)));
                state = resumed.state();
            }
        }
    }

    @Test void sliceRejectsLethalOrForeignWeaponHitsAndDeniedStatusesDoNotConsumeOrRefresh() throws Exception {
        var compiled = link("combat_damage", "strand_defense", "continuity", "slice"); var engine = engine(compiled); var source = source(SLICE);
        var active = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "chorus:class_ability_used", event(source));
        var lethal = send(engine, active.state(), 1_000_000, "chorus:hit", hit(source, "target", "fatal", 10, false, true, false));
        assertTrue(lethal.actions().isEmpty()); assertEquals(5, buff(lethal.state(), SLICE, "player").count());
        var foreign = new EffectSource("other", SLICE, "player", new com.imdomestic.chorus.effect.buff.BuffInstance.Origin("player", "other", "other-weapon", ""), Set.of());
        assertTrue(send(engine, active.state(), 0, "chorus:hit", hit(foreign, "target", "foreign", 1, false, false, false)).actions().isEmpty());
        for (var decision : List.of(StatusResult.Decision.DENIED, StatusResult.Decision.DEAD, StatusResult.Decision.MISSING)) {
            var waiting = send(engine, active.state(), 1_000_000, "chorus:hit", hit(source, "target", decision.name(), 1, false, false, false));
            var check = (StatusResult.Check) waiting.actions().getFirst().command();
            var denied = complete(engine, waiting, new StatusResult.Checked(check, decision));
            assertEquals(waiting.state().engine().domain(), denied.state().engine().domain());
            assertEquals(8_000_000, buff(denied.state(), SLICE, "player").stacks().getFirst().expiresAt());
        }
    }

    @Test void enhancedSliceUsesNineSecondsAndPvpStatusUsesFiveButMismatchedAuthorizationFails() throws Exception {
        var compiled = link("combat_damage", "strand_defense", "continuity", "slice"); var engine = engine(compiled); var base = source(SLICE);
        var source = new EffectSource(base.instance(), base.bundle(), base.holder(), base.origin(), Set.of("chorus:enhanced"));
        var active = send(engine, engine.initial(EffectState.empty().withSource(source).withMode(EffectState.Mode.PVP)), 0, "chorus:class_ability_used", event(source));
        assertEquals(9_000_000, buff(active.state(), SLICE, "player").stacks().getFirst().expiresAt());
        var waiting = send(engine, active.state(), 1_000_000, "chorus:hit", hit(source, "target", "hit", 1, false, false, false));
        var check = (StatusResult.Check) waiting.actions().getFirst().command(); assertEquals(5_000_000, check.duration());
        var success = complete(engine, waiting, new StatusResult.Checked(check, StatusResult.Decision.ALLOWED));
        assertEquals(10_000_000, buff(success.state(), SLICE, "player").stacks().getFirst().expiresAt());
        var wrong = new StatusResult.Checked(new StatusResult.Check("other-target", check.definition(), check.source(), check.stacks(), check.tier(), check.duration()), StatusResult.Decision.ALLOWED);
        var failed = engine.transition(waiting.state(), new Completed(waiting.actions().getFirst().id(), wrong));
        assertTrue(failed.state().engine().failure().isPresent());
        assertEquals(waiting.state().engine().domain(), failed.state().engine().domain());
        assertEquals(wrong, failed.state().receipts().get(waiting.actions().getFirst().id()));
    }

    @Test void applyingHitCountsOnceEvenWhenRefreshInitializationAndExistingListenerBothRun() throws Exception {
        var compiled = load("accumulator_initialization"); var engine = engine(compiled); var source = source("test:apply");
        var first = send(engine, engine.initial(EffectState.empty().withSource(source)), 0, "chorus:hit", hit(source, "target", "first", 50, true, false, false));
        assertTrue(first.state().engine().domain().buffs().instances().isEmpty());
        var applied = complete(engine, first, allowed(first.actions().getFirst()));
        assertEquals(50, buff(applied.state(), ACCUMULATOR, "target").components().numbers().get("damage"));
        var second = send(engine, applied.state(), 1_000_000, "chorus:hit", hit(source, "target", "second", 35, true, false, false));
        var refreshed = complete(engine, second, allowed(second.actions().getFirst()));
        assertEquals(85, buff(refreshed.state(), ACCUMULATOR, "target").components().numbers().get("damage"));
        assertEquals(Set.of("first", "second"), buff(refreshed.state(), ACCUMULATOR, "target").components().sets().get("seen_damage"));
        var thirdEvent = hit(source, "target", "third", 12, false, false, false);
        var third = send(engine, refreshed.state(), 2_000_000, "chorus:hit", thirdEvent);
        assertEquals(97, buff(third.state(), ACCUMULATOR, "target").components().numbers().get("damage"));
        var repeated = send(engine, third.state(), 2_000_000, "chorus:hit", thirdEvent);
        assertEquals(third.state().engine().domain(), repeated.state().engine().domain());
        var anotherTarget = send(engine, repeated.state(), 2_000_000, "chorus:hit", hit(source, "unaffected", "fourth", 999, false, false, false));
        assertEquals(repeated.state().engine().domain(), anotherTarget.state().engine().domain());
        var threshold = new EffectEvent("player", "target", source.origin(), Set.of(), Map.of("threshold", new Measure(97, Unit.DAMAGE)));
        var inspected = send(engine, anotherTarget.state(), 2_000_000, "test:inspect", threshold);
        assertInstanceOf(com.imdomestic.chorus.effect.data.Action.CueCommand.class, inspected.actions().getFirst().command());
        var finished = complete(engine, inspected, Empty.INSTANCE);
        var reset = send(engine, finished.state(), 3_000_000, "test:reset", event(source));
        assertEquals(0, buff(reset.state(), ACCUMULATOR, "target").components().numbers().get("damage"));
        var oldDamage = send(engine, reset.state(), 3_000_000, "chorus:hit", thirdEvent);
        assertEquals(0, buff(oldDamage.state(), ACCUMULATOR, "target").components().numbers().get("damage"));
        var replacement = send(engine, oldDamage.state(), 12_000_000, "chorus:hit", hit(source, "target", "third", 12, true, false, false));
        var fresh = complete(engine, replacement, allowed(replacement.actions().getFirst()));
        assertEquals(12, buff(fresh.state(), ACCUMULATOR, "target").components().numbers().get("damage"));
        assertNotEquals(buff(oldDamage.state(), ACCUMULATOR, "target").generation(), buff(fresh.state(), ACCUMULATOR, "target").generation());
    }

    @Test void componentUnitsNamesAndDeduplicationConfigurationAreCheckedAtLoad() throws Exception {
        for (String mutation : List.of("name", "unit", "pair", "set")) {
            var data = json("accumulator_initialization");
            var action = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject()
                    .getAsJsonArray("do").get(1).getAsJsonObject().getAsJsonArray("then").get(0).getAsJsonObject();
            switch (mutation) {
                case "name" -> action.addProperty("component", "missing");
                case "unit" -> action.add("value", JsonParser.parseString("{\"type\":\"chorus:constant\",\"value\":2,\"unit\":\"count\"}"));
                case "pair" -> action.remove("event_reference");
                case "set" -> action.addProperty("once_set", "undeclared");
            }
            assertTrue(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).error().isPresent(), mutation);
        }
        for (String fixture : List.of("slice", "accumulator_initialization")) {
            var compiled = fixture.equals("slice") ? link("combat_damage", "strand_defense", "continuity", "slice") : load(fixture);
            var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, compiled).getOrThrow();
            assertEquals(compiled.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        }
    }
}
