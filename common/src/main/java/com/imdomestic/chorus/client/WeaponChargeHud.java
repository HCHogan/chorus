package com.imdomestic.chorus.client;

import com.imdomestic.chorus.network.WeaponChargePayloads.Phase;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Minimal generic feedback. D2 art, perfect-window rules and weapon animation belong to installed content. */
public final class WeaponChargeHud {
    private WeaponChargeHud() {}
    public static void render(GuiGraphicsExtractor g, DeltaTracker ignored) {
        var client = Minecraft.getInstance(); if (client.gui.hud.isHidden()) return;
        WeaponChargeClient.view().filter(v -> v.held() && v.phase() != Phase.OFF).ifPresent(view -> {
            int width = 72, x = (g.guiWidth() - width) / 2, y = g.guiHeight() / 2 + 22;
            boolean ready = view.phase() == Phase.READY, recovering = view.phase() == Phase.RECOVERING;
            g.fill(x - 1, y - 1, x + width + 1, y + 4, 0xBB101820);
            g.fill(x, y, x + width, y + 3, 0xFF485460);
            g.fill(x, y, x + (int) Math.round(width * view.fraction()), y + 3, ready ? 0xFFFFD778 : 0xFFE8F0F5);
            int minimum = x + (int) Math.round(width * (view.minimumAt() - view.startedAt()) / (double) (view.fullAt() - view.startedAt()));
            if (!recovering) g.fill(minimum, y - 1, minimum + 1, y + 4, 0xFF8BB9C9);
            var label = Component.translatable(recovering ? "hud.chorus.charge.recovering" : ready ? "hud.chorus.charge.ready" : "hud.chorus.charge.charging");
            g.text(client.font, label, (g.guiWidth() - client.font.width(label)) / 2, y + 7, 0xFFE8F0F5, true);
        });
    }
}
