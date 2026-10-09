package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerEquipmentMixin implements PlayerEquipment.Owner {
    @Unique private final PlayerEquipment chorus$equipment = new PlayerEquipment();
    @Override public PlayerEquipment chorus$equipment() { return chorus$equipment; }
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void chorus$saveEquipment(ValueOutput output, CallbackInfo ci) { chorus$equipment.save(output); }
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void chorus$loadEquipment(ValueInput input, CallbackInfo ci) { chorus$equipment.load(input); }
    @Inject(method = "dropEquipment", at = @At("TAIL"))
    private void chorus$dropEquipment(ServerLevel level, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) chorus$equipment.dropOnDeath(player);
    }
}
