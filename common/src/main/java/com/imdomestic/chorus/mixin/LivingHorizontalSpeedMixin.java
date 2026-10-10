package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftHorizontalSpeed;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.*;

@Mixin(LivingEntity.class)
abstract class LivingHorizontalSpeedMixin implements MinecraftHorizontalSpeed.Synced {
    @Unique private MinecraftHorizontalSpeed.State chorus$horizontalSpeed;
    @Override public MinecraftHorizontalSpeed.State chorus$horizontalSpeed(){if(chorus$horizontalSpeed==null)chorus$horizontalSpeed=new MinecraftHorizontalSpeed.State();return chorus$horizontalSpeed;}
}
