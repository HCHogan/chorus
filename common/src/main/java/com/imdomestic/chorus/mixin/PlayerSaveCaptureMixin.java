package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftSaveCapture;
import com.imdomestic.chorus.platform.minecraft.MinecraftPlayerSaveAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.File;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.*;

@Mixin(PlayerDataStorage.class)
abstract class PlayerSaveCaptureMixin implements MinecraftPlayerSaveAccess {
    @Shadow @Final private File playerDir;
    @Override public java.nio.file.Path chorus$playerFile(java.util.UUID player) { return playerDir.toPath().resolve(player + ".dat"); }
    @WrapMethod(method = "save")
    private void chorus$save(Player player, Operation<Void> original) {
        MinecraftSaveCapture.saving(chorus$playerFile(player.getUUID()), () -> original.call(player));
    }
}
