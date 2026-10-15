package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.combat.DamageBasis;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.combat.ShieldDamage;
import com.imdomestic.chorus.platform.minecraft.DamageCapture;
import com.imdomestic.chorus.platform.minecraft.MinecraftDamageExecutor;
import com.imdomestic.chorus.platform.minecraft.MinecraftEffectRuntime;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.CalculationProfile;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

/** Entrance rejection must release native scratch state before the original hurt body can execute. */
public class DamageCaptureBoundaryGameTest {
    private static class Observer implements DamageCapture.Observer {
        int prepared,oldBegins,oldRevisions,abandoned,failed,committed;
        RuntimeException prepareFailure,descriptionFailure,abandonFailure,notificationFailure;
        final Deque<String> reservations=new ArrayDeque<>();
        final List<DamageCapture.Observed> hits=new ArrayList<>();
        final List<DamageCapture.Observed> actuals=new ArrayList<>();
        final List<Integer> actualsAtFailure=new ArrayList<>();
        final List<Throwable> errors=new ArrayList<>();
        public void prepare(){prepared++;if(prepareFailure!=null)throw prepareFailure;}
        public DamageCommand describe(LivingEntity target,DamageSource source,float amount){
            if(descriptionFailure!=null)throw descriptionFailure;
            return MinecraftEffectRuntime.nativeSource(target,source,amount);
        }
        public DamageCommand begin(String id,DamageCommand command){oldBegins++;reservations.push(id);return command;}
        public DamageCommand revise(String id,DamageCommand command){oldRevisions++;return command;}
        public Optional<CalculationProfile.Result> outgoing(DamageCommand command,double amount){return Optional.empty();}
        public Optional<CalculationProfile.Result> defense(DamageCommand command,double amount){return Optional.empty();}
        public ShieldDamage.Planned shields(DamageCommand command,double amount,DamageBasis basis){return ShieldDamage.plan(amount,List.of());}
        public Optional<List<RuleEngine.Signal>> finished(String id,DamageCommand command,DamageReceipt receipt,boolean managed){
            if(!id.equals(reservations.peek()))throw new AssertionError("Finished a different native reservation");
            reservations.pop();return Optional.empty();
        }
        public void abandoned(String id){
            abandoned++;if(id.equals(reservations.peek()))reservations.pop();
            if(abandonFailure!=null)throw abandonFailure;
        }
        public void committed(List<DamageCapture.Observed> observations){committed++;hits.addAll(observations);}
        public void actual(DamageCommand command,DamageReceipt receipt,boolean managed){actuals.add(new DamageCapture.Observed(command,receipt));}
        public void failed(Throwable error,List<DamageCapture.Observed> observations){
            failed++;errors.add(error);hits.addAll(observations);actualsAtFailure.add(actuals.size());
            if(notificationFailure!=null)throw notificationFailure;
        }
    }
    private static LivingEntity cow(GameTestHelper h,int x){
        var value=h.spawnWithNoFreeWill(EntityTypes.COW,x,40,3);value.setNoGravity(true);value.setHealth(10);return value;
    }
    private static RuntimeException rejected(Runnable action){
        try{action.run();}catch(RuntimeException expected){return expected;}
        throw new AssertionError("Expected entrance rejection");
    }
    private static long count(Observer observer,String damageId){
        return observer.hits.stream().filter(hit->hit.receipt().damageId().equals(damageId)).count();
    }

