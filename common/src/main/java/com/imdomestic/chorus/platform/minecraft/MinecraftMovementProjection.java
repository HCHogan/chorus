package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.input.ActionGate;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** Server queries decide; the host owns axis anchors and synchronizes the resulting restrictions. */
public final class MinecraftMovementProjection implements AutoCloseable {
    public record Report(String holder,MinecraftNativeActions.Report query) {}
    private final ServerLevel level;private final CompiledEffects program;
    private final Set<LivingEntity> owned=Collections.newSetFromMap(new IdentityHashMap<>());
    private List<Report> reports=List.of();
    MinecraftMovementProjection(ServerLevel level,CompiledEffects program){this.level=level;this.program=program;}
    public List<Report> reports(){return reports;}
    private LivingEntity resolve(String holder){
        try{var entity=level.getEntity(UUID.fromString(holder));return entity instanceof LivingEntity living?living:null;}
        catch(IllegalArgumentException invalid){return null;}
    }
    public void reconcile(EffectState state){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Movement projection requires the server thread");
        if(!MinecraftMovementInput.hasGates(program))return;
        var holders=new TreeSet<String>();state.sources().values().forEach(s->holders.add(s.holder()));state.buffs().instances().values().forEach(b->holders.add(b.key().holder()));
        var desired=new LinkedHashMap<LivingEntity,Integer>();var observations=new ArrayList<Report>();RuntimeException failure=null;
        for(String holder:holders){
            var actor=resolve(holder);if(actor==null||actor.isRemoved()||!actor.isAlive()||actor.level()!=level||actor.isSpectator())continue;
            int mask=0;
            for(var kind:MinecraftMovementInput.KINDS){
                if(!program.hasActionGates(kind))continue;
                var input=MinecraftNativeActions.input(actor,null,(MinecraftMovementInput.bit(kind)&MinecraftMovementInput.MOTION)!=0?"minecraft:motion_constraint":"minecraft:movement_input",kind);
                var phase=kind==ActionGate.Kind.JUMP?ActionGate.Phase.START:ActionGate.Phase.CONTINUE;
                int bit=MinecraftMovementInput.bit(kind);
                MinecraftNativeActions.Report report;
                try{
                    var decision=program.checkAction(state,kind,phase,input);if(!decision.allowed())mask|=bit;
                    report=new MinecraftNativeActions.Report(state.buffs().timeMicros(),input,decision.allowed()?MinecraftNativeActions.Outcome.ALLOWED:MinecraftNativeActions.Outcome.RESTRICTED,Optional.of(decision),Optional.empty());
                }catch(RuntimeException error){
                    // A broken query cannot authorize this input. Other queries still supply their own evidence.
                    mask|=bit;if(failure==null)failure=error;else failure.addSuppressed(error);
                    report=new MinecraftNativeActions.Report(state.buffs().timeMicros(),input,MinecraftNativeActions.Outcome.QUERY_FAILED,Optional.empty(),Optional.of(error.getClass().getSimpleName()+": "+error.getMessage()));
                }
                observations.add(new Report(holder,report));
            }
            if(mask!=0)desired.put(actor,mask);
        }
        reports=List.copyOf(observations);
        for(var previous:List.copyOf(owned))if(!desired.containsKey(previous)){
            ((MinecraftMovementInput.Synced)previous).chorus$movementRestrictions(this,0);owned.remove(previous);
        }
        desired.forEach((actor,mask)->{
            // Own the committed field even if sending its update fails after the write.
            owned.add(actor);
            ((MinecraftMovementInput.Synced)actor).chorus$movementRestrictions(this,mask);
            if(actor instanceof net.minecraft.server.level.ServerPlayer player){
                player.setLastClientInput(MinecraftMovementInput.filter(player.getLastClientInput(),mask));
                if((mask&MinecraftMovementInput.MOVE)!=0){player.setShiftKeyDown(false);player.setSprinting(false);}
            }
        });
        if(failure!=null)throw failure;
    }
    @Override public void close(){
        RuntimeException failure=null;
        for(var actor:owned)try{((MinecraftMovementInput.Synced)actor).chorus$movementRestrictions(this,0);}
        catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        owned.clear();reports=List.of();if(failure!=null)throw failure;
    }
}
