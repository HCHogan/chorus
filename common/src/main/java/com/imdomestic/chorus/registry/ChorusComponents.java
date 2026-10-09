package com.imdomestic.chorus.registry;

import com.imdomestic.chorus.item.EngramData;
import com.imdomestic.chorus.effect.equipment.EquipmentCodecs;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.Services;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;

public final class ChorusComponents {
    private ChorusComponents() {
    }

    public static final Supplier<DataComponentType<EngramData>> ENGRAM_DATA = Services.REGISTRATION.register(
            Registries.DATA_COMPONENT_TYPE, "engram_data",
            key -> DataComponentType.<EngramData>builder()
                    .persistent(EngramData.CODEC)
                    .networkSynchronized(EngramData.STREAM_CODEC)
                    .build());

    public static final Supplier<DataComponentType<Loadout.Gear>> EQUIPMENT = Services.REGISTRATION.register(
            Registries.DATA_COMPONENT_TYPE, "equipment",
            key -> DataComponentType.<Loadout.Gear>builder().persistent(EquipmentCodecs.GEAR)
                    .networkSynchronized(ByteBufCodecs.fromCodec(EquipmentCodecs.GEAR)).build());

    public static void init() {
    }
}
