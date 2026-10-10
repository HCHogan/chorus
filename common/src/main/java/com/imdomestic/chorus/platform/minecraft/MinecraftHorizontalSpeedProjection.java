package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.motion.HorizontalSpeedLimit;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** One owner token per runtime; a former dimension/runtime cannot clear a newer projection. */
public final class MinecraftHorizontalSpeedProjection implements AutoCloseable {
    public record Report(String holder,Optional<HorizontalSpeedLimit.Decision> decision,Optional<String> error) {}
    private final ServerLevel level;private final CompiledEffects program;
    private final Set<LivingEntity> owned=Collections.newSetFromMap(new IdentityHashMap<>());private List<Report> reports=List.of();
    MinecraftHorizontalSpeedProjection(ServerLevel level,CompiledEffects program){this.level=level;this.program=program;}
    public List<Report> reports(){return reports;}
    private LivingEntity resolve(String holder){try{var actor=level.getEntity(UUID.fromString(holder));return actor instanceof LivingEntity living?living:null;}catch(IllegalArgumentException invalid){return null;}}
    public void reconcile(EffectState state){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Motion projection requires the server thread");if(!program.hasHorizontalSpeedLimits())return;
        var holders=new TreeSet<String>();state.sources().values().forEach(s->holders.add(s.holder()));state.buffs().instances().values().forEach(b->holders.add(b.key().holder()));
        var desired=new LinkedHashMap<LivingEntity,OptionalDouble>();var observations=new ArrayList<Report>();RuntimeException failure=null;
        for(String holder:holders){
            var actor=resolve(holder);if(actor==null||actor.isRemoved()||!actor.isAlive()||actor.isSpectator()||actor.level()!=level)continue;
            try{
                var input=MinecraftNativeActions.input(actor,null,"minecraft:horizontal_speed_limit",ActionGate.Kind.HORIZONTAL_MOTION);
                var decision=program.horizontalSpeedLimit(state,input);observations.add(new Report(holder,Optional.of(decision),Optional.empty()));if(decision.maximum().isPresent())desired.put(actor,decision.maximum());
            }catch(RuntimeException error){
                desired.put(actor,OptionalDouble.of(0));observations.add(new Report(holder,Optional.empty(),Optional.of(error.getClass().getSimpleName()+": "+error.getMessage())));
                if(failure==null)failure=error;else failure.addSuppressed(error);
            }
        }
        reports=List.copyOf(observations);
        for(var actor:List.copyOf(owned))if(!desired.containsKey(actor)){((MinecraftHorizontalSpeed.Synced)actor).chorus$horizontalSpeed().apply(this,OptionalDouble.empty(),actor);owned.remove(actor);}
        desired.forEach((actor,speed)->{owned.add(actor);((MinecraftHorizontalSpeed.Synced)actor).chorus$horizontalSpeed().apply(this,speed,actor);});
        if(failure!=null)throw failure;
    }
    @Override public void close(){
        RuntimeException failure=null;for(var actor:owned)try{((MinecraftHorizontalSpeed.Synced)actor).chorus$horizontalSpeed().apply(this,OptionalDouble.empty(),actor);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        owned.clear();reports=List.of();if(failure!=null)throw failure;
    }
}
