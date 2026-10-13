package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.network.EquipmentPayloads.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.equipment.EquipmentEditor;
import com.imdomestic.chorus.network.EquipmentNetworkServer;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class EquipmentEditorGameTest {
    private static final class Network {
        final Map<ServerPlayer, View> views = new IdentityHashMap<>();
        final EquipmentNetworkServer server = new EquipmentNetworkServer(views::put);
        View last(ServerPlayer p) { return views.get(p); }
        void open(ServerPlayer p) { server.visit(p, new Visit(UUID.randomUUID(), true)); }
        Request request(ServerPlayer p, Operation operation, String slot, Map<String, String> choices) { var v = last(p); return new Request(v.screen(), v.session(), v.sequence(), operation, slot, "", -1, choices); }
        void inspect(ServerPlayer p) { server.request(p, request(p, Operation.INSPECT, "test:arms", Map.of())); }
    }
    private static void equip(PlayerEquipmentGameTest.Harness t, ServerPlayer p, String a, String b) {
        var e = PlayerEquipment.get(p); e.grants(p, EquipmentAccessGameTest.BOTH, e.revision());
        p.getInventory().setItem(0, EquipmentBudgetGameTest.armor("owned-" + p.getUUID(), a, b, 10)); e.swap(p, "test:arms", 0, e.revision());
    }
    @GameCase public void inspectionRoundTripsCostsAndEditableFlagsWithoutMutatingOrTransmittingHolderGrants(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_configure")) {
            var p = t.player(); equip(t, p, "cheap", "cheap"); var e = PlayerEquipment.get(p); var n = new Network(); n.open(p);
            var state = t.runtime.state().engine().domain(); long revision = e.revision(); n.inspect(p);
            var view = n.last(p); var editor = view.content().inspection().orElseThrow().editor();
            h.assertValueEqual(editor.capacities(), Map.of("energy", 10), "wrong owned capacity"); h.assertTrue(editor.sockets().get("a").editable() && !editor.sockets().get("c").editable(), "fixed roll became editable");
            h.assertValueEqual(editor.sockets().get("a").options().get("expensive").costs(), Map.of("energy", 6), "wrong option cost");
            h.assertValueEqual(e.revision(), revision, "inspection mutated equipment"); h.assertValueEqual(t.runtime.state().engine().domain(), state, "inspection changed effects");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                View.CODEC.encode(buffer, view); var decoded = View.CODEC.decode(buffer);
                h.assertTrue(decoded.content().same(view.content()) && decoded.content().equipment().grants().isEmpty(), "view roundtrip lost catalogue or transmitted grants");
                buffer.clear(); var request = n.request(p, Operation.CONFIGURE, "test:arms", Map.of("a", "expensive", "b", "cheap"));
                Request.CODEC.encode(buffer, request); h.assertValueEqual(Request.CODEC.decode(buffer), request, "edit request roundtrip changed choices");
            } finally { buffer.release(); }
            n.server.request(p, n.request(p, Operation.INSPECT, "", Map.of())); h.assertTrue(n.last(p).content().inspection().isEmpty(), "closing inspector retained detail"); t.settled();
        } h.succeed();
    }
    @GameCase public void onlyTheInspectedOwnedItemCanBeEditedAndRejectedOrReplayedDraftsDoNotMutateIt(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_configure")) {
            var p = t.player(); var other = t.player(); equip(t, p, "cheap", "cheap"); var e = PlayerEquipment.get(p); var n = new Network(); n.open(p); n.open(other);
            var choices = Map.of("a", "expensive", "b", "cheap");
            n.server.request(p, n.request(p, Operation.CONFIGURE, "test:arms", choices)); h.assertValueEqual(n.last(p).reply(), Reply.REJECTED, "edit accepted without inspection"); n.inspect(p);
            var request = n.request(p, Operation.CONFIGURE, "test:arms", choices); n.server.request(other, request); h.assertTrue(PlayerEquipment.get(other).isEmpty(), "edit crossed player ownership");
            long revision = e.revision(); n.server.request(p, request); h.assertValueEqual(n.last(p).reply(), Reply.APPLIED, "valid edit failed");
            h.assertValueEqual(e.revision(), revision + 1, "one edit advanced revision incorrectly"); near(h, p.getHealth(), 23, "only changed effect replaced");
            n.server.request(p, request); h.assertValueEqual(n.last(p).reply(), Reply.STALE, "replayed edit accepted"); near(h, p.getHealth(), 23, "replay repeated effect");
            var original = e.item("test:arms");
            for (var invalid : List.of(Map.of("a", "expensive", "b", "expensive"), Map.of("a", "free", "b", "free", "c", "free"), Map.of("a", "free"))) {
                n.server.request(p, n.request(p, Operation.CONFIGURE, "test:arms", invalid)); h.assertValueEqual(n.last(p).reply(), Reply.REJECTED, "invalid draft accepted");
                h.assertTrue(ItemStack.matches(original, e.item("test:arms")), "rejected edit changed physical stack");
            }
            t.settled();
        } h.succeed();
    }
    @GameCase public void revocationAndRuntimeReplacementInvalidateDraftsButALegalReplacementRepairsTheItem(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_configure")) {
            var p = t.player(); equip(t, p, "expensive", "cheap"); var e = PlayerEquipment.get(p); var n = new Network(); n.open(p); n.inspect(p);
            var stale = n.request(p, Operation.CONFIGURE, "test:arms", Map.of("a", "cheap", "b", "cheap")); e.grants(p, Set.of("test:unlocked"), e.revision());
            n.server.request(p, stale); h.assertValueEqual(n.last(p).reply(), Reply.STALE, "grant change accepted old view");
            h.assertTrue(!n.last(p).content().inspection().orElseThrow().editor().sockets().get("a").options().get("expensive").available(), "revoked option still unlocked");
            h.assertValueEqual(e.item("test:arms").get(ChorusComponents.EQUIPMENT.get()).choices().get("a"), "expensive", "stale request changed item");
            n.server.request(p, n.request(p, Operation.CONFIGURE, "test:arms", Map.of("a", "cheap", "b", "cheap")));
            h.assertValueEqual(n.last(p).reply(), Reply.APPLIED, "valid repair failed"); h.assertTrue(e.inactiveReason().isEmpty(), "repair left item inactive");
            var oldRuntime = n.request(p, Operation.CONFIGURE, "test:arms", Map.of("a", "free", "b", "free")); long revision = e.revision();
            t.runtime.close(); t.install(t.program); n.server.request(p, oldRuntime);
            h.assertValueEqual(n.last(p).reply(), Reply.STALE, "same-version runtime replacement accepted old draft"); h.assertValueEqual(e.revision(), revision, "stale draft advanced revision"); t.settled();
        } h.succeed();
    }
    @GameCase public void unknownEditCleanupReturnsTheCommittedItemAndCannotBeReplayed(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h, "equipment_configure")) {
            var p = t.player(); equip(t, p, "cheap", "cheap"); var n = new Network(); n.open(p); n.inspect(p); t.failCleanup = true;
            var choices = Map.of("a", "expensive", "b", "cheap"); var request = n.request(p, Operation.CONFIGURE, "test:arms", choices); n.server.request(p, request);
            h.assertValueEqual(n.last(p).reply(), Reply.FAILED, "unknown edit reported success"); h.assertValueEqual(n.last(p).content().status(), Status.RUNTIME_FAILED, "failed runtime hidden");
            h.assertValueEqual(n.last(p).content().equipment().items().get("test:arms").get(ChorusComponents.EQUIPMENT.get()).choices(), choices, "response rolled back actual edit");
            n.server.request(p, request); near(h, p.getHealth(), 22, "unknown write repeated"); h.assertValueEqual(t.heals.size(), 3, "later attach ran after failure");
        } h.succeed();
    }
    @GameCase public void duplicateAndOversizedChoiceMapsAreRejectedBeforeARequestCanReachEquipment(GameTestHelper h) {
        var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            for (int count : new int[]{-1, EquipmentEditor.MAX_SOCKETS + 1, 2}) {
                b.clear(); b.writeUUID(UUID.randomUUID()); b.writeUUID(UUID.randomUUID()); b.writeVarLong(0); b.writeEnum(Operation.CONFIGURE); b.writeUtf("test:arms"); b.writeUtf(""); b.writeVarInt(-1); b.writeVarInt(count);
                if (count == 2) { b.writeUtf("a"); b.writeUtf("cheap"); b.writeUtf("a"); b.writeUtf("free"); }
                boolean rejected = false; try { Request.CODEC.decode(b); } catch (IllegalArgumentException expected) { rejected = true; }
                h.assertTrue(rejected, "invalid wire choice count/duplicate accepted");
            }
        } finally { b.release(); }
        h.succeed();
    }
}
