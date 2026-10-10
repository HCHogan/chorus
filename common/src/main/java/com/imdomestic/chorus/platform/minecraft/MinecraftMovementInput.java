package com.imdomestic.chorus.platform.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import java.util.List;

/** Input gates and explicit axis constraints are independent; only axis constraints replace native velocity. */
public final class MinecraftMovementInput {
    public static final int MOVE=1,JUMP=2,HORIZONTAL=4,VERTICAL=8,MOTION=HORIZONTAL|VERTICAL;
    public static final List<ActionGate.Kind> KINDS=List.of(ActionGate.Kind.MOVEMENT_INPUT,ActionGate.Kind.JUMP,ActionGate.Kind.HORIZONTAL_MOTION,ActionGate.Kind.VERTICAL_MOTION);
    public static boolean hasGates(CompiledEffects program){return KINDS.stream().anyMatch(program::hasActionGates);}
    public static int bit(ActionGate.Kind kind){return switch(kind){case MOVEMENT_INPUT->MOVE;case JUMP->JUMP;case HORIZONTAL_MOTION->HORIZONTAL;case VERTICAL_MOTION->VERTICAL;default->throw new IllegalArgumentException("Not a movement gate: "+kind);};}
    private MinecraftMovementInput() {}
    public interface Synced {
        int chorus$movementRestrictions();
        void chorus$movementRestrictions(Object owner,int value);
        Vec3 chorus$movementAnchor();
        void chorus$movementProjection(Object owner,int value,Vec3 anchor);
        void chorus$beginMotionTeleport();
        void chorus$endMotionTeleport();
        boolean chorus$motionTeleporting();
    }
    public static int mask(LivingEntity actor){
        if(actor.level() instanceof ServerLevel level)MinecraftEffectRuntime.installed(level).filter(r->hasGates(r.program())).ifPresent(MinecraftEffectRuntime::prepare);
        return ((Synced)actor).chorus$movementRestrictions();
    }
    public static boolean blocked(LivingEntity actor,int bit){return (mask(actor)&bit)!=0;}
    public static Input filter(Input input,int mask){
        boolean move=(mask&MOVE)==0,jump=(mask&JUMP)==0;
        return new Input(move&&input.forward(),move&&input.backward(),move&&input.left(),move&&input.right(),jump&&input.jump(),move&&input.shift(),move&&input.sprint());
    }
}
