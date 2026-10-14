package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.PlayerAbilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerAbilitiesMixin implements PlayerAbilities.Owner {
    @Unique private final PlayerAbilities chorus$abilities = new PlayerAbilities();
    @Override public PlayerAbilities chorus$abilities() { return chorus$abilities; }
    @Inject(method="addAdditionalSaveData", at=@At("TAIL"))
    private void chorus$saveAbilities(ValueOutput output, CallbackInfo ci) { chorus$abilities.save(output); }
    @Inject(method="readAdditionalSaveData", at=@At("TAIL"))
    private void chorus$loadAbilities(ValueInput input, CallbackInfo ci) { chorus$abilities.load(input); }
}
