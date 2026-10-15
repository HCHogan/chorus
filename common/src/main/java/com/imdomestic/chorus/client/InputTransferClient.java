package com.imdomestic.chorus.client;

import com.imdomestic.chorus.network.InputTransferPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import org.lwjgl.sdl.SDLMouse;

/** Explicit transfer packets are the only path which can rebind a held input to another local player object. */
public final class InputTransferClient {
    private InputTransferClient() {}
    public static void accept(InputTransferPayload payload, Connection receivingConnection) {
        var current = Minecraft.getInstance().getConnection();
        if (current == null || current.getConnection() != receivingConnection) return;
        switch (payload.kind()) {
            case AIM -> WeaponAimClient.acceptTransfer(payload);
            case CHARGE -> WeaponChargeClient.acceptTransfer(payload);
            case ABILITY -> AbilityInputClient.acceptTransfer(payload);
        }
    }
    static InputTransferGate.Acknowledgement acknowledgement(InputTransferPayload payload) {
        return new InputTransferGate.Acknowledgement(payload.player(), payload.entity(), payload.fromDimension(), payload.toDimension(),
                payload.sequence(), payload.gesture(), payload.revision(), payload.previousRevision());
    }
    static InputTransferGate start(Minecraft client, long gesture) {
        return new InputTransferGate(client.getConnection(), client.player, client.player.getUUID(), client.player.getId(), dimension(client), gesture);
    }
    static String dimension(Minecraft client) { return client.level == null ? null : client.level.dimension().identifier().toString(); }
    static boolean terrain(Minecraft client) { return client.gui.screen() instanceof LevelLoadingScreen; }
    static boolean eligible(Minecraft client, boolean channel) {
        return channel && client.player != null && client.level != null && client.player.isAlive() && !client.player.isSpectator()
                && client.gui.screen() == null && client.isWindowActive();
    }
    static boolean down(Minecraft client, KeyMapping key, InputTransferGate transfer, boolean blocked) {
        if (!blocked && (transfer == null || !transfer.transferred() && !transfer.waiting() && !terrain(client)
                && transfer.matches(client.player, dimension(client)))) return key.isDown();
        // Loading screens release KeyMapping state. Preserve only an existing physical hold, never create a new press here.
        if (!client.isWindowActive() || key.isUnbound()) return false;
        var binding = InputConstants.getKey(key.saveString());
        return switch (binding.getType()) {
            case KEYBOARD -> InputConstants.isKeyDown(binding.getValue());
            case MOUSE -> binding.getValue() > 0 && binding.getValue() <= 32
                    && (SDLMouse.SDL_GetMouseState(null, null) & (1 << (binding.getValue() - 1))) != 0;
        };
    }
    static InputTransferGate.Decision tick(Minecraft client, InputTransferGate transfer, boolean down, boolean channel) {
        var player = client.player;
        var original = (LocalPlayer) transfer.owner();
        return transfer.tick(new InputTransferGate.Observation(client.getConnection(), player,
                player == null ? null : player.getUUID(), player == null ? 0 : player.getId(), dimension(client), down,
                original.getHealth() > 0 && !original.isSpectator(), player != null && player.isAlive() && !player.isSpectator(),
                channel && client.isWindowActive(), client.gui.screen() == null ? InputTransferGate.Screen.NONE
                    : terrain(client) ? InputTransferGate.Screen.TERRAIN : InputTransferGate.Screen.OTHER));
    }
}
