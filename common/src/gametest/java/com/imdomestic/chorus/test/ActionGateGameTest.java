package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Native command ownership, actual healing and ammunition with synthetic restriction declarations. */
public class ActionGateGameTest {
    static WeaponReloadGameTest.Harness harness(GameTestHelper h)throws Exception{
        return new WeaponReloadGameTest.Harness(h,data->{
            WeaponReloadGameTest.incremental(data);
            var gates=ThreadedSpikeGameTest.json("action_gates");
            for(String key:List.of("resources","abilities"))data.add(key,gates.get(key));
            for(String key:List.of("buffs","bundles"))gates.getAsJsonArray(key).forEach(data.getAsJsonArray(key)::add);
        });
    }
    static String holder(ServerPlayer player){return player.getUUID().toString();}
    static void choose(WeaponReloadGameTest.Harness t,ServerPlayer player,String ability){
        t.runtime.abilities(new AbilityChange(holder(player),AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:slot","test:"+ability))));
    }
    static EffectSource source(ServerPlayer player,String id,String bundle,String weapon){
        return new EffectSource(id,"test:"+bundle,holder(player),new BuffInstance.Origin(holder(player),id,weapon,""),Set.of());
    }
    static double energy(WeaponReloadGameTest.Harness t,ServerPlayer player){return t.state().resources().get(new ResourceState.Key(holder(player),"test:gate_energy")).value();}
    static void restricted(WeaponReloadGameTest.Harness t,ServerPlayer player,String command){
        try{t.command(player,command);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){t.h.assertTrue(e.getMessage().contains("RESTRICTED"),"command reported wrong rejection: "+e.getMessage());return;}
        t.h.assertTrue(false,"restricted command was accepted: "+command);
    }

    @GameCase(environment="chorus_gametest:action_gate_ability")
    public void ordinaryAbilityCommandChecksTheReplacementAndCannotBorrowAnotherPlayersRestriction(GameTestHelper h)throws Exception{
        try(var t=harness(h)){
            var owner=t.player("gate-");var other=t.player("other-");choose(t,owner,"plain");choose(t,other,"blocked");
            t.runtime.bind(source(owner,"guard","input_gate",""));t.runtime.bind(source(owner,"replace","gate_replacement",""));
            restricted(t,owner,"chorus ability use test:slot");
            var receipt=t.runtime.useAbility(owner,"test:slot");h.assertValueEqual(receipt.outcome(),AbilityUse.Outcome.RESTRICTED,"typed denial");
            h.assertValueEqual(receipt.resolved(),"test:blocked","replacement resolved before restriction");
            h.assertValueEqual(receipt.restriction().orElseThrow().denials().getFirst().origin().source(),"guard","denial origin retained");
            near(h,energy(t,owner),2,"denied use did not pay");near(h,owner.getHealth(),10,"denied use did not heal");
            h.assertValueEqual(t.command(other,"chorus ability use test:slot"),1,"other holder may use the same definition");
            near(h,energy(t,other),1,"accepted use pays once");near(h,other.getHealth(),15,"accepted native healing");
            t.runtime.unbind("guard");h.assertValueEqual(t.command(owner,"chorus ability use test:slot"),1,"unbinding restriction restores eligibility");
            near(h,energy(t,owner),1,"restored use pays once");near(h,owner.getHealth(),15,"restored native healing");t.settled();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:action_gate_expiry",maxTicks=14)
    public void aDebuffRestrictsItsRecipientUntilExpiryWithoutConsumingAbilityEnergy(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            var caster=t.player("caster-");var victim=t.player("victim-");choose(t,victim,"plain");
            var origin=source(caster,"inputs","gate_inputs","");t.runtime.bind(origin);
            t.runtime.start(new RuleEngine.Signal("test:lock",new EffectEvent(holder(caster),holder(victim),origin.origin(),Set.of(),Map.of())));
            var receipt=t.runtime.useAbility(victim,"test:slot");h.assertValueEqual(receipt.outcome(),AbilityUse.Outcome.RESTRICTED,"recipient buff denies ability");
            h.assertValueEqual(receipt.restriction().orElseThrow().denials().getFirst().origin().owner(),holder(caster),"caster attribution preserved");
            near(h,energy(t,victim),2,"debuff rejection costs no energy");near(h,victim.getHealth(),10,"no rejected healing");
            h.runAfterDelay(6,()->{try(t){
                h.assertValueEqual(t.command(victim,"chorus ability use test:slot"),1,"expiry restores native input");
                near(h,energy(t,victim),1,"only accepted use pays");near(h,victim.getHealth(),15,"native healing after expiry");t.settled();h.succeed();
            }catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IllegalStateException(e);}});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:action_gate_reload",maxTicks=14)
    public void deniedWeaponInputsDoNotSpendAmmoOrCancelThePlanUntilItsCompletionCheck(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            var owner=t.player("guarded-");var other=t.player("other-");h.assertValueEqual(t.command(owner,"chorus weapon reload"),1,"initial reload starts");
            var plan=t.state().reloads().get(holder(owner));t.runtime.bind(source(owner,"guard","input_gate",""));
            restricted(t,owner,"chorus weapon fire");restricted(t,owner,"chorus weapon reload");
            h.assertValueEqual(t.state().reloads().get(holder(owner)),plan,"denied input preserved existing plan");
            h.assertValueEqual(t.magazine("guarded-a"),1,"denied fire kept round");
            h.assertValueEqual(t.command(other,"chorus weapon fire"),1,"other player's weapon remains eligible");h.assertValueEqual(t.magazine("other-a"),0,"other player really fired");
            h.runAfterDelay(6,()->{try(t){
                t.runtime.prepare();t.settled();h.assertTrue(t.state().reloads().isEmpty(),"completion restriction cancelled reload");
                h.assertValueEqual(t.magazine("guarded-a"),1,"completion cannot transfer ammo");h.assertValueEqual(t.reserve("guarded-a"),12,"reserve unchanged");
                near(h,owner.getHealth(),10,"cancelled reload emitted no completion healing");h.assertTrue(t.heals.isEmpty(),"no completion reaction");h.succeed();
            }});
        }catch(Exception|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:action_gate_insert",maxTicks=20)
    public void anInsertionReactionCancelsTheNextStepWithoutUndoingTheCommittedRound(GameTestHelper h)throws Exception{
        var t=harness(h);
        try{
            var owner=t.player("insert-");t.runtime.bind(source(owner,"after","after_insert_lock","insert-a"));
            h.assertValueEqual(t.command(owner,"chorus weapon reload"),1,"incremental reload starts");
            h.runAfterDelay(6,()->{try{
                t.runtime.prepare();t.settled();h.assertValueEqual(t.magazine("insert-a"),2,"first insertion committed");h.assertValueEqual(t.reserve("insert-a"),11,"only one round transferred");
                near(h,owner.getHealth(),11,"actual first insertion reaction");h.assertTrue(t.state().reloads().isEmpty(),"restricted next insertion cancelled");
                h.assertValueEqual(t.runtime.fire(owner).outcome(),WeaponFire.Outcome.RESTRICTED,"weapon-scoped buff blocks fire while active");
            }catch(Exception|Error e){t.close();throw e;}});
            h.runAfterDelay(12,()->{try(t){
                h.assertValueEqual(t.command(owner,"chorus weapon fire"),1,"expired restriction allows fire");h.assertValueEqual(t.magazine("insert-a"),1,"fire uses committed round without restarting reload");
                h.assertValueEqual(t.heals.size(),1,"cancelled insertions never replay");t.settled();h.succeed();
            }catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IllegalStateException(e);}});
        }catch(Exception|Error e){t.close();throw e;}
    }
}