    @GameCase public void beginRejectionCannotHurtAndCleanupFailuresDoNotLeakTheNextCapture(GameTestHelper h){
        var level=h.getLevel();var first=cow(h,2);var next=cow(h,3);var source=level.damageSources().generic();
        var denial=new IllegalStateException("Denied before native hurt");
        var abandon=new IllegalStateException("Abandon notification failed");
        var notification=new IllegalStateException("Boundary notification failed");
        class Rejecting extends Observer {
            boolean reject=true;final List<LivingEntity> targets=new ArrayList<>();
            @Override public DamageCommand begin(String id,DamageCommand command,LivingEntity actualTarget,DamageSource actualSource){
                h.assertTrue(actualSource==source,"begin received the exact native DamageSource");targets.add(actualTarget);
                var prepared=super.begin(id,command);if(reject)throw denial;return prepared;
            }
        }
        var observer=new Rejecting();observer.abandonFailure=abandon;observer.notificationFailure=notification;
        DamageCapture.install(level,observer);
        try{
            var actual=rejected(()->MinecraftDamageExecutor.execute("rejected-begin",first,source,1,false));
            h.assertTrue(actual==denial,"cleanup must preserve the original authorization failure");
            h.assertTrue(List.of(actual.getSuppressed()).containsAll(List.of(abandon,notification)),"cleanup failures retained as suppressed evidence");
            h.assertValueEqual(observer.failed,1,"failed entrance reported exactly once");
            h.assertValueEqual(observer.abandoned,1,"partially created reservation abandoned once");
            h.assertTrue(observer.reservations.isEmpty()&&observer.hits.isEmpty(),"rejection publishes no fabricated hit and leaves no reservation");
            HealingGameTest.near(h,first.getHealth(),10,"rejected begin did not enter original hurt");
            observer.reject=false;observer.abandonFailure=null;observer.notificationFailure=null;
            MinecraftDamageExecutor.execute("after-rejected-begin",next,source,1,false);
            h.assertValueEqual(observer.targets,List.of(first,next),"new begin overload retains the actual target objects");
            h.assertValueEqual(observer.prepared,2,"next request owns a fresh outer native scope");
            h.assertValueEqual(observer.oldBegins,2,"new overload can delegate to the compatible old callback");
            h.assertValueEqual(observer.committed,1,"next independent hit commits normally");
            h.assertValueEqual(observer.hits.size(),1,"only the actual accepted hit is reported");
            HealingGameTest.near(h,next.getHealth(),9,"next capture executes one native hit");
            HealingGameTest.near(h,first.getHealth(),10,"failed request never replays");
        }finally{DamageCapture.remove(level,observer);first.discard();next.discard();}
        h.succeed();
    }

    @GameCase public void preparationAndDescriptionFailuresAreReportedBeforeNativeHurtAndReleaseTheBoundary(GameTestHelper h){
        for(boolean preparing:List.of(true,false)){
            var level=h.getLevel();var first=cow(h,2);var next=cow(h,3);var source=level.damageSources().generic();
            var denial=new IllegalStateException(preparing?"Preparation rejected":"Description rejected");var observer=new Observer();
            if(preparing)observer.prepareFailure=denial;else observer.descriptionFailure=denial;
            DamageCapture.install(level,observer);
            try{
                h.assertTrue(rejected(()->MinecraftDamageExecutor.execute("rejected-description",first,source,1,false))==denial,"entrance failure propagates");
                h.assertValueEqual(observer.failed,1,"pre-begin error reaches the owning observer");
                h.assertValueEqual(observer.oldBegins,0,"authorization has not started");
                h.assertTrue(observer.hits.isEmpty()&&observer.reservations.isEmpty(),"no receipt or reservation was invented");
                HealingGameTest.near(h,first.getHealth(),10,"failed preparation cannot apply native damage");
                observer.prepareFailure=null;observer.descriptionFailure=null;
                MinecraftDamageExecutor.execute("after-rejected-description",next,source,1,false);
                h.assertValueEqual(observer.prepared,2,"cleanup permits the next outer preparation");
                h.assertValueEqual(observer.oldBegins,1,"default new callback still calls the legacy begin override");
                h.assertValueEqual(observer.hits.size(),1,"later observation does not replay rejection");
                HealingGameTest.near(h,next.getHealth(),9,"later native hit works");
            }finally{DamageCapture.remove(level,observer);first.discard();next.discard();}
        }
        h.succeed();
    }

