package com.imdomestic.chorus.registry;

import com.imdomestic.chorus.platform.Services;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/** Host movement controls use the same native projection and synchronization as other attributes. */
public final class ChorusAttributes {
    public static final String FORWARD_GROUND_SPEED_ID = "chorus:forward_ground_speed";
    private static final Supplier<Attribute> FORWARD_GROUND_SPEED = Services.REGISTRATION.register(
            Registries.ATTRIBUTE, "forward_ground_speed",
            _ -> new RangedAttribute("attribute.name.chorus.forward_ground_speed", 1, 0, 1024).setSyncable(true));

    private ChorusAttributes() {}
    public static void init() {}
    public static Holder<Attribute> forwardGroundSpeed() {
        return BuiltInRegistries.ATTRIBUTE.wrapAsHolder(FORWARD_GROUND_SPEED.get());
    }
}
