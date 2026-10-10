package com.imdomestic.chorus.platform.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;

/** Active input restrictions. These bits do not represent immobilization or replace native velocity. */
public final class MinecraftMovementInput {
    public static final int MOVE=1,JUMP=2;
    private MinecraftMovementInput() {}
    public interface Synced {
        int chorus$movementRestrictions();
        void chorus$movementRestrictions(Object owner,int value);
    }
    public static int mask(LivingEntity actor){
        if(actor.level() instanceof ServerLevel level)MinecraftEffectRuntime.installed(level).filter(r->r.program().hasActionGates(com.imdomestic.chorus.effect.input.ActionGate.Kind.MOVEMENT_INPUT)||r.program().hasActionGates(com.imdomestic.chorus.effect.input.ActionGate.Kind.JUMP)).ifPresent(MinecraftEffectRuntime::prepare);
        return ((Synced)actor).chorus$movementRestrictions();
    }
    public static boolean blocked(LivingEntity actor,int bit){return (mask(actor)&bit)!=0;}
    public static Input filter(Input input,int mask){
        boolean move=(mask&MOVE)==0,jump=(mask&JUMP)==0;
        return new Input(move&&input.forward(),move&&input.backward(),move&&input.left(),move&&input.right(),jump&&input.jump(),move&&input.shift(),move&&input.sprint());
    }
}
