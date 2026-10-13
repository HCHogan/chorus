package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonObject;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Actual paid impact, LOS scan, one-second server delay, locked secondary flight and shared Solar. */
public class FireboltGameTest {
    static final String D="chorus_d2:", ABILITY=D+"firebolt";
    static void prepare(JsonObject data) {
        HeatRisesGameTest.prepare(data);
        data.getAsJsonArray("profiles").asList().removeIf(x->x.getAsJsonObject().get("id").getAsString().equals(D+"outgoing"));
        for(var p:ThreadedSpikeGameTest.json("combat_damage").getAsJsonArray("profiles"))if(p.getAsJsonObject().get("id").getAsString().equals(D+"outgoing"))data.getAsJsonArray("profiles").add(p);
        for(String name:List.of("firebolt","firebolt_energy","ability_stat_damage")) {var part=ThreadedSpikeGameTest.json(name);if(name.equals("firebolt"))AscensionGameTest.calibrate(name,part);GamblersDodgeGameTest.merge(data,part);}
        GamblersDodgeGameTest.merge(data,ThreadedSpikeGameTest.json("firebolt_test_calibration").getAsJsonObject("program"));AscensionGameTest.version(data);
    }
    static final class Scenario extends PhoenixDiveGameTest.Scenario {
        boolean closed;
        Scenario(GameTestHelper h)throws Exception {super(h,FireboltGameTest::prepare);AscensionGameTest.choose(t,D+"grenade",ABILITY);bind("test:heat_inputs");bind(D+"ability_stat_damage");}
        @Override public void close(){if(!closed){closed=true;super.close();}}
        @Override double energy(){return AscensionGameTest.energy(t,D+"grenade_energy");}
        @Override void cast(){h.assertValueEqual(t.runtime.useAbility(t.owner,D+"grenade").outcome(),AbilityUse.Outcome.ACCEPTED,"paid Firebolt");near(h,energy(),0,"one grenade payment");}
        void impact(){t.owner.setYRot(0);t.owner.setXRot(90);int before=t.projectiles.size();cast();var p=t.projectiles.get(before);for(int i=0;i<12&&!p.isRemoved();i++)p.tick();h.assertTrue(p.isRemoved(),"shell must physically hit floor");h.assertValueEqual(t.projectiles.size(),before+1,"no immediate seekers");h.assertTrue(t.hits.isEmpty()||before>0,"shell cannot deal bolt damage");}
        LivingEntity enemy(double distance){var e=t.cow(5.5+distance,40,3.5);e.setPos(h.absoluteVec(new Vec3(5.5+distance,40.6-e.getBbHeight()*.5,3.5)));e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);e.setHealth(1000);h.assertTrue(h.getLevel().isPositionEntityTicking(e.blockPosition()),"target geometry must be simulated");return e;}
        void touch(){t.runtime.bind(new EffectSource("touch","test:heat_touch",owner(),new BuffInstance.Origin(owner(),"touch","",""),Set.of(D+"touch_of_flame")));}
        void block(int x,int y,int z){var p=h.absolutePos(new BlockPos(x,y,z));t.blocks.putIfAbsent(p,h.getLevel().getBlockState(p));h.getLevel().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());}
        void finishFlights(){for(var p:List.copyOf(t.projectiles))for(int i=0;i<60&&!p.isRemoved();i++)p.tick();}
        List<DamageCommand> bolts(){return t.hits.stream().filter(c->c.tags().contains(D+"firebolt_damage")).toList();}
        void full(){t.runtime.start(new RuleEngine.Signal("test:heat_full",new EffectEvent(owner(),owner(),new BuffInstance.Origin(owner(),"input","",""),Set.of(),Map.of())));}
    }
    static void scenario(GameTestHelper h,java.util.function.Consumer<Scenario> run){ready(h,()->{var s=new Scenario(h);try{run.accept(s);}catch(RuntimeException|Error e){s.close();throw e;}});}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_scan",maxTicks=100)
    public void paidImpactScansFourVisibleEnemiesBeforeDelayedPhysicalBolts(GameTestHelper h){scenario(h,s->{
        var targets=new ArrayList<LivingEntity>();for(int d=3;d<=7;d++)targets.add(s.enemy(d));var outside=s.enemy(8.501);var ally=s.enemy(2);h.getLevel().getScoreboard().addPlayerToTeam(ally.getScoreboardName(),s.team);
        var hidden=s.enemy(4);hidden.setPos(h.absoluteVec(new Vec3(5.5,40.6-hidden.getBbHeight()*.5,7.5)));for(int y=40;y<=42;y++)s.block(5,y,5);
        s.impact();h.assertValueEqual(s.t.queries.getLast().limit().orElseThrow(),4,"four-target scan");near(h,s.t.queries.getLast().radius(),8.5,"base radius");
        s.later(19,()->{h.assertValueEqual(s.t.projectiles.size(),1,"seekers fired before one second");h.assertTrue(s.bolts().isEmpty(),"damage before physical seekers");});
        s.finish(22,()->{h.assertValueEqual(s.t.projectiles.size(),5,"shell plus four seekers");s.finishFlights();h.assertValueEqual(s.bolts().size(),4,"four independent bolt hits");for(int i=0;i<4;i++){near(h,targets.get(i).getHealth(),966.3,"actual bolt damage");h.assertValueEqual(s.buff("scorch",targets.get(i)).orElseThrow().count(),20,"shared Scorch");}for(var e:List.of(targets.get(4),outside,ally,hidden))near(h,e.getHealth(),1000,"unselected target");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_touch",maxTicks=100)
    public void castTouchAndAshesPersistAfterRemovalAndReachTheFifthTargetAtTwelveMeters(GameTestHelper h){scenario(h,s->{
        for(int d=3;d<=6;d++)s.enemy(d);var edge=s.enemy(12);var outside=s.enemy(12.001);s.touch();s.bind(D+"ember_of_ashes");s.impact();s.t.runtime.unbind("touch");s.t.runtime.unbind(s.owner()+"/"+D+"ember_of_ashes");AscensionGameTest.choose(s.t,D+"grenade",null);
        s.finish(22,()->{near(h,s.t.queries.getFirst().radius(),12,"Touch radius");h.assertValueEqual(s.t.projectiles.size(),6,"five delayed seekers");s.finishFlights();h.assertValueEqual(s.bolts().size(),5,"five actual arrivals");h.assertValueEqual(s.buff("scorch",edge).orElseThrow().count(),30,"cast Ashes retained");near(h,outside.getHealth(),1000,"outside Touch boundary");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_entity",maxTicks=100)
    public void primaryEntityCollisionOnlyScansAndTheSecondaryFlightAppliesDamageOnce(GameTestHelper h){scenario(h,s->{
        var target=s.enemy(3);target.setPos(target.getX(),s.t.owner.getEyeY()-target.getBbHeight()*.5,target.getZ());s.t.owner.setYRot(-90);s.t.owner.setXRot(0);s.cast();var shell=s.t.projectiles.getFirst();for(int i=0;i<12&&!shell.isRemoved();i++)shell.tick();
        h.assertTrue(shell.isRemoved()&&s.t.queries.size()==1&&s.t.hits.isEmpty(),"primary entity contact must scan without direct damage");near(h,target.getHealth(),1000,"no impact damage before secondary flight");
        s.finish(22,()->{h.assertValueEqual(s.t.projectiles.size(),2,"one secondary for the contacted enemy");s.finishFlights();near(h,target.getHealth(),966.3,"one physical secondary hit");h.assertValueEqual(s.bolts().size(),1,"entity contact double counted");h.assertValueEqual(s.buff("scorch",target).orElseThrow().count(),20,"secondary shared Scorch");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_move",maxTicks=100)
    public void selectedMovingLifeRemainsTheTargetAndNewCloserEnemiesCannotReplaceIt(GameTestHelper h){scenario(h,s->{
        var target=s.enemy(6);s.impact();target.setPos(target.position().add(12,0,0));var late=s.enemy(3);AscensionGameTest.choose(s.t,D+"grenade",null);
        s.finish(22,()->{h.assertValueEqual(s.t.projectiles.size(),2,"only scanned target");h.assertValueEqual(s.t.projectiles.getLast().launch().orElseThrow().parameters().destination().orElseThrow().target(),target.getUUID().toString(),"fixed chosen target");s.finishFlights();near(h,target.getHealth(),966.3,"moved target actual damage");near(h,late.getHealth(),1000,"late enemy replaced target");h.assertValueEqual(s.t.queries.size(),1,"flight must not rescan");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_delay_life",maxTicks=100)
    public void replacedDeadAndRemovedTargetsDoNotLaunchOrFillVacantSlots(GameTestHelper h){scenario(h,s->{
        var old=s.enemy(3);var dead=s.enemy(4);var removed=s.enemy(5);var valid=s.enemy(6);var extra=s.enemy(7);s.impact();var replacement=ProjectileDestinationLifeGameTest.replace(s.t,old,false);dead.setHealth(0);removed.discard();
        s.finish(22,()->{h.assertValueEqual(s.t.projectiles.size(),2,"only valid captured life fires");s.finishFlights();near(h,valid.getHealth(),966.3,"valid life damage");near(h,replacement.getHealth(),20,"new life hit by old scan");near(h,extra.getHealth(),1000,"vacancy refilled from outside snapshot");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_flight_life",maxTicks=100)
    public void replacementAfterLaunchCannotReceiveTheOldSeekersDamage(GameTestHelper h){scenario(h,s->{
        var old=s.enemy(8);s.impact();s.finish(21,()->{h.assertValueEqual(s.t.projectiles.size(),2,"secondary launched");h.assertTrue(s.bolts().isEmpty(),"replacement setup must precede arrival");var replacement=ProjectileDestinationLifeGameTest.replace(s.t,old,false);s.finishFlights();near(h,replacement.getHealth(),20,"new life inherited old seeker");h.assertTrue(s.bolts().isEmpty()&&s.buff("scorch",replacement).isEmpty(),"old flight awarded damage or status");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_cover",maxTicks=100)
    public void coverRaisedAfterTheScanStopsThePhysicalSecondaryBolt(GameTestHelper h){scenario(h,s->{
        var target=s.enemy(6);s.impact();for(int y=40;y<=43;y++)for(int z=2;z<=4;z++)s.block(7,y,z);
        s.finish(22,()->{h.assertValueEqual(s.t.projectiles.size(),2,"saved scan still launches");s.finishFlights();near(h,target.getHealth(),1000,"secondary tunneled through new cover");h.assertTrue(s.bolts().isEmpty()&&s.buff("scorch",target).isEmpty(),"block collision counted as enemy impact");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_denied",maxTicks=100)
    public void deniedScorchDoesNotUndoConfirmedBoltDamageOrGrenadePayment(GameTestHelper h){scenario(h,s->{
        var target=s.enemy(6);s.t.deniedStatusTargets.add(target.getUUID().toString());s.impact();s.finish(22,()->{s.finishFlights();near(h,target.getHealth(),966.3,"confirmed damage retained");h.assertTrue(s.buff("scorch",target).isEmpty(),"denied status granted");h.assertTrue(s.energy()<.02,"grenade refunded");});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_unknown",maxTicks=100)
    public void unknownActualBoltDamageStopsBeforeScorchAndNeverReplays(GameTestHelper h){scenario(h,s->{
        var target=s.enemy(6);s.impact();s.t.failAfterDamage=true;s.later(22,()->{try(s){s.finishFlights();h.assertTrue(s.t.runtime.failure().isPresent(),"unknown damage was acknowledged");near(h,target.getHealth(),966.3,"committed native damage retained");h.assertTrue(s.buff("scorch",target).isEmpty(),"unknown damage granted Scorch");MinecraftEffectRuntime.tick(h.getLevel());s.finishFlights();h.assertValueEqual(s.bolts().size(),1,"unknown hit replayed");h.succeed();}});
    });}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_heat",maxTicks=100)
    public void heldHeatRisesConsumesThisRealGrenadeAndNeverExecutesItsThrow(GameTestHelper h){scenario(h,s->{
        s.t.runtime.bind(new EffectSource("heat",D+"heat_rises_aspect",s.owner(),new BuffInstance.Origin(s.owner(),"heat","",""),Set.of(),Map.of("hold_time",new Measure(.3,Unit.SECOND),"receipt_seconds",new Measure(60,Unit.SECOND))));
        s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.PRESS,1);s.later(7,()->{var r=s.t.runtime.abilityInput(s.t.owner,D+"grenade",AbilityInput.Edge.RELEASE,1);h.assertValueEqual(r.cast().orElseThrow().resolved(),D+"heat_rises_consume","actual held conversion");s.finish(3,()->{h.assertTrue(s.t.projectiles.isEmpty()&&s.buff("heat_rises",s.t.owner).isPresent(),"consumption ran Firebolt throw");h.assertTrue(s.energy()<.01,"actual grenade account unpaid");h.assertTrue(s.cure().size()==2&&s.cure().stream().allMatch(c->c.amount()==6),"ordinary Firebolt consumption Cure x2");});});
    });}
    static void repeat(Scenario s,LivingEntity target,int count){s.impact();s.later(22,()->{s.finishFlights();if(count<4){s.full();repeat(s,target,count+1);}else{s.finish(1,()->{s.h.assertTrue(s.buff("scorch_lockout",target).isPresent(),"four paid casts failed shared ignition");s.h.assertTrue(s.t.hits.stream().anyMatch(c->c.tags().contains(D+"ignition_damage")&&c.source().ability().equals(ABILITY)),"ignition lost original grenade credit");});}});}
    @GameCase(structure="chorus_gametest:firebolt",environment="chorus_gametest:firebolt_ignition",maxTicks=150)
    public void repeatedPaidThrowsReachTheSharedIgnitionWithoutSuppressingLegitimateEffects(GameTestHelper h){scenario(h,s->{var target=s.enemy(6);s.bind(D+"ember_of_ashes");repeat(s,target,1);});}
}
