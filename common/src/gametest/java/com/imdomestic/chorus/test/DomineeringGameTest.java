package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.owner;
import static com.imdomestic.chorus.test.TimelostMagazineGameTest.state;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.NumericQuery;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusAttributes;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Synthetic paid on-hit suppression feeds the real Fragment, equipment, native movement and private pickup. */
public class DomineeringGameTest {
    static final String D="chorus_d2:";
    static EffectSource fragment(LivingEntity actor,String id) {String who=actor.getUUID().toString();return new EffectSource(id,D+"echo_of_domineering",who,new BuffInstance.Origin(who,id,who+"/b",""),Set.of(),Map.of("receipt_seconds",new Measure(12,Unit.SECOND)));}
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t=StarvationGameTest.open(h,false,"suppression","echo_of_domineering","directional_movement_attributes","domineering_inputs");
        try {
            var equipment=PlayerEquipment.get(t.owner);
            for(String suffix:List.of("a","b")) {
                var item=new ItemStack(Items.DIAMOND_SWORD);item.set(ChorusComponents.EQUIPMENT.get(),new Loadout.Gear(owner(t)+"/"+suffix,"test:rifle",Map.of("perk","kill_clip")));
                t.owner.getInventory().setItem(0,item);equipment.swap(t.owner,suffix.equals("a")?"test:primary":"test:secondary",0,equipment.revision());
            }
            equipment.draw(t.owner,Optional.of("test:primary"),equipment.revision());
            t.runtime.bind(fragment(t.owner,"fragment"));IncandescentGameTest.bind(t,"suppressing_hits","test:suppressing_hits","");return t;
        } catch(Exception|Error e){t.close();throw e;}
    }
    static EffectSource apply(ProjectileGameTest.Harness t,LivingEntity caster,LivingEntity target) {
        String who=caster.getUUID().toString();var s=new EffectSource("application/"+who,D+"suppression_application",who,new BuffInstance.Origin(who,"application",who+"/b","ability"),Set.of(),Map.of("combatant_duration",new Measure(10,Unit.SECOND),"guardian_duration",new Measure(5,Unit.SECOND)));
        t.runtime.bind(s);FreezeGameTest.event(t.runtime,s,target,"apply_suppression");ShieldThrowGameTest.settled(t);return s;
    }
    static Optional<BuffInstance> status(ProjectileGameTest.Harness t,LivingEntity actor,String name) {return state(t).buffs().instances().values().stream().filter(b->b.key().holder().equals(actor.getUUID().toString())&&b.definition().id().equals(D+name)).findFirst();}
    static int magazine(ProjectileGameTest.Harness t,String suffix) {return state(t).ammunition().get(owner(t)+"/"+suffix).magazine();}
    static void spend(ProjectileGameTest.Harness t,String suffix,int rounds) {t.runtime.start(new RuleEngine.Signal("test:spend",new EffectEvent(owner(t),owner(t),new BuffInstance.Origin(owner(t),"input",owner(t)+"/"+suffix,""),Set.of(),Map.of("rounds",new Measure(rounds,Unit.ROUND)))));ShieldThrowGameTest.settled(t);}
    static double stat(ProjectileGameTest.Harness t,String name) {return t.runtime.program().attribute(state(t),owner(t),D+name+"_stat",new Measure(50,Unit.STAT_POINT),NumericQuery.Path.empty()).output().value();}

    @GameCase(environment="chorus_gametest:domineering_paid")
    public void paidPhysicalHitSuppressesAndRefillsThenActualKillCreatesCollectableBreach(GameTestHelper h) throws Exception {
        try(var t=open(h)) {
            var victim=FeedTheVoidGameTest.target(t,0,1000);
            t.runtime.start(new RuleEngine.Signal("chorus:kill",new EffectEvent(owner(t),"fixture",new BuffInstance.Origin(owner(t),"weapon",owner(t)+"/a",""),Set.of("chorus:weapon_kill"),Map.of())));
            FeedTheVoidGameTest.cast(t,false);FeedTheVoidGameTest.contacts(t,1);
            h.assertTrue(victim.isAlive()&&victim.getHealth()<1000,"physical paid skill hit");h.assertTrue(status(t,victim,"suppression").isPresent(),"real status applied after hit");
            h.assertValueEqual(magazine(t,"a"),5,"current actual container weapon refilled");h.assertValueEqual(magazine(t,"b"),1,"origin weapon stays stowed");
            near(h,stat(t,"weapons"),80,"temporary weapons bonus");near(h,stat(t,"grenade"),60,"passive grenade bonus");
            near(h,t.owner.getAttributeValue(ChorusAttributes.forwardGroundSpeed()),1.04,"native directional projection");
            near(h,t.owner.getHealth(),100,"refill did not trigger reload observer healing");h.assertTrue(status(t,t.owner,"kill_clip_window").isPresent()&&status(t,t.owner,"kill_clip").isEmpty(),"no reload credit");
            com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor.execute("domineering/"+UUID.randomUUID(),victim,h.getLevel().damageSources().playerAttack(t.owner),2000,false);h.assertTrue(victim.isDeadOrDying(),"native confirmed death after nonlethal skill hit");victim.discard();ShieldThrowGameTest.settled(t);h.assertTrue(status(t,victim,"suppression").isEmpty(),"death removes live status");h.assertValueEqual(t.pickups.size(),1,"one physical Breach");
            StarvationGameTest.collect(t,t.pickups.getFirst());near(h,t.owner.getHealth(),170,"real Starvation collection");near(h,StarvationGameTest.account(t,"class_a"),.025,"current class reward");
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:domineering_applicants")
    public void reapplicantGetsBenefitsWhileSharedSuppressionKeepsTheFirstCastersOrigin(GameTestHelper h) throws Exception {
        try(var t=open(h)) {
            var target=t.cow(4.5,40,3.5);var other=t.cow(6.5,40,3.5);t.runtime.bind(fragment(other,"other"));
            apply(t,other,target);var first=status(t,target,"suppression").orElseThrow();h.assertValueEqual(magazine(t,"a"),1,"other applicant did not refill owner");
            t.runtime.bind(fragment(t.owner,"duplicate"));apply(t,t.owner,target);
            h.assertValueEqual(status(t,target,"suppression").orElseThrow().origin(),first.origin(),"first source remains on target");
            h.assertTrue(status(t,t.owner,"domineering_active").isPresent()&&status(t,other,"domineering_active").isPresent(),"both actual applicants receive timed benefits");
            near(h,stat(t,"grenade"),60,"duplicate source not double passive");near(h,stat(t,"weapons"),80,"one shared active bonus");
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:domineering_rejected")
    public void rejectedDeadMissingAndPureStatusGrantsDoNotAwardFragmentBenefits(GameTestHelper h) throws Exception {
        try(var t=open(h)) {
            for(String reason:List.of("denied","dead","missing")) {
                var target=t.cow(4.5,40,3.5);if(reason.equals("denied"))t.deniedStatusTargets.add(target.getUUID().toString());if(reason.equals("dead"))target.setHealth(0);if(reason.equals("missing"))target.discard();apply(t,t.owner,target);
            }
            var target=t.cow(5.5,40,3.5);IncandescentGameTest.bind(t,"pure","test:domineering_inputs","");
            t.runtime.start(new RuleEngine.Signal("test:pure_suppression",new EffectEvent(owner(t),target.getUUID().toString(),new BuffInstance.Origin(owner(t),"pure","",""),Set.of(),Map.of())));ShieldThrowGameTest.settled(t);
            h.assertTrue(status(t,target,"suppression").isPresent(),"pure grant produces state only");h.assertTrue(status(t,t.owner,"domineering_active").isEmpty(),"no application benefit");h.assertValueEqual(magazine(t,"a"),1,"no invented refill");
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:domineering_time",maxTicks=310)
    public void realFourSecondCooldownAllowsAnotherRefillAndResetsTenSecondBenefit(GameTestHelper h) throws Exception {
        var t=open(h);
        try {
            var victim=t.cow(4.5,40,3.5);apply(t,t.owner,victim);spend(t,"a",5);t.runtime.unbind("fragment");t.runtime.bind(fragment(t.owner,"again"));
            RampageGameTest.at(t,60,()->{apply(t,t.owner,victim);h.assertValueEqual(magazine(t,"a"),0,"within four seconds");});
            RampageGameTest.at(t,82,()->{var e=PlayerEquipment.get(t.owner);e.draw(t.owner,Optional.of("test:secondary"),e.revision());apply(t,t.owner,victim);h.assertValueEqual(magazine(t,"b"),5,"new drawn gun after four seconds");h.assertValueEqual(magazine(t,"a"),0,"old gun untouched");});
            RampageGameTest.at(t,205,()->{near(h,t.owner.getAttributeValue(ChorusAttributes.forwardGroundSpeed()),1.04,"refreshed benefit outlives original ten seconds");});
            t.finish(285,()->{near(h,stat(t,"weapons"),50,"ten seconds since second activation expired");near(h,t.owner.getAttributeValue(ChorusAttributes.forwardGroundSpeed()),1,"directional bonus expired");near(h,stat(t,"grenade"),60,"passive Fragment remains");});
        } catch(Exception|Error e){t.close();throw e;}
    }

    @GameCase(environment="chorus_gametest:domineering_history")
    public void historicalSuppressionAndDeathPositionSurviveCorpseRemovalAndForeignCaster(GameTestHelper h) throws Exception {
        try(var t=open(h)) {
            var target=FeedTheVoidGameTest.target(t,0,1);var caster=t.cow(6.5,40,3.5);apply(t,caster,target);var point=target.position();
            t.afterDamageReceipt=(d,r)->{if(d.target().equals(target.getUUID().toString())&&r.lethal())target.discard();};
            FeedTheVoidGameTest.damage(t,"weapon",target,1000);h.assertTrue(target.isRemoved(),"corpse removed before reactions");h.assertValueEqual(t.pickups.size(),1,"historical suppressed kill creates Breach");near(h,t.pickups.getFirst().position().distanceTo(point),0,"historical position");
            h.assertTrue(status(t,t.owner,"domineering_cooldown").isEmpty(),"kill did not start suppression bonus cooldown");
            t.runtime.unbind("fragment");StarvationGameTest.collect(t,t.pickups.getFirst());near(h,t.owner.getHealth(),170,"accepted pickup survives generator removal");
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:domineering_live")
    public void currentFragmentAtImpactControlsActivationAndEmptyHandsStillGetTimedBenefits(GameTestHelper h) throws Exception {
        for(boolean active:List.of(false,true))try(var t=open(h)) {
            var target=FeedTheVoidGameTest.target(t,0,1000);FeedTheVoidGameTest.cast(t,false);t.runtime.unbind("fragment");
            if(active){t.runtime.bind(fragment(t.owner,"late"));var e=PlayerEquipment.get(t.owner);e.draw(t.owner,Optional.empty(),e.revision());}
            FeedTheVoidGameTest.contacts(t,1);h.assertTrue(status(t,target,"suppression").isPresent(),"physical application independent of Fragment");
            h.assertValueEqual(status(t,t.owner,"domineering_active").isPresent(),active,"current Fragment controls benefits");h.assertValueEqual(magazine(t,"a"),1,"no Fragment or empty hands cannot refill stowed gun");
            if(active){t.owner.setOnGround(true);t.owner.setYRot(0);t.owner.setDeltaMovement(Vec3.ZERO);t.owner.moveRelative(1,new Vec3(0,0,1));near(h,t.owner.getDeltaMovement().z,1.04,"actual Fragment modifies native forward input");}
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:domineering_unknown")
    public void unknownResultAfterPhysicalBreachCreationKeepsPriorBenefitsAndDoesNotSpawnAgain(GameTestHelper h) throws Exception {
        try(var t=open(h)) {
            var target=FeedTheVoidGameTest.target(t,0,1);apply(t,t.owner,target);t.failAfterPickup=true;
            try{t.runtime.start(new RuleEngine.Signal("test:feed_weapon",new EffectEvent(owner(t),target.getUUID().toString(),new BuffInstance.Origin(owner(t),"input","weapon",""),Set.of(),Map.of("amount",new Measure(1000,Unit.DAMAGE)))));}catch(IllegalStateException expected){}
            h.assertTrue(t.runtime.failure().isPresent()&&target.isDeadOrDying(),"confirmed death followed by unknown spawn outcome");h.assertValueEqual(t.pickups.size(),1,"physical write retained");h.assertValueEqual(magazine(t,"a"),5,"earlier refill retained");
            for(int i=0;i<3;i++)t.runtime.prepare();h.assertValueEqual(t.pickups.size(),1,"no replay");
        } h.succeed();
    }
}
