package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.EffectPrograms;
import net.minecraft.core.*;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableServerRegistries.class)
abstract class EffectProgramReloadMixin {
    /** All files are decoded and registry identities frozen; failure here rejects the whole reload. */
    @Inject(method = "createAndValidateFullContext", at = @At("HEAD"))
    private static void chorus$linkPrograms(LayeredRegistryAccess<RegistryLayer> layers, HolderLookup.Provider context,
            RegistryAccess.Frozen reloaded, CallbackInfoReturnable<ReloadableServerRegistries.LoadResult> callback) {
        EffectPrograms.validate(reloaded);
    }
}
