package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EquipmentBudgetTest {
    static JsonObject item(JsonObject data) { return data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject(); }
    static JsonObject budget(JsonObject data) { return item(data).getAsJsonObject("budgets").getAsJsonObject("energy"); }
    static Loadout.Gear gear(String id, String a, String b) { return new Loadout.Gear(id, "test:armor", Map.of("a", a, "b", b)); }
    static Loadout wearing(Loadout.Gear gear) { return new Loadout(Map.of("test:arms", gear), Optional.empty()); }
    static Loadout.Gear rolled(int capacity, String a, String b) { return new Loadout.Gear("rolled", "test:rolled_armor", Map.of("a", a, "b", b), Map.of("energy", new Measure(capacity, Unit.COUNT))); }

    @Test void exactCapacityIsAcceptedAndSeparateItemsCannotSubsidizeAnOverBudgetItem() throws Exception {
        var p = load("equipment_budget").equipment();
        p.validate(wearing(gear("first", "expensive", "cheap")));
        p.validate(new Loadout(Map.of("test:arms", gear("first", "expensive", "cheap"), "test:class_item", gear("second", "expensive", "cheap")), Optional.empty()));
        var error = assertThrows(IllegalArgumentException.class, () -> p.validate(new Loadout(Map.of("test:arms", gear("first", "expensive", "expensive"), "test:class_item", gear("second", "free", "free")), Optional.empty())));
        assertTrue(error.getMessage().contains("12/10"));
        p.validate(wearing(gear("first", "free", "free")));
    }
    @Test void optionalSocketsAndInactiveDrawnEffectsStillHaveExplicitAllocationCosts() throws Exception {
        var data = json("equipment_budget");
        var slot = data.getAsJsonObject("equipment").getAsJsonArray("slots").get(0).getAsJsonObject(); slot.addProperty("weapon", true);
        item(data).getAsJsonObject("sockets").getAsJsonObject("a").getAsJsonObject("options").getAsJsonObject("expensive").addProperty("activation", "drawn");
        var p = compile(data).equipment(); var base = gear("first", "expensive", "cheap");
        p.validate(wearing(base)); assertEquals(1, p.sources("player", wearing(base)).size(), "undrawn effect absent but paid");
        p.validate(new Loadout(wearing(base).slots(), Optional.of("test:arms")));
        assertThrows(IllegalArgumentException.class, () -> p.validate(wearing(new Loadout.Gear("first", "test:armor", Map.of("a", "expensive", "b", "cheap", "c", "cheap")))));
        assertThrows(IllegalArgumentException.class, () -> p.validate(wearing(gear("first", "expensive", "expensive"))));
    }
    @Test void capacityParametersAndMultipleBudgetsAreValidatedIndependently() throws Exception {
        var p = load("equipment_budget").equipment(); p.validate(wearing(rolled(8, "cheap", "cheap")));
        p.validate(wearing(rolled(0, "free", "free")));
        assertThrows(IllegalArgumentException.class, () -> p.validate(wearing(rolled(7, "cheap", "cheap"))));
        for (var measure : List.of(new Measure(8.5, Unit.COUNT), new Measure(11, Unit.COUNT), new Measure(8, Unit.STAT_POINT)))
            assertThrows(IllegalArgumentException.class, () -> p.validate(wearing(new Loadout.Gear("rolled", "test:rolled_armor", Map.of("a", "cheap", "b", "cheap"), Map.of("energy", measure)))));
        var data = json("equipment_budget"); var extra = budget(data).deepCopy(); extra.addProperty("capacity", 7); item(data).getAsJsonObject("budgets").add("second", extra);
        var multiple = compile(data).equipment(); assertThrows(IllegalArgumentException.class, () -> multiple.validate(wearing(gear("first", "cheap", "cheap"))));
    }
    @Test void incompleteCostTablesAndInvalidCapacityDeclarationsFailAtCompilation() throws Exception {
        for (String fault : List.of("negative", "fractional", "too_large", "missing_capacity", "both", "unknown_parameter", "unknown_socket", "missing_option", "extra_option", "empty", "unknown_field", "fractional_cost", "negative_cost")) {
            var data = json("equipment_budget"); var b = budget(data); var costs = b.getAsJsonObject("costs");
            switch (fault) {
                case "negative" -> b.addProperty("capacity", -1);
                case "fractional" -> b.addProperty("capacity", 1.5);
                case "too_large" -> b.addProperty("capacity", 2147483648L);
                case "missing_capacity" -> b.remove("capacity");
                case "both" -> b.addProperty("capacity_parameter", "energy");
                case "unknown_parameter" -> { b.remove("capacity"); b.addProperty("capacity_parameter", "missing"); }
                case "unknown_socket" -> costs.add("missing", costs.get("a").deepCopy());
                case "missing_option" -> costs.getAsJsonObject("a").remove("free");
                case "extra_option" -> costs.getAsJsonObject("a").addProperty("missing", 0);
                case "empty" -> b.add("costs", new JsonObject());
                case "unknown_field" -> b.addProperty("regeneration", 1);
                case "fractional_cost" -> costs.getAsJsonObject("a").addProperty("cheap", 1.5);
                case "negative_cost" -> costs.getAsJsonObject("a").addProperty("cheap", -1);
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
        for (String fault : List.of("unit", "integral", "minimum", "maximum")) {
            var data = json("equipment_budget"); var parameter = data.getAsJsonObject("equipment").getAsJsonArray("items").get(1).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject("energy");
            switch (fault) { case "unit" -> parameter.addProperty("unit", "second"); case "integral" -> parameter.addProperty("integral", false); case "minimum" -> parameter.addProperty("minimum", -1); case "maximum" -> parameter.addProperty("maximum", 2147483648L); }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
    }
    @Test void costSumsDoNotWrapAndCatalogueMapsCannotChangeAfterConstruction() throws Exception {
        var data = json("equipment_budget"); var b = budget(data); b.addProperty("capacity", Integer.MAX_VALUE);
        for (var entry : b.getAsJsonObject("costs").entrySet()) entry.getValue().getAsJsonObject().addProperty("expensive", Integer.MAX_VALUE);
        var p = compile(data).equipment(); p.validate(wearing(gear("first", "expensive", "free")));
        var error = assertThrows(IllegalArgumentException.class, () -> p.validate(wearing(gear("first", "expensive", "expensive")))); assertTrue(error.getMessage().contains("4294967294"));
        var choices = new HashMap<>(Map.of("a", 1)); Map<String, Map<String, Integer>> tables = new HashMap<>(Map.of("socket", choices));
        var budget = new EquipmentSchema.Budget(Optional.of(1), Optional.empty(), tables); choices.put("a", 0); tables.clear();
        assertEquals(1, budget.costs().get("socket").get("a")); assertThrows(UnsupportedOperationException.class, () -> budget.costs().get("socket").put("a", 0));
    }
    @Test void rejectedReplacementDoesNotCommitSourcesOrDetachTheOldEquipment() throws Exception {
        var p = load("equipment_budget"); var before = wearing(gear("first", "expensive", "cheap"));
        var state = new EquipmentChange("player", Loadout.EMPTY, before).apply(EffectState.empty(), p.equipment()).state();
        var rejected = new EquipmentChange("player", before, wearing(gear("next", "expensive", "expensive")));
        assertThrows(IllegalArgumentException.class, () -> rejected.apply(state, p.equipment()));
        assertEquals(before, state.equipment().get("player")); assertEquals(p.equipment().sources("player", before), state.sources());
    }
    @Test void budgetProgramRoundTripsAndHistoricalItemsMigrateWithoutInventingABudget() throws Exception {
        var p = load("equipment_budget").program();
        assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        assertEquals(p, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p), EffectProgram.class));
        var current = p.equipment().items().getFirst(); var old = EffectCheckpointCodec.DATA.encode(current).getAsJsonObject();
        old.addProperty("$", "chorus:effect/equipment/equipment_schema/item"); old.getAsJsonObject("v").remove("budgets");
        var migrated = EffectCheckpointCodec.DATA.decode(old, EquipmentSchema.Item.class); assertTrue(migrated.budgets().isEmpty()); assertEquals(current.sockets(), migrated.sockets());
        old.getAsJsonObject("v").add("budgets", new JsonObject()); assertThrows(IllegalArgumentException.class, () -> EffectCheckpointCodec.DATA.decode(old, EquipmentSchema.Item.class));
    }
}
