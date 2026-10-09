package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.network.EquipmentPayloads.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.network.EquipmentNetworkServer;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class EquipmentNetworkGameTest {
    private static final class Network {
        final Map<ServerPlayer, List<View>> sent = new IdentityHashMap<>();
        final EquipmentNetworkServer server = new EquipmentNetworkServer((p, v) -> sent.computeIfAbsent(p, _ -> new ArrayList<>()).add(v));
        View last(ServerPlayer p) { return sent.get(p).getLast(); }
        void open(ServerPlayer p) { server.visit(p, new Visit(UUID.randomUUID(), true)); }
        Request request(ServerPlayer p, Operation op, String slot, String destination, int inventory) { var v = last(p); return new Request(v.screen(), v.session(), v.sequence(), op, slot, destination, inventory); }
    }
    private static ItemStack gun(String id, String option) {
        var stack = new ItemStack(Items.DIAMOND_SWORD); stack.setDamageValue(11); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", option))); return stack;
    }
    @GameCase public void wireRoundTripsPreserveItemComponentsAndReplayCannotSwapTwiceOrTargetAnotherPlayer(GameTestHelper h) throws Exception {
        try (var test = new PlayerEquipmentGameTest.Harness(h)) {
            var a = test.player(); var b = test.player(); a.getInventory().setItem(0, gun("a", "normal")); b.getInventory().setItem(0, gun("b", "enhanced"));
            var n = new Network(); n.open(a); n.open(b); var original = n.last(a);
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                View.CODEC.encode(buf, original); var decoded = View.CODEC.decode(buf);
                h.assertTrue(decoded.content().same(original.content()) && decoded.session().equals(original.session()), "wire view lost state or components");
                decoded.content().inventory().getFirst().setCount(50); h.assertValueEqual(a.getInventory().getItem(0).getCount(), 1, "wire view leaked live item");
                h.assertValueEqual(original.content().inventory().getFirst().getCount(), 1, "wire view exposed mutable retained stack");
                buf.clear(); var request = n.request(a, Operation.SWAP, "test:weapon_a", "", 0); Request.CODEC.encode(buf, request); var wire = Request.CODEC.decode(buf);
                n.server.request(b, wire); h.assertTrue(PlayerEquipment.get(b).isEmpty(), "request crossed connection owner");
                n.server.request(a, wire); h.assertValueEqual(n.last(a).reply(), Reply.APPLIED, "first request result");
                h.assertTrue(a.getInventory().getItem(0).isEmpty() && !PlayerEquipment.get(a).isEmpty(), "network did not transfer physical item");
                n.server.request(a, wire); h.assertValueEqual(n.last(a).reply(), Reply.STALE, "replayed view not rejected");
                h.assertTrue(a.getInventory().getItem(0).isEmpty(), "replay swapped equipped item back"); h.assertValueEqual(test.heals.size(), 1, "replay retriggered attach");
            } finally { buf.release(); }
            test.settled();
        }
        h.succeed();
    }
    @GameCase public void changedBagAndReplacedRuntimeInvalidateThePublishedViewBeforeMutation(GameTestHelper h) throws Exception {
        try (var test = new PlayerEquipmentGameTest.Harness(h)) {
            var p = test.player(); p.getInventory().setItem(0, gun("a", "normal")); var n = new Network(); n.open(p);
            var stale = n.request(p, Operation.SWAP, "test:weapon_a", "", 0);
            p.getInventory().getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Changed after view"));
            n.server.request(p, stale); h.assertValueEqual(n.last(p).reply(), Reply.STALE, "bag change did not invalidate view");
            h.assertTrue(PlayerEquipment.get(p).isEmpty(), "stale inventory request moved new item");
            n.server.request(p, n.request(p, Operation.SWAP, "test:weapon_a", "", 0)); h.assertValueEqual(n.last(p).reply(), Reply.APPLIED, "refreshed request failed");
            var move = n.request(p, Operation.MOVE, "test:weapon_a", "test:weapon_b", -1); long revision = PlayerEquipment.get(p).revision();
            test.runtime.close(); test.install(test.program); n.server.request(p, move);
            h.assertValueEqual(n.last(p).reply(), Reply.STALE, "same-version replacement runtime accepted old view");
            h.assertValueEqual(PlayerEquipment.get(p).revision(), revision, "runtime replacement moved physical item");
            h.assertTrue(PlayerEquipment.get(p).item("test:weapon_b").isEmpty(), "stale move executed"); test.settled();
        }
        h.succeed();
    }
    @GameCase public void cursorOwnershipAndScreenSessionChangesRejectOldActionsAndClosedViewsStopUpdating(GameTestHelper h) throws Exception {
        try (var test = new PlayerEquipmentGameTest.Harness(h)) {
            var p = test.player(); p.getInventory().setItem(0, gun("a", "normal")); var n = new Network(); n.open(p);
            p.inventoryMenu.setCarried(new ItemStack(Items.STICK)); n.server.tick(h.getLevel());
            h.assertValueEqual(n.last(p).content().status(), Status.BUSY, "cursor stack not reflected");
            n.server.request(p, n.request(p, Operation.SWAP, "test:weapon_a", "", 0)); h.assertValueEqual(n.last(p).reply(), Reply.REJECTED, "cursor ownership ignored");
            h.assertTrue(PlayerEquipment.get(p).isEmpty(), "busy container mutated"); p.inventoryMenu.setCarried(ItemStack.EMPTY); n.server.tick(h.getLevel());
            var old = n.request(p, Operation.SWAP, "test:weapon_a", "", 0); n.open(p); int sent = n.sent.get(p).size(); n.server.request(p, old);
            h.assertValueEqual(n.sent.get(p).size(), sent, "old session generated current acknowledgement"); h.assertTrue(PlayerEquipment.get(p).isEmpty(), "old session changed new screen");
            n.server.visit(p, new Visit(n.last(p).screen(), false)); p.getInventory().setItem(1, new ItemStack(Items.DIAMOND)); n.server.tick(h.getLevel());
            h.assertValueEqual(n.sent.get(p).size(), sent, "closed view retained subscription"); test.settled();
        }
        h.succeed();
    }
    @GameCase public void failureResponseShowsCommittedItemsAndRecoveryUsesTheNewAuthoritativeView(GameTestHelper h) throws Exception {
        try (var test = new PlayerEquipmentGameTest.Harness(h)) {
            var p = test.player(); p.getInventory().setItem(0, gun("old", "normal")); var n = new Network(); n.open(p);
            n.server.request(p, n.request(p, Operation.SWAP, "test:weapon_a", "", 0)); p.getInventory().setItem(1, gun("new", "enhanced")); n.server.tick(h.getLevel()); test.failCleanup = true;
            var request = n.request(p, Operation.SWAP, "test:weapon_a", "", 1); n.server.request(p, request); var failed = n.last(p);
            h.assertValueEqual(failed.reply(), Reply.FAILED, "unknown world outcome was reported as success"); h.assertValueEqual(failed.content().status(), Status.RUNTIME_FAILED, "runtime failure missing from view");
            h.assertValueEqual(failed.content().equipment().items().get("test:weapon_a").get(ChorusComponents.EQUIPMENT.get()).instance(), "new", "response rolled back transferred equipment");
            h.assertValueEqual(failed.content().inventory().get(1).get(ChorusComponents.EQUIPMENT.get()).instance(), "old", "response lost returned item");
            n.server.request(p, request); h.assertValueEqual(test.heals.size(), 2, "failure replayed world healing");
            n.server.request(p, n.request(p, Operation.SWAP, "test:weapon_a", "", 2)); h.assertValueEqual(n.last(p).reply(), Reply.APPLIED, "failed runtime blocked item retrieval");
            h.assertTrue(n.last(p).content().equipment().items().isEmpty(), "recovery view retained equipped item"); h.assertValueEqual(test.heals.size(), 2, "recovery replayed cleanup");
        }
        h.succeed();
    }
}
