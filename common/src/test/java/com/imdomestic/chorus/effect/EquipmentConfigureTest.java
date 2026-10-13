package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class EquipmentConfigureTest {
    static Loadout wearing(Loadout.Gear gear) {
        return new Loadout(Map.of("test:arms", gear), Optional.empty(), EquipmentAccessTest.BOTH);
    }
    @Test void aWholeItemEditCanReallocateAnExactBudgetWithoutChangingIdentityParametersOrOtherItems() throws Exception {
        var data = json("equipment_configure"); data.getAsJsonObject("equipment").getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("weapon", true);
        var p = compile(data).equipment(); var gear = EquipmentBudgetTest.rolled(10, "expensive", "cheap");
        var before = new Loadout(Map.of("test:arms", gear, "test:class_item", EquipmentBudgetTest.gear("other", "free", "free")), Optional.of("test:arms"), EquipmentAccessTest.BOTH);
        assertThrows(IllegalArgumentException.class, () -> p.configure(before, "test:arms", Map.of("a", "expensive", "b", "expensive")));
        var input = new HashMap<>(Map.of("a", "cheap", "b", "expensive")); var after = p.configure(before, "test:arms", input); input.clear();
        var updated = after.slots().get("test:arms"); assertEquals(Map.of("a", "cheap", "b", "expensive"), updated.choices());
        assertEquals(gear.instance(), updated.instance()); assertEquals(gear.definition(), updated.definition()); assertEquals(gear.parameters(), updated.parameters());
        assertEquals(before.drawn(), after.drawn()); assertEquals(before.grants(), after.grants()); assertEquals(before.slots().get("test:class_item"), after.slots().get("test:class_item"));
        assertEquals("expensive", before.slots().get("test:arms").choices().get("a"));
    }
    @Test void readOnlySocketsMayBeRetainedButCannotBeInsertedChangedOrRemoved() throws Exception {
        var p = load("equipment_configure").equipment();
        var before = wearing(new Loadout.Gear("owned", "test:armor", Map.of("a", "cheap", "b", "free", "c", "cheap")));
        p.configure(before, "test:arms", Map.of("a", "free", "b", "cheap", "c", "cheap"));
        assertThrows(IllegalArgumentException.class, () -> p.configure(before, "test:arms", Map.of("a", "free", "b", "free", "c", "expensive")));
        assertThrows(IllegalArgumentException.class, () -> p.configure(before, "test:arms", Map.of("a", "free", "b", "free")));
        var without = wearing(EquipmentBudgetTest.gear("owned", "free", "free"));
        assertThrows(IllegalArgumentException.class, () -> p.configure(without, "test:arms", Map.of("a", "free", "b", "free", "c", "free")));
    }
    @Test void optionalEditableSocketsCanBeClearedButRequiredUnknownAndUnaffordableChoicesAreRejected() throws Exception {
        var data = json("equipment_configure"); EquipmentBudgetTest.item(data).getAsJsonObject("sockets").getAsJsonObject("c").addProperty("editable", true);
        var p = compile(data).equipment(); var before = wearing(new Loadout.Gear("owned", "test:armor", Map.of("a", "free", "b", "free", "c", "cheap")));
        assertFalse(p.configure(before, "test:arms", Map.of("a", "free", "b", "free")).slots().get("test:arms").choices().containsKey("c"));
        for (var choices : List.of(Map.of("a", "free"), Map.of("a", "unknown", "b", "free"), Map.of("a", "free", "b", "free", "unknown", "free"), Map.of("a", "expensive", "b", "expensive")))
            assertThrows(IllegalArgumentException.class, () -> p.configure(before, "test:arms", choices));
        for (String slot : List.of("test:unknown", "test:class_item")) assertThrows(IllegalArgumentException.class, () -> p.configure(before, slot, Map.of()));
    }
    @Test void finalLoadoutValidationAllowsRepairButDoesNotGrantAccessOrIgnoreOtherInvalidItems() throws Exception {
        var p = load("equipment_configure").equipment(); var before = EquipmentAccessTest.wearing("expensive", "cheap", EquipmentAccessTest.UNLOCKED);
        assertThrows(IllegalArgumentException.class, () -> p.validate(before));
        var after = p.configure(before, "test:arms", Map.of("a", "cheap", "b", "cheap")); p.validate(after);
        assertEquals(before.grants(), after.grants());
        assertThrows(IllegalArgumentException.class, () -> p.configure(after, "test:arms", Map.of("a", "expensive", "b", "cheap")));
        var invalidOther = new Loadout(Map.of("test:arms", before.slots().get("test:arms"), "test:class_item", EquipmentBudgetTest.gear("other", "expensive", "cheap")), Optional.empty(), before.grants());
        assertThrows(IllegalArgumentException.class, () -> p.configure(invalidOther, "test:arms", Map.of("a", "cheap", "b", "cheap")));
    }
    @Test void onlyChangedEffectsAreReplacedAndTheSameWeaponDoesNotEmitStowDrawFacts() throws Exception {
        var data = json("equipment_configure"); data.getAsJsonObject("equipment").getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("weapon", true);
        var p = compile(data).equipment(); var before = new Loadout(wearing(EquipmentBudgetTest.gear("owned", "cheap", "cheap")).slots(), Optional.of("test:arms"), EquipmentAccessTest.BOTH);
        var state = new EquipmentChange("player", Loadout.EMPTY, before).apply(EffectState.empty(), p).state();
        var after = p.configure(before, "test:arms", Map.of("a", "expensive", "b", "cheap"));
        var result = new EquipmentChange("player", before, after).apply(state, p);
        assertEquals(List.of("chorus:source_detached", "chorus:source_attached", "chorus:equipment_changed"), result.emitted().stream().map(s -> s.type()).toList());
        var key = state.sources().keySet().stream().filter(s -> s.endsWith("/socket/b")).findFirst().orElseThrow();
        assertEquals(state.sources().get(key), result.state().sources().get(key));
        assertTrue(new EquipmentChange("player", after, p.configure(after, "test:arms", after.slots().get("test:arms").choices())).apply(result.state(), p).emitted().isEmpty());
    }
    @Test void oldJsonAndCheckpointSocketsRemainReadOnlyAndNewProgramsRoundTrip() throws Exception {
        var old = load("equipment_budget").equipment(); assertTrue(old.schema().items().stream().flatMap(i -> i.sockets().values().stream()).noneMatch(EquipmentSchema.Socket::editable));
        var p = load("equipment_configure").program();
        assertEquals(p, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p), p.getClass()));
        assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        var encoded = EffectCheckpointCodec.DATA.encode(p.equipment().items().getFirst().sockets().get("a")).getAsJsonObject();
        encoded.addProperty("$", "chorus:effect/equipment/equipment_schema/socket"); encoded.getAsJsonObject("v").remove("editable");
        assertFalse(EffectCheckpointCodec.DATA.decode(encoded, EquipmentSchema.Socket.class).editable());
        var invalid = json("equipment_configure"); EquipmentBudgetTest.item(invalid).getAsJsonObject("sockets").getAsJsonObject("a").addProperty("editible", true);
        assertThrows(IllegalStateException.class, () -> compile(invalid));
    }
    @Test void allThreeExpiredArtifactModsCanBeReplacedTogetherWithoutRerollingTheChest() throws Exception {
        var p = NoDistractionsTest.program(UnflinchingTest.data("solar")).equipment(); var gear = UnflinchingTest.chest("solar_artifact", "solar_artifact", "solar_artifact");
        var before = new Loadout(Map.of("test:chest", gear), Optional.empty(), Set.of("chorus_d2:unlock/unflinching"));
        assertThrows(IllegalArgumentException.class, () -> p.validate(before));
        var choices = new TreeMap<>(gear.choices()); choices.replaceAll((_, _) -> "solar");
        var after = p.configure(before, "test:chest", choices); p.validate(after);
        assertEquals(gear.instance(), after.slots().get("test:chest").instance()); assertEquals(3, p.sources("player", after).size());
        var grants = Set.of("chorus_d2:unlock/unflinching", "chorus_d2:artifact/unflinching_solar");
        assertEquals(p.sources("player", new Loadout(before.slots(), before.drawn(), grants)), p.sources("player", after), "price alone does not restart identical effects");
    }
}
