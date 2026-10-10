package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.input.ActionGate;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** Native attack-start checks, deliberately independent of projectile impact and damage receipts. */
public final class MinecraftNativeActions {
    private MinecraftNativeActions() {}
    public enum Outcome { ALLOWED, RESTRICTED, INELIGIBLE, QUERY_FAILED }
    public record Report(long timeMicros,EffectEvent input,Outcome outcome,Optional<ActionGate.Decision> decision,Optional<String> failure){
        public Report{Objects.requireNonNull(input);Objects.requireNonNull(outcome);Objects.requireNonNull(decision);Objects.requireNonNull(failure);}
    }
    public static boolean ranged(Mob actor,LivingEntity victim,String attack){
        if(!(actor.level() instanceof ServerLevel level))return true;
        return MinecraftEffectRuntime.installed(level).map(runtime->runtime.authorizeNativeRanged(actor,victim,attack)).orElse(true);
    }
    static EffectEvent input(Mob actor,LivingEntity victim,String attack){
        var tags=new HashSet<>(actor.entityTags());
        actor.getType().builtInRegistryHolder().tags().forEach(tag->tags.add(tag.location().toString()));
        tags.add("chorus:combatant");
        var origin=new BuffInstance.Origin(actor.getUUID().toString(),attack,"","",tags);
        return new EffectEvent(actor.getUUID().toString(),victim==null?"":victim.getUUID().toString(),origin,Set.of("chorus:native_ranged_attack"),Map.of(),
                Map.of("on_ground",actor.onGround(),"sprinting",actor.isSprinting(),"crouching",actor.isCrouching()),
                Map.of("native_attack",attack,"entity_type",BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType()).toString()));
    }
}
