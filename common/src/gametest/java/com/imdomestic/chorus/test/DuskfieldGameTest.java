package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Real registry damage types, physical impact, time, area membership and shared status lifecycle. */
public class DuskfieldGameTest {
    static final String ABILITY="chorus_d2:duskfield",FIELD="chorus_d2:duskfield_field",SLOT="chorus_d2:grenade",ENERGY="chorus_d2:grenade_energy";
    static CompiledEffects program(){
        var data=ThreadedSpikeGameTest.json("duskfield");ThreadedSpikeGameTest.json("duskfield_test_calibration").getAsJsonObject("parameters").entrySet().forEach(e->data.getAsJsonArray("abilities").get(0).getAsJsonObject().getAsJsonObject("parameters").getAsJsonObject(e.getKey()).add("value",e.getValue()));
        var parts=new ArrayList<EffectProgram>();parts.add(DuranceGameTest.program().program());parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow());for(String name:List.of("grenade_energy","duskfield_energy","duskfield_damage_test_calibration","duskfield_inputs"))parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json(name)).getOrThrow());return CompiledEffects.link(parts);
    }
    static final class Harness implements AutoCloseable{
        final GameTestHelper h;final MinecraftEffectRuntime runtime;final MinecraftWorldActions world;final ServerPlayer owner;final List<LivingEntity> entities=new ArrayList<>();final List<EffectProjectile> flights=new ArrayList<>();final List<DamageCommand> damage=new ArrayList<>();final List<DamageReceipt> receipts=new ArrayList<>();final List<Long> times=new ArrayList<>();final List<StatusResult.Check> checks=new ArrayList<>();final Map<BlockPos,BlockState> blocks=new HashMap<>();boolean failPulse;
        Harness(GameTestHelper h,EffectState.Mode mode){
            this.h=h;owner=player(2.5);floor(2);world=new MinecraftWorldActions(h.getLevel(),this::resolve,d->{var type=h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE,Identifier.parse(d.damageType())));return new DamageSource(type,null,resolve(d.source().owner()));},(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program(),EffectState.empty().withMode(mode),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),r->{
                if(r.command() instanceof StatusResult.Check q)checks.add(q);var result=world.apply(r);
                if(result instanceof ProjectileFlight.Receipt receipt&&receipt.entity().isPresent())flights.add((EffectProjectile)h.getLevel().getEntity(UUID.fromString(receipt.entity().orElseThrow())));
                if(result instanceof DamageReceipt receipt){var d=(DamageCommand)r.command();damage.add(d);receipts.add(receipt);times.add(state().buffs().timeMicros());if(failPulse&&d.tags().contains("chorus_d2:duskfield_tick"))throw new IllegalStateException("unknown native duskfield tick");}
                return result;
            },MinecraftEffectRuntime::nativeSource);
        }
        LivingEntity resolve(String id){try{return h.getLevel().getEntity(UUID.fromString(id)) instanceof LivingEntity e?e:null;}catch(IllegalArgumentException bad){return null;}}
        EffectState state(){return runtime.state().engine().domain();}
        void floor(int x){var pos=h.absolutePos(new BlockPos(x,39,2));blocks.putIfAbsent(pos,h.getLevel().getBlockState(pos));h.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());}
        ServerPlayer player(double x){var p=NativeMeleeGameTest.player(h);p.setPos(h.absoluteVec(new Vec3(x,40,2.5)));p.setNoGravity(true);p.setXRot(90);p.setYRot(0);p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);p.setHealth(1000);entities.add(p);return p;}
        LivingEntity mob(double x){var e=h.spawnWithNoFreeWill(EntityTypes.COW,new Vec3(x,40,2.5));e.setNoGravity(true);e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);e.setHealth(1000);e.addTag("chorus_d2:elite");entities.add(e);return e;}
        void select(ServerPlayer p,boolean selected){String id=p.getUUID().toString();runtime.abilities(new AbilityChange(id,state().abilities().getOrDefault(id,AbilityLoadout.EMPTY),selected?new AbilityLoadout(Map.of(SLOT,ABILITY)):AbilityLoadout.EMPTY));}
        void cast(ServerPlayer p){select(p,true);h.assertValueEqual(runtime.useAbility(p,SLOT).outcome(),AbilityUse.Outcome.ACCEPTED,"grenade accepted");}
        List<BuffInstance> fields(){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals(FIELD)).toList();}
        Optional<BuffInstance> status(LivingEntity target,String name){return state().buffs().instances().values().stream().filter(b->b.definition().id().equals("chorus_d2:"+name)&&b.key().holder().equals(target.getUUID().toString())).findFirst();}
        void settled(){h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"Duskfield runtime failed: "+runtime.failure());}
        void at(int ticks,Runnable check){h.runAfterDelay(ticks,()->{try{runtime.prepare();check.run();settled();}catch(Exception|Error e){close();throw e;}});}
        void finish(int ticks,Runnable check){at(ticks,()->{check.run();close();h.succeed();});}
        @Override public void close(){runtime.close();flights.forEach(Entity::discard);entities.forEach(Entity::discard);blocks.forEach((p,s)->h.getLevel().setBlockAndUpdate(p,s));}
    }
    @GameCase(environment="chorus_gametest:duskfield_members",maxTicks=45)
    public void nativeDotBypassesHitCooldownAndFixedFieldFindsLateEntrantsWithoutFollowingOwner(GameTestHelper h){
        var t=new Harness(h,EffectState.Mode.PVE);try{
            var target=t.mob(3.5);var late=t.mob(20);t.cast(t.owner);
            t.at(6,()->{h.assertValueEqual(t.fields().size(),1,"physical ground collision creates one field");h.assertTrue(t.flights.getFirst().isRemoved(),"impact did not consume grenade");near(h,target.getHealth(),990,"synthetic one-meter impact falloff");late.setPos(h.absoluteVec(new Vec3(6.5,40,2.5)));t.owner.setPos(h.absoluteVec(new Vec3(100,40,2.5)));});
            t.at(13,()->{near(h,target.getHealth(),989,"first periodic point was swallowed by native hurt cooldown");near(h,late.getHealth(),999,"late entrant at four-meter boundary");h.assertValueEqual(t.status(target,"slow").orElseThrow().count(),30,"impact and pulse stacks");h.assertValueEqual(t.status(late,"slow").orElseThrow().count(),10,"late entrant receives only pulse stacks");h.assertTrue(target.getLastDamageSource().is(DamageTypeTags.BYPASSES_COOLDOWN),"not the production DOT damage type");target.setPos(h.absoluteVec(new Vec3(6.51,40,2.5)));});
            t.finish(20,()->{near(h,target.getHealth(),989,"exited target received another pulse");near(h,late.getHealth(),998,"fixed center moved with owner");h.assertValueEqual(t.damage.getLast().source().owner(),t.owner.getUUID().toString(),"moved caster attribution");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:duskfield_durance",maxTicks=210)
    public void unequippedGrenadeKeepsNineSecondFieldButLaterSlowUsesCurrentDuranceAndFreezes(GameTestHelper h){
        var t=new Harness(h,EffectState.Mode.PVE);try{
            var target=t.mob(3.5);t.cast(t.owner);t.select(t.owner,false);t.runtime.bind(DuranceGameTest.fragment("own",t.owner));var deadline=new long[1];
            t.at(6,()->{var field=t.fields().getFirst();deadline[0]=field.deadline();h.assertValueEqual(t.checks.getLast().duration(),4_000_000L,"initial Slow extension");h.assertTrue(deadline[0]-t.state().buffs().timeMicros()>8_500_000L,"field extension missing");t.runtime.unbind("own");});
            t.at(13,()->{h.assertValueEqual(t.fields().getFirst().deadline(),deadline[0],"unequipping resized existing field");h.assertValueEqual(t.checks.getLast().duration(),2_000_000L,"later Slow kept unequipped Durance");});
            t.at(62,()->{h.assertTrue(t.status(target,"slow").isEmpty()&&t.status(target,"freeze").isPresent(),"field alone did not reach Freeze");h.assertValueEqual(NativeMovementGameTest.mask(target),15,"native frozen movement");h.assertValueEqual(t.status(target,"freeze").orElseThrow().origin(),t.damage.getFirst().source(),"field lost original grenade credit");});
            t.at(170,()->h.assertValueEqual(t.fields().size(),1,"extended field ended before nine seconds"));
            t.finish(190,()->{h.assertTrue(t.fields().isEmpty(),"extended field did not expire");h.assertTrue(t.times.stream().allMatch(time->time<deadline[0]),"pulse at or after field expiry");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:duskfield_guardian",maxTicks=135)
    public void actualGuardianUsesFiveStacksAndPointThreeSecondPulsesUntilFreeze(GameTestHelper h){
        var t=new Harness(h,EffectState.Mode.PVP);try{
            var target=t.player(3.5);t.cast(t.owner);
            t.finish(115,()->{var pulses=new ArrayList<Long>();for(int i=0;i<t.damage.size();i++)if(t.damage.get(i).tags().contains("chorus_d2:duskfield_tick"))pulses.add(t.times.get(i));h.assertTrue(pulses.size()>=18,"too few PvP pulses");for(int i=1;i<pulses.size();i++)h.assertValueEqual(pulses.get(i)-pulses.get(i-1),300_000L,"PvP logical cadence");near(h,target.getHealth(),990-pulses.size(),"every native one-point tick applies");h.assertTrue(t.status(target,"freeze").isPresent(),"initial ten plus eighteen times five did not Freeze Guardian");h.assertValueEqual(NativeMovementGameTest.mask(target),15,"Guardian freeze controls");h.assertValueEqual(t.checks.stream().filter(c->c.definition().id().equals("chorus_d2:freeze")).findFirst().orElseThrow().duration(),1_350_000L,"player-sourced Freeze duration");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:duskfield_independent",maxTicks=170)
    public void twoCastersKeepIndependentAnchorsAndSevenVersusNineSecondFieldLifetimes(GameTestHelper h){
        var t=new Harness(h,EffectState.Mode.PVE);try{
            // Keep both flights inside the 8x8 structure's ticking footprint, even at a chunk boundary.
            var other=t.player(6.5);t.floor(6);t.mob(3.5);t.mob(7.5);t.runtime.bind(DuranceGameTest.fragment("own",t.owner));t.cast(t.owner);t.cast(other);
            t.at(6,()->{h.assertValueEqual(t.fields().size(),2,"two physical impacts have independent fields; flights="+t.flights.stream().map(f->f.position()+" ticks="+f.tickCount+" progress="+f.progress()).toList());h.assertValueEqual(t.fields().stream().map(b->b.origin().owner()).distinct().count(),2L,"caster credit merged");h.assertValueEqual(t.fields().stream().map(b->b.components().positions().get("anchor")).distinct().count(),2L,"anchors merged");});
            t.finish(150,()->{h.assertValueEqual(t.fields().size(),1,"seven-second field did not end independently");h.assertValueEqual(t.fields().getFirst().origin().owner(),t.owner.getUUID().toString(),"wrong field survived");});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:duskfield_fault",maxTicks=45)
    public void unknownNativeTickKeepsCommittedDamageAndChargeWithoutFurtherPulses(GameTestHelper h){
        var t=new Harness(h,EffectState.Mode.PVE);try{
            var target=t.mob(3.5);t.cast(t.owner);t.failPulse=true;
            h.runAfterDelay(25,()->{try(t){h.assertTrue(t.runtime.failure().isPresent(),"unknown native tick did not stop runtime");near(h,target.getHealth(),989,"committed native tick repeated or rolled back");h.assertValueEqual(t.damage.size(),2,"additional pulse after unknown write");h.assertValueEqual(t.checks.size(),1,"unknown tick applied another Slow");h.assertTrue(t.state().resources().get(new ResourceState.Key(t.owner.getUUID().toString(),ENERGY)).value()<.01,"spent charge restored");h.succeed();}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
