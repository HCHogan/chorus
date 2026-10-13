package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Real homing shell contacts, persistent attachment, two detached fuses and shared Solar effects. */
public class FusionGrenadeGameTest {
    static final String D="chorus_d2:", ABILITY=D+"fusion_grenade";
    static void prepare(JsonObject data) {
        FireboltGameTest.prepare(data);
        for(String name:List.of("fusion_grenade","fusion_grenade_energy")){var part=ThreadedSpikeGameTest.json(name);if(name.equals("fusion_grenade"))AscensionGameTest.calibrate(name,part);GamblersDodgeGameTest.merge(data,part);}
        GamblersDodgeGameTest.merge(data,ThreadedSpikeGameTest.json("fusion_grenade_test_calibration").getAsJsonObject("program"));AscensionGameTest.version(data);
    }
    static final class Scenario extends FireboltGameTest.Scenario {
        Scenario(GameTestHelper h)throws Exception {super(h,FusionGrenadeGameTest::prepare,ABILITY);}
        @Override void cast(){h.assertValueEqual(t.runtime.useAbility(t.owner,D+"grenade").outcome(),AbilityUse.Outcome.ACCEPTED,"paid Fusion Grenade");near(h,energy(),0,"one grenade payment");}
        @Override List<DamageCommand> blasts(){return t.hits.stream().filter(c->c.tags().contains(D+"fusion_grenade_damage")).toList();}
        EffectState state(){return t.runtime.state().engine().domain();}
        List<TargetQuery> blastQueries(){return t.queries.stream().filter(q->q.center() instanceof TargetQuery.PositionCenter).toList();}
        WorldPosition center(int index){return ((TargetQuery.PositionCenter)blastQueries().get(index).center()).position().orElseThrow();}
        EntityAttachments.Handle attachment(){h.assertValueEqual(state().attachments().size(),1,"one entity attachment");return state().attachments().values().iterator().next().handle();}
        LivingEntity attach(){var target=enemy(3);target.setPos(target.getX(),t.owner.getEyeY()-target.getBbHeight()*.5,target.getZ());t.owner.setYRot(-90);t.owner.setXRot(0);cast();var shell=t.projectiles.getLast();for(int i=0;i<12&&!shell.isRemoved();i++)shell.tick();h.assertTrue(shell.isRemoved(),"physical entity contact");attachment();h.assertTrue(blasts().isEmpty(),"contact damaged before fuse");return target;}
    }
    static void scenario(GameTestHelper h,java.util.function.Consumer<Scenario> run){ready(h,()->{var s=new Scenario(h);try{run.accept(s);}catch(RuntimeException|Error e){s.close();throw e;}});}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_surface",maxTicks=100)
    public void paidSurfaceImpactWaitsThenSelectsCurrentVisibleEnemiesWithRadialDamage(GameTestHelper h){scenario(h,s->{
        s.impact();var middle=s.enemy(3);var edge=s.enemy(6);var outside=s.enemy(6.001);var ally=s.enemy(2);h.getLevel().getScoreboard().addPlayerToTeam(ally.getScoreboardName(),s.team);var hidden=s.enemy(4);hidden.setPos(hidden.getX(),hidden.getY(),hidden.getZ()+4);for(int y=40;y<=43;y++)for(int x=6;x<=10;x++)s.block(x,y,5);
        s.later(11,()->h.assertTrue(s.blasts().isEmpty(),"fuse fired before 0.6 seconds"));s.finish(13,()->{near(h,middle.getHealth(),953.875,"radial midpoint damage");near(h,edge.getHealth(),981.55,"radial edge damage");for(var e:List.of(outside,ally,hidden))near(h,e.getHealth(),1000,"excluded recipient");h.assertValueEqual(s.blasts().size(),2,"one blast per eligible enemy");h.assertValueEqual(s.buff("scorch",edge).orElseThrow().count(),40,"shared Scorch");h.assertTrue(s.state().attachments().isEmpty(),"surface creates no attachment");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_touch",maxTicks=100)
    public void capturedTouchAshesAndUnequipRetainTheSecondBlastWithoutApplyingScorchTwice(GameTestHelper h){scenario(h,s->{
        s.touch();s.bind(D+"ember_of_ashes");s.impact();var first=s.enemy(3);var outer=s.enemy(7);s.t.runtime.unbind("touch");s.t.runtime.unbind(s.owner()+"/"+D+"ember_of_ashes");AscensionGameTest.choose(s.t,D+"grenade",null);
        s.later(13,()->{h.assertValueEqual(s.blasts().size(),1,"first six-meter blast");h.assertValueEqual(s.buff("scorch",first).orElseThrow().count(),60,"captured Ashes");near(h,outer.getHealth(),1000,"outside first radius");});s.later(21,()->h.assertValueEqual(s.blasts().size(),1,"second blast too early"));
        s.finish(23,()->{h.assertValueEqual(s.blasts().size(),3,"second eight-meter blast");h.assertValueEqual(s.buff("scorch",first).orElseThrow().count(),60,"second blast doubled Scorch");h.assertTrue(s.buff("scorch",outer).isEmpty(),"second-only recipient gained Scorch");near(h,outer.getHealth(),1000-85.1*(1-.75*7/8),"outer second damage");h.assertTrue(s.blasts().stream().allMatch(c->c.source().ability().equals(ABILITY)),"original ability credit");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_move",maxTicks=100)
    public void attachedPhysicalContactFollowsBothMovesAndEachExplosionQueriesNewNeighbors(GameTestHelper h){scenario(h,s->{
        s.touch();var target=s.attach();var contact=s.attachment().contact();target.setPos(target.position().add(8,0,0));var newcomers=new ArrayList<LivingEntity>();
        s.later(13,()->{var expected=new WorldPosition(contact.dimension(),contact.x()+8,contact.y(),contact.z());h.assertValueEqual(s.center(0),expected,"first contact translation");h.assertValueEqual(s.blasts().size(),1,"first blast attached recipient");target.setPos(target.position().add(8,0,0));var newcomer=s.enemy(20);newcomer.setPos(newcomer.getX(),contact.y()-newcomer.getBbHeight()*.5,newcomer.getZ());newcomers.add(newcomer);});
        s.finish(23,()->{var expected=new WorldPosition(contact.dimension(),contact.x()+16,contact.y(),contact.z());h.assertValueEqual(s.center(1),expected,"second contact translation");h.assertValueEqual(s.blasts().size(),3,"same life and new neighbor receive second blast");h.assertTrue(newcomers.getFirst().getHealth()<1000&&s.buff("scorch",newcomers.getFirst()).isEmpty(),"second-only recipient missing or Scorched");h.assertValueEqual(s.buff("scorch",target).orElseThrow().count(),40,"Scorch only once");h.assertTrue(s.state().attachments().isEmpty(),"attachment leaked after second fuse");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_tracking",maxTicks=100)
    public void slightPhysicalHomingTurnsTowardAnOffAxisEnemyBeforeAttaching(GameTestHelper h){scenario(h,s->{
        var target=s.enemy(5);target.setPos(target.getX(),s.t.owner.getEyeY()-target.getBbHeight()*.5,target.getZ()+.75);s.t.owner.setYRot(-90);s.t.owner.setXRot(0);s.cast();var shell=s.t.projectiles.getLast();double turned=0;
        for(int i=0;i<12&&!shell.isRemoved();i++){shell.tick();turned=Math.max(turned,shell.getDeltaMovement().z);}h.assertTrue(turned>.01,"actual flight never turned toward off-axis enemy");h.assertTrue(shell.isRemoved(),"homing shell failed to make contact");h.assertValueEqual(s.attachment().initial().reference().target(),target.getUUID().toString(),"physical tracking attached to wrong entity");
        s.finish(13,()->{h.assertValueEqual(s.blasts().size(),1,"tracked attachment did not detonate");h.assertTrue(target.getHealth()<1000&&s.buff("scorch",target).isPresent(),"tracked blast missing damage or Scorch");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_removed",maxTicks=100)
    public void confirmedRemovalFixesTheLastPositionAndCannotAttachToTheReplacementLife(GameTestHelper h){scenario(h,s->{
        s.touch();var old=s.attach();var contact=s.attachment().contact();old.setPos(old.position().add(8,0,0));var replacement=ProjectileDestinationLifeGameTest.replace(s.t,old,false);replacement.setPos(replacement.position().add(12,0,0));var near=s.enemy(11);near.setPos(near.getX(),contact.y()-near.getBbHeight()*.5,near.getZ());
        s.finish(23,()->{var expected=new WorldPosition(contact.dimension(),contact.x()+8,contact.y(),contact.z());h.assertValueEqual(s.center(0),expected,"recorded removal center");h.assertValueEqual(s.center(1),expected,"second blast moved to replacement");near(h,replacement.getHealth(),20,"replacement inherited old attachment");h.assertValueEqual(s.blasts().size(),2,"two fixed blasts hit current nearby enemy");h.assertTrue(s.blasts().stream().allMatch(c->c.target().equals(near.getUUID().toString())),"wrong fixed-blast target");h.assertTrue(s.state().attachments().isEmpty(),"retired handle leaked");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_unloaded",maxTicks=100)
    public void nativeUnloadEndsThePendingAttachmentBeforeEitherExplosion(GameTestHelper h){scenario(h,s->{
        s.touch();var target=s.attach();var neighbor=s.enemy(4);target.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);s.finish(23,()->{h.assertTrue(s.blasts().isEmpty()&&s.state().attachments().isEmpty(),"unloaded attachment exploded");near(h,neighbor.getHealth(),1000,"unload harmed neighbor");h.assertTrue(s.energy()<.02,"unload refunded payment");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_death",maxTicks=100)
    public void firstExplosionKillsAttachedLifeAndSecondStillHitsTheSurroundingWorld(GameTestHelper h){scenario(h,s->{
        s.touch();var target=s.attach();target.setHealth(1);var neighbor=s.enemy(4);neighbor.setPos(neighbor.getX(),target.getY(),neighbor.getZ());
        s.later(13,()->{h.assertTrue(target.isDeadOrDying(),"first explosion must kill actual attached entity");h.assertTrue(s.buff("scorch",target).isEmpty(),"lethal hit granted Scorch");});s.finish(23,()->{h.assertValueEqual(s.blasts().stream().filter(c->c.target().equals(neighbor.getUUID().toString())).count(),2L,"corpse attachment lost second blast");h.assertTrue(s.blasts().stream().anyMatch(c->c.target().equals(target.getUUID().toString())&&c.tags().contains(D+"fusion_first")),"actual first blast kill missing");h.assertTrue(s.state().attachments().isEmpty(),"dead attachment leaked");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_cover",maxTicks=100)
    public void newlyRaisedCoverExcludesTheRecipientFromOnlyTheSecondBlast(GameTestHelper h){scenario(h,s->{
        s.touch();s.impact();var target=s.enemy(4);s.later(13,()->{h.assertValueEqual(s.blasts().size(),1,"first visible blast");for(int y=40;y<=43;y++)for(int z=2;z<=4;z++)s.block(7,y,z);});s.finish(23,()->{h.assertValueEqual(s.blasts().size(),1,"second blast ignored current cover");h.assertValueEqual(s.buff("scorch",target).orElseThrow().count(),40,"first status lost");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_denied",maxTicks=100)
    public void deniedStatusRetainsFirstDamageAndDoesNotCancelIndependentSecondExplosion(GameTestHelper h){scenario(h,s->{
        s.touch();s.impact();var target=s.enemy(3);s.t.deniedStatusTargets.add(target.getUUID().toString());s.finish(23,()->{near(h,target.getHealth(),1000-46.125-85.1*(1-.75*3/8),"both native damage writes retained");h.assertTrue(s.buff("scorch",target).isEmpty(),"denied Scorch granted");h.assertValueEqual(s.blasts().size(),2,"denied status canceled second blast");h.assertTrue(s.energy()<.02,"grenade refunded");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_unknown",maxTicks=100)
    public void unknownAppliedBlastDamagePreservesHpLossAndStopsBeforeStatusAndReplay(GameTestHelper h){scenario(h,s->{
        s.touch();s.impact();var target=s.enemy(3);s.t.failAfterDamage=true;s.later(13,()->{try(s){h.assertTrue(s.t.runtime.failure().isPresent(),"unknown actual damage acknowledged");near(h,target.getHealth(),953.875,"committed HP loss retained");h.assertTrue(s.buff("scorch",target).isEmpty(),"unknown damage granted status");MinecraftEffectRuntime.tick(h.getLevel());h.assertValueEqual(s.blasts().size(),1,"unknown blast replayed");h.succeed();}});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_heat",maxTicks=100)
    public void heldHeatRisesConsumesTheRealFusionChargeWithoutLaunchingAShell(GameTestHelper h){scenario(h,s->{
        s.t.runtime.bind(new EffectSource("heat",D+"heat_rises_aspect",s.owner(),new BuffInstance.Origin(s.owner(),"heat","",""),Set.of(),Map.of("hold_time",new Measure(.3,Unit.SECOND),"receipt_seconds",new Measure(60,Unit.SECOND))));s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.PRESS,1);s.later(7,()->{var r=s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.RELEASE,1);h.assertValueEqual(r.cast().orElseThrow().resolved(),D+"heat_rises_consume","held conversion");s.finish(3,()->{h.assertTrue(s.t.projectiles.isEmpty()&&s.buff("heat_rises",s.t.owner).isPresent(),"consume launched grenade or missed Heat Rises");near(h,s.energy(),.15/95.8,"actual Fusion payment and regeneration");});});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:fusion_ignition",maxTicks=100)
    public void twoPaidPhysicalAshesGrenadesReachIgnitionWithoutCountingTheSecondExplosionAsScorch(GameTestHelper h){scenario(h,s->{
        s.touch();s.bind(D+"ember_of_ashes");s.impact();var target=s.enemy(3);s.later(23,()->{h.assertValueEqual(s.buff("scorch",target).orElseThrow().count(),60,"single Touch cast cannot ignite");s.full();s.impact();s.finish(23,()->{h.assertTrue(s.buff("scorch_lockout",target).isPresent()&&s.buff("scorch",target).isEmpty(),"two actual casts did not ignite");h.assertTrue(s.t.hits.stream().anyMatch(c->c.tags().contains(D+"ignition_damage")&&c.source().ability().equals(ABILITY)),"ignition lost Fusion credit");h.assertValueEqual(s.blasts().size(),4,"four independent physical detonations");});});
    });}
}
