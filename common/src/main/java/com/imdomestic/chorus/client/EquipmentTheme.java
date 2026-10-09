package com.imdomestic.chorus.client;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.Constants;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** A bounded presentation template; resource packs cannot inject actions or change server equipment rules. */
public record EquipmentTheme(int background, int panel, int border, int accent, int text, int muted, List<String> order) {
    public static final EquipmentTheme DEFAULT = new EquipmentTheme(0xf2141923, 0xff232b38, 0xff526071, 0xff85c6ef, 0xfff1f3f5, 0xffb7bec9, List.of());
    public EquipmentTheme { order = List.copyOf(order); }
    public static EquipmentTheme load(String name) {
        try {
            var id = Identifier.parse(name); var path = Identifier.fromNamespaceAndPath(id.getNamespace(), "ui/" + id.getPath() + ".json");
            var resource = Minecraft.getInstance().getResourceManager().getResource(path);
            if (resource.isEmpty()) return DEFAULT;
            try (var reader = resource.orElseThrow().openAsReader()) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!json.get("template").getAsString().equals("equipment_two_panel")) return DEFAULT;
                var order = new ArrayList<String>(); if (json.has("slot_order")) json.getAsJsonArray("slot_order").forEach(value -> order.add(value.getAsString()));
                return new EquipmentTheme(color(json, "background", DEFAULT.background), color(json, "panel", DEFAULT.panel), color(json, "border", DEFAULT.border),
                        color(json, "accent", DEFAULT.accent), color(json, "text", DEFAULT.text), color(json, "muted", DEFAULT.muted), order);
            }
        } catch (Exception invalid) { Constants.LOG.warn("Using default equipment presentation for {}", name, invalid); return DEFAULT; }
    }
    private static int color(com.google.gson.JsonObject json, String key, int fallback) {
        if (!json.has(key)) return fallback; String value = json.get(key).getAsString();
        if (!value.matches("[0-9a-fA-F]{8}")) throw new IllegalArgumentException("Expected ARGB hex color"); return (int) Long.parseLong(value, 16);
    }
}
