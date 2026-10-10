package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.effect.target.WorldDirection;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ImpulseGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h;final ServerPlayer owner;final EmbeddedChannel channel;final MinecraftWorldActions world;final MinecraftEffectRuntime runtime;
        final List<LivingEntity> entities=new ArrayList<>();final List<Impulse.Receipt> receipts=new ArrayList<>();final Map<BlockPos,BlockState> blocks=new HashMap<>();boolean failAfterImpulse;
        Harness(GameTestHelper h) {
            this.h=h;var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"impulse-test"),false);
            owner=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND);channel=new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,owner,cookie);owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5,3,1.5)));owner.setNoGravity(true);owner.setYRot(0);owner.setXRot(0);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);owner.setHealth(10);entities.add(owner);
            var program=EffectCodecs.COMPILED.parse(JsonOps.INSTANCE,ThreadedSpikeGameTest.json("impulse")).getOrThrow();
            world=new MinecraftWorldActions(h.getLevel(),id->entities.stream().filter(e->e.getUUID().toString().equals(id)).findFirst().orElse(null),_->h.getLevel().damageSources().generic(),(_,_) -> true,_ -> {});
            runtime=MinecraftEffectRuntime.install(h.getLevel(),program,EffectState.empty(),new EffectClock((_,_)->new EffectClock.Rate(0,List.of())),request->{
                var result=world.apply(request);if(result instanceof Impulse.Receipt receipt){receipts.add(receipt);if(failAfterImpulse)throw new IllegalStateException("Unknown native impulse outcome");}return result;
            },MinecraftEffectRuntime::nativeSource);
            runtime.abilities(new AbilityChange(id(),AbilityLoadout.EMPTY,new AbilityLoadout(Map.of("test:movement","test:dash"))));packets();
        }
        String id(){return owner.getUUID().toString();}
        BuffInstance.Origin origin(){return new BuffInstance.Origin(id(),"test-source","","");}
        void cast() throws com.mojang.brigadier.exceptions.CommandSyntaxException {h.getLevel().getServer().getCommands().getDispatcher().execute("chorus ability use test:movement",owner.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS));}
        double energy(){return runtime.state().engine().domain().resources().get(new ResourceState.Key(id(),"test:dash_energy")).value();}
        List<ClientboundSetEntityMotionPacket> packets(){var result=new ArrayList<ClientboundSetEntityMotionPacket>();Object packet;while((packet=channel.readOutbound())!=null)if(packet instanceof ClientboundSetEntityMotionPacket p)result.add(p);return result;}
        LivingEntity cow(double x,double y,double z){var cow=h.spawnWithNoFreeWill(EntityTypes.COW,new Vec3(x,y,z));cow.setNoGravity(true);entities.add(cow);return cow;}
        Impulse.Receipt apply(String target,Optional<WorldDirection> direction,double speed){return (Impulse.Receipt)world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99,0,receipts.size()),new Impulse.Command(target,direction,speed,Impulse.Scale.ONE,origin(),Set.of())));}
        Optional<WorldDirection> direction(){return Optional.of(new WorldDirection(h.getLevel().dimension().identifier().toString(),0,0,1));}
        void block(int x,int y,int z){var p=h.absolutePos(new BlockPos(x,y,z));blocks.putIfAbsent(p,h.getLevel().getBlockState(p));h.getLevel().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());}
        void settled(){h.assertTrue(runtime.failure().isEmpty()&&runtime.state().idle(),"impulse runtime failed: "+runtime.failure());}
        @Override public void close(){runtime.close();entities.forEach(Entity::discard);blocks.forEach((p,b)->h.getLevel().setBlockAndUpdate(p,b));}
    }
    @GameCase(environment="chorus_gametest:impulse_player",maxTicks=12)
    public void ordinarySkillUsesItsCapturedHeadingAndSendsTheActualVelocityPacket(GameTestHelper h)throws Exception{
        var t=new Harness(h);try{
            t.owner.setXRot(-36.869896f);var look=t.owner.getLookAngle();var captured=new WorldDirection(h.getLevel().dimension().identifier().toString(),look.x,look.y,look.z);
            t.owner.setDeltaMovement(.1,.25,.2);t.cast();near(h,t.energy(),0,"paid before movement");t.owner.setYRot(180);t.owner.setXRot(0);
            h.assertTrue(t.receipts.isEmpty(),"impulse applied before delay");
            h.runAfterDelay(4,()->{try(t){t.runtime.prepare();t.settled();var receipt=t.receipts.getFirst();
                h.assertValueEqual(receipt.outcome(),Impulse.Outcome.APPLIED,"impulse outcome");near(h,receipt.requireChange().delta().z(),captured.z()*20,"captured native pitch and horizontal projection");near(h,receipt.requireChange().delta().y(),0,"vertical motion preserved");
                near(h,t.owner.getHealth(),11+captured.z()*20,"typed receipt and applied fact reactions");
                var packets=t.packets().stream().filter(p->p.id()==t.owner.getId()).toList();h.assertValueEqual(packets.size(),1,"exactly one immediate player velocity packet");
                near(h,packets.getFirst().movement().z()*20,receipt.requireChange().after().z(),"packet matches actual velocity");
                h.assertTrue(!t.owner.syncVelocity,"immediate send left duplicate tracker velocity pending");h.succeed();
            }});
        }catch(Exception|Error failure){t.close();throw failure;}
    }
    @GameCase(environment="chorus_gametest:impulse_collision",maxTicks=18)
    public void outwardImpulseMovesAMobWithVanillaCollisionInsteadOfTeleporting(GameTestHelper h){
        var t=new Harness(h);try{
            var target=t.cow(2.5,3,2.5);for(int x=1;x<=4;x++)for(int y=1;y<=5;y++)t.block(x,y,5);
            var initial=target.position();t.runtime.bind(new EffectSource("push","test:push",t.id(),t.origin(),Set.of()));
            t.runtime.start(new RuleEngine.Signal("test:push",new EffectEvent(t.id(),target.getUUID().toString(),t.origin(),Set.of(),Map.of())));t.settled();
            h.assertValueEqual(target.position(),initial,"impulse must not teleport");near(h,target.getDeltaMovement().z(),.5,"ten meters per second is half a block per tick");
            h.assertTrue(target.syncVelocity,"mob motion was not marked for entity tracking");
            h.runAfterDelay(10,()->{try(t){t.runtime.prepare();t.settled();h.assertTrue(target.getZ()>initial.z+.25,"vanilla physical movement did not occur");
                h.assertTrue(target.getBoundingBox().maxZ<=h.absolutePos(new BlockPos(2,3,5)).getZ()+1e-5,"impulse bypassed the wall collision");h.assertValueEqual(t.receipts.size(),1,"continuous motion repeated the one-shot action");h.succeed();}});
        }catch(Exception|Error failure){t.close();throw failure;}
    }
    @GameCase(environment="chorus_gametest:impulse_eligibility")
    public void rejectedRecipientsAndAbsentAxesNeverAcquireVelocityOrGracePackets(GameTestHelper h){
        try(var t=new Harness(h)){
            var direction=t.direction();near(h,t.owner.getDeltaMovement().length(),0,"initial velocity");
            h.assertValueEqual(t.apply("missing",direction,10).outcome(),Impulse.Outcome.MISSING_TARGET,"missing entity");
            h.assertValueEqual(t.apply(t.id(),Optional.empty(),10).outcome(),Impulse.Outcome.MISSING_DIRECTION,"missing direction");
            h.assertValueEqual(t.apply(t.id(),Optional.of(new WorldDirection("minecraft:the_nether",0,0,1)),10).outcome(),Impulse.Outcome.WRONG_DIMENSION,"captured axis from another dimension");
            h.assertValueEqual(t.apply(t.id(),direction,0).outcome(),Impulse.Outcome.UNCHANGED,"zero impulse");
            t.owner.setGameMode(GameType.SPECTATOR);h.assertValueEqual(t.apply(t.id(),direction,10).outcome(),Impulse.Outcome.SPECTATOR,"spectator");t.owner.setGameMode(GameType.SURVIVAL);
            var dead=t.cow(4,3,2);dead.setHealth(0);h.assertValueEqual(t.apply(dead.getUUID().toString(),direction,10).outcome(),Impulse.Outcome.DEAD,"dead target");
            var mount=t.cow(4,3,3);t.owner.startRiding(mount);h.assertValueEqual(t.apply(t.id(),direction,10).outcome(),Impulse.Outcome.PASSENGER,"mounted recipient");t.owner.stopRiding();
            var bed=t.owner.blockPosition();t.blocks.putIfAbsent(bed,h.getLevel().getBlockState(bed));h.getLevel().setBlockAndUpdate(bed,Blocks.STRAW_BED.defaultBlockState());
            h.assertTrue(t.owner.startSleeping(bed),"real bed sleeping setup");h.assertValueEqual(t.apply(t.id(),direction,10).outcome(),Impulse.Outcome.SLEEPING,"sleeping recipient");t.owner.stopSleeping();
            var far=t.cow(4,3,4);far.setPos(1_000_000,70,1_000_000);h.assertValueEqual(t.apply(far.getUUID().toString(),direction,10).outcome(),Impulse.Outcome.UNLOADED,"unloaded recipient");
            h.assertTrue(t.packets().stream().noneMatch(p->p.id()==t.owner.getId()),"rejected impulse sent velocity packet");h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:impulse_unknown",maxTicks=12)
    public void unknownNativeImpulseKeepsVelocityAndPaymentWithoutResending(GameTestHelper h)throws Exception{
        var t=new Harness(h);try{t.failAfterImpulse=true;t.cast();h.runAfterDelay(4,()->{try(t){t.runtime.prepare();
            h.assertTrue(t.runtime.failure().isPresent(),"unknown movement outcome was not retained");near(h,t.energy(),0,"paid cost retained");
            h.assertValueEqual(t.receipts.size(),1,"single executed movement");near(h,t.owner.getHealth(),10,"later reactions did not execute");
            boolean rejected=false;try{t.runtime.useAbility(t.owner,"test:movement");}catch(IllegalStateException expected){rejected=true;}
            h.assertTrue(rejected&&t.receipts.size()==1,"failed session repeated impulse");
            h.assertValueEqual(t.packets().stream().filter(p->p.id()==t.owner.getId()).count(),1L,"velocity packet was resent");h.succeed();
        }});}catch(Exception|Error failure){t.close();throw failure;}
    }
}