    @GameCase public void nestedBeginRejectionRetainsTheEarlierActualHitAndFlushesOnlyAtTheOuterReturn(GameTestHelper h){
        var level=h.getLevel();var outer=cow(h,2);var accepted=cow(h,3);var denied=cow(h,4);var next=cow(h,5);
        var source=level.damageSources().generic();var denial=new IllegalStateException("Nested authorization denied");
        var observer=new Observer(){
            @Override public DamageCommand begin(String id,DamageCommand command,LivingEntity actualTarget,DamageSource actualSource){
                var prepared=super.begin(id,command);if(actualTarget==denied)throw denial;return prepared;
            }
        };
        DamageCapture.install(level,observer);
        try{
            DamageCapture.hurt(outer,source,1,1,_->{
                MinecraftDamageExecutor.execute("confirmed-child",accepted,source,1,false);
                h.assertTrue(rejected(()->MinecraftDamageExecutor.execute("denied-child",denied,source,1,false))==denial,"nested denial escapes its entrance");
                h.assertValueEqual(observer.failed,0,"nested failure waits for the owning outer boundary");
                h.assertTrue(observer.hits.isEmpty(),"earlier child observations are buffered until outer return");
                return false; // A native caller catching the rejection cannot erase the boundary's failure.
            });
            h.assertValueEqual(observer.failed,1,"outer boundary reports sticky failure once");
            h.assertValueEqual(observer.actualsAtFailure,List.of(2),"every completed native result is delivered before failure flush");
            h.assertValueEqual(observer.committed,0,"caught nested failure is not a successful boundary");
            h.assertValueEqual(count(observer,"confirmed-child"),1L,"earlier confirmed hit is retained exactly once");
            h.assertValueEqual(count(observer,"denied-child"),0L,"rejected child has no damage receipt");
            h.assertTrue(observer.reservations.isEmpty(),"all successful and rejected reservations were released");
            HealingGameTest.near(h,accepted.getHealth(),9,"actual earlier damage remains");
            HealingGameTest.near(h,denied.getHealth(),10,"rejected nested hit never executes");
            MinecraftDamageExecutor.execute("after-nested-denial",next,source,1,false);
            h.assertValueEqual(observer.prepared,2,"the next hit gets another outer preparation");
            h.assertValueEqual(count(observer,"confirmed-child"),1L,"old successful facts do not replay at the next flush");
            h.assertValueEqual(count(observer,"after-nested-denial"),1L,"next successful hit is reported once");
            HealingGameTest.near(h,next.getHealth(),9,"next independent hit remains usable");
        }finally{DamageCapture.remove(level,observer);outer.discard();accepted.discard();denied.discard();next.discard();}
        h.succeed();
    }

    @GameCase public void rejectedSourceRevisionReceivesActualObjectsAndRestoresTheDelegationLayer(GameTestHelper h){
        var level=h.getLevel();var target=cow(h,2);var next=cow(h,3);
        var original=level.damageSources().generic();var revised=level.damageSources().magic();
        var denial=new IllegalStateException("Source revision rejected");var rejectedBody=new AtomicInteger();var joinedBody=new AtomicInteger();
        var observer=new Observer(){
            @Override public DamageCommand revise(String id,DamageCommand command,LivingEntity actualTarget,DamageSource actualSource){
                h.assertTrue(actualTarget==target&&actualSource==revised,"revision receives exact target and replacement DamageSource");
                super.revise(id,command);throw denial;
            }
        };
        DamageCapture.install(level,observer);
        try{
            DamageCapture.hurt(target,original,1,3,_->{
                h.assertTrue(rejected(()->DamageCapture.hurt(target,revised,1,2,amount->{rejectedBody.incrementAndGet();return false;}))==denial,"rejected delegation does not invoke original body");
                DamageCapture.hurt(target,original,1,2,amount->{joinedBody.incrementAndGet();return false;});
                h.assertValueEqual(observer.oldBegins,1,"later lower layer still joins the original call");
                h.assertValueEqual(observer.failed,0,"outer call still owns failure delivery");return false;
            });
            h.assertValueEqual(rejectedBody.get(),0,"rejected revision blocks the delegated native body");
            h.assertValueEqual(joinedBody.get(),1,"restored layer permits ordinary delegation");
            h.assertValueEqual(observer.oldRevisions,1,"default compatibility callback runs once");
            h.assertValueEqual(observer.failed,1,"caught revision failure remains sticky");
            h.assertTrue(observer.reservations.isEmpty(),"outer reservation finishes once");
            HealingGameTest.near(h,target.getHealth(),10,"synthetic rejected delegation never writes HP");
            MinecraftDamageExecutor.execute("after-revision-denial",next,original,1,false);
            h.assertValueEqual(observer.prepared,2,"revision failure cannot leak the native stack");
            HealingGameTest.near(h,next.getHealth(),9,"next real native hit succeeds");
        }finally{DamageCapture.remove(level,observer);target.discard();next.discard();}
        h.succeed();
    }

