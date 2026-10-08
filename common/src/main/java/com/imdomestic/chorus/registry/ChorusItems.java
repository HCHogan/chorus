package com.imdomestic.chorus.registry;

import java.util.function.Supplier;

import com.imdomestic.chorus.item.EngramItem;
import com.imdomestic.chorus.platform.Services;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

public final class ChorusItems {
    private ChorusItems() {
    }

    public static final Supplier<Item> ENGRAM = Services.REGISTRATION.register(Registries.ITEM, "engram",
            key -> new EngramItem(new Item.Properties().setId(key).stacksTo(16)));

    public static void init() {
    }
}
