package com.imdomestic.chorus.test.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fabric client GameTest suppresses Window.onFocus unconditionally; control its real backing flag in this fixture. */
@Mixin(Window.class)
public interface WindowFocusAccessor {
    @Accessor("focused") void chorus$focused(boolean focused);
}
