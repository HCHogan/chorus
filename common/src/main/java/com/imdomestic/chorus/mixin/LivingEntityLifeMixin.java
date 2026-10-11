package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftEntityLife;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class LivingEntityLifeMixin implements MinecraftEntityLife.Owner {
    @Unique private final MinecraftEntityLife chorus$entityLife = new MinecraftEntityLife();
    @Override public MinecraftEntityLife chorus$entityLife() { return chorus$entityLife; }
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void chorus$saveLife(ValueOutput output, CallbackInfo ci) { chorus$entityLife.save(output); }
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void chorus$loadLife(ValueInput input, CallbackInfo ci) { chorus$entityLife.load(input); }
}
