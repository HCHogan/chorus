package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class SuppressionGameTest {
    static WeaponReloadGameTest.Harness harness(GameTestHelper h){
        return harness(h,EffectState.Mode.PVE);
    }
    static WeaponReloadGameTest.Harness harness(GameTestHelper h,EffectState.Mode mode){
        try{return new WeaponReloadGameTest.Harness(h,data->{
            // Only version metadata is harmonized for the legacy weapon test composition.
            data.getAsJsonArray("weapons").get(0).getAsJsonObject().add("fire",ThreadedSpikeGameTest.json("incremental_reload_settings").get("fire"));
            for(String name:List.of("suppression","suppression_inputs")){
                var part=ThreadedSpikeGameTest.json(name);
                for(var buff:part.getAsJsonArray("buffs")){buff.getAsJsonObject().getAsJsonObject("definition").addProperty("version","test-1");data.getAsJsonArray("buffs").add(buff);}
                for(var bundle:part.getAsJsonArray("bundles"))data.getAsJsonArray("bundles").add(bundle);
                for(String key:List.of("resources","abilities"))if(part.has(key))data.add(key,part.get(key));
            }
        },mode);}catch(Exception e){throw new IllegalStateException(e);}
    }
    static String holder(ServerPlayer player){return player.getUUID().toString();}
    static EffectSource source(ServerPlayer player,String name){return new EffectSource(name,"test:suppression_inputs",holder(player),new BuffInstance.Origin(holder(player),name,name+"-weapon",""),Set.of());}
    static void choose(WeaponReloadGameTest.Harness t,ServerPlayer p){t.runtime.abilities(new AbilityChange(holder(p),AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:super","test:roaming_super","test:transcendence","test:transcendence"))));}
    static boolean has(WeaponReloadGameTest.Harness t,ServerPlayer p,String buff){return t.state().buffs().instances().values().stream().anyMatch(b->b.key().holder().equals(holder(p))&&b.definition().id().equals(buff));}
    static void event(WeaponReloadGameTest.Harness t,String type,EffectSource source,ServerPlayer victim){t.runtime.start(new RuleEngine.Signal("test:"+type,new EffectEvent(source.holder(),holder(victim),source.origin(),Set.of(),Map.of())));}
    static long pulses(WeaponReloadGameTest.Harness t,ServerPlayer p){return t.heals.stream().filter(x->x.target().equals(holder(p))&&x.amount()==1).count();}
    static void command(WeaponReloadGameTest.Harness t,ServerPlayer p,String command){try{t.h.assertValueEqual(t.command(p,command),1,"accepted ordinary command");}catch(Exception e){throw new IllegalStateException(e);}}

    @GameCase(environment="chorus_gametest:suppression_interrupt",maxTicks=24)
    public void actualSuppressionStopsActiveAbilityTicksButAllowsWeaponsAndDetachedEffects(GameTestHelper h){
        var t=harness(h);
        try{
            var owner=t.player("suppressed-");var caster=t.player("caster-");var other=t.player("other-");choose(t,owner);choose(t,other);
            var source=source(caster,"caster");var ownerSource=source(owner,"owner");t.runtime.bind(source);t.runtime.bind(ownerSource);
            command(t,owner,"chorus ability use test:super");command(t,owner,"chorus ability use test:transcendence");command(t,other,"chorus ability use test:transcendence");
            event(t,"unrelated",ownerSource,owner);
            var baseline=new double[1];var pulseCount=new long[1];
            h.runAfterDelay(3,()->{try{
                t.runtime.prepare();h.assertTrue(pulses(t,owner)>0,"active ability caused real periodic healing");baseline[0]=owner.getHealth();pulseCount[0]=pulses(t,owner);
                event(t,"detached",ownerSource,owner);event(t,"suppress",source,owner);
                h.assertTrue(has(t,owner,"chorus_d2:suppression")&&!has(t,owner,"test:roaming_super")&&!has(t,owner,"test:transcendence"),"suppression did not end both active states");
                h.assertTrue(has(t,owner,"test:unrelated")&&has(t,other,"test:transcendence"),"suppression touched unrelated status or holder");
                ActionGateGameTest.restricted(t,owner,"chorus ability use test:super");near(h,t.state().resources().get(new ResourceState.Key(holder(owner),"test:suppression_energy")).value(),1,"rejected recast did not pay");
                event(t,"external_transcendence",source,owner);h.assertTrue(!has(t,owner,"test:transcendence"),"late active state bypassed suppression");
                command(t,owner,"chorus weapon fire");command(t,owner,"chorus weapon reload");
            }catch(RuntimeException|Error e){t.close();throw e;}});
            h.runAfterDelay(13,()->{try(t){
                t.runtime.prepare();t.settled();h.assertValueEqual(pulses(t,owner),pulseCount[0],"old ability pulses stopped");
                near(h,owner.getHealth(),baseline[0]+12,"detached healing and actual five-round reload continue");h.assertValueEqual(t.magazine("suppressed-a"),5,"guardian weapon reload remains usable");
                h.assertTrue(has(t,owner,"chorus_d2:suppression"),"still inside real ten-second duration");h.succeed();
            }});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:suppression_expiry",maxTicks=124)
    public void fiveSecondPvpSuppressionExpiresWithoutResumingTheInterruptedSuper(GameTestHelper h){
        var t=harness(h,EffectState.Mode.PVP);
        try{
            var owner=t.player("expiry-");var caster=t.player("caster-");choose(t,owner);var source=source(caster,"caster");t.runtime.bind(source);
            command(t,owner,"chorus ability use test:super");event(t,"suppress",source,owner);
            h.runAfterDelay(98,()->{try{ActionGateGameTest.restricted(t,owner,"chorus ability use test:super");h.assertTrue(has(t,owner,"chorus_d2:suppression"),"five-second state expired early");}catch(RuntimeException|Error e){t.close();throw e;}});
            h.runAfterDelay(102,()->{try(t){
                t.runtime.prepare();h.assertTrue(!has(t,owner,"chorus_d2:suppression")&&!has(t,owner,"test:roaming_super"),"expiry resumed an interrupted ability or stayed suppressed");
                command(t,owner,"chorus ability use test:super");near(h,t.state().resources().get(new ResourceState.Key(holder(owner),"test:suppression_energy")).value(),0,"new cast paid the remaining charge");
                h.assertTrue(has(t,owner,"test:roaming_super"),"new accepted cast did not activate ability");t.settled();h.succeed();
            }});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
}
