package com.imdomestic.chorus.mixin;

import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface EntityStorageSaveAccessor {
    @Accessor("permanentStorage") EntityPersistentStorage<?> chorus$permanentStorage();
}
