package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftSaveCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.nio.file.Path;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Util.class)
abstract class SaveReplacementMixin {
    @WrapOperation(method = "safeReplaceFile", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;safeReplaceOrMoveFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;Z)Z"))
    private static boolean chorus$replace(Path target, Path incoming, Path backup, boolean noRollback, Operation<Boolean> original) {
        try {
            boolean replaced = original.call(target, incoming, backup, noRollback);
            // Observe the boolean that vanilla's void safeReplaceFile wrapper discards (noRollback is false here).
            MinecraftSaveCapture.replaced(target, replaced); return replaced;
        } catch (RuntimeException | Error error) { MinecraftSaveCapture.replacementFailed(target, error); throw error; }
    }
}
