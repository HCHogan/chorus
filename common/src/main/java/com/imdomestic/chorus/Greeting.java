package com.imdomestic.chorus;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class Greeting {
    private Greeting() {
    }

    public static void onPlayerJoin(ServerPlayer player) {
        player.sendSystemMessage(
                Component.literal("Hello, your name is " + player.getPlainTextName() + "！").withStyle(ChatFormatting.AQUA));
    }
}
