package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.PlayerActor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerActorMixin implements PlayerActor.Owner {
    @Unique private final PlayerActor chorus$actor = new PlayerActor();
    @Override public PlayerActor chorus$actor() { return chorus$actor; }
    @Inject(method="addAdditionalSaveData", at=@At("TAIL"))
    private void chorus$saveActor(ValueOutput output, CallbackInfo ci) { chorus$actor.save(output); }
    @Inject(method="readAdditionalSaveData", at=@At("TAIL"))
    private void chorus$loadActor(ValueInput input, CallbackInfo ci) { chorus$actor.load(input); }
}
