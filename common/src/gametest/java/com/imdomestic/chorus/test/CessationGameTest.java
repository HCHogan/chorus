package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;

/** Actual self-owned finisher input, shared Volatile damage, historical centers and physical Breach collection. */
public class CessationGameTest {
    static final String D="chorus_d2:";
    static EffectSource fragment(ProjectileGameTest.Harness t,String id) {String owner=t.owner.getUUID().toString();return new EffectSource(id,D+"echo_of_cessation",owner,new BuffInstance.Origin(owner,id,"",""),Set.of(),Map.of("burst_radius",new Measure(4,Unit.METER),"receipt_seconds",new Measure(12,Unit.SECOND)));}
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t=StarvationGameTest.open(h,false,"volatile","echo_of_cessation","cessation_inputs");
        try {t.owner.setYRot(0);t.owner.setXRot(0);t.runtime.bind(FinisherInputGameTest.input(t.owner));t.runtime.bind(FinisherInputGameTest.observer(t.owner));t.runtime.bind(fragment(t,"cessation"));return t;}catch(Exception|Error e){t.close();throw e;}
    }
    static LivingEntity victim(ProjectileGameTest.Harness t){var e=t.cow(2.5,40,5.5);e.addTag("test:finishable");e.addTag("chorus_d2:combatant_tier_1");e.setHealth(20);return e;}
    static LivingEntity neighbor(ProjectileGameTest.Harness t,double x,double z){var e=t.cow(x,40,z);e.addTag("chorus_d2:combatant_tier_1");return e;}
    static Optional<BuffInstance> status(ProjectileGameTest.Harness t,LivingEntity e){return t.runtime.state().engine().domain().buffs().instances().values().stream().filter(b->b.key().definition().equals(D+"volatile")&&b.key().holder().equals(e.getUUID().toString())).findFirst();}
    static void request(ProjectileGameTest.Harness t) throws Exception {TimelostMagazineGameTest.command(t,"chorus finisher");ShieldThrowGameTest.settled(t);}
    static void kill(ProjectileGameTest.Harness t,LivingEntity e){MinecraftDamageExecutor.execute("cessation/"+UUID.randomUUID(),e,t.h.getLevel().damageSources().playerAttack(t.owner),2000,false);t.h.assertTrue(e.isDeadOrDying(),"actual native death");e.discard();ShieldThrowGameTest.settled(t);}
    @GameCase(environment="chorus_gametest:cessation_chain",maxTicks=40)
    public void actualFinisherAppliesVolatileThenNativeKillExplodesFromRemovedCorpseAndBreachFeedsStarvation(GameTestHelper h)throws Exception{
        var t=open(h);try{var finished=victim(t);var near=neighbor(t,5.5,5.5);var edge=neighbor(t,6.5,5.5);var outside=neighbor(t,6.6,5.5);
            t.afterDamageReceipt=(damage,receipt)->{if(damage.target().equals(finished.getUUID().toString())&&receipt.lethal())finished.discard();};request(t);
            h.assertTrue(status(t,near).isEmpty(),"start grants no finisher completion reward");
            t.finish(10,()->{h.assertTrue(finished.isRemoved()&&status(t,near).isPresent()&&status(t,edge).isPresent()&&status(t,outside).isEmpty(),"saved center and closed radius");h.assertValueEqual(t.hits.size(),1,"no invented damage in Cessation burst");h.assertTrue(t.pickups.isEmpty(),"finisher alone does not create Breach without prior Volatile");
                var center=near.position();kill(t,near);h.assertTrue(edge.getHealth()<100,"Volatile explosion still damages neighbors after corpse removal");h.assertValueEqual(t.pickups.size(),1,"confirmed volatile kill creates one actual Breach");near(h,t.pickups.getFirst().getX(),center.x,"historical pickup center");
                t.runtime.unbind("cessation");StarvationGameTest.collect(t,t.pickups.getFirst());near(h,t.owner.getHealth(),170,"real contact activates shared Devour");near(h,StarvationGameTest.account(t,"class_a"),.025,"real contact grants current Class energy");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:cessation_targets",maxTicks=40)
    public void individualAuthorizationAndActualGuardianClassificationControlNearbyVolatile(GameTestHelper h)throws Exception{
        var t=open(h);try{victim(t);var denied=neighbor(t,5.5,5.5);t.deniedStatusTargets.add(denied.getUUID().toString());var dead=neighbor(t,5.5,6);dead.setHealth(0);
            var guardian=NativeMeleeGameTest.player(h);t.entities.add(guardian);guardian.setNoGravity(true);guardian.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(5,40,5.5)));request(t);
            t.finish(10,()->{h.assertTrue(status(t,denied).isEmpty()&&status(t,dead).isEmpty(),"denied/dead neighbors excluded");h.assertValueEqual(status(t,guardian).orElseThrow().originalDurationMicros(),6_000_000L,"actual Guardian uses six seconds even in PvE host");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:cessation_allied",maxTicks=40)
    public void teammatesAndTheFinisherOwnerAreExcludedFromTheBurst(GameTestHelper h)throws Exception{
        var team=h.getLevel().getScoreboard().addPlayerTeam("cessation-"+UUID.randomUUID());var t=open(h);
        try{victim(t);var ally=neighbor(t,5.5,5.5);var enemy=neighbor(t,5.5,6);var board=h.getLevel().getScoreboard();board.addPlayerToTeam(t.owner.getScoreboardName(),team);board.addPlayerToTeam(ally.getScoreboardName(),team);request(t);
            h.runAfterDelay(10,()->{try{ShieldThrowGameTest.settled(t);h.assertTrue(status(t,ally).isEmpty()&&status(t,t.owner).isEmpty()&&status(t,enemy).isPresent(),"actual team filtering");h.succeed();}finally{t.close();board.removePlayerTeam(team);}});
        }catch(Throwable e){t.close();h.getLevel().getScoreboard().removePlayerTeam(team);throw e;}
    }
    @GameCase(environment="chorus_gametest:cessation_cancelled",maxTicks=40)
    public void survivingTheActualFinisherStrikeDoesNotApplyVolatileOrCreateBreach(GameTestHelper h)throws Exception{
        var t=open(h);try{var target=victim(t);target.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));var next=neighbor(t,5.5,5.5);request(t);
            t.finish(10,()->{h.assertTrue(target.isAlive()&&t.receipts.getFirst().deathPrevented(),"actual prevented death");h.assertTrue(status(t,next).isEmpty()&&t.pickups.isEmpty(),"no completion-dependent effects");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:cessation_late",maxTicks=40)
    public void lateFragmentEquipmentUsesCurrentCompletionRulesAndExistingVolatileSurvivesUnbinding(GameTestHelper h)throws Exception{
        var t=open(h);try{victim(t);var next=neighbor(t,5.5,5.5);t.runtime.unbind("cessation");request(t);t.runtime.bind(fragment(t,"late"));t.runtime.bind(fragment(t,"duplicate"));
            t.finish(10,()->{h.assertTrue(status(t,next).isPresent(),"current fragment reacts to already accepted finisher");h.assertValueEqual(FinisherGameTest.count(t,"volatile_applied"),1L,"duplicate fragment does not double-apply");t.runtime.unbind("late");t.runtime.unbind("duplicate");h.assertTrue(status(t,next).isPresent(),"applied target state outlives source");kill(t,next);h.assertTrue(t.pickups.isEmpty(),"removed fragment does not observe new volatile kills");});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:cessation_foreign")
    public void volatileFromAnotherActualCasterStillQualifiesTheOwnersOrdinaryKill(GameTestHelper h)throws Exception{
        try(var t=open(h)){var caster=neighbor(t,8.5,5.5);var next=neighbor(t,5.5,5.5);String owner=caster.getUUID().toString();var s=new EffectSource("external_volatile",D+"volatile_status_application",owner,new BuffInstance.Origin(owner,"external","",""),Set.of(),Map.of("combatant_duration",new Measure(10,Unit.SECOND),"guardian_duration",new Measure(6,Unit.SECOND)));
            t.runtime.bind(s);t.runtime.start(new com.imdomestic.chorus.rule.RuleEngine.Signal(D+"apply_volatile",new EffectEvent(owner,next.getUUID().toString(),s.origin(),Set.of(),Map.of(),Map.of(),Map.of("source_instance",s.instance(),"bundle",s.bundle()))));ShieldThrowGameTest.settled(t);h.assertValueEqual(status(t,next).orElseThrow().origin(),s.origin(),"foreign first giver");kill(t,next);
            h.assertValueEqual(t.pickups.size(),1,"ordinary kill creates Breach for current fragment holder");float beforePickup=t.owner.getHealth();h.assertTrue(beforePickup>0&&beforePickup<100,"foreign Volatile explosion also damages the actual killer");StarvationGameTest.collect(t,t.pickups.getFirst());near(h,t.owner.getHealth(),beforePickup+70,"actual killer collects reward after foreign explosion damage");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:cessation_unknown_status",maxTicks=40)
    public void unknownReactionAfterAnActualStatusWriteStopsWithoutRepeatingTheFinisher(GameTestHelper h)throws Exception{
        var t=open(h);try{victim(t);var first=neighbor(t,5.5,5.5);var second=neighbor(t,5.5,6);t.onCue=cue->{if(cue.cue().equals("test:volatile_applied"))throw new IllegalStateException("Injected unknown status reaction");};request(t);
            h.runAfterDelay(10,()->{try{h.assertTrue(t.runtime.failure().isPresent()&&(status(t,first).isPresent()||status(t,second).isPresent()),"known status state remains before unknown reaction");h.assertValueEqual(t.hits.size(),1,"one actual finisher strike");int operations=t.operations.size();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"no replay after failure");h.succeed();}finally{t.close();}});
        }catch(Throwable e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:cessation_unknown_pickup",maxTicks=40)
    public void unknownReceiptAfterCreatingThePhysicalBreachCannotCreateAnotherCopy(GameTestHelper h)throws Exception{
        var t=open(h);try{victim(t);var next=neighbor(t,5.5,5.5);request(t);
            h.runAfterDelay(10,()->{try{ShieldThrowGameTest.settled(t);h.assertTrue(status(t,next).isPresent(),"actual volatile prerequisite");t.failAfterPickup=true;MinecraftDamageExecutor.execute("cessation/"+UUID.randomUUID(),next,h.getLevel().damageSources().playerAttack(t.owner),2000,false);next.discard();t.runtime.prepare();
                h.assertTrue(t.runtime.failure().isPresent()&&t.pickups.size()==1&&!t.pickups.getFirst().isRemoved(),"physical spawn happened before unknown receipt");int operations=t.operations.size();t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.operations.size(),operations,"unknown spawn never repeats");h.succeed();}finally{t.close();}});
        }catch(Throwable e){t.close();throw e;}
    }
}
