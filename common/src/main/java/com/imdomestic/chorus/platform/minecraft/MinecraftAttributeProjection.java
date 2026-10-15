package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.attribute.NativeAttributeBinding;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.resource.ResourceReadView;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;

/** Reconciles only this runtime's transient modifiers. Never reads a native total back into Chorus. */
public final class MinecraftAttributeProjection implements AutoCloseable {
    public enum Outcome { PROJECTED, ZERO, MISSING_TARGET, INELIGIBLE, UNSUPPORTED_ATTRIBUTE }
    /** Last successful reconciliation, including requested contribution and the clamped native total at that boundary. */
    public record Report(String holder,NativeAttributeBinding.Calculated calculation,Outcome outcome,OptionalDouble nativeValue) {}
    private record Key(LivingEntity entity,String binding) {}
    private record Owned(AttributeInstance attribute,AttributeModifier modifier) {}
    private record PendingReport(String holder,NativeAttributeBinding.Calculated calculation,Outcome outcome,AttributeInstance attribute) {}
    private final ServerLevel level;private final CompiledEffects program;
    private final String prefix="projection/"+UUID.randomUUID()+"/";
    private final Map<String,Holder<Attribute>> attributes=new LinkedHashMap<>();
    private final Map<Key,Owned> owned=new LinkedHashMap<>();
    private List<Report> reports=List.of();
    public MinecraftAttributeProjection(ServerLevel level,CompiledEffects program){
        this.level=level;this.program=program;
        var registry=level.registryAccess().lookupOrThrow(Registries.ATTRIBUTE);
        for(var binding:program.program().nativeAttributes())attributes.put(binding.id(),registry.getOrThrow(ResourceKey.create(Registries.ATTRIBUTE,Identifier.parse(binding.attribute()))));
    }
    public List<Report> reports(){return reports;}
    private LivingEntity resolve(String holder){
        try{var entity=level.getEntity(UUID.fromString(holder));return entity instanceof LivingEntity living?living:null;}
        catch(IllegalArgumentException invalid){return null;}
    }
    public void reconcile(EffectState state){reconcile(new MinecraftProjectionQueries(program,state),Optional.empty(),0);}
    public void reconcile(ResourceReadView view){reconcile(new MinecraftProjectionQueries(program,view),Optional.empty(),0);}
    boolean tracks(EffectState state,LivingEntity entity){
        if(attributes.isEmpty())return false;
        String holder=entity.getUUID().toString();
        return state.equipment().containsKey(holder)||state.abilities().containsKey(holder)
                ||state.sources().values().stream().anyMatch(source->source.holder().equals(holder))
                ||state.buffs().instances().values().stream().anyMatch(buff->buff.key().holder().equals(holder))
                ||owned.keySet().stream().anyMatch(key->key.entity().getUUID().equals(entity.getUUID()));
    }
    /** NBT attributes are loaded, but vanilla has not yet clamped Health and the entity may not be indexed. */
    public void beforeLoadedHealth(EffectState state,LivingEntity entity,float health){
        beforeLoadedHealth(new MinecraftProjectionQueries(program,state),entity,health);
    }
    public void beforeLoadedHealth(ResourceReadView view,LivingEntity entity,float health){
        beforeLoadedHealth(new MinecraftProjectionQueries(program,view),entity,health);
    }
    private void beforeLoadedHealth(MinecraftProjectionQueries queries,LivingEntity entity,float health){
        if(entity.level()!=level)throw new IllegalArgumentException("Loading entity belongs to another level");
        reconcile(queries,Optional.of(entity),health);
    }
    private void reconcile(MinecraftProjectionQueries queries,Optional<LivingEntity> loading,float loadedHealth){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Native attributes require the server thread");
        if(attributes.isEmpty())return;
        var state=queries.state();
        var holders=new TreeSet<String>();state.sources().values().forEach(s->holders.add(s.holder()));state.buffs().instances().values().forEach(b->holders.add(b.key().holder()));
        holders.addAll(state.equipment().keySet());holders.addAll(state.abilities().keySet());
        loading.ifPresent(entity->holders.retainAll(Set.of(entity.getUUID().toString())));
        java.util.function.Predicate<Key> affected=key->loading.isEmpty()||key.entity().getUUID().equals(loading.orElseThrow().getUUID());
        var desired=new LinkedHashMap<Key,Owned>();var observations=new ArrayList<PendingReport>();
        var identities=new IdentityHashMap<LivingEntity,String>();
        // Resolve every query and conflict before changing any modifier, including cleanup of old targets.
        for(String holder:holders){
            var calculated=queries.nativeAttributes(holder);var target=loading.orElseGet(()->resolve(holder));
            if(target!=null&&identities.put(target,holder)!=null)throw new IllegalArgumentException("Multiple native attribute references resolve to one entity");
            for(var value:calculated){
                var binding=value.binding();AttributeInstance attribute=null;Outcome outcome;
                if(target==null||target.isRemoved()||target.level()!=level)outcome=Outcome.MISSING_TARGET;
                else if((loading.isPresent()?!(loadedHealth>0):!target.isAlive())||target.isSpectator())outcome=Outcome.INELIGIBLE;
                else if((attribute=target.getAttribute(attributes.get(binding.id())))==null)outcome=Outcome.UNSUPPORTED_ATTRIBUTE;
                else if(value.amount()==0)outcome=Outcome.ZERO;
                else{
                    outcome=Outcome.PROJECTED;
                    var id=Identifier.fromNamespaceAndPath("chorus",prefix+binding.id().replace(':','/'));
                    var modifier=new AttributeModifier(id,value.amount(),AttributeModifier.Operation.valueOf(binding.operation().name()));
                    desired.put(new Key(target,binding.id()),new Owned(attribute,modifier));
                }
                observations.add(new PendingReport(holder,value,outcome,attribute));
            }
        }
        for(var entry:owned.entrySet()){
            if(!affected.test(entry.getKey()))continue;
            var previous=entry.getValue();
            var actual=previous.attribute().getModifier(previous.modifier().id());
            if(actual!=null&&!actual.equals(previous.modifier()))throw new IllegalStateException("Native attribute projection was overwritten outside its runtime: "+actual.id());
        }
        for(var entry:desired.entrySet()){
            var next=entry.getValue();var actual=next.attribute().getModifier(next.modifier().id());var previous=owned.get(entry.getKey());
            // Exact known copies can be adopted after native entity replacement; an unrelated modifier is never overwritten.
            if(actual!=null&&(previous==null||previous.attribute()!=next.attribute())&&owned.values().stream().noneMatch(o->o.modifier().equals(actual)))
                throw new IllegalStateException("Native attribute modifier identity conflict: "+actual.id());
        }
        for(var entry:List.copyOf(owned.entrySet())){
            if(!affected.test(entry.getKey()))continue;
            var previous=entry.getValue();var next=desired.get(entry.getKey());
            if(next==null||next.attribute()!=previous.attribute()){
                if(previous.modifier().equals(previous.attribute().getModifier(previous.modifier().id())))previous.attribute().removeModifier(previous.modifier().id());
                owned.remove(entry.getKey());
            }
        }
        for(var entry:desired.entrySet()){
            var next=entry.getValue();owned.put(entry.getKey(),next);
            if(!next.modifier().equals(next.attribute().getModifier(next.modifier().id())))next.attribute().addOrUpdateTransientModifier(next.modifier());
        }
        var nextReports=new ArrayList<Report>();
        if(loading.isPresent())reports.stream().filter(r->!r.holder().equals(loading.orElseThrow().getUUID().toString())).forEach(nextReports::add);
        observations.stream().map(o->new Report(o.holder(),o.calculation(),o.outcome(),o.attribute()==null?OptionalDouble.empty():OptionalDouble.of(o.attribute().getValue()))).forEach(nextReports::add);
        reports=List.copyOf(nextReports);
    }
    @Override public void close(){
        RuntimeException failure=null;
        for(var value:owned.values())try{
            if(value.modifier().equals(value.attribute().getModifier(value.modifier().id())))value.attribute().removeModifier(value.modifier().id());
        }catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        owned.clear();reports=List.of();if(failure!=null)throw failure;
    }
}
