package com.imdomestic.chorus.client;

import static com.imdomestic.chorus.network.EquipmentPayloads.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Rebuildable server view. A click only submits references; no optimistic inventory mutation. */
public final class EquipmentScreen extends Screen {
    private UUID screen = UUID.randomUUID();
    private View view;
    private EquipmentTheme theme = EquipmentTheme.DEFAULT;
    private String selected = "", moveFrom = "", dimension;
    private int inventory = -1, page, x, y, panelWidth, panelHeight, right, cell, rows;
    private boolean opened, pending;
    private final List<ItemButton> itemButtons = new ArrayList<>();
    public EquipmentScreen() { super(Component.translatable("equipment.chorus.title")); }
    @Override protected void init() {
        if (!opened) { opened = true; dimension = minecraft.level.dimension().identifier().toString(); EquipmentClient.send(new Visit(screen, true)); }
        rebuild();
    }
    public void accept(View next) {
        if (!screen.equals(next.screen()) || view != null && view.session().equals(next.session()) && next.sequence() <= view.sequence()) return;
        String oldTheme = view == null ? "" : view.content().presentation(); view = next; pending = false; inventory = -1; moveFrom = "";
        if (!oldTheme.equals(next.content().presentation())) theme = EquipmentTheme.load(next.content().presentation());
        if (view.content().slots().stream().noneMatch(slot -> slot.id().equals(selected))) selected = "";
        rebuild();
    }
    private Component tr(String key) { return Component.translatable("equipment.chorus." + key); }
    private void request(Operation operation, String slot, String target, int index) {
        if (view == null || pending) return;
        pending = true; EquipmentClient.send(new Request(screen, view.session(), view.sequence(), operation, slot, target, index)); rebuild();
    }
    private void refresh() {
        EquipmentClient.send(new Visit(screen, false)); screen = UUID.randomUUID(); view = null; pending = false; inventory = -1; moveFrom = "";
        EquipmentClient.send(new Visit(screen, true)); rebuild();
    }
    private List<Slot> slots() {
        if (view == null) return List.of();
        return view.content().slots().stream().sorted(Comparator.comparingInt((Slot slot) -> {
            int i = theme.order().indexOf(slot.id()); return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(Slot::id)).toList();
    }
    private static Component slotName(String id) {
        String key = "equipment.slot." + id.replace(':', '.').replace('/', '.');
        return Component.translatableWithFallback(key, id.substring(id.indexOf(':') + 1).replace('_', ' ').replace('/', ' '));
    }
    private Button button(Component label, int bx, int by, int w, Runnable action, boolean enabled) {
        var button = Button.builder(label, _ -> action.run()).bounds(bx, by, w, 20).build(); button.active = enabled; return addRenderableWidget(button);
    }
    private boolean usable() { return view != null && !pending && view.content().status() != Status.DEAD && view.content().status() != Status.BUSY; }
    private void rebuild() {
        clearWidgets(); itemButtons.clear(); panelWidth = Math.min(width - 16, 620); panelHeight = Math.min(height - 16, 330);
        x = (width - panelWidth) / 2; y = (height - panelHeight) / 2; cell = Math.min(24, Math.max(18, Math.min((panelWidth / 2 - 16) / 9, (panelHeight - 142) / 4))); right = x + panelWidth - 9 * cell - 10;
        rows = Math.max(1, (panelHeight - 96) / 28);
        button(tr("close"), x + panelWidth - 56, y + 8, 46, this::onClose, true);
        button(tr("refresh"), x + panelWidth - 110, y + 8, 50, this::refresh, true);
        if (view == null) return;
        var slots = slots(); int pages = Math.max(1, (slots.size() + rows - 1) / rows); page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(slots.size(), (page + 1) * rows); i++) {
            var slot = slots.get(i); var stack = view.content().equipment().items().getOrDefault(slot.id(), ItemStack.EMPTY);
            var b = new ItemButton(x + 10, y + 48 + (i % rows) * 28, right - x - 24, 26, stack, slotName(slot.id()), slot.id().equals(selected), view.content().equipment().drawn().filter(slot.id()::equals).isPresent(), () -> {
                if (!moveFrom.isEmpty()) request(Operation.MOVE, moveFrom, slot.id(), -1); else { selected = slot.id(); rebuild(); }
            }); b.active = !pending; addRenderableWidget(b); itemButtons.add(b);
        }
        if (pages > 1) {
            button(Component.literal("<"), x + 10, y + panelHeight - 43, 24, () -> { page--; rebuild(); }, page > 0);
            button(Component.literal(">"), x + 38, y + panelHeight - 43, 24, () -> { page++; rebuild(); }, page + 1 < pages);
        }
        var bag = view.content().inventory();
        for (int i = 0; i < Math.min(36, bag.size()); i++) {
            int index = i; var b = new ItemButton(right + i % 9 * cell, y + 48 + i / 9 * cell, cell - 2, cell - 2, bag.get(i), Component.empty(), inventory == i, false,
                    () -> { inventory = index; moveFrom = ""; rebuild(); }); b.active = !pending; addRenderableWidget(b); itemButtons.add(b);
        }
        int actions = y + 52 + 4 * cell, w = (9 * cell - 4) / 2;
        var chosen = view.content().equipment().items().getOrDefault(selected, ItemStack.EMPTY);
        int empty = -1; for (int i = 0; i < bag.size(); i++) if (bag.get(i).isEmpty()) { empty = i; break; } int emptyIndex = empty;
        boolean selectedSlot = !selected.isEmpty();
        button(tr("swap"), right, actions, w, () -> request(Operation.SWAP, selected, "", inventory), usable() && selectedSlot && inventory >= 0);
        button(tr("unequip"), right + w + 4, actions, w, () -> request(Operation.SWAP, selected, "", emptyIndex), usable() && !chosen.isEmpty() && emptyIndex >= 0);
        boolean ready = usable() && view.content().status() == Status.READY;
        boolean weapon = slots.stream().anyMatch(slot -> slot.id().equals(selected) && slot.weapon());
        button(tr("draw"), right, actions + 24, w, () -> request(Operation.DRAW, selected, "", -1), ready && weapon && !chosen.isEmpty());
        button(tr("stow"), right + w + 4, actions + 24, w, () -> request(Operation.STOW, "", "", -1), ready && view.content().equipment().drawn().isPresent());
        button(tr("move"), right, actions + 48, 9 * cell, () -> { moveFrom = selected; rebuild(); }, ready && !chosen.isEmpty());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        g.fill(0, 0, width, height, 0x99000000); g.fill(x, y, x + panelWidth, y + panelHeight, theme.background());
        g.fill(x, y, x + panelWidth, y + 2, theme.accent()); g.text(font, title, x + 10, y + 14, theme.text(), false);
        g.text(font, tr("equipped"), x + 10, y + 35, theme.muted(), false); g.text(font, tr("inventory"), right, y + 35, theme.muted(), false);
        Component message = view == null || pending ? tr("waiting") : !moveFrom.isEmpty() ? tr("choose_destination")
                : view.reply() == Reply.STALE ? tr("stale") : view.reply() == Reply.REJECTED ? tr("rejected") : view.reply() == Reply.FAILED ? tr("failed")
                : view.content().status() != Status.READY ? tr("status." + view.content().status().name().toLowerCase(Locale.ROOT)) : tr("select");
        g.text(font, font.plainSubstrByWidth(message.getString(), panelWidth - 20), x + 10, y + panelHeight - 16, theme.muted(), false);
        super.extractRenderState(g, mouseX, mouseY, tick);
        for (var b : itemButtons) if (b.isHovered() && !b.stack.isEmpty()) g.setTooltipForNextFrame(font, getTooltipFromItem(minecraft, b.stack), Optional.empty(), mouseX, mouseY);
    }
    @Override public void tick() { if (minecraft.player == null || minecraft.level == null || !minecraft.level.dimension().identifier().toString().equals(dimension)) onClose(); }
    @Override public void removed() { EquipmentClient.send(new Visit(screen, false)); }
    @Override public boolean isPauseScreen() { return false; }
    private final class ItemButton extends Button {
        final ItemStack stack; final Component label; final boolean selected, drawn;
        ItemButton(int bx, int by, int w, int h, ItemStack stack, Component label, boolean selected, boolean drawn, Runnable action) {
            super(bx, by, w, h, label.copy().append(" ").append(stack.isEmpty() ? tr("empty") : stack.getHoverName()), _ -> action.run(), DEFAULT_NARRATION);
            this.stack = stack.copy(); this.label = label; this.selected = selected; this.drawn = drawn;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
            int border = selected || isHoveredOrFocused() ? theme.accent() : theme.border();
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
            g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, theme.panel());
            int ix = getX() + (label.getString().isEmpty() ? (getWidth() - 16) / 2 : 2), iy = getY() + (getHeight() - 16) / 2;
            if (!stack.isEmpty()) { g.item(stack, ix, iy); g.itemDecorations(font, stack, ix, iy); }
            if (!label.getString().isEmpty()) {
                int tx = getX() + 22, available = getWidth() - 27;
                g.text(font, font.plainSubstrByWidth(label.getString(), available), tx, getY() + 3, theme.muted(), false);
                g.text(font, font.plainSubstrByWidth((stack.isEmpty() ? tr("empty") : stack.getHoverName()).getString(), available), tx, getY() + 14, theme.text(), false);
            }
            if (drawn) g.fill(getX(), getY(), getX() + 3, getY() + getHeight(), theme.accent());
        }
    }
}
