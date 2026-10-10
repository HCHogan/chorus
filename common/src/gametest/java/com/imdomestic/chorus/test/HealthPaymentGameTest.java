package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealthPayment;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;

public class HealthPaymentGameTest {
    public static CompiledEffects program(){return EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("health_payment")).getOrThrow();}
    static final class Harness implements AutoCloseable {
        final ServerPlayer player;final EffectSource source;final MinecraftEffectRuntime runtime;final List<HealthPayment.Receipt> receipts=new ArrayList<>();final List<Action.CueCommand> cues=new ArrayList<>();boolean fault;
        Harness(GameTestHelper h){
            player=NativeMeleeGameTest.player(h);String id=player.getUUID().toString();source=new EffectSource("payment","test:health_payment",id,new BuffInstance.Origin(id,"payment","",""),Set.of());
            var world=new MinecraftWorldActions(h.getLevel(),ref->ref.equals(id)?player:null,_->h.getLevel().damageSources().generic(),(_,_) -> true,cues::add);
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program(),EffectState.empty().withSource(source),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{var result=world.apply(request);if(result instanceof HealthPayment.Receipt receipt){receipts.add(receipt);if(fault)throw new IllegalStateException("unknown payment result after health write");}return result;},MinecraftEffectRuntime::nativeSource);
        }
        void pay(String mode,String target,double amount,double minimum){runtime.start(new RuleEngine.Signal("test:"+mode,new EffectEvent(source.holder(),target,source.origin(),Set.of(),Map.of("amount",new Measure(amount,Unit.DAMAGE),"minimum",new Measure(minimum,Unit.DAMAGE)))));}
        void pay(String mode,double amount,double minimum){pay(mode,source.holder(),amount,minimum);}
        Optional<BuffInstance> buff(String id){return runtime.state().engine().domain().buffs().instances().values().stream().filter(b->b.definition().id().equals("test:"+id)).findFirst();}
        @Override public void close(){runtime.close();player.discard();}
    }
    @GameCase(environment="chorus_gametest:health_payment")
    public void nativeHealthPaymentBypassesDefensesWithoutHitsDeathsOrTotemConsumption(GameTestHelper h){
        try(var t=new Harness(h)){
            t.player.setHealth(20);t.player.getAttribute(Attributes.ARMOR).setBaseValue(30);t.player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION,600,1));t.player.setAbsorptionAmount(8);near(h,t.player.getAbsorptionAmount(),8,"initial absorption");t.player.setPermanentlyInvulnerable(true);t.player.damageCooldownTime=10;t.player.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));
            t.runtime.start(new RuleEngine.Signal("test:shield",new EffectEvent(t.source.holder(),t.source.holder(),t.source.origin(),Set.of(),Map.of())));t.pay("exact",5,1);near(h,t.player.getHealth(),15,"direct health cost");near(h,t.player.getAbsorptionAmount(),8,"absorption untouched");near(h,t.buff("shield").orElseThrow().components().numbers().get("capacity"),45,"Chorus shield untouched");h.assertValueEqual(t.player.damageCooldownTime,10,"hurt cooldown changed");
            t.pay("exact",20,1);h.assertValueEqual(t.receipts.getLast().outcome(),HealthPayment.Outcome.INSUFFICIENT,"exact affordability");near(h,t.player.getHealth(),15,"insufficient payment changed health");t.pay("up_to",100,1);near(h,t.player.getHealth(),1,"capped nonlethal floor");h.assertTrue(t.player.isAlive()&&t.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING),"payment killed or consumed totem");h.assertTrue(t.cues.isEmpty(),"payment produced damage/healing/death facts");near(h,t.buff("payment_meter").orElseThrow().components().numbers().get("effective"),19,"payment fact reports actual health");h.assertTrue(t.runtime.failure().isEmpty(),"payment runtime failed");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:health_payment_precision")
    public void floatQuantizationNeverOverchargesAndZeroOrBelowFloorPaymentsNeverHeal(GameTestHelper h){
        try(var t=new Harness(h)){
            t.player.setHealth(20);t.pay("exact",.1,1);var r=t.receipts.getLast();h.assertTrue(r.paid()&&r.effective()>0&&r.effective()<=.1,"float overcharge");near(h,r.effective(),20-t.player.getHealth(),"reported actual write");
            t.pay("exact",0,1);near(h,t.receipts.getLast().effective(),0,"zero payment");t.player.setHealth(.5f);t.pay("up_to",10,1);near(h,t.player.getHealth(),.5,"below floor healed");near(h,t.receipts.getLast().effective(),0,"below floor charged");h.assertTrue(t.cues.isEmpty()&&t.runtime.failure().isEmpty(),"unexpected payment behavior");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:health_payment_missing")
    public void deadAndMissingTargetsCannotPayOrRunSuccessBranches(GameTestHelper h){
        try(var t=new Harness(h)){
            t.pay("exact","missing",5,1);h.assertValueEqual(t.receipts.getLast().outcome(),HealthPayment.Outcome.MISSING,"missing target");t.player.setHealth(0);t.pay("up_to",5,1);h.assertValueEqual(t.receipts.getLast().outcome(),HealthPayment.Outcome.DEAD,"dead target");h.assertTrue(t.buff("paid").isEmpty()&&t.buff("payment_meter").isEmpty(),"unavailable target paid");h.assertTrue(t.runtime.failure().isEmpty(),"unavailable result failed runtime");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:health_payment_fault")
    public void unknownReceiptKeepsCommittedHealthAndNeverRepeatsTheWorldWrite(GameTestHelper h){
        try(var t=new Harness(h)){
            t.player.setHealth(20);t.fault=true;try{t.pay("exact",5,1);throw new AssertionError("unknown payment did not throw");}catch(IllegalStateException expected){h.assertTrue(expected.getMessage().contains("unknown payment"),"unexpected error");}h.assertTrue(t.runtime.failure().isPresent(),"unknown payment did not fail");near(h,t.player.getHealth(),15,"committed payment rolled back");h.assertTrue(t.buff("paid").isEmpty()&&t.buff("payment_meter").isEmpty(),"unknown payment ran continuation");t.runtime.prepare();t.runtime.prepare();h.assertValueEqual(t.receipts.size(),1,"payment replayed");near(h,t.player.getHealth(),15,"payment charged again");
        }h.succeed();
    }
}
