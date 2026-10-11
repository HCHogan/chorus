package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftSaveCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.File;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.*;

@Mixin(PlayerDataStorage.class)
abstract class PlayerSaveCaptureMixin {
    @Shadow @Final private File playerDir;
    @WrapMethod(method = "save")
    private void chorus$save(Player player, Operation<Void> original) {
        MinecraftSaveCapture.saving(playerDir.toPath().resolve(player.getStringUUID() + ".dat"), () -> original.call(player));
    }
}
