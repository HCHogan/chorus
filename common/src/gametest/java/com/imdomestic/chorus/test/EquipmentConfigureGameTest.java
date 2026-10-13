package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.*;

public class EquipmentConfigureGameTest {
    private static int command(CommandSourceStack source, long revision, String choices) throws CommandSyntaxException {
        return source.getServer().getCommands().getDispatcher().execute("chorus equipment configure test:arms " + revision + " " + choices, source);
    }
    private static void reject(GameTestHelper h, Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        h.assertTrue(rejected, "invalid configure accepted");
    }
    @GameCase public void ordinaryPlayerEditsOneSourceAndPreservesAllOwnedComponentsAcrossSave(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_configure")) {
            var p = t.player(); var e = PlayerEquipment.get(p); e.grants(p, EquipmentAccessGameTest.BOTH, 0);
            var original = EquipmentBudgetGameTest.armor("owned", "cheap", "cheap", 10);
            original.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 2);
            p.getInventory().setItem(0, original); e.swap(p, "test:arms", 0, e.revision());
            var other = EquipmentBudgetGameTest.armor("other", "free", "free", null); p.getInventory().setItem(1, other); e.swap(p, "test:class_item", 1, e.revision());
            var old = original.get(ChorusComponents.EQUIPMENT.get()); var choices = Map.of("a", "expensive", "b", "cheap");
            var expected = original.copy(); expected.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(old.instance(), old.definition(), choices, old.parameters()));
            long revision = e.revision(); var source = p.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS);
            h.assertValueEqual(command(source, revision, "{\"a\":\"expensive\",\"b\":\"cheap\"}"), 1, "ordinary player could not edit");
            h.assertValueEqual(e.revision(), revision + 1, "one transaction must consume one revision");
            h.assertTrue(ItemStack.matches(expected, e.item("test:arms")) && ItemStack.matches(other, e.item("test:class_item")), "edit changed identity, rolled capacity, durability, name, enchantment or another item");
            h.assertTrue(p.getInventory().getItem(0).isEmpty() && p.getInventory().getItem(1).isEmpty(), "edit copied equipment into inventory");
            near(h, p.getHealth(), 23, "only changed socket detached and attached"); h.assertValueEqual(t.heals.size(), 4, "unchanged socket replayed");
            for (int i = 2; i < t.heals.size(); i++) {
                h.assertTrue(ItemStack.matches(expected, t.physical.get(i).items().get("test:arms")), "reaction saw old physical choices");
                h.assertValueEqual(t.domains.get(i).equipment().get(p.getUUID().toString()).slots().get("test:arms").choices(), choices, "reaction saw old logical choices");
            }
            command(source, e.revision(), "{\"a\":\"expensive\",\"b\":\"cheap\"}");
            h.assertValueEqual(e.revision(), revision + 1, "noop advanced revision"); h.assertValueEqual(t.heals.size(), 4, "noop replayed effects");
            boolean stale = false; try { command(source, revision, "{\"a\":\"cheap\",\"b\":\"cheap\"}"); } catch (CommandSyntaxException expectedError) { stale = true; }
            h.assertTrue(stale && ItemStack.matches(expected, e.item("test:arms")), "stale command modified item");
            var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess()); p.saveWithoutId(out);
            p.discard(); t.runtime.prepare(); t.players.remove(p); var restored = t.player(); restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            t.runtime.trackEquipment(restored); t.runtime.prepare();
            h.assertTrue(ItemStack.matches(expected, PlayerEquipment.get(restored).item("test:arms")), "physical save lost configured components"); t.settled();
        } h.succeed();
    }
    @GameCase public void playerCommandsCannotEditFixedRollsExceedBudgetsOrForgeAccessAndMetadata(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_configure")) {
            var p = t.player(); var e = PlayerEquipment.get(p); e.grants(p, Set.of("test:unlocked"), 0);
            var original = EquipmentBudgetGameTest.armor("owned", "cheap", "cheap", null); p.getInventory().setItem(0, original); e.swap(p, "test:arms", 0, e.revision());
            long revision = e.revision(); var state = t.runtime.state().engine().domain();
            var source = p.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS);
            for (String json : List.of("{\"a\":\"expensive\",\"b\":\"cheap\"}", "{\"a\":\"free\",\"b\":\"free\",\"c\":\"free\"}", "{\"a\":\"free\"}", "{\"a\":\"unknown\",\"b\":\"free\"}", "{\"instance\":\"forged\"}", "{\"grants\":[\"test:artifact\"]}", "{\"a\":1}", "{\"parameters\":{\"energy\":10}}", "[]")) {
                boolean rejected = false; try { command(source, revision, json); } catch (CommandSyntaxException expected) { rejected = true; }
                h.assertTrue(rejected, "invalid command accepted: " + json);
            }
            h.assertValueEqual(t.runtime.state().engine().domain(), state, "invalid commands changed domain");
            e.grants(p, EquipmentAccessGameTest.BOTH, e.revision()); revision = e.revision();
            reject(h, () -> e.configure(p, "test:arms", Map.of("a", "expensive", "b", "expensive"), e.revision()));
            var stranger = t.player(); reject(h, () -> e.configure(stranger, "test:arms", Map.of("a", "free", "b", "free"), e.revision()));
            h.assertTrue(ItemStack.matches(original, e.item("test:arms")), "rejection mutated physical stack"); h.assertValueEqual(e.revision(), revision, "rejection advanced revision"); near(h, p.getHealth(), 12, "rejection invoked effects");
            t.settled(); t.runtime.close(); reject(h, () -> e.configure(p, "test:arms", Map.of("a", "cheap", "b", "cheap"), e.revision()));
        } h.succeed();
    }
    @GameCase public void unknownCleanupRetainsTheCommittedChoicesAndWorldWriteWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_configure")) {
            var p = t.player(); var e = PlayerEquipment.get(p); e.grants(p, EquipmentAccessGameTest.BOTH, 0);
            p.getInventory().setItem(0, EquipmentBudgetGameTest.armor("owned", "cheap", "cheap", null)); e.swap(p, "test:arms", 0, e.revision()); long revision = e.revision();
            var choices = Map.of("a", "expensive", "b", "cheap"); t.failCleanup = true;
            reject(h, () -> e.configure(p, "test:arms", choices, revision));
            h.assertTrue(t.runtime.failure().isPresent(), "unknown cleanup did not stop runtime");
            h.assertValueEqual(e.item("test:arms").get(ChorusComponents.EQUIPMENT.get()).choices(), choices, "unknown write rolled back committed choices");
            h.assertValueEqual(e.revision(), revision + 1, "unknown cleanup lost committed revision"); near(h, p.getHealth(), 22, "actual cleanup write retained");
            reject(h, () -> e.configure(p, "test:arms", choices, e.revision())); t.runtime.prepare();
            near(h, p.getHealth(), 22, "unknown cleanup replayed"); h.assertValueEqual(t.heals.size(), 3, "later attach ran after unknown cleanup");
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:equipment_configure_flinch", maxTicks=40)
    public void changingArmorModsKeepsTheWeaponAmmoAimAndActivePerkWhileChangingRealFlinch(GameTestHelper h) throws Exception {
        var t = UnflinchingGameTest.open(h); UnflinchingGameTest.mods(t, "solar"); WeaponAimGameTest.input(t, true);
        at(t, 20, () -> { try (t) {
            t.runtime.prepare(); var equipment = PlayerEquipment.get(t.owner); var before = equipment.item("test:chest");
            h.assertValueEqual(t.runtime.fire(t.owner).outcome(), WeaponFire.Outcome.ACCEPTED, "actual weapon shot");
            h.assertValueEqual(PrecisionAmmoGameTest.magazine(t, "a"), 11, "one round paid");
            NoDistractionsGameTest.hit(t); NoDistractionsGameTest.angle(t, .90625 * .9 * .75 * .65);
            equipment.configure(t.owner, "test:chest", Map.of("mod_0", "solar", "mod_1", "solar", "mod_2", "solar"), equipment.revision());
            h.assertTrue(NoDistractionsGameTest.active(t), "mod edit reset weapon perk"); h.assertValueEqual(PrecisionAmmoGameTest.magazine(t, "a"), 11, "mod edit refilled weapon");
            h.assertValueEqual(equipment.item("test:chest").get(ChorusComponents.EQUIPMENT.get()).instance(), before.get(ChorusComponents.EQUIPMENT.get()).instance(), "mod edit replaced physical identity");
            NoDistractionsGameTest.hit(t); NoDistractionsGameTest.angle(t, .90625 * .9 * .65 * .65);
            var grants = new HashSet<>(equipment.grants()); grants.add("chorus_d2:artifact/unflinching_solar"); equipment.grants(t.owner, grants, equipment.revision());
            var sources = t.runtime.state().engine().domain().sources();
            equipment.configure(t.owner, "test:chest", Map.of("mod_0", "solar_artifact", "mod_1", "solar_artifact", "mod_2", "solar_artifact"), equipment.revision());
            h.assertValueEqual(t.runtime.state().engine().domain().sources(), sources, "discount alone restarted identical effects");
            NoDistractionsGameTest.hit(t); NoDistractionsGameTest.angle(t, .90625 * .9 * .65 * .65); h.succeed();
        }});
    }
    @GameCase(environment="chorus_gametest:equipment_configure_repair", maxTicks=20)
    public void allExpiredArtifactModsCanBeRepairedInOnePhysicalTransaction(GameTestHelper h) throws Exception {
        var t = UnflinchingGameTest.open(h); var equipment = PlayerEquipment.get(t.owner);
        equipment.grants(t.owner, Set.of("chorus_d2:unlock/unflinching", "chorus_d2:artifact/unflinching_solar"), equipment.revision());
        UnflinchingGameTest.mods(t, "solar_artifact", "solar_artifact", "solar_artifact"); var original = equipment.item("test:chest");
        equipment.grants(t.owner, Set.of("chorus_d2:unlock/unflinching"), equipment.revision()); h.assertTrue(equipment.inactiveReason().isPresent(), "expired mods remained active");
        reject(h, () -> equipment.configure(t.owner, "test:chest", Map.of("mod_0", "solar", "mod_1", "solar_artifact", "mod_2", "solar_artifact"), equipment.revision()));
        long revision = equipment.revision(); equipment.configure(t.owner, "test:chest", Map.of("mod_0", "solar", "mod_1", "solar", "mod_2", "solar"), revision);
        h.assertTrue(equipment.inactiveReason().isEmpty(), "valid replacement failed to reactivate loadout"); h.assertValueEqual(equipment.revision(), revision + 1, "batch edit was split into multiple commits");
        h.assertValueEqual(equipment.item("test:chest").get(ChorusComponents.EQUIPMENT.get()).instance(), original.get(ChorusComponents.EQUIPMENT.get()).instance(), "repair lost physical identity");
        WeaponAimGameTest.input(t, true);
        at(t, 4, () -> { try (t) { NoDistractionsGameTest.hit(t); NoDistractionsGameTest.angle(t, .90625 * .9 * .65); h.succeed(); }});
    }
}
