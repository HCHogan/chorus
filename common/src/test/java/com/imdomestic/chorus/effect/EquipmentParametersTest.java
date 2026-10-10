package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EquipmentParametersTest {
    static Loadout.Gear gear(String id, double points) { return new Loadout.Gear(id, "test:armor", Map.of(), Map.of("roll", new Measure(points, Unit.STAT_POINT))); }
    static Loadout one(Loadout.Gear gear) { return new Loadout(Map.of("test:arms", gear), Optional.empty()); }
    static EffectSource source(Map<String, Measure> values) { return new EffectSource("source", "test:rolled", "player", new BuffInstance.Origin("player", "source", "", ""), Set.of(), values); }
    static double points(CompiledEffects p, EffectState s, String holder) { return p.attribute(s, holder, "test:points", new Measure(0, Unit.STAT_POINT), NumericQuery.Path.empty()).output().value(); }
    static DamageCommand attack() { return new DamageCommand("target", new BuffInstance.Origin("player", "weapon", "weapon", ""), 10, "minecraft:generic", Set.of(), Set.of(), false, Optional.of("test:damage")); }
    static final class Harness {
        final CompiledEffects p; final EffectSession session; final List<Double> heals = new ArrayList<>();
        Harness() throws Exception { this(load("equipment_parameters")); }
        Harness(CompiledEffects program) {
            p = program; session = new EffectSession(engine(p), EffectState.empty(), request -> {
                var c = (HealingCommand) request.command(); heals.add(c.amount());
                return new HealingReceipt(request.id().toString(), c, HealingReceipt.Outcome.APPLIED, c.amount(), c.amount(), 0);
            });
        }
        EffectState state() { return session.state().engine().domain(); }
        void equip(Loadout next) { session.start(0, new EquipmentChange("player", state().equipment().getOrDefault("player", Loadout.EMPTY), next).signal()); }
    }
    @Test void independentRollsSumAndMovingInstancesKeepsTheirBoundSources() throws Exception {
        var h = new Harness(); var a = gear("a", 20); var b = gear("b", 30);
        h.equip(new Loadout(Map.of("test:arms", a, "test:class_item", b), Optional.empty())); var before = h.state();
        assertEquals(50, points(h.p, before, "player")); assertEquals(0, points(h.p, before, "other")); assertEquals(List.of(2.0, 3.0), h.heals);
        h.equip(new Loadout(Map.of("test:arms", b, "test:class_item", a), Optional.empty()));
        assertEquals(before.sources(), h.state().sources()); assertEquals(2, h.heals.size());
        assertEquals(20, h.state().equipment().get("player").slots().get("test:class_item").parameters().get("roll").value());
    }
    @Test void replacementUsesOldCleanupAndDetachedAndCapturedActionsRetainOldValues() throws Exception {
        var h = new Harness(); h.equip(one(gear("same", 20))); var snapshot = h.p.captureDamage(h.state(), attack());
        h.session.start(0, new RuleEngine.Signal("test:later", new EffectEvent("player", "target", attack().source(), Set.of(), Map.of())));
        h.equip(one(gear("same", 40)));
        assertEquals(List.of(2.0, 2.0, 4.0), h.heals); assertEquals(40, points(h.p, h.state(), "player"));
        assertEquals(14, h.p.outgoing(h.state(), attack(), 10).orElseThrow().output().value(), 1e-12);
        assertEquals(12, h.p.outgoing(h.state(), snapshot.command("target"), 10).orElseThrow().output().value(), 1e-12);
        h.equip(Loadout.EMPTY); h.session.observe(100_000, List.of());
        assertEquals(List.of(2.0, 2.0, 4.0, 4.0, 2.0), h.heals); assertTrue(h.session.state().idle());
        assertEquals(12, h.p.outgoing(h.state(), snapshot.command("target"), 10).orElseThrow().output().value(), 1e-12);
    }
    @Test void capturedReactionRulesUseOriginalParametersAfterSameInstanceIsRerolled() throws Exception {
        var data = json("equipment_parameters"); var rules = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules");
        var rule = rules.get(0).deepCopy().getAsJsonObject(); rule.addProperty("id", "origin_hit"); rule.addProperty("on", "chorus:hit");
        rule.addProperty("binding", "origin_bundle"); rule.remove("if"); rules.add(rule);
        var h = new Harness(compile(data)); h.equip(one(gear("same", 20))); var shot = h.p.captureDamage(h.state(), attack());
        h.equip(one(gear("same", 40))); h.heals.clear();
        h.session.observe(0, DamageFacts.from(shot.command("target"), new DamageReceipt("hit", DamageReceipt.Outcome.APPLIED, 0, 0, 10, Optional.empty(), false)));
        assertEquals(List.of(2.0), h.heals); assertEquals(40, points(h.p, h.state(), "player"));
    }
    @Test void invalidInstanceValuesAreRejectedBeforeEquipmentOrSourceChanges() throws Exception {
        var h = new Harness(); h.equip(one(gear("valid", 10))); var before = h.state();
        for (var values : List.of(Map.<String, Measure>of(), Map.of("typo", new Measure(1, Unit.STAT_POINT)),
                Map.of("roll", new Measure(1, Unit.DAMAGE)), Map.of("roll", new Measure(-1, Unit.STAT_POINT)),
                Map.of("roll", new Measure(101, Unit.STAT_POINT)), Map.of("roll", new Measure(1.5, Unit.STAT_POINT)))) {
            var next = one(new Loadout.Gear("invalid", "test:armor", Map.of(), values));
            assertThrows(IllegalArgumentException.class, () -> new EquipmentChange("player", before.equipment().get("player"), next).apply(before, h.p.equipment()));
            assertSame(before, h.state()); assertEquals(List.of(1.0), h.heals);
        }
        h.p.equipment().validate(one(gear("minimum", 0))); h.p.equipment().validate(one(gear("maximum", 100)));
    }
    @Test void directSourcesAndInitialStatesRequireExactDeclaredNamesAndUnits() throws Exception {
        var p = load("equipment_parameters");
        for (var values : List.of(Map.<String, Measure>of(), Map.of("extra", new Measure(1, Unit.STAT_POINT)), Map.of("points", new Measure(1, Unit.DAMAGE)))) {
            var s = source(values); assertThrows(IllegalArgumentException.class, () -> p.validateSource(s));
            assertThrows(IllegalArgumentException.class, () -> points(p, EffectState.empty().withSource(s), "player"));
        }
        var s = source(Map.of("points", new Measure(25, Unit.STAT_POINT))); p.validateSource(s);
        assertEquals(25, points(p, EffectState.empty().withSource(s), "player"));
        assertThrows(IllegalArgumentException.class, () -> load("equipment").validateSource(s));
    }
    @Test void declarationsAndEverySocketMappingAreValidatedAtLinkTime() throws Exception {
        for (String invalid : List.of("unknown_read", "wrong_unit", "buff_scope", "missing_mapping", "unknown_field", "extra_mapping", "socket_mapping", "reversed", "empty_integer_range")) {
            var d = json("equipment_parameters"); var bundle = d.getAsJsonArray("bundles").get(0).getAsJsonObject();
            var item = d.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject();
            var effect = item.getAsJsonObject("effects").getAsJsonObject("stats"); var spec = item.getAsJsonObject("parameters").getAsJsonObject("roll");
            switch (invalid) {
                case "unknown_read" -> bundle.getAsJsonArray("modifiers").get(0).getAsJsonObject().getAsJsonObject("value").addProperty("name", "absent");
                case "wrong_unit" -> spec.addProperty("unit", "damage");
                case "buff_scope" -> bundle.addProperty("scope", "buff");
                case "missing_mapping" -> effect.remove("parameters");
                case "unknown_field" -> effect.getAsJsonObject("parameters").addProperty("points", "absent");
                case "extra_mapping" -> effect.getAsJsonObject("parameters").addProperty("extra", "roll");
                case "socket_mapping" -> item.add("sockets", JsonParser.parseString("{\"perk\":{\"required\":false,\"options\":{\"unselected\":{\"bundle\":\"test:rolled\"}}}}"));
                case "reversed" -> spec.addProperty("minimum", 101);
                case "empty_integer_range" -> { spec.addProperty("minimum", .2); spec.addProperty("maximum", .8); }
            }
            assertThrows(RuntimeException.class, () -> compile(d), invalid);
        }
    }
    @Test void codecsRoundTripTypedRollsAndOldDataDefaultsToEmpty() throws Exception {
        var p = load("equipment_parameters");
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow().program());
        var l = one(gear("a", 20)); assertEquals(l, EquipmentCodecs.LOADOUT.parse(JsonOps.INSTANCE, EquipmentCodecs.LOADOUT.encodeStart(JsonOps.INSTANCE, l).getOrThrow()).getOrThrow());
        assertTrue(EquipmentCodecs.GEAR.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"instance\":\"a\",\"definition\":\"test:armor\"}")).getOrThrow().parameters().isEmpty());
        var encoded = EquipmentCodecs.GEAR.encodeStart(JsonOps.INSTANCE, gear("a", 20)).getOrThrow().getAsJsonObject();
        encoded.getAsJsonObject("parameters").getAsJsonObject("roll").addProperty("typo", 1);
        assertTrue(EquipmentCodecs.GEAR.parse(JsonOps.INSTANCE, encoded).error().isPresent());
        assertThrows(IllegalArgumentException.class, () -> new Measure(Double.NaN, Unit.STAT_POINT));
        assertThrows(IllegalArgumentException.class, () -> source(Map.of("bad name", new Measure(1, Unit.STAT_POINT))));
    }
    @Test void instanceMapsAreImmutableAndCannotChangeCapturedFramesByAliasing() {
        var values = new HashMap<String, Measure>(); values.put("points", new Measure(10, Unit.STAT_POINT)); var s = source(values);
        values.put("points", new Measure(50, Unit.STAT_POINT)); assertEquals(10, s.parameters().get("points").value());
        assertThrows(UnsupportedOperationException.class, () -> s.parameters().clear());
        var roll = new HashMap<String, Measure>(); roll.put("roll", new Measure(20, Unit.STAT_POINT)); var g = new Loadout.Gear("a", "test:armor", Map.of(), roll);
        roll.clear(); assertEquals(20, g.parameters().get("roll").value()); assertThrows(UnsupportedOperationException.class, () -> g.parameters().clear());
    }
}
