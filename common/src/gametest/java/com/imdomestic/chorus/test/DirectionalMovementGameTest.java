package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.registry.ChorusAttributes;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Native travel and native attribute packets exercise both loader registrations and the common mixin. */
public class DirectionalMovementGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        return new ProjectileGameTest.Harness(h, "directional_movement", _ -> {}, true);
    }
    static void bind(ProjectileGameTest.Harness t, String name, String bundle, LivingEntity actor) {
        NativeAttributeGameTest.bind(t, name, bundle, actor);
    }
    static Vec3 accelerate(LivingEntity actor, Vec3 input) {
        actor.setDeltaMovement(Vec3.ZERO);
        actor.moveRelative(1, input);
        return actor.getDeltaMovement();
    }
    static void vector(GameTestHelper h, Vec3 actual, Vec3 expected, String label) {
        near(h, actual.x, expected.x, label + " x");
        near(h, actual.y, expected.y, label + " y");
        near(h, actual.z, expected.z, label + " z");
    }
    static AttributeInstance attribute(LivingEntity actor) { return actor.getAttribute(ChorusAttributes.forwardGroundSpeed()); }

    @GameCase(environment="chorus_gametest:directional_vectors")
    public void forwardDiagonalsUseNormalizedInputAndKeepTheirDirectionAcrossYaw(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var actor = t.owner; actor.setOnGround(true);
            var inputs = List.of(new Vec3(0,0,1), new Vec3(1,0,1), new Vec3(-1,0,1),
                    new Vec3(0,0,.25), new Vec3(1,.4,1), new Vec3(0,0,-1),
                    new Vec3(1,0,-1), new Vec3(1,0,0), new Vec3(-1,0,0), Vec3.ZERO);
            for (float yaw : new float[]{0, 90, -45, 180}) {
                actor.setYRot(yaw);
                for (var input : inputs) {
                    var before = accelerate(actor, input);
                    bind(t, "bonus", "forward", actor);
                    var after = accelerate(actor, input);
                    double factor = input.z > 0 ? 1.04 : 1;
                    vector(h, after, new Vec3(before.x * factor, before.y, before.z * factor), "normalized " + input + " yaw " + yaw);
                    t.runtime.unbind("bonus");
                }
            }
            NativeAttributeGameTest.settled(h, t);
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:directional_momentum")
    public void inputAccelerationPreservesExternalMomentumAndCannotBypassMovementDenial(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var actor = t.cow(3.5,40,3.5); actor.setOnGround(true); actor.setYRot(0);
            bind(t, "bonus", "forward", actor);
            var momentum = new Vec3(.7,-.3,.2); actor.setDeltaMovement(momentum);
            actor.moveRelative(.5f, new Vec3(0,0,1));
            vector(h, actor.getDeltaMovement(), momentum.add(0,0,.52), "only new acceleration scaled");
            actor.setDeltaMovement(momentum); actor.moveRelative(1, Vec3.ZERO);
            vector(h, actor.getDeltaMovement(), momentum, "no input cannot amplify knockback");
            bind(t, "stop", "stop", actor);
            actor.moveRelative(1, new Vec3(0,0,1));
            vector(h, actor.getDeltaMovement(), momentum, "gate denies acceleration but retains external velocity");
            t.runtime.unbind("stop");
            vector(h, accelerate(actor,new Vec3(0,0,1)), new Vec3(0,0,1.04), "independent bonus remains");
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:directional_modes")
    public void airborneFlyingRidingSpectatorAndDeadActorsDoNotGetGroundAcceleration(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            var actor = t.owner; actor.setYRot(0); bind(t, "bonus", "forward", actor);
            actor.setOnGround(false); near(h, accelerate(actor,new Vec3(0,0,1)).z,1,"airborne");
            actor.setOnGround(true); actor.getAbilities().flying=true;
            near(h, accelerate(actor,new Vec3(0,0,1)).z,1,"creative flight"); actor.getAbilities().flying=false;
            var vehicle=t.cow(3.5,40,3.5); actor.startRiding(vehicle,true,false); actor.setOnGround(true);
            h.assertTrue(actor.isPassenger(),"actual rider setup"); near(h, accelerate(actor,new Vec3(0,0,1)).z,1,"riding"); actor.stopRiding();
            actor.setGameMode(GameType.SPECTATOR); actor.setOnGround(true);
            near(h, accelerate(actor,new Vec3(0,0,1)).z,1,"spectator");
            actor.setGameMode(GameType.SURVIVAL); actor.getAbilities().flying=false; actor.setOnGround(true); t.runtime.prepare();
            actor.setSprinting(true); near(h, accelerate(actor,new Vec3(0,0,1)).z,1.04,"ground sprint");
            actor.setSprinting(false); actor.setShiftKeyDown(true); near(h, accelerate(actor,new Vec3(0,0,1)).z,1.04,"ground crouch input");
            actor.setHealth(0); near(h, accelerate(actor,new Vec3(0,0,1)).z,1,"dead");
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:directional_fluid")
    public void actualWaterAndLavaImmersionDoNotReceiveTheGroundBonus(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            for (var fluid : List.of(Blocks.WATER, Blocks.LAVA)) {
                var actor=t.cow(3.5,40,3.5); actor.setYRot(0);
                var p=h.absolutePos(new BlockPos(3,40,3)); t.blocks.putIfAbsent(p,h.getLevel().getBlockState(p));
                h.getLevel().setBlockAndUpdate(p,fluid.defaultBlockState()); actor.baseTick(); actor.setOnGround(true);
                h.assertTrue(fluid==Blocks.WATER?actor.isInWater():actor.isInLava(),"actual fluid immersion");
                bind(t,"bonus","forward",actor);
                near(h,accelerate(actor,new Vec3(0,0,1)).z,1,"fluid movement unaffected");
                t.runtime.unbind("bonus"); actor.discard(); h.getLevel().setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());
            }
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:directional_travel")
    public void nativeTravelActuallyMovesFurtherOnlyWhenWalkingForward(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            for (int x=0;x<8;x++) for(int z=0;z<8;z++) {
                var p=h.absolutePos(new BlockPos(x,39,z));t.blocks.putIfAbsent(p,h.getLevel().getBlockState(p));
                h.getLevel().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());
            }
            var actor=t.cow(3.5,40,3.5); actor.setYRot(0); actor.setSpeed(.1f);
            var start=actor.position();
            for(var input:List.of(new Vec3(0,0,1),new Vec3(1,0,1),new Vec3(1,0,0),new Vec3(0,0,-1))) {
                actor.setPos(start);actor.setOnGround(true);actor.setDeltaMovement(Vec3.ZERO);actor.travel(input);
                var baseline=actor.position().subtract(start); h.assertTrue(baseline.horizontalDistance()>0,"native travel baseline");
                bind(t,"bonus","forward",actor);
                actor.setPos(start);actor.setOnGround(true);actor.setDeltaMovement(Vec3.ZERO);actor.travel(input);
                var actual=actor.position().subtract(start); double factor=input.z>0?1.04:1;
                vector(h,actual,new Vec3(baseline.x*factor,baseline.y,baseline.z*factor),"physical travel "+input);
                t.runtime.unbind("bonus");
            }
        } h.succeed();
    }

    @GameCase(environment="chorus_gametest:directional_expiry",maxTicks=15)
    public void recipientExpiryAndSourceRemovalKeepIndependentContributions(GameTestHelper h) throws Exception {
        var t=open(h);
        try {
            var actor=t.cow(3.5,40,3.5);actor.setOnGround(true);actor.setYRot(0);
            bind(t,"bonus","forward",actor);bind(t,"inputs","inputs",t.owner);
            NativeAttributeGameTest.signal(t,"temporary",actor);
            near(h,attribute(actor).getValue(),1.14,"source plus recipient buff");
            near(h,attribute(t.owner).getValue(),1,"caster unaffected");
            t.runtime.unbind("inputs");
            h.runAfterDelay(6,()->{try(t){
                actor.setOnGround(true);near(h,accelerate(actor,new Vec3(0,0,1)).z,1.04,"input observes actual expiry");
                t.runtime.unbind("bonus");near(h,attribute(actor).getValue(),1,"last source removed");
                near(h,accelerate(actor,new Vec3(0,0,1)).z,1,"normal acceleration restored");
                NativeAttributeGameTest.settled(h,t);h.succeed();
            }});
        } catch(Exception|Error failure){t.close();throw failure;}
    }

    @GameCase(environment="chorus_gametest:directional_native_sync")
    public void nativeAttributePacketsCarryTheProjectionAndRemovalWithoutOverwritingOtherModifiers(GameTestHelper h) throws Exception {
        try(var t=open(h)) {
            var actor=t.owner;var speed=attribute(actor);
            h.assertTrue(speed!=null&&ChorusAttributes.forwardGroundSpeed().value().isClientSyncable(),"registered syncable player attribute");
            h.assertTrue(attribute(t.cow(3.5,40,3.5))!=null,"registered living mob attribute");
            var foreign=new AttributeModifier(Identifier.parse("test:foreign_forward"),.2,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            speed.addTransientModifier(foreign);bind(t,"bonus","forward",actor);
            near(h,speed.getValue(),1.2*1.04,"native contribution composition");
            near(h,speed.getBaseValue(),1,"native base unchanged");
            var nativeSpeed=actor.getAttribute(Attributes.MOVEMENT_SPEED);double base=nativeSpeed.getValue();
            bind(t,"global","global",actor);near(h,nativeSpeed.getValue(),base*1.2,"global speed remains independent");
            near(h,speed.getValue(),1.2*1.04,"global speed not fed back into directional contribution");
            packet(h,actor,speed.getValue());
            t.runtime.close();near(h,speed.getValue(),1.2,"close removes only Chorus contribution");
            h.assertTrue(speed.hasModifier(foreign.id()),"foreign modifier preserved");packet(h,actor,1.2);
        } h.succeed();
    }
    static void packet(GameTestHelper h,LivingEntity actor,double expected) {
        h.assertTrue(actor.getAttributes().getAttributesToSync().contains(attribute(actor)),"changed attribute queued for native sync");
        var packet=new ClientboundUpdateAttributesPacket(actor.getId(),actor.getAttributes().getSyncableAttributes());
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
        try {
            ClientboundUpdateAttributesPacket.STREAM_CODEC.encode(buffer,packet);
            var decoded=ClientboundUpdateAttributesPacket.STREAM_CODEC.decode(buffer);
            var snapshot=decoded.getValues().stream().filter(v->v.attribute().equals(ChorusAttributes.forwardGroundSpeed())).findFirst().orElseThrow();
            var replica=new AttributeInstance(snapshot.attribute(),_ -> {});
            replica.setBaseValue(snapshot.base());snapshot.modifiers().forEach(replica::addTransientModifier);
            near(h,replica.getValue(),expected,"native client attribute snapshot");
            h.assertValueEqual(decoded.getEntityId(),actor.getId(),"native packet entity identity");
        } finally {buffer.release();}
    }
}
