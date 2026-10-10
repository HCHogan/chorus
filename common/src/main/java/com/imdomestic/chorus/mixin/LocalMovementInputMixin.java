package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.MinecraftMovementInput;
import net.minecraft.client.player.*;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
abstract class LocalMovementInputMixin {
    @Shadow public ClientInput input;
    @Shadow private int jumpRidingTicks;
    @Shadow private float jumpRidingScale;
    @Unique private void chorus$filter(){
        int mask=MinecraftMovementInput.mask((LocalPlayer)(Object)this);
        input.keyPresses=MinecraftMovementInput.filter(input.keyPresses,mask);
        if((mask&MinecraftMovementInput.MOVE)!=0)((ClientInputAccessor)input).chorus$moveVector(Vec2.ZERO);
        // Do not interpret newly denied jumping as releasing an already charged mount jump.
        if((mask&MinecraftMovementInput.JUMP)!=0){jumpRidingTicks=0;jumpRidingScale=0;}
    }
    @Inject(method="aiStep",at=@At("HEAD"))
    private void chorus$previousInput(CallbackInfo ci){chorus$filter();}
    @Inject(method="aiStep",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/ClientInput;tick()V",shift=At.Shift.AFTER))
    private void chorus$keyboard(CallbackInfo ci){chorus$filter();}
    @Inject(method="aiStep",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/ClientInput;makeJump()V",shift=At.Shift.AFTER))
    private void chorus$autoJump(CallbackInfo ci){chorus$filter();}
}
