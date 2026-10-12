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

class EquipmentAccessTest {
    static final Set<String> UNLOCKED = Set.of("test:unlocked"), BOTH = Set.of("test:unlocked", "test:artifact");
    static Loadout wearing(String a, String b, Set<String> grants) { return new Loadout(EquipmentBudgetTest.wearing(EquipmentBudgetTest.gear("owned", a, b)).slots(), Optional.empty(), grants); }
    @Test void itemAndOptionRequirementsAreConjunctiveAndGrantsAreNotItemTags() throws Exception {
        var data = json("equipment_access"); EquipmentBudgetTest.item(data).getAsJsonArray("tags").add("test:unlocked");
        var p = compile(data).equipment();
        assertThrows(IllegalArgumentException.class, () -> p.validate(wearing("free", "free", Set.of())));
        p.validate(wearing("cheap", "cheap", UNLOCKED));
        assertThrows(IllegalArgumentException.class, () -> p.validate(wearing("expensive", "cheap", UNLOCKED)));
        assertThrows(IllegalArgumentException.class, () -> p.validate(wearing("expensive", "cheap", Set.of("test:artifact"))));
        p.validate(wearing("expensive", "cheap", BOTH));
        assertThrows(IllegalArgumentException.class, () -> p.validate(wearing("expensive", "expensive", BOTH)), "grants do not override budget");
    }
    @Test void grantOnlyLoadoutsAreRetainedAndCannotAuthorizeAnotherHolder() throws Exception {
        var p = load("equipment_access"); var empty = new Loadout(Map.of(), Optional.empty(), BOTH);
        var state = new EquipmentChange("player", Loadout.EMPTY, empty).apply(EffectState.empty(), p.equipment()).state();
        assertEquals(BOTH, state.equipment().get("player").grants()); assertTrue(state.sources().isEmpty());
        var saved = EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(state), EffectState.class); assertEquals(state, saved);
        assertThrows(IllegalArgumentException.class, () -> new EquipmentChange("other", Loadout.EMPTY, wearing("expensive", "cheap", Set.of())).apply(saved, p.equipment()));
    }
    @Test void unrelatedGrantsDoNotRestartSourcesAndRevocationUsesAnExplicitInactiveProjection() throws Exception {
        var p = load("equipment_access").equipment(); var selected = wearing("expensive", "cheap", BOTH);
        var state = new EquipmentChange("player", Loadout.EMPTY, selected).apply(EffectState.empty(), p).state();
        var extra = new HashSet<>(BOTH); extra.add("test:unrelated"); var unchanged = new Loadout(selected.slots(), selected.drawn(), extra);
        var change = new EquipmentChange("player", selected, unchanged).apply(state, p);
        assertEquals(state.sources(), change.state().sources()); assertEquals(1, change.emitted().size(), "only equipment_changed, no source churn");
        assertThrows(IllegalArgumentException.class, () -> new EquipmentChange("player", unchanged, wearing("expensive", "cheap", UNLOCKED)).apply(change.state(), p));
        var inactive = new Loadout(Map.of(), Optional.empty(), UNLOCKED);
        var revoked = new EquipmentChange("player", unchanged, inactive).apply(change.state(), p).state();
        assertTrue(revoked.sources().isEmpty()); assertEquals(UNLOCKED, revoked.equipment().get("player").grants());
    }
    @Test void timedWeaponSwitchAndRestoreKeepHolderGrants() throws Exception {
        var h = new WeaponHandlingTest.Harness(); var guns = WeaponHandlingTest.loadout("primary"); h.equip(new Loadout(guns.slots(), guns.drawn(), BOTH));
        h.select("secondary"); h.until(200_000); assertEquals(BOTH, h.physical.grants()); h.restore();
        h.until(500_000); assertEquals(Optional.of("test:secondary"), h.physical.drawn()); assertEquals(BOTH, h.physical.grants());
    }
    @Test void strictCatalogueChecksAndImmutableSetsRejectMisspelledRequirements() throws Exception {
        for (String fault : List.of("grant", "socket", "option", "extra", "empty")) {
            var data = json("equipment_access"); var r = EquipmentBudgetTest.item(data).getAsJsonObject("requires"); var choices = r.getAsJsonObject("choices");
            switch (fault) {
                case "grant" -> r.getAsJsonArray("all").add("bad grant");
                case "socket" -> choices.add("missing", choices.get("a").deepCopy());
                case "option" -> choices.getAsJsonObject("a").add("missing", new JsonArray());
                case "extra" -> r.addProperty("automatic", true);
                case "empty" -> choices.add("a", new JsonObject());
            }
            assertThrows(RuntimeException.class, () -> compile(data), fault);
        }
        var mutable = new HashSet<>(BOTH); var loadout = wearing("free", "free", mutable); mutable.clear(); assertEquals(BOTH, loadout.grants());
        assertThrows(UnsupportedOperationException.class, () -> loadout.grants().clear());
        assertThrows(IllegalArgumentException.class, () -> wearing("free", "free", Set.of("invalid")));
    }
    @Test void currentProgramAndLoadoutRoundTripWhileOldStorageGrantsNothing() throws Exception {
        var p = load("equipment_access").program();
        assertEquals(p, EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, p).getOrThrow()).getOrThrow());
        assertEquals(p, EffectCheckpointCodec.DATA.decode(EffectCheckpointCodec.DATA.encode(p), EffectProgram.class));
        var selected = wearing("expensive", "cheap", BOTH); assertEquals(selected, EquipmentCodecs.LOADOUT.parse(JsonOps.INSTANCE, EquipmentCodecs.LOADOUT.encodeStart(JsonOps.INSTANCE, selected).getOrThrow()).getOrThrow());
        var old = EffectCheckpointCodec.DATA.encode(selected).getAsJsonObject(); old.addProperty("$", "chorus:effect/equipment/loadout"); old.getAsJsonObject("v").remove("grants");
        var migrated = EffectCheckpointCodec.DATA.decode(old, Loadout.class); assertTrue(migrated.grants().isEmpty()); assertEquals(selected.slots(), migrated.slots());
        var item = EffectCheckpointCodec.DATA.encode(p.equipment().items().getFirst()).getAsJsonObject(); item.addProperty("$", "chorus:effect/equipment/equipment_schema/item_v2"); item.getAsJsonObject("v").remove("requires");
        var migratedItem = EffectCheckpointCodec.DATA.decode(item, EquipmentSchema.Item.class); assertEquals(EquipmentSchema.Requirements.NONE, migratedItem.requires()); assertFalse(migratedItem.budgets().isEmpty());
    }
    @Test void discountedUnflinchingNeedsItsSpecificArtifactGrantAndRetainsTheSameEffect() throws Exception {
        var p = NoDistractionsTest.program(UnflinchingTest.data("solar")).equipment(); var base = Set.of("chorus_d2:unlock/unflinching");
        p.validate(new Loadout(Map.of("test:chest", UnflinchingTest.chest()), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> p.validate(new Loadout(Map.of("test:chest", UnflinchingTest.chest("solar")), Optional.empty())));
        var chest = UnflinchingTest.chest("solar_artifact", "solar_artifact", "solar_artifact");
        assertThrows(IllegalArgumentException.class, () -> p.validate(new Loadout(Map.of("test:chest", chest), Optional.empty(), Set.of("chorus_d2:artifact/unflinching_solar"))));
        assertThrows(IllegalArgumentException.class, () -> p.validate(new Loadout(Map.of("test:chest", chest), Optional.empty(), base)));
        var wrong = new HashSet<>(base); wrong.add("chorus_d2:artifact/unflinching_arc");
        assertThrows(IllegalArgumentException.class, () -> p.validate(new Loadout(Map.of("test:chest", chest), Optional.empty(), wrong)));
        var right = new HashSet<>(base); right.add("chorus_d2:artifact/unflinching_solar");
        var loadout = new Loadout(Map.of("test:chest", chest), Optional.empty(), right); var sources = p.sources("player", loadout);
        assertEquals(3, sources.size()); assertTrue(sources.values().stream().allMatch(s -> s.bundle().equals("chorus_d2:unflinching_solar")));
        var item = p.schema().items().stream().filter(i -> i.id().equals(chest.definition())).findFirst().orElseThrow();
        for (var costs : item.budgets().get("energy").costs().values()) assertEquals(1, costs.get("solar_artifact"));
    }
}