    @GameCase public void managedPrimaryActualReceiptIsDiagnosticOnFailureAndNotPublishedAgainOnSuccess(GameTestHelper h){
        var level=h.getLevel();var success=cow(h,2);var failedTarget=cow(h,3);var next=cow(h,4);var source=level.damageSources().generic();
        var observationFailure=new IllegalStateException("Observation failed after managed HP write");
        var actualFailure=new IllegalStateException("Actual result notification also failed");
        var observer=new Observer(){
            @Override public Optional<com.imdomestic.chorus.effect.target.EntityObservation> observeEntities(
                    DamageCommand command,LivingEntity target,DamageSource actualSource){
                if(target==failedTarget)throw observationFailure;return Optional.empty();
            }
            @Override public void actual(DamageCommand command,DamageReceipt receipt,boolean managed){
                super.actual(command,receipt,managed);
                if(command.target().equals(failedTarget.getUUID().toString()))throw actualFailure;
            }
        };
        DamageCapture.install(level,observer);
        try{
            var command=MinecraftEffectRuntime.nativeSource(success,source,1);
            try(var request=DamageCapture.request("managed-success",success,source,false,command)){
                var actual=request.receipt(success.hurtServer(level,source,1));
                HealingGameTest.near(h,actual.healthLoss(),1,"managed caller receives the real successful primary receipt");
            }
            h.assertValueEqual(observer.committed,1,"successful outer boundary is notified");
            h.assertTrue(observer.hits.isEmpty(),"managed primary success is left to its action completion");
            h.assertValueEqual(observer.actuals.size(),1,"successful managed primary still reports its actual native result");
            var failing=MinecraftEffectRuntime.nativeSource(failedTarget,source,1);
            h.assertTrue(rejected(()->{
                try(var request=DamageCapture.request("managed-observation-failed",failedTarget,source,false,failing)){
                    failedTarget.hurtServer(level,source,1);
                }
            })==observationFailure,"post-hurt failure remains unknown to the parent operation");
            h.assertTrue(List.of(observationFailure.getSuppressed()).contains(actualFailure),"actual callback failure does not hide the original observation failure");
            HealingGameTest.near(h,failedTarget.getHealth(),9,"already applied managed HP loss remains real");
            h.assertValueEqual(observer.failed,1,"managed failure reaches the observer once");
            h.assertValueEqual(observer.actualsAtFailure,List.of(2),"failed notification observes all raw actual results first");
            h.assertValueEqual(count(observer,"managed-observation-failed"),1L,"failure includes the completed managed primary once");
            var observed=observer.hits.getFirst();
            HealingGameTest.near(h,observed.receipt().healthLoss(),1,"raw receipt retains the actual damage before observation failed");
            h.assertTrue(observed.receipt().observedEntities().isEmpty()&&!observed.receipt().consumptionSettled(),"missing observation or consumption is not fabricated");
            h.assertValueEqual(count(observer,"managed-success"),0L,"earlier primary success never appears in later failure diagnostics");
            h.assertTrue(observer.reservations.isEmpty(),"failed finalization abandons its reservation");
            MinecraftDamageExecutor.execute("after-managed-observation-failure",next,source,1,false);
            h.assertValueEqual(count(observer,"managed-observation-failed"),1L,"raw completed primary is not replayed on the next boundary");
            HealingGameTest.near(h,next.getHealth(),9,"next native capture still succeeds");
        }finally{DamageCapture.remove(level,observer);success.discard();failedTarget.discard();next.discard();}
        h.succeed();
    }
}
