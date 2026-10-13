package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EquipmentEditorTest {
    @Test void descriptorUsesRealRollCapacityAndReportsChoiceEligibilitySeparatelyFromCost() throws Exception {
        var p = load("equipment_configure").equipment();
        var before = new Loadout(Map.of("test:arms", EquipmentBudgetTest.rolled(8, "cheap", "cheap")), Optional.empty(), EquipmentAccessTest.UNLOCKED);
        var editor = p.editor(before, "test:arms");
        assertEquals(Map.of("energy", 8), editor.capacities()); assertTrue(editor.available());
        assertFalse(editor.sockets().get("a").options().get("expensive").available());
        assertEquals(Map.of("energy", 6), editor.sockets().get("a").options().get("expensive").costs());
        assertEquals(Map.of("energy", 8L), editor.usage(before.slots().get("test:arms").choices()));
        assertTrue(editor.valid(before.slots().get("test:arms").choices(), Map.of("a", "cheap", "b", "cheap")));
        assertFalse(editor.valid(before.slots().get("test:arms").choices(), Map.of("a", "expensive", "b", "free")), "spare capacity cannot unlock a choice");
        assertFalse(editor.sockets().get("c").editable());
    }
    @Test void draftCanBeTemporarilyOverBudgetButOnlyFinalValidChoicesCanBeApplied() throws Exception {
        var p = load("equipment_configure").equipment(); var before = EquipmentConfigureTest.wearing(EquipmentBudgetTest.gear("owned", "expensive", "cheap"));
        var editor = p.editor(before, "test:arms"); var original = before.slots().get("test:arms").choices();
        assertEquals(Map.of("energy", 12L), editor.usage(Map.of("a", "expensive", "b", "expensive")));
        assertFalse(editor.valid(original, Map.of("a", "expensive", "b", "expensive")));
        assertTrue(editor.valid(original, Map.of("a", "cheap", "b", "expensive")));
        assertFalse(editor.valid(original, Map.of("a", "free")));
        assertFalse(editor.valid(original, Map.of("a", "free", "b", "free", "c", "free")));
        assertFalse(editor.valid(original, Map.of("a", "unknown", "b", "free")));
        assertThrows(IllegalArgumentException.class, () -> editor.usage(Map.of("a", "unknown")));
        var locked = p.editor(new Loadout(before.slots(), before.drawn()), "test:arms"); assertFalse(locked.available()); assertFalse(locked.valid(original, Map.of("a", "free", "b", "free")));
    }
    @Test void editorDataIsBoundedImmutableAndKeepsLargeAllocationSumsExact() {
        var costs = new HashMap<>(Map.of("energy", Integer.MAX_VALUE)); var option = new EquipmentEditor.Option("test:effect", true, costs); costs.clear();
        var socket = new EquipmentEditor.Socket(true, true, Map.of("chosen", option));
        var editor = new EquipmentEditor("test:item", true, Map.of("a", socket, "b", socket), Map.of("energy", Integer.MAX_VALUE));
        assertEquals(Map.of("energy", 4294967294L), editor.usage(Map.of("a", "chosen", "b", "chosen")));
        assertFalse(editor.valid(Map.of(), Map.of("a", "chosen", "b", "chosen")));
        assertThrows(UnsupportedOperationException.class, () -> editor.sockets().clear());
        assertThrows(IllegalArgumentException.class, () -> new EquipmentEditor("test:item", true, Map.of("a", socket), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EquipmentEditor.Option("test:effect", true, Map.of("energy", -1)));
        var tooMany = new HashMap<String, EquipmentEditor.Socket>(); for (int i = 0; i <= EquipmentEditor.MAX_SOCKETS; i++) tooMany.put("s" + i, socket);
        assertThrows(IllegalArgumentException.class, () -> new EquipmentEditor("test:item", true, tooMany, Map.of("energy", 1)));
    }
    @Test void expiredUnflinchingChoicesRemainVisibleAndCanBeReplacedInOneDraft() throws Exception {
        var p = NoDistractionsTest.program(UnflinchingTest.data("solar")).equipment(); var gear = UnflinchingTest.chest("solar_artifact", "solar_artifact", "solar_artifact");
        var before = new Loadout(Map.of("test:chest", gear), Optional.empty(), Set.of("chorus_d2:unlock/unflinching"));
        var editor = p.editor(before, "test:chest"); assertEquals(Map.of("energy", 3L), editor.usage(gear.choices()));
        assertFalse(editor.valid(gear.choices(), gear.choices()));
        var draft = new TreeMap<>(gear.choices()); draft.replaceAll((_, _) -> "solar");
        assertTrue(editor.valid(gear.choices(), draft)); assertEquals(Map.of("energy", 9L), editor.usage(draft)); p.configure(before, "test:chest", draft);
        assertEquals(editor.sockets().get("mod_0").options().get("solar").effect(), editor.sockets().get("mod_0").options().get("solar_artifact").effect());
    }
}
