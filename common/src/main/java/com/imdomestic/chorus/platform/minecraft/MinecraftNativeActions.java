package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.input.ActionGate;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Native attack-start checks, deliberately independent of projectile impact and damage receipts. */
public final class MinecraftNativeActions {
    private MinecraftNativeActions() {}
    public enum Outcome { ALLOWED, RESTRICTED, INELIGIBLE, QUERY_FAILED }
    public record Report(long timeMicros,EffectEvent input,Outcome outcome,Optional<ActionGate.Decision> decision,Optional<String> failure){
        public Report{Objects.requireNonNull(input);Objects.requireNonNull(outcome);Objects.requireNonNull(decision);Objects.requireNonNull(failure);}
    }
    public static boolean ranged(Mob actor,LivingEntity victim,String attack){
        return attempt(actor,victim,attack,ActionGate.Kind.RANGED_ATTACK);
    }
    public static boolean melee(LivingEntity actor,Entity victim,String attack){
        return attempt(actor,victim,attack,ActionGate.Kind.MELEE_ATTACK);
    }
    private static boolean attempt(LivingEntity actor,Entity victim,String attack,ActionGate.Kind kind){
        if(!(actor.level() instanceof ServerLevel level))return true;
        return MinecraftEffectRuntime.installed(level).map(runtime->runtime.authorizeNativeAction(actor,victim,attack,kind)).orElse(true);
    }
    static EffectEvent input(LivingEntity actor,Entity victim,String attack,ActionGate.Kind kind){
        var tags=new HashSet<>(actor.entityTags());
        actor.getType().builtInRegistryHolder().tags().forEach(tag->tags.add(tag.location().toString()));
        if(actor instanceof Player)tags.add("chorus:guardian");else if(actor instanceof Mob)tags.add("chorus:combatant");
        String tag=switch(kind){case RANGED_ATTACK->"chorus:native_ranged_attack";case MELEE_ATTACK->"chorus:native_melee_attack";default->throw new IllegalArgumentException("Unsupported native attack: "+kind);};
        var origin=new BuffInstance.Origin(actor.getUUID().toString(),attack,"","",tags);
        return new EffectEvent(actor.getUUID().toString(),victim==null?"":victim.getUUID().toString(),origin,Set.of(tag),Map.of(),
                Map.of("on_ground",actor.onGround(),"sprinting",actor.isSprinting(),"crouching",actor.isCrouching()),
                Map.of("native_attack",attack,"entity_type",BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType()).toString()));
    }
}
