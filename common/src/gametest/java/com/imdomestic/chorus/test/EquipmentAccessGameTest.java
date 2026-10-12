package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.RampageGameTest.at;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.network.EquipmentNetworkServer;
import com.imdomestic.chorus.network.EquipmentPayloads.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.*;

public class EquipmentAccessGameTest {
    static final Set<String> BOTH = Set.of("test:unlocked", "test:artifact");
    @GameCase public void clientRequestsCannotGrantAccessAndOldViewsBecomeStaleWhenAnOperatorGrantsIt(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_access")) {
            var p = t.player(); var e = PlayerEquipment.get(p); var item = EquipmentBudgetGameTest.armor("locked", "expensive", "cheap", null); p.getInventory().setItem(0, item);
            var sent = new ArrayList<View>(); var network = new EquipmentNetworkServer((_, v) -> sent.add(v)); network.visit(p, new Visit(UUID.randomUUID(), true)); var old = sent.getLast();
            network.request(p, new Request(old.screen(), old.session(), old.sequence(), Operation.SWAP, "test:arms", "", 0));
            h.assertValueEqual(sent.getLast().reply(), Reply.REJECTED, "locked client request accepted"); h.assertTrue(e.isEmpty() && ItemStack.matches(item, p.getInventory().getItem(0)), "rejection moved item");
            var source = p.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS); boolean denied = false;
            try { h.getLevel().getServer().getCommands().getDispatcher().execute("chorus equipment grant test:unlocked 0", source); } catch (CommandSyntaxException expected) { denied = true; }
            h.assertTrue(denied && e.grants().isEmpty(), "ordinary player self-granted unlock");
            var admin = source.withPermission(PermissionSet.ALL_PERMISSIONS);
            h.getLevel().getServer().getCommands().getDispatcher().execute("chorus equipment grant test:unlocked 0", admin);
            h.getLevel().getServer().getCommands().getDispatcher().execute("chorus equipment grant test:artifact 1", admin);
            h.assertValueEqual(e.grants(), BOTH, "operator grants missing");
            network.request(p, new Request(old.screen(), old.session(), old.sequence(), Operation.SWAP, "test:arms", "", 0));
            h.assertValueEqual(sent.getLast().reply(), Reply.STALE, "old view survived permission revision");
            var current = sent.getLast(); network.request(p, new Request(current.screen(), current.session(), current.sequence(), Operation.SWAP, "test:arms", "", 0));
            h.assertValueEqual(sent.getLast().reply(), Reply.APPLIED, "authorized equip rejected"); near(h, p.getHealth(), 12, "authorized effects once");
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            try { View.CODEC.encode(buf, sent.getLast()); var view = View.CODEC.decode(buf); h.assertTrue(view.content().same(sent.getLast().content()), "presentation changed across wire"); h.assertTrue(view.content().equipment().grants().isEmpty(), "server grants leaked into presentation authority"); } finally { buf.release(); }
            t.settled();
        } h.succeed();
    }
    @GameCase public void revokeAndRegrantReconcileSourcesWithoutMovingItemsOrRepeatingANoopGrant(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_access")) {
            var p = t.player(); var e = PlayerEquipment.get(p); e.grants(p, BOTH, 0); var item = EquipmentBudgetGameTest.armor("owned", "expensive", "cheap", null);
            p.getInventory().setItem(0, item); e.swap(p, "test:arms", 0, e.revision()); near(h, p.getHealth(), 12, "initial attach");
            long before = e.revision(); e.grants(p, Set.of("test:unlocked"), before);
            h.assertTrue(ItemStack.matches(item, e.item("test:arms")) && p.getInventory().getItem(0).isEmpty(), "revocation moved ownership");
            h.assertTrue(e.inactiveReason().orElse("").contains("test:artifact") && t.runtime.state().engine().domain().sources().isEmpty(), "locked sources still active");
            boolean stale = false; try { e.grants(p, BOTH, before); } catch (IllegalStateException expected) { stale = true; } h.assertTrue(stale, "stale grant update accepted");
            e.grants(p, BOTH, e.revision()); near(h, p.getHealth(), 14, "regrant reactivated once"); long granted = e.revision(); e.grants(p, BOTH, granted);
            h.assertValueEqual(e.revision(), granted, "noop grant advanced revision"); near(h, p.getHealth(), 14, "noop grant replayed effects");
            e.grants(p, Set.of(), e.revision()); e.swap(p, "test:arms", 0, e.revision());
            h.assertTrue(e.isEmpty() && ItemStack.matches(item, p.getInventory().getItem(0)), "revoked item could not be retrieved"); t.settled();
        } h.succeed();
    }
    @GameCase public void grantsSurvivePlayerSaveButDoNotTravelWithAGiftedItem(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_access")) {
            var p = t.player(); var e = PlayerEquipment.get(p); e.grants(p, BOTH, 0);
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess()); p.saveWithoutId(output);
            p.discard(); t.runtime.prepare(); t.players.remove(p); var restored = t.player(); restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
            t.runtime.trackEquipment(restored); t.runtime.prepare(); e = PlayerEquipment.get(restored); h.assertValueEqual(e.grants(), BOTH, "empty-container save lost grants");
            h.assertValueEqual(t.runtime.state().engine().domain().equipment().get(restored.getUUID().toString()).grants(), BOTH, "domain missed saved grants");
            var item = EquipmentBudgetGameTest.armor("gift", "expensive", "cheap", null); restored.getInventory().setItem(0, item); e.swap(restored, "test:arms", 0, e.revision()); e.swap(restored, "test:arms", 0, e.revision());
            var recipient = t.player(); var other = PlayerEquipment.get(recipient); recipient.getInventory().setItem(0, restored.getInventory().removeItemNoUpdate(0));
            boolean denied = false; try { other.swap(recipient, "test:arms", 0, other.revision()); } catch (IllegalArgumentException expected) { denied = true; }
            h.assertTrue(denied && other.grants().isEmpty() && other.isEmpty(), "item carried donor grants"); h.assertTrue(ItemStack.matches(item, recipient.getInventory().getItem(0)), "rejection lost gift"); t.settled();
        } h.succeed();
    }
    @GameCase public void grantsRemainThroughDeathAndMoveToTheReplacementPlayerOnlyOnce(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_access")) {
            var p = t.player(); var e = PlayerEquipment.get(p); e.grants(p, BOTH, 0);
            p.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), 1000); h.assertTrue(!p.isAlive(), "death fixture survived");
            h.assertValueEqual(e.grants(), BOTH, "death erased unlocks"); var replacement = t.player(); var destination = PlayerEquipment.get(replacement);
            destination.grants(replacement, Set.of("test:existing"), destination.revision());
            boolean refused = false; try { destination.transferFrom(e); } catch (IllegalStateException expected) { refused = true; }
            h.assertTrue(refused && destination.grants().equals(Set.of("test:existing")) && e.grants().equals(BOTH), "transfer overwrote an existing holder's grants");
            destination.grants(replacement, Set.of(), destination.revision()); replacement.restoreFrom(p, false);
            t.runtime.trackEquipment(replacement); t.runtime.prepare();
            h.assertValueEqual(PlayerEquipment.get(replacement).grants(), BOTH, "respawn lost unlocks"); h.assertTrue(e.grants().isEmpty(), "old player retained transferred grants"); t.settled();
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:unflinching_access", maxTicks=25)
    public void artifactDiscountHasTheSameRealFlinchAndRevocationRemovesItsPhysicalBenefit(GameTestHelper h) throws Exception {
        var t = NoDistractionsGameTest.open(h, false, data -> {
            UnflinchingGameTest.prepare(data);
            for (var entry : data.getAsJsonObject("equipment").getAsJsonArray("items")) {
                var item = entry.getAsJsonObject(); if (item.get("id").getAsString().equals("test:unflinching_chest")) item.getAsJsonObject("budgets").getAsJsonObject("energy").addProperty("capacity", 3);
            }
        });
        var equipment = PlayerEquipment.get(t.owner); var grants = Set.of("chorus_d2:unlock/unflinching", "chorus_d2:artifact/unflinching_solar");
        equipment.grants(t.owner, grants, equipment.revision()); UnflinchingGameTest.mods(t, "solar_artifact", "solar_artifact", "solar_artifact"); WeaponAimGameTest.input(t, true);
        at(t, 4, () -> {
            NoDistractionsGameTest.hit(t); NoDistractionsGameTest.angle(t, .90625 * .9 * .65);
            var chest = equipment.item("test:chest"); equipment.grants(t.owner, Set.of("chorus_d2:unlock/unflinching"), equipment.revision());
            h.assertTrue(ItemStack.matches(chest, equipment.item("test:chest")) && equipment.inactiveReason().isPresent(), "revocation lost item or left it eligible");
            NoDistractionsGameTest.hit(t); NoDistractionsGameTest.angle(t, 0);
            equipment.grants(t.owner, grants, equipment.revision()); WeaponAimGameTest.input(t, true);
        });
        at(t, 8, () -> { try (t) { NoDistractionsGameTest.hit(t); NoDistractionsGameTest.angle(t, .90625 * .9 * .65); near(h, t.owner.getHealth(), 7, "access changed flinch, not damage"); h.succeed(); }});
    }
}
