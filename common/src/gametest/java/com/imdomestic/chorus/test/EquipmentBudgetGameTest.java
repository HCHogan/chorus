package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.network.EquipmentNetworkServer;
import com.imdomestic.chorus.network.EquipmentPayloads.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.*;

public class EquipmentBudgetGameTest {
    static ItemStack armor(String id, String a, String b, Integer energy) {
        var stack = new ItemStack(Items.DIAMOND_CHESTPLATE); stack.setDamageValue(7); stack.set(DataComponents.CUSTOM_NAME, Component.literal("Budget " + id));
        stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, energy == null ? "test:armor" : "test:rolled_armor", Map.of("a", a, "b", b),
                energy == null ? Map.of() : Map.of("energy", new Measure(energy, Unit.COUNT)))); return stack;
    }
    @GameCase public void overBudgetClientReplacementPreservesBothOwnedStacksAndTheActiveEffects(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_budget")) {
            var p = t.player(); var e = PlayerEquipment.get(p); var original = armor("valid", "expensive", "cheap", null);
            p.getInventory().setItem(0, original); e.swap(p, "test:arms", 0, e.revision()); t.settled(); near(h, p.getHealth(), 12, "valid two sources healed");
            var invalid = armor("invalid", "expensive", "expensive", null); p.getInventory().setItem(0, invalid);
            var sent = new ArrayList<View>(); var network = new EquipmentNetworkServer((_, view) -> sent.add(view)); network.visit(p, new Visit(UUID.randomUUID(), true));
            var view = sent.getLast(); var state = t.runtime.state().engine().domain(); long revision = e.revision();
            network.request(p, new Request(view.screen(), view.session(), view.sequence(), Operation.SWAP, "test:arms", "", 0));
            h.assertValueEqual(sent.getLast().reply(), Reply.REJECTED, "over-budget request accepted");
            h.assertTrue(ItemStack.matches(original, e.item("test:arms")) && ItemStack.matches(invalid, p.getInventory().getItem(0)), "rejected transfer changed ownership or components");
            h.assertValueEqual(e.revision(), revision, "rejected transfer advanced revision"); h.assertValueEqual(t.runtime.state().engine().domain(), state, "rejected transfer changed domain");
            near(h, p.getHealth(), 12, "invalid replacement invoked effects"); h.assertValueEqual(t.heals.size(), 2, "duplicate activation"); t.settled();
        } h.succeed();
    }
    @GameCase public void perItemCapacitySurvivesPhysicalSaveAndRejectsUnderfundedRolls(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_budget")) {
            var p = t.player(); var e = PlayerEquipment.get(p); var original = armor("rolled", "cheap", "cheap", 8);
            p.getInventory().setItem(0, original); e.swap(p, "test:arms", 0, e.revision());
            var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess()); p.saveWithoutId(out);
            p.discard(); t.runtime.prepare(); t.players.remove(p); var restored = t.player();
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            t.runtime.trackEquipment(restored); t.runtime.prepare(); e = PlayerEquipment.get(restored);
            h.assertTrue(ItemStack.matches(original, e.item("test:arms")), "save lost capacity or ownership");
            var invalid = armor("underfunded", "cheap", "cheap", 7); restored.getInventory().setItem(0, invalid); var before = e.snapshot();
            boolean rejected = false; try { e.swap(restored, "test:class_item", 0, e.revision()); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected && e.item("test:class_item").isEmpty(), "other item's unused budget subsidized item");
            h.assertValueEqual(e.revision(), before.revision(), "underfunded roll changed revision");
            h.assertTrue(ItemStack.matches(invalid, restored.getInventory().getItem(0)), "rejection lost incoming item"); t.settled();
        } h.succeed();
    }
    @GameCase public void aStricterReloadDeactivatesInvalidEquipmentWithoutPreventingRetrieval(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_budget")) {
            var p = t.player(); var e = PlayerEquipment.get(p); var original = armor("valid-before-reload", "expensive", "cheap", null);
            p.getInventory().setItem(0, original); e.swap(p, "test:arms", 0, e.revision());
            var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, t.program.program()).getOrThrow().getAsJsonObject();
            data.getAsJsonObject("equipment").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("budgets").getAsJsonObject("energy").addProperty("capacity", 9);
            t.runtime.close(); t.install(EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow());
            t.runtime.trackEquipment(p); t.runtime.prepare();
            h.assertTrue(e.inactiveReason().orElse("").contains("10/9"), "invalid budget not surfaced");
            h.assertTrue(ItemStack.matches(original, e.item("test:arms")), "reload erased physical item");
            h.assertTrue(t.runtime.state().engine().domain().sources().isEmpty(), "over-budget sources remained active");
            e.swap(p, "test:arms", 0, e.revision());
            h.assertTrue(e.isEmpty() && ItemStack.matches(original, p.getInventory().getItem(0)), "invalid item's retrieval blocked or duplicated"); t.settled();
        } h.succeed();
    }
}
