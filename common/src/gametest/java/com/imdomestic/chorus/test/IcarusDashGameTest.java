package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Displacement;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class IcarusDashGameTest {
    static final String SLOT="chorus_d2:air_move", ABILITY="chorus_d2:icarus_dash";
    static void version(com.google.gson.JsonElement value) {
        if(value.isJsonObject()){var object=value.getAsJsonObject();if(object.has("version"))object.addProperty("version","compendium-2026-10-05");object.entrySet().forEach(e->version(e.getValue()));}
        else if(value.isJsonArray())value.getAsJsonArray().forEach(IcarusDashGameTest::version);
    }
    static CompiledEffects program() { return CompiledEffects.link(List.of("icarus_dash","icarus_dash_inputs","icarus_dash_cure","cure").stream().map(n->{var data=ThreadedSpikeGameTest.json(n);version(data);return EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE,data).getOrThrow();}).toList()); }
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer player; final MinecraftEffectRuntime runtime; final MinecraftWorldActions world;
        final List<Displacement.Receipt> moves=new ArrayList<>(); final Map<BlockPos,BlockState> blocks=new HashMap<>(); boolean fail;
        Harness(GameTestHelper h) {
            this.h=h;player=NativeMeleeGameTest.player(h);player.setPos(h.absoluteVec(new Vec3(2.5,40,2.5)));player.setNoGravity(true);player.setOnGround(false);player.setYRot(0);player.setXRot(90);
            world=new MinecraftWorldActions(h.getLevel(),ref->{try{return h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e?e:null;}catch(IllegalArgumentException e){return null;}},_ -> h.getLevel().damageSources().generic(),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program(),EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{
                var result=world.apply(request);if(result instanceof Displacement.Receipt r){moves.add(r);if(fail)throw new IllegalStateException("Unknown applied dash");}return result;
            },MinecraftEffectRuntime::nativeSource);
            runtime.bind(new EffectSource("input","test:icarus_inputs",id(),new BuffInstance.Origin(id(),"input","",""),Set.of()));select("");
        }
        String id(){return player.getUUID().toString();} EffectState state(){return runtime.state().engine().domain();}
        ResourceState account(String suffix){return state().resources().get(new ResourceState.Key(id(),"chorus_d2:icarus_dash_"+suffix));}
        void select(String melee){var slots=new HashMap<String,String>();slots.put(SLOT,ABILITY);if(!melee.isEmpty())slots.put("chorus_d2:melee","test:"+melee);runtime.abilities(new AbilityChange(id(),state().abilities().getOrDefault(id(),AbilityLoadout.EMPTY),new AbilityLoadout(slots)));}
        void input(String event,double seconds){runtime.start(new RuleEngine.Signal("test:"+event,new EffectEvent(id(),id(),new BuffInstance.Origin(id(),"input","",""),Set.of(),Map.of("amount",new Measure(seconds,Unit.SECOND)))));}
        void use(AbilityUse.Outcome expected){h.assertValueEqual(runtime.useAbility(player,SLOT).outcome(),expected,"dash acceptance");}
        void healthy(){runtime.prepare();h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"dash runtime failed: "+runtime.failure());}
        void wall(int z){for(int x=1;x<=4;x++)for(int y=40;y<=42;y++){var pos=h.absolutePos(new BlockPos(x,y,z));blocks.putIfAbsent(pos,h.getLevel().getBlockState(pos));h.getLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());}}
        void at(int tick,Runnable body){h.runAfterDelay(tick,()->{try{healthy();body.run();}catch(RuntimeException|Error e){close();throw e;}});}
        void finish(int tick,Runnable body){at(tick,()->{body.run();close();h.succeed();});}
        @Override public void close(){runtime.close();player.discard();blocks.forEach((p,s)->h.getLevel().setBlockAndUpdate(p,s));}
    }
    @GameCase public void groundedCastIsRejectedAndVerticalLookStillMovesHorizontallyForEightMeters(GameTestHelper h) throws Exception {
        try(var t=new Harness(h)){
            var before=t.player.position();t.player.setOnGround(true);t.use(AbilityUse.Outcome.CONDITION);near(h,t.account("uses").value(),1,"grounded cast spent energy");h.assertTrue(t.moves.isEmpty(),"grounded cast moved");
            t.player.setOnGround(false);t.player.setYRot(90);t.player.setXRot(-90);t.use(AbilityUse.Outcome.ACCEPTED);
            near(h,t.player.getX(),before.x-8,"yaw-based horizontal distance");near(h,t.player.getY(),before.y,"vertical look changed dash height");near(h,t.player.getZ(),before.z,"horizontal heading drift");
            h.assertValueEqual(t.moves.getFirst().outcome(),Displacement.Outcome.APPLIED,"actual native displacement");NativeMotionGameTest.acknowledge(t.player);t.healthy();
            var vertical=(DirectionQuery.Result)t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99,0,0),new DirectionQuery(t.id())));
            h.assertTrue(vertical.direction().orElseThrow().y()>.99,"legacy full look mode was flattened");
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:icarus_wall")
    public void daybreakDistanceUsesTenMetersAndWallCollisionKeepsThePaidCharge(GameTestHelper h) {
        try(var t=new Harness(h)){
            t.input("daybreak",20);t.wall(6);var before=t.player.position();double clearance=h.absolutePos(new BlockPos(2,40,6)).getZ()-t.player.getBoundingBox().maxZ;
            t.use(AbilityUse.Outcome.ACCEPTED);var r=t.moves.getFirst();near(h,r.command().distance(),10,"Daybreak 25 percent distance");
            h.assertTrue(r.clipped(),"dash ignored wall");near(h,t.player.getZ(),before.z+clearance,"full body collision clearance");near(h,t.player.getY(),before.y,"wall caused vertical stepping");near(h,t.account("uses").value(),0,"clipped dash refunded payment");t.healthy();
        }h.succeed();
    }
    @GameCase(environment="chorus_gametest:icarus_transition",maxTicks=105)
    public void realHeatRisesTransitionKeepsPartialProgressAndRestoresTwoPaidDashes(GameTestHelper h) {
        var t=new Harness(h);
        try{
            t.use(AbilityUse.Outcome.ACCEPTED);long started=t.runtime.nowMicros();long[] switched={0};
            t.at(40,()->{double old=t.account("progress").value();t.input("heat",20);switched[0]=t.runtime.nowMicros();near(h,t.account("progress").value(),old,"Heat Rises reset progress");near(h,t.account("uses").capacity(),2,"Heat Rises capacity");near(h,t.account("uses").value(),0,"capacity expansion granted a free use");});
            t.at(70,()->{double expected=(switched[0]-started)/4_000_000.0+(t.runtime.nowMicros()-switched[0])/5_000_000.0;near(h,t.account("progress").value(),expected,"mixed 4s/5s rate segments");t.use(AbilityUse.Outcome.INSUFFICIENT_ENERGY);});
            t.finish(94,()->{near(h,t.account("uses").value(),2,"linked completion did not restore both uses");near(h,t.account("progress").value(),0,"full uses kept charging");t.use(AbilityUse.Outcome.ACCEPTED);t.use(AbilityUse.Outcome.ACCEPTED);t.use(AbilityUse.Outcome.INSUFFICIENT_ENERGY);h.assertValueEqual(t.moves.size(),3,"restored paid dashes missing");});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase(environment="chorus_gametest:icarus_expiry",maxTicks=115)
    public void realBuffExpiryClipsCapacityBeforeTheSameBoundaryCompletesRecharge(GameTestHelper h) {
        var t=new Harness(h);
        try{
            t.input("heat",5);t.input("fill",0);t.use(AbilityUse.Outcome.ACCEPTED);t.use(AbilityUse.Outcome.ACCEPTED);
            t.at(90,()->{near(h,t.account("uses").capacity(),2,"heat ended early");near(h,t.account("uses").value(),0,"cycle ended early");});
            t.finish(102,()->{near(h,t.account("uses").capacity(),1,"expiry did not shrink capacity");near(h,t.account("uses").value(),1,"completion used expired extra capacity");near(h,t.account("progress").value(),0,"completed meter not consumed");t.use(AbilityUse.Outcome.ACCEPTED);t.use(AbilityUse.Outcome.INSUFFICIENT_ENERGY);});
        }catch(RuntimeException|Error e){t.close();throw e;}
    }
    @GameCase public void realMeleeSelectionControlsSongOfFlameEligibilityWithoutResettingProgress(GameTestHelper h) {
        try(var t=new Harness(h)){
            t.input("song",20);near(h,t.account("uses").capacity(),1,"Song alone gained second charge");t.select("snap");near(h,t.account("uses").capacity(),2,"selected Snap missing extra charge");t.input("fill",0);near(h,t.account("uses").value(),2,"linked charges not ready");
            t.select("celestial");near(h,t.account("uses").capacity(),1,"Celestial Fire retained extra charge");near(h,t.account("uses").value(),1,"capacity shrink retained second charge");t.input("heat",20);near(h,t.account("uses").capacity(),2,"Heat Rises should qualify independently");t.healthy();
        }h.succeed();
    }
    @GameCase public void unknownNativeDashResultRetainsActualMovementAndPaymentWithoutReplay(GameTestHelper h) {
        try(var t=new Harness(h)){
            var before=t.player.position();t.fail=true;try{t.use(AbilityUse.Outcome.ACCEPTED);}catch(IllegalStateException expected){/* paid state and actual movement precede the failed observation */}
            h.assertTrue(t.runtime.failure().isPresent(),"unknown dash did not stop runtime");near(h,t.player.getZ(),before.z+8,"native move rolled back");near(h,t.account("uses").value(),0,"payment rolled back");
            t.runtime.prepare();h.assertValueEqual(t.moves.size(),1,"unknown dash replayed");near(h,t.player.getZ(),before.z+8,"movement repeated");
        }h.succeed();
    }
}
