package com.imdomestic.chorus.network;

import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.effect.equipment.EquipmentEditor;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Version 2: operation references and editable choice ids, never client-provided stacks, owners, parameters or effect definitions. */
public final class EquipmentPayloads {
    private EquipmentPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) { return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("chorus", path)); }
    public enum Operation { SWAP, DRAW, STOW, MOVE, INSPECT, CONFIGURE }
    public enum Status { READY, NO_RULESET, INVALID_EQUIPMENT, RUNTIME_FAILED, DEAD, BUSY }
    public enum Reply { UPDATED, APPLIED, STALE, REJECTED, FAILED }
    public record Visit(UUID screen, boolean open) implements CustomPacketPayload {
        public static final Type<Visit> TYPE = EquipmentPayloads.type("equipment_visit_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, Visit> CODEC = StreamCodec.ofMember((v,b) -> { b.writeUUID(v.screen()); b.writeBoolean(v.open()); }, b -> new Visit(b.readUUID(), b.readBoolean()));
        public Visit { Objects.requireNonNull(screen); }
        @Override public Type<Visit> type() { return TYPE; }
    }
    public record Request(UUID screen, UUID session, long view, Operation operation, String slot, String destination, int inventory, Map<String, String> choices) implements CustomPacketPayload {
        public Request(UUID screen, UUID session, long view, Operation operation, String slot, String destination, int inventory) { this(screen, session, view, operation, slot, destination, inventory, Map.of()); }
        public static final Type<Request> TYPE = EquipmentPayloads.type("equipment_request_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.ofMember(Request::write, Request::read);
        public Request {
            Objects.requireNonNull(screen); Objects.requireNonNull(session); Objects.requireNonNull(operation);
            text(slot); text(destination); if (view < 0) throw new IllegalArgumentException("Negative view number");
            if (choices.size() > EquipmentEditor.MAX_SOCKETS || operation != Operation.CONFIGURE && !choices.isEmpty()) throw new IllegalArgumentException("Invalid equipment edit choices");
            var copy = new TreeMap<String, String>(); choices.forEach((key, value) -> { key(key); key(value); copy.put(key, value); }); choices = Collections.unmodifiableMap(copy);
        }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(screen); b.writeUUID(session); b.writeVarLong(view); b.writeEnum(operation); b.writeUtf(slot, 512); b.writeUtf(destination, 512); b.writeVarInt(inventory);
            b.writeVarInt(choices.size()); choices.forEach((key, value) -> { b.writeUtf(key, EquipmentEditor.MAX_KEY); b.writeUtf(value, EquipmentEditor.MAX_KEY); });
        }
        private static Request read(RegistryFriendlyByteBuf b) {
            UUID screen = b.readUUID(), session = b.readUUID(); long view = b.readVarLong(); var operation = b.readEnum(Operation.class);
            String slot = b.readUtf(512), destination = b.readUtf(512); int inventory = b.readVarInt(); var choices = new TreeMap<String, String>();
            int size = size(b, EquipmentEditor.MAX_SOCKETS);
            for (int i = 0; i < size; i++) if (choices.putIfAbsent(b.readUtf(EquipmentEditor.MAX_KEY), b.readUtf(EquipmentEditor.MAX_KEY)) != null) throw new IllegalArgumentException("Duplicate editor socket");
            return new Request(screen, session, view, operation, slot, destination, inventory, choices);
        }
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Slot(String id, boolean weapon) { public Slot { text(id); } }
    public record Inspection(String slot, EquipmentEditor editor) { public Inspection { text(slot); if (slot.isEmpty()) throw new IllegalArgumentException("Empty inspected slot"); Objects.requireNonNull(editor); } }
    public record Content(PlayerEquipment.Stored equipment, List<Slot> slots, List<ItemStack> inventory, Status status, String presentation, String version, Optional<Inspection> inspection) {
        public Content(PlayerEquipment.Stored equipment, List<Slot> slots, List<ItemStack> inventory, Status status, String presentation, String version) { this(equipment, slots, inventory, status, presentation, version, Optional.empty()); }
        public Content {
            Objects.requireNonNull(equipment); slots = List.copyOf(slots); inventory = copy(inventory); Objects.requireNonNull(status); text(presentation); text(version); Objects.requireNonNull(inspection);
            if (slots.size() > 1024 || equipment.items().size() > 1024 || inventory.size() > 256) throw new IllegalArgumentException("Equipment view exceeds protocol limits");
        }
        @Override public List<ItemStack> inventory() { return copy(inventory); }
        // Holder grants are server-only progression input, intentionally absent from this presentation protocol.
        public boolean same(Content other) {
            if (other == null || !slots.equals(other.slots) || status != other.status || !presentation.equals(other.presentation) || !version.equals(other.version) || !inspection.equals(other.inspection)
                    || equipment.revision() != other.equipment.revision() || !equipment.drawn().equals(other.equipment.drawn()) || inventory.size() != other.inventory.size()) return false;
            var a = equipment.items(); var b = other.equipment.items(); if (!a.keySet().equals(b.keySet())) return false;
            for (var id : a.keySet()) if (!ItemStack.matches(a.get(id), b.get(id))) return false;
            for (int i = 0; i < inventory.size(); i++) if (!ItemStack.matches(inventory.get(i), other.inventory.get(i))) return false;
            return true;
        }
    }
    public record View(UUID screen, UUID session, long sequence, UUID player, String dimension, Reply reply, Content content) implements CustomPacketPayload {
        public static final Type<View> TYPE = EquipmentPayloads.type("equipment_view_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.ofMember(View::write, View::read);
        public View { Objects.requireNonNull(screen); Objects.requireNonNull(session); Objects.requireNonNull(player); Objects.requireNonNull(reply); Objects.requireNonNull(content); text(dimension); if (sequence < 0) throw new IllegalArgumentException("Negative view sequence"); }
        private void write(RegistryFriendlyByteBuf b) {
            b.writeUUID(screen); b.writeUUID(session); b.writeVarLong(sequence); b.writeUUID(player); b.writeUtf(dimension, 512); b.writeEnum(reply);
            b.writeEnum(content.status()); b.writeUtf(content.presentation(), 512); b.writeUtf(content.version(), 512);
            b.writeVarLong(content.equipment().revision()); b.writeUtf(content.equipment().drawn().orElse(""), 512);
            var items = content.equipment().items(); b.writeVarInt(items.size()); items.forEach((slot, stack) -> { b.writeUtf(slot, 512); ItemStack.STREAM_CODEC.encode(b, stack); });
            b.writeVarInt(content.slots().size()); content.slots().forEach(slot -> { b.writeUtf(slot.id(), 512); b.writeBoolean(slot.weapon()); });
            b.writeVarInt(content.inventory.size()); content.inventory.forEach(stack -> ItemStack.OPTIONAL_STREAM_CODEC.encode(b, stack));
            b.writeBoolean(content.inspection().isPresent()); content.inspection().ifPresent(value -> { b.writeUtf(value.slot(), 512); writeEditor(b, value.editor()); });
        }
        private static View read(RegistryFriendlyByteBuf b) {
            UUID screen = b.readUUID(), session = b.readUUID(); long sequence = b.readVarLong(); UUID player = b.readUUID(); String dimension = b.readUtf(512); Reply reply = b.readEnum(Reply.class);
            Status status = b.readEnum(Status.class); String presentation = b.readUtf(512), version = b.readUtf(512); long revision = b.readVarLong(); String drawn = b.readUtf(512);
            var items = new TreeMap<String, ItemStack>(); int size = size(b, 1024);
            for (int i = 0; i < size; i++) if (items.putIfAbsent(b.readUtf(512), ItemStack.STREAM_CODEC.decode(b)) != null) throw new IllegalArgumentException("Duplicate equipment slot");
            var slots = new ArrayList<Slot>(); size = size(b, 1024); for (int i = 0; i < size; i++) slots.add(new Slot(b.readUtf(512), b.readBoolean()));
            var inventory = new ArrayList<ItemStack>(); size = size(b, 256); for (int i = 0; i < size; i++) inventory.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
            var inspection = b.readBoolean() ? Optional.of(new Inspection(b.readUtf(512), readEditor(b))) : Optional.<Inspection>empty();
            return new View(screen, session, sequence, player, dimension, reply, new Content(new PlayerEquipment.Stored(items, drawn.isEmpty() ? Optional.empty() : Optional.of(drawn), revision), slots, inventory, status, presentation, version, inspection));
        }
        @Override public Type<View> type() { return TYPE; }
    }
    private static void writeAmounts(RegistryFriendlyByteBuf b, Map<String, Integer> values) { b.writeVarInt(values.size()); values.forEach((name, value) -> { b.writeUtf(name, EquipmentEditor.MAX_KEY); b.writeVarInt(value); }); }
    private static Map<String, Integer> readAmounts(RegistryFriendlyByteBuf b) {
        var result = new TreeMap<String, Integer>(); int size = size(b, EquipmentEditor.MAX_BUDGETS);
        for (int i = 0; i < size; i++) if (result.putIfAbsent(b.readUtf(EquipmentEditor.MAX_KEY), b.readVarInt()) != null) throw new IllegalArgumentException("Duplicate editor budget");
        return result;
    }
    private static void writeEditor(RegistryFriendlyByteBuf b, EquipmentEditor editor) {
        b.writeUtf(editor.definition(), 512); b.writeBoolean(editor.available()); writeAmounts(b, editor.capacities()); b.writeVarInt(editor.sockets().size());
        editor.sockets().forEach((name, socket) -> {
            b.writeUtf(name, EquipmentEditor.MAX_KEY); b.writeBoolean(socket.required()); b.writeBoolean(socket.editable()); b.writeVarInt(socket.options().size());
            socket.options().forEach((choice, option) -> { b.writeUtf(choice, EquipmentEditor.MAX_KEY); b.writeUtf(option.effect(), 512); b.writeBoolean(option.available()); writeAmounts(b, option.costs()); });
        });
    }
    private static EquipmentEditor readEditor(RegistryFriendlyByteBuf b) {
        String definition = b.readUtf(512); boolean available = b.readBoolean(); var capacities = readAmounts(b);
        var sockets = new TreeMap<String, EquipmentEditor.Socket>(); int size = size(b, EquipmentEditor.MAX_SOCKETS), total = 0;
        for (int i = 0; i < size; i++) {
            String name = b.readUtf(EquipmentEditor.MAX_KEY); boolean required = b.readBoolean(), editable = b.readBoolean(); int count = size(b, EquipmentEditor.MAX_OPTIONS);
            total += count; if (total > EquipmentEditor.MAX_OPTIONS) throw new IllegalArgumentException("Too many editor options");
            var options = new TreeMap<String, EquipmentEditor.Option>();
            for (int j = 0; j < count; j++) {
                String choice = b.readUtf(EquipmentEditor.MAX_KEY); var option = new EquipmentEditor.Option(b.readUtf(512), b.readBoolean(), readAmounts(b));
                if (options.putIfAbsent(choice, option) != null) throw new IllegalArgumentException("Duplicate editor option");
            }
            if (sockets.putIfAbsent(name, new EquipmentEditor.Socket(required, editable, options)) != null) throw new IllegalArgumentException("Duplicate editor socket");
        }
        return new EquipmentEditor(definition, available, sockets, capacities);
    }
    private static void key(String value) { if (value == null || value.length() > EquipmentEditor.MAX_KEY || !value.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid editor choice id"); }
    private static int size(RegistryFriendlyByteBuf b, int maximum) { int size = b.readVarInt(); if (size < 0 || size > maximum) throw new IllegalArgumentException("Invalid equipment view collection size"); return size; }
    private static void text(String value) { if (value == null || value.length() > 512) throw new IllegalArgumentException("Invalid equipment protocol string"); }
    private static List<ItemStack> copy(List<ItemStack> values) { return values.stream().map(ItemStack::copy).toList(); }
}
