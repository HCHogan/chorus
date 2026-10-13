package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.platform.Services;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Owns the real stacks; all public observations are defensive copies. No vanilla equipment-slot mirrors. */
public final class PlayerEquipment {
    public interface Owner { PlayerEquipment chorus$equipment(); }
    public static PlayerEquipment get(Player player) { return ((Owner) player).chorus$equipment(); }
    private static final String SAVE_KEY = "chorus:equipment";
    public record Stored(Map<String, ItemStack> items, Optional<String> drawn, long revision, Set<String> grants) {
        public Stored(Map<String, ItemStack> items, Optional<String> drawn, long revision) { this(items, drawn, revision, Set.of()); }
        public Stored {
            items = copy(items); Objects.requireNonNull(drawn); grants = new Loadout(Map.of(), Optional.empty(), grants).grants();
            if (revision < 0) throw new IllegalArgumentException("Negative equipment revision");
        }
        @Override public Map<String, ItemStack> items() { return copy(items); }
        public static final Codec<Stored> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, ItemStack.CODEC).fieldOf("items").forGetter(Stored::items),
                Codec.STRING.optionalFieldOf("drawn").forGetter(Stored::drawn),
                Codec.LONG.optionalFieldOf("revision", 0L).forGetter(Stored::revision),
                com.imdomestic.chorus.core.codec.DataCodecs.ID.listOf().xmap(Set::copyOf, values -> values.stream().sorted().toList()).optionalFieldOf("grants", Set.of()).forGetter(Stored::grants)
        ).apply(i, Stored::new));
    }
    private Map<String, ItemStack> items = Map.of();
    private Optional<String> drawn = Optional.empty();
    private Set<String> grants = Set.of();
    private Optional<String> inactiveReason = Optional.empty();
    private long revision;
    private boolean changing, transferCommitted;

    private static Map<String, ItemStack> copy(Map<String, ItemStack> input) {
        var result = new TreeMap<String, ItemStack>();
        input.forEach((key, value) -> { if (!value.isEmpty()) result.put(Objects.requireNonNull(key), value.copy()); });
        return Collections.unmodifiableMap(result);
    }
    public boolean isEmpty() { return items.isEmpty(); }
    public long revision() { return revision; }
    public ItemStack item(String slot) { return items.getOrDefault(slot, ItemStack.EMPTY).copy(); }
    public Stored snapshot() { return new Stored(items, drawn, revision, grants); }
    public Set<String> grants() { return grants; }
    /** Trusted server progression input. There is no corresponding client request operation. */
    public void grants(ServerPlayer player, Set<String> next, long expectedRevision) {
        check(player, expectedRevision); next = new Loadout(Map.of(), Optional.empty(), next).grants();
        if (grants.equals(next)) return;
        change(player, items, drawn, next, expectedRevision, true, () -> {}, () -> {});
    }
    public Optional<String> inactiveReason() { return inactiveReason; }
    /** Persistence retains unknown prototypes/slots; validation only determines whether their effects are active. */
    public void save(ValueOutput output) { output.store(SAVE_KEY, Stored.CODEC, snapshot()); }
    public void load(ValueInput input) {
        if (changing) throw new IllegalStateException("Cannot load equipment during a transfer");
        var stored = input.read(SAVE_KEY, Stored.CODEC).orElseGet(() -> new Stored(Map.of(), Optional.empty(), 0));
        items = stored.items(); drawn = stored.drawn(); grants = stored.grants(); revision = stored.revision(); inactiveReason = Optional.empty();
    }
    private static Loadout metadata(Map<String, ItemStack> items, Optional<String> drawn, Set<String> grants) {
        var slots = new TreeMap<String, Loadout.Gear>();
        items.forEach((slot, stack) -> {
            if (stack.getCount() != 1) throw new IllegalArgumentException("Equipment slot must own exactly one item: " + slot);
            var gear = stack.get(ChorusComponents.EQUIPMENT.get());
            if (gear == null) throw new IllegalArgumentException("Item has no equipment identity: " + slot);
            slots.put(slot, gear);
        });
        return new Loadout(slots, drawn, grants);
    }
    Loadout projection(CompiledEquipment schema) {
        try {
            var result = metadata(items, drawn, grants); schema.validate(result); inactiveReason = Optional.empty(); return result;
        } catch (IllegalArgumentException invalid) {
            inactiveReason = Optional.of(invalid.getMessage()); return new Loadout(Map.of(), Optional.empty(), grants);
        }
    }
    private void check(ServerPlayer player, long expected) {
        if (get(player) != this || !player.level().getServer().isSameThread() || player.isRemoved() || !player.isAlive()) throw new IllegalStateException("Equipment belongs to a live server player");
        if (changing) throw new IllegalStateException("Reentrant equipment transfer");
        if (revision != expected) throw new IllegalStateException("Stale equipment revision");
    }
    private static void inventorySlot(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= player.getInventory().getNonEquipmentItems().size()) throw new IllegalArgumentException("Expected a main inventory slot");
    }
    /** Exchanges complete owned stacks with a main-inventory slot; equipped stacks must have count one. */
    public void swap(ServerPlayer player, String slot, int inventorySlot, long expectedRevision) {
        check(player, expectedRevision); inventorySlot(player, inventorySlot);
        var incoming = player.getInventory().getItem(inventorySlot).copy(); var outgoing = item(slot);
        var next = new TreeMap<>(items);
        if (incoming.isEmpty()) next.remove(slot); else next.put(slot, incoming);
        var nextDrawn = drawn.filter(next::containsKey);
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        // Retrieval into an empty inventory slot remains possible when a definition or runtime was removed.
        boolean retrieval = incoming.isEmpty();
        if (!retrieval && runtime == null) throw new IllegalStateException("Equip requires an installed ruleset");
        if (!retrieval) {
            var desired = metadata(next, nextDrawn, grants); runtime.program().equipment().validate(desired);
            String instance = Objects.requireNonNull(incoming.get(ChorusComponents.EQUIPMENT.get())).instance();
            for (var stack : items.values()) {
                var gear = stack.get(ChorusComponents.EQUIPMENT.get());
                if (gear != null && gear.instance().equals(instance)) throw new IllegalArgumentException("Item identity already exists in equipment");
            }
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (i != inventorySlot) {
                var gear = player.getInventory().getItem(i).get(ChorusComponents.EQUIPMENT.get());
                if (gear != null && gear.instance().equals(instance)) throw new IllegalArgumentException("Duplicate item identity in player inventory");
            }
        }
        if (incoming.isEmpty() && outgoing.isEmpty()) return;
        change(player, next, nextDrawn, expectedRevision, retrieval, () -> {
            if (!ItemStack.matches(player.getInventory().getItem(inventorySlot), incoming)) throw new IllegalStateException("Inventory changed during equipment preflight");
        }, () -> { player.getInventory().setItem(inventorySlot, outgoing); player.getInventory().setChanged(); });
    }
    /** Player-editable sockets only; the owned instance and all other stack components are retained. */
    public void configure(ServerPlayer player, String slot, Map<String, String> choices, long expectedRevision) {
        check(player, expectedRevision);
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElseThrow(() -> new IllegalStateException("Configure requires an installed ruleset"));
        var before = metadata(items, drawn, grants);
        var after = runtime.program().equipment().configure(before, slot, choices);
        runtime.trackEquipment(player); runtime.prepareEquipmentChange(); check(player, expectedRevision);
        if (before.equals(after)) return;
        var stack = item(slot); stack.set(ChorusComponents.EQUIPMENT.get(), after.slots().get(slot));
        var next = new TreeMap<>(items); next.put(slot, stack);
        change(player, next, drawn, expectedRevision, false, () -> {}, () -> {});
    }
    /** Moves/swaps actual equipment ownership; the drawn item follows its instance to the destination slot. */
    public void move(ServerPlayer player, String from, String to, long expectedRevision) {
        check(player, expectedRevision); if (from.equals(to)) return;
        if (!items.containsKey(from)) throw new IllegalArgumentException("Source equipment slot is empty");
        var next = new TreeMap<>(items); var first = next.remove(from); var second = next.remove(to);
        next.put(to, first); if (second != null) next.put(from, second);
        var nextDrawn = drawn.map(slot -> slot.equals(from) ? to : slot.equals(to) ? from : slot);
        change(player, next, nextDrawn, expectedRevision, false, () -> {}, () -> {});
    }
    public void draw(ServerPlayer player, Optional<String> slot, long expectedRevision) {
        check(player, expectedRevision); Objects.requireNonNull(slot);
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if (runtime != null && runtime.switchWeapon(player, slot, expectedRevision)) return;
        if (drawn.equals(slot)) return;
        change(player, items, slot, expectedRevision, false, () -> {}, () -> {});
    }
    /** The accepted intent consumes its container revision even before a timed phase changes drawn. */
    void acceptHandling(ServerPlayer player, long expectedRevision) {
        check(player, expectedRevision); revision = Math.incrementExact(revision);
    }
    boolean applyHandling(ServerPlayer player, EquipmentChange change, CompiledEquipment schema) {
        check(player, revision);
        if (!change.holder().equals(player.getUUID().toString()) || !change.before().slots().equals(change.after().slots())
                || !change.before().grants().equals(change.after().grants())
                || !projection(schema).equals(change.before())) return false;
        schema.validate(change.after()); long next = Math.incrementExact(revision);
        drawn = change.after().drawn(); revision = next; inactiveReason = Optional.empty();
        return true;
    }
    private void change(ServerPlayer player, Map<String, ItemStack> desired, Optional<String> nextDrawn, long expected,
            boolean retrieval, Runnable checkInventory, Runnable transferInventory) {
        change(player, desired, nextDrawn, grants, expected, retrieval, checkInventory, transferInventory);
    }
    private void change(ServerPlayer player, Map<String, ItemStack> desired, Optional<String> nextDrawn, Set<String> nextGrants, long expected,
            boolean allowInactive, Runnable checkInventory, Runnable transferInventory) {
        var nextItems = copy(desired); long nextRevision = Math.incrementExact(revision);
        var runtime = MinecraftEffectRuntime.installed(player.level()).orElse(null);
        if (runtime == null && !allowInactive) throw new IllegalStateException("Equip requires an installed ruleset");
        Loadout target = new Loadout(Map.of(), Optional.empty(), nextGrants);
        if (runtime != null) {
            try { target = metadata(nextItems, nextDrawn, nextGrants); runtime.program().equipment().validate(target); }
            catch (IllegalArgumentException invalid) { if (!allowInactive) throw invalid; target = new Loadout(Map.of(), Optional.empty(), nextGrants); }
            runtime.trackEquipment(player); runtime.prepare();
        }
        check(player, expected); checkInventory.run(); // clock/attach reactions may have changed inventory
        changing = true; transferCommitted = false;
        try {
            Runnable transfer = () -> {
                transferInventory.run(); items = nextItems; drawn = nextDrawn; grants = nextGrants; revision = nextRevision; inactiveReason = Optional.empty(); transferCommitted = true;
            };
            if (runtime == null || runtime.failure().isPresent() && allowInactive) transfer.run();
            else {
                String holder = player.getUUID().toString();
                var before = runtime.state().engine().domain().equipment().getOrDefault(holder, Loadout.EMPTY);
                runtime.commitEquipment(new EquipmentChange(holder, before, target), transfer);
            }
        } finally { changing = false; transferCommitted = false; }
        player.inventoryMenu.broadcastChanges();
    }
    /** Ownership moves, rather than copying, when Minecraft replaces the ServerPlayer during respawn. */
    public void transferFrom(PlayerEquipment old) {
        if (old == this) return;
        if (changing || old.changing || !items.isEmpty() || !grants.isEmpty()) throw new IllegalStateException("Invalid player equipment transfer");
        long nextRevision = Math.incrementExact(Math.max(revision, old.revision));
        items = old.items; drawn = old.drawn; grants = old.grants; revision = nextRevision; inactiveReason = Optional.empty();
        old.items = Map.of(); old.drawn = Optional.empty(); old.grants = Set.of(); old.revision = nextRevision; old.inactiveReason = Optional.empty();
    }
    public void dropOnDeath(ServerPlayer player) {
        if (player.level().getGameRules().get(GameRules.KEEP_INVENTORY)) return;
        if (changing && !transferCommitted) throw new IllegalStateException("Cannot drop equipment before ownership transfer");
        for (String slot : List.copyOf(items.keySet())) {
            var stack = items.get(slot); boolean vanish = EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
            long nextRevision = Math.incrementExact(revision); var remaining = new TreeMap<>(items); remaining.remove(slot);
            items = Collections.unmodifiableMap(remaining); drawn = drawn.filter(items::containsKey); revision = nextRevision;
            if (!vanish && Services.PLATFORM.dropEquipmentOnDeath(player, stack.copy()) == null) {
                // A refused drop has not transferred ownership; retain it for respawn/recovery.
                var retained = new TreeMap<>(items); retained.put(slot, stack); items = Collections.unmodifiableMap(retained);
            }
        }
    }
}
