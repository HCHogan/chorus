package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.ArmorChargeGameTest.*;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.ability.AbilityUse;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;

/** Real Orb acquisition, server-authorized finishers and actual payment/reward consumers. */
public class ArmorFinisherGameTest {
    static ProjectileGameTest.Harness openMods(GameTestHelper h,String... additional)throws Exception{
        var modules=new ArrayList<>(List.of("armor_finisher_mods","armor_finisher_inputs"));modules.addAll(List.of(additional));
        var t=ArmorChargeGameTest.open(h,modules.toArray(String[]::new));
        try{t.runtime.bind(FinisherInputGameTest.input(t.owner));t.runtime.bind(FinisherInputGameTest.observer(t.owner));IncandescentGameTest.bind(t,"mod_inputs","test:finisher_mod_inputs","");StarvationGameTest.select(t,"melee","finisher_melee");return t;}catch(Exception|Error e){t.close();throw e;}
    }
    static void charge(ProjectileGameTest.Harness t,String name,int n){mod(t,name,0);for(int i=0;i<3;i++)mod(t,"charged_up",i);for(int i=0;i<n;i++)orb(t);}
    static LivingEntity begin(ProjectileGameTest.Harness t)throws Exception{var pos=t.h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5,40,3.5));t.owner.setPos(pos);t.owner.setXRot(0);t.owner.setYRot(0);var victim=FinisherInputGameTest.target(t,5.5,20);TimelostMagazineGameTest.command(t,"chorus finisher");ShieldThrowGameTest.settled(t);return victim;}
    @GameCase(environment="chorus_gametest:armor_finisher_heal",maxTicks=40)
    public void realFinisherConsumesOneOrbChargeAndFullyHealsWithoutAddingAbsorption(GameTestHelper h)throws Exception{
        var t=openMods(h);try{charge(t,"healthy_finisher",3);mod(t,"healthy_finisher",1);t.owner.addEffect(new MobEffectInstance(MobEffects.ABSORPTION,600,1));t.owner.setAbsorptionAmount(7);var victim=begin(t);h.assertValueEqual(charges(t),3,"no payment at input");near(h,t.owner.getHealth(),100,"no premature heal");
            t.finish(10,()->{h.assertTrue(!victim.isAlive(),"actual completed victim");h.assertValueEqual(charges(t),2,"one payment despite duplicate copies");near(h,t.owner.getHealth(),1000,"full ordinary health");near(h,t.owner.getAbsorptionAmount(),7,"no invented overshield healing");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_finisher_bulwark",maxTicks=40)
    public void sixChargesGiveFourteenSecondSharedShieldThatAbsorbsRealCombatantDamage(GameTestHelper h)throws Exception{
        var t=openMods(h);try{charge(t,"bulwark_finisher",6);begin(t);t.finish(10,()->{h.assertValueEqual(charges(t),0,"all six charges paid");var b=buff(t,"void_overshield").orElseThrow();near(h,b.components().numbers().get("capacity"),45,"shared shield pool");near(h,b.originalDurationMicros()/1e6,14,"six-stack duration");unmod(t,"bulwark_finisher",0);var attacker=t.cow(8,40,8);MinecraftDamageExecutor.execute("bulwark/"+UUID.randomUUID(),t.owner,h.getLevel().damageSources().mobAttack(attacker),10,false);ShieldThrowGameTest.settled(t);near(h,t.owner.getHealth(),100,"shield survives mod removal and takes actual hit");near(h,buff(t,"void_overshield").orElseThrow().components().numbers().get("capacity"),42,"combatant 70 percent DR on shield");});
        }catch(Throwable e){t.close();throw e;}
    }
    static void energy(GameTestHelper h,String modName,String slot,String account)throws Exception{
        var t=openMods(h);try{charge(t,modName,6);var first=begin(t);RampageGameTest.at(t,10,()->{h.assertTrue(!first.isAlive(),"first confirmed finisher");h.assertValueEqual(charges(t),0,"six paid");near(h,StarvationGameTest.account(t,account),.5,"one CES application");first.discard();for(int i=0;i<6;i++)orb(t);begin(t);});
            t.finish(20,()->{h.assertValueEqual(charges(t),0,"second independent finisher paid");near(h,StarvationGameTest.account(t,account),1,"two rewards fill one use");var receipt=t.runtime.useAbility(t.owner,"chorus_d2:"+slot);h.assertValueEqual(receipt.outcome(),AbilityUse.Outcome.ACCEPTED,"real selected ability accepts energy");ShieldThrowGameTest.settled(t);near(h,StarvationGameTest.account(t,account),0,"ability actually consumed the awarded energy");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_finisher_grenade",maxTicks=40)
    public void explosiveFinisherRewardsCanActuallyPayTheSelectedGrenade(GameTestHelper h)throws Exception{energy(h,"explosive_finisher","grenade","grenade_a");}
    @GameCase(environment="chorus_gametest:armor_finisher_melee",maxTicks=40)
    public void oneTwoFinisherRewardsCanActuallyPayTheSelectedMelee(GameTestHelper h)throws Exception{energy(h,"one_two_finisher","melee","finisher_melee");}
    @GameCase(environment="chorus_gametest:armor_finisher_class",maxTicks=40)
    public void utilityFinisherRewardsCanActuallyPayTheSelectedClassAbility(GameTestHelper h)throws Exception{energy(h,"utility_finisher","class","class_a");}
    @GameCase(environment="chorus_gametest:armor_finisher_full",maxTicks=40)
    public void fullHealthKeepsChargesAndLateModSelectionUsesTheCurrentAbilityAccount(GameTestHelper h)throws Exception{
        var t=openMods(h);try{charge(t,"healthy_finisher",3);t.owner.setHealth(1000);var first=begin(t);RampageGameTest.at(t,10,()->{h.assertValueEqual(charges(t),3,"full health has no benefit or charge cost");first.discard();begin(t);unmod(t,"healthy_finisher",0);mod(t,"utility_finisher",0);StarvationGameTest.select(t,"class","class_b");});
            t.finish(20,()->{h.assertValueEqual(charges(t),0,"late equipped mod consumes after completion");near(h,StarvationGameTest.account(t,"class_b"),.7*.75,"current selected account scalar");near(h,StarvationGameTest.account(t,"class_a"),0,"old account unchanged");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_finisher_protected",maxTicks=40)
    public void actualDeathProtectionNeverSpendsOrHealsAndInsufficientChargeStillAllowsCompletion(GameTestHelper h)throws Exception{
        var t=openMods(h);try{charge(t,"healthy_finisher",1);var victim=begin(t);victim.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));RampageGameTest.at(t,10,()->{h.assertTrue(victim.isAlive()&&victim.getOffhandItem().isEmpty(),"actual death prevented");h.assertValueEqual(charges(t),1,"protected strike has no payment");near(h,t.owner.getHealth(),100,"no completion reward");victim.discard();unmod(t,"healthy_finisher",0);mod(t,"utility_finisher",0);begin(t);});
            t.finish(20,()->{h.assertValueEqual(FinisherGameTest.count(t,"completed"),1L,"insufficient cost cannot block normal finisher");h.assertValueEqual(charges(t),1,"no partial payment");near(h,StarvationGameTest.account(t,"class_a"),0,"no unpaid reward");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_finisher_modes",maxTicks=40)
    public void mixedEmpoweredAndConsumerSourcesDoNotGenerateAndSpendFromTheSameCompletion(GameTestHelper h)throws Exception{
        var t=openMods(h);try{charge(t,"healthy_finisher",3);mod(t,"empowered_finish",0);begin(t);t.finish(10,()->{h.assertValueEqual(FinisherGameTest.count(t,"completed"),1L,"finisher completes with invalid content combination");h.assertValueEqual(charges(t),3,"no mixed-mode mutation");near(h,t.owner.getHealth(),100,"no benefit chosen by source iteration order");});}catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_finisher_empowered",maxTicks=40)
    public void empoweredDuplicateCopiesGrantOnceAtZeroAndUseStacksOnStacks(GameTestHelper h)throws Exception{
        var t=openMods(h);try{mod(t,"empowered_finish",0);mod(t,"empowered_finish",1);mod(t,"stacks_on_stacks",0);var first=begin(t);RampageGameTest.at(t,10,()->{h.assertValueEqual(charges(t),2,"one reward plus one bonus");first.discard();begin(t);});t.finish(20,()->h.assertValueEqual(charges(t),2,"nonzero charge suppresses later reward"));}catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:armor_finisher_unknown",maxTicks=40)
    public void unknownHealingAfterActualCompletionRetainsPaymentAndHealthWithoutAnotherReward(GameTestHelper h)throws Exception{
        var t=openMods(h);try{charge(t,"healthy_finisher",3);t.failAfterHealing=true;var victim=begin(t);h.runAfterDelay(10,()->{try{h.assertTrue(!victim.isAlive()&&t.runtime.failure().isPresent(),"confirmed kill then unknown healing");h.assertValueEqual(charges(t),2,"paid once");near(h,t.owner.getHealth(),1000,"actual healing committed");int operations=t.operations.size();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"stopped runtime does not repeat world operations");h.assertValueEqual(charges(t),2,"no second charge payment");h.succeed();}finally{t.close();}});}catch(Throwable e){t.close();throw e;}
    }
}
