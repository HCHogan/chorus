package com.imdomestic.chorus.client;

import static com.imdomestic.chorus.network.EquipmentPayloads.*;
import com.imdomestic.chorus.effect.equipment.EquipmentEditor;
import com.imdomestic.chorus.registry.ChorusComponents;
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
    private final Map<String, String> draft = new TreeMap<>();
    private String selectedSocket = "";
    private int socketPage, optionPage, budgetPage;
    private boolean draftReset;
    private final List<ItemButton> itemButtons = new ArrayList<>();
    private final List<EditorButton> editorButtons = new ArrayList<>();
    public EquipmentScreen() { super(Component.translatable("equipment.chorus.title")); }
    @Override protected void init() {
        if (!opened) { opened = true; dimension = minecraft.level.dimension().identifier().toString(); EquipmentClient.send(new Visit(screen, true)); }
        rebuild();
    }
    public void accept(View next) {
        if (!screen.equals(next.screen()) || view != null && view.session().equals(next.session()) && next.sequence() <= view.sequence()) return;
        draftReset = editing() && !draft.equals(originalChoices()) && next.reply() != Reply.APPLIED;
        String oldTheme = view == null ? "" : view.content().presentation(); view = next; pending = false; inventory = -1; moveFrom = "";
        if (!oldTheme.equals(next.content().presentation())) theme = EquipmentTheme.load(next.content().presentation());
        if (view.content().slots().stream().noneMatch(slot -> slot.id().equals(selected))) selected = "";
        draft.clear(); draft.putAll(originalChoices());
        if (editing()) selected = view.content().inspection().orElseThrow().slot();
        rebuild();
    }
    private Component tr(String key) { return Component.translatable("equipment.chorus." + key); }
    private void request(Operation operation, String slot, String target, int index) {
        request(operation, slot, target, index, Map.of());
    }
    private void request(Operation operation, String slot, String target, int index, Map<String, String> choices) {
        if (view == null || pending) return;
        pending = true; EquipmentClient.send(new Request(screen, view.session(), view.sequence(), operation, slot, target, index, choices)); rebuild();
    }
    private void refresh() {
        EquipmentClient.send(new Visit(screen, false)); screen = UUID.randomUUID(); view = null; pending = false; inventory = -1; moveFrom = "";
        draft.clear(); draftReset = false;
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
    private boolean editing() { return view != null && view.content().inspection().isPresent(); }
    private boolean canEdit() { return usable() && (view.content().status() == Status.READY || view.content().status() == Status.INVALID_EQUIPMENT); }
    private Map<String, String> originalChoices() {
        if (!editing()) return Map.of();
        var stack = view.content().equipment().items().get(view.content().inspection().orElseThrow().slot());
        var gear = stack == null ? null : stack.get(ChorusComponents.EQUIPMENT.get()); return gear == null ? Map.of() : gear.choices();
    }
    private void rebuild() {
        clearWidgets(); itemButtons.clear(); editorButtons.clear(); panelWidth = Math.min(width - 16, 620); panelHeight = Math.min(height - 16, 330);
        x = (width - panelWidth) / 2; y = (height - panelHeight) / 2; cell = Math.min(24, Math.max(18, Math.min((panelWidth / 2 - 16) / 9, (panelHeight - 142) / 4))); right = x + panelWidth - 9 * cell - 10;
        rows = Math.max(1, (panelHeight - 96) / 28);
        button(tr("close"), x + panelWidth - 56, y + 8, 46, this::onClose, true);
        button(tr("refresh"), x + panelWidth - 110, y + 8, 50, this::refresh, true);
        if (view == null) return;
        if (editing()) { buildEditor(); return; }
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
        button(tr("move"), right, actions + 48, w, () -> { moveFrom = selected; rebuild(); }, ready && !chosen.isEmpty());
        button(tr("edit"), right + w + 4, actions + 48, w, () -> request(Operation.INSPECT, selected, "", -1), canEdit() && !chosen.isEmpty());
    }
    private static String human(String name) { return name.substring(name.indexOf(':') + 1).replace('_', ' ').replace('/', ' '); }
    private Component optionName(String id, EquipmentEditor.Option option) {
        return option == null ? Component.literal(human(id)) : Component.translatableWithFallback("effect." + option.effect().replace(':', '.').replace('/', '.'), human(id));
    }
    private String costs(EquipmentEditor.Option option) {
        return option.costs().entrySet().stream().map(entry -> human(entry.getKey()) + ": " + entry.getValue()).collect(java.util.stream.Collectors.joining(" · "));
    }
    private void choose(String option) {
        if (option.isEmpty()) draft.remove(selectedSocket); else draft.put(selectedSocket, option);
        draftReset = false; rebuild();
    }
    private void editorButton(Component label, Component detail, int bx, int by, int w, boolean chosen, boolean enabled, net.minecraft.resources.Identifier icon, Runnable action) {
        var button = new EditorButton(label, detail, bx, by, w, chosen, icon, action); button.active = enabled; addRenderableWidget(button); editorButtons.add(button);
    }
    private void buildEditor() {
        var editor = view.content().inspection().orElseThrow().editor(); var names = new ArrayList<>(editor.sockets().keySet());
        if (!editor.sockets().containsKey(selectedSocket)) { selectedSocket = names.isEmpty() ? "" : names.getFirst(); optionPage = 0; }
        int split = x + Math.max(96, panelWidth / 3), visibleSockets = Math.max(1, (panelHeight - 150) / 30);
        int socketPages = Math.max(1, (names.size() + visibleSockets - 1) / visibleSockets); socketPage = Math.min(socketPage, socketPages - 1);
        for (int i = socketPage * visibleSockets; i < Math.min(names.size(), (socketPage + 1) * visibleSockets); i++) {
            String name = names.get(i); var socket = editor.sockets().get(name); String chosen = draft.get(name);
            Component detail = chosen == null ? tr("empty") : optionName(chosen, socket.options().get(chosen));
            if (!socket.editable()) detail = tr("fixed");
            editorButton(Component.literal(human(name)), detail, x + 10, y + 51 + i % visibleSockets * 30, split - x - 18, name.equals(selectedSocket), !pending, null,
                    () -> { selectedSocket = name; optionPage = 0; rebuild(); });
        }
        int nav = y + panelHeight - 100;
        if (socketPages > 1) {
            button(Component.literal("<"), x + 10, nav, 24, () -> { socketPage--; rebuild(); }, !pending && socketPage > 0);
            button(Component.literal(">"), x + 38, nav, 24, () -> { socketPage++; rebuild(); }, !pending && socketPage + 1 < socketPages);
        }
        var socket = editor.sockets().get(selectedSocket);
        if (socket != null) {
            var options = new ArrayList<>(socket.options().keySet()); if (!socket.required()) options.addFirst("");
            int available = panelWidth - (split - x) - 16, columns = available >= 280 ? 2 : 1;
            int visible = Math.max(1, (panelHeight - 152) / 30) * columns, pages = Math.max(1, (options.size() + visible - 1) / visible);
            optionPage = Math.min(optionPage, pages - 1); int w = (available - (columns - 1) * 4) / columns;
            for (int i = optionPage * visible; i < Math.min(options.size(), (optionPage + 1) * visible); i++) {
                String optionId = options.get(i); var option = socket.options().get(optionId); int index = i % visible;
                var label = option == null ? tr("clear") : optionName(optionId, option);
                var detail = option == null ? Component.empty() : Component.literal(costs(option));
                if (option != null && (!option.available() || !editor.available())) detail = detail.copy().append(" · ").append(tr("locked"));
                var icon = option == null ? null : theme.effectIcons().get(option.effect());
                editorButton(label, detail, split + index % columns * (w + 4), y + 51 + index / columns * 30, w,
                        optionId.equals(draft.getOrDefault(selectedSocket, "")), canEdit() && socket.editable() && editor.available() && (option == null || option.available()), icon, () -> choose(optionId));
            }
            if (pages > 1) {
                button(Component.literal("<"), split, nav, 24, () -> { optionPage--; rebuild(); }, !pending && optionPage > 0);
                button(Component.literal(">"), split + 28, nav, 24, () -> { optionPage++; rebuild(); }, !pending && optionPage + 1 < pages);
            }
        }
        var budgets = new ArrayList<>(editor.capacities().keySet()); budgetPage = Math.min(budgetPage, Math.max(0, budgets.size() - 1));
        if (budgets.size() > 1) {
            button(Component.literal("<"), x + panelWidth - 64, y + panelHeight - 76, 24, () -> { budgetPage--; rebuild(); }, budgetPage > 0);
            button(Component.literal(">"), x + panelWidth - 36, y + panelHeight - 76, 24, () -> { budgetPage++; rebuild(); }, budgetPage + 1 < budgets.size());
        }
        button(tr("back"), x + 10, y + panelHeight - 49, 70, () -> request(Operation.INSPECT, "", "", -1), !pending);
        button(tr("reset"), x + 84, y + panelHeight - 49, 70, () -> { draft.clear(); draft.putAll(originalChoices()); draftReset = false; rebuild(); }, !pending && !draft.equals(originalChoices()));
        button(tr("apply"), x + panelWidth - 80, y + panelHeight - 49, 70, () -> request(Operation.CONFIGURE, view.content().inspection().orElseThrow().slot(), "", -1, draft),
                canEdit() && !draft.equals(originalChoices()) && editor.valid(originalChoices(), draft));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        g.fill(0, 0, width, height, 0x99000000); g.fill(x, y, x + panelWidth, y + panelHeight, theme.background());
        g.fill(x, y, x + panelWidth, y + 2, theme.accent()); g.text(font, title, x + 10, y + 14, theme.text(), false);
        if (editing()) {
            var inspection = view.content().inspection().orElseThrow(); var editor = inspection.editor(); var stack = view.content().equipment().items().getOrDefault(inspection.slot(), ItemStack.EMPTY);
            g.text(font, font.plainSubstrByWidth(stack.getHoverName().getString(), panelWidth - 20), x + 10, y + 35, theme.muted(), false);
            var budgets = new ArrayList<>(editor.capacities().keySet());
            String summary = tr(editor.available() ? "no_budget" : "locked").getString(); boolean over = false;
            if (editor.available() && !budgets.isEmpty()) {
                String budget = budgets.get(budgetPage); long capacity = editor.capacities().get(budget);
                try { long used = editor.usage(draft).get(budget); summary = human(budget) + ": " + used + " / " + capacity; over = used > capacity; }
                catch (IllegalArgumentException unknown) { summary = tr("unknown_choice").getString(); over = true; }
            }
            g.text(font, font.plainSubstrByWidth(summary, panelWidth - (budgets.size() > 1 ? 84 : 20)), x + 10, y + panelHeight - 70, over ? 0xffff8d85 : theme.accent(), false);
        } else {
            g.text(font, tr("equipped"), x + 10, y + 35, theme.muted(), false); g.text(font, tr("inventory"), right, y + 35, theme.muted(), false);
        }
        Component message = view == null || pending ? tr("waiting") : !moveFrom.isEmpty() ? tr("choose_destination")
                : view.reply() == Reply.STALE ? tr("stale") : view.reply() == Reply.REJECTED ? tr("rejected") : view.reply() == Reply.FAILED ? tr("failed")
                : draftReset ? tr("draft_reset") : editing() && (view.content().status() == Status.READY || view.content().status() == Status.INVALID_EQUIPMENT) ? tr("edit_hint")
                : view.content().status() != Status.READY ? tr("status." + view.content().status().name().toLowerCase(Locale.ROOT)) : tr("select");
        g.text(font, font.plainSubstrByWidth(message.getString(), panelWidth - 20), x + 10, y + panelHeight - 16, theme.muted(), false);
        super.extractRenderState(g, mouseX, mouseY, tick);
        for (var b : itemButtons) if (b.isHovered() && !b.stack.isEmpty()) g.setTooltipForNextFrame(font, getTooltipFromItem(minecraft, b.stack), Optional.empty(), mouseX, mouseY);
        for (var b : editorButtons) if (b.isHovered()) g.setTooltipForNextFrame(font, b.getMessage(), mouseX, mouseY);
    }
    @Override public void tick() { if (minecraft.player == null || minecraft.level == null || !minecraft.level.dimension().identifier().toString().equals(dimension)) onClose(); }
    @Override public void removed() { EquipmentClient.send(new Visit(screen, false)); }
    @Override public boolean isPauseScreen() { return false; }
    private final class EditorButton extends Button {
        final Component label, detail; final boolean chosen; final net.minecraft.resources.Identifier icon;
        EditorButton(Component label, Component detail, int bx, int by, int w, boolean chosen, net.minecraft.resources.Identifier icon, Runnable action) {
            super(bx, by, w, 28, label.copy().append(" · ").append(detail), _ -> action.run(), DEFAULT_NARRATION);
            this.label = label; this.detail = detail; this.chosen = chosen; this.icon = icon;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
            g.fill(getX(), getY(), getRight(), getBottom(), chosen || isHoveredOrFocused() ? theme.accent() : theme.border());
            g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, theme.panel());
            int tx = getX() + 4;
            if (icon != null) { g.blit(icon, tx, getY() + 4, tx + 20, getY() + 24, 0, 1, 0, 1); tx += 24; }
            g.text(font, font.plainSubstrByWidth(label.getString(), getRight() - tx - 4), tx, getY() + 4, active ? theme.text() : theme.muted(), false);
            g.text(font, font.plainSubstrByWidth(detail.getString(), getRight() - tx - 4), tx, getY() + 16, theme.muted(), false);
        }
    }
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
