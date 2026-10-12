package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.motion.AimImpulse;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class AimImpulseGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer owner; final EmbeddedChannel channel;
        final MinecraftWorldActions world; final MinecraftEffectRuntime runtime;
        final List<LivingEntity> entities = new ArrayList<>(); final List<AimImpulse.Receipt> receipts = new ArrayList<>();
        boolean unknown;
        Harness(GameTestHelper h) {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "aim-kick-test"), false);
            owner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, cookie);
            owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            owner.setPos(h.absoluteVec(new Vec3(2.5, 40, 3.5))); owner.setNoGravity(true); owner.setYRot(0); owner.setXRot(0);
            owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); owner.setHealth(10); owner.getFoodData().setFoodLevel(0); entities.add(owner);
            var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("aim_impulse")).getOrThrow();
            world = new MinecraftWorldActions(h.getLevel(), id -> entities.stream().filter(e -> e.getUUID().toString().equals(id)).findFirst().orElse(null),
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (result instanceof AimImpulse.Receipt receipt) { receipts.add(receipt); if (unknown) throw new IllegalStateException("Unknown native aim write"); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
            runtime.abilities(new AbilityChange(id(), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:aim", "test:aim_kick")))); packets();
        }
        String id() { return owner.getUUID().toString(); }
        BuffInstance.Origin origin() { return new BuffInstance.Origin(id(), "test-source", "", ""); }
        void cast() { h.assertValueEqual(runtime.useAbility(owner, "test:aim").outcome(), AbilityUse.Outcome.ACCEPTED, "paid ability"); }
        double energy() { return runtime.state().engine().domain().resources().get(new ResourceState.Key(id(), "test:aim_energy")).value(); }
        List<ClientboundPlayerRotationPacket> packets() {
            var result = new ArrayList<ClientboundPlayerRotationPacket>(); Object packet;
            while ((packet = channel.readOutbound()) != null) if (packet instanceof ClientboundPlayerRotationPacket p) result.add(p);
            return result;
        }
        AimImpulse.Receipt apply(String target, double yaw, double pitch) {
            return (AimImpulse.Receipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), new AimImpulse.Command(target, yaw, pitch, origin(), Set.of())));
        }
        LivingEntity cow() { var cow = h.spawnWithNoFreeWill(EntityTypes.COW, new Vec3(4, 40, 4)); cow.setNoGravity(true); entities.add(cow); return cow; }
        @Override public void close() { runtime.close(); entities.forEach(Entity::discard); }
    }
    @GameCase(environment="chorus_gametest:aim_impulse_player", maxTicks=12)
    public void paidRuleSamplesResistanceThenRotatesWithoutMovingOrChangingVelocity(GameTestHelper h) {
        var t = new Harness(h);
        try {
            t.cast(); t.runtime.bind(new EffectSource("resist", "test:aim_resist", t.id(), t.origin(), Set.of()));
            near(h, t.energy(), 0, "paid before delayed kick");
            h.runAfterDelay(4, () -> { try (t) {
                t.runtime.prepare(); h.assertTrue(t.runtime.failure().isEmpty(), "aim rule failed");
                var receipt = t.receipts.getFirst(); near(h, receipt.command().pitch(), -6, "profile at execution");
                near(h, t.owner.getYRot(), 3, "native yaw"); near(h, t.owner.getXRot(), -6, "native pitch");
                near(h, t.owner.getHealth(), 17, "actual angle and completion reactions");
                h.assertValueEqual(t.owner.position(), h.absoluteVec(new Vec3(2.5, 40, 3.5)), "position unchanged");
                h.assertValueEqual(t.owner.getDeltaMovement(), Vec3.ZERO, "velocity unchanged");
                var packets = t.packets(); h.assertValueEqual(packets.size(), 1, "one rotation packet");
                h.assertTrue(packets.getFirst().relativeX() && packets.getFirst().relativeY(), "must preserve unsent mouse movement");
                near(h, packets.getFirst().xRot(), -6, "packet pitch"); near(h, packets.getFirst().yRot(), 3, "packet yaw"); h.succeed();
            }});
        } catch (Exception | Error error) { t.close(); throw error; }
    }
    @GameCase(environment="chorus_gametest:aim_impulse_limits")
    public void nativePitchLimitsAndFloatRoundingProduceActualDeltasAndNoZeroPackets(GameTestHelper h) {
        try (var t = new Harness(h)) {
            t.owner.setYRot(179); t.owner.setXRot(-88);
            var first = t.apply(t.id(), 3, -12); near(h, first.requireChange().pitch(), -2, "clamped pitch");
            near(h, first.requireChange().yaw(), 3, "yaw crosses 180 without reversing"); near(h, t.owner.getYHeadRot(), 182, "native head direction");
            h.assertValueEqual(t.packets().size(), 1, "one applied packet");
            h.assertValueEqual(t.apply(t.id(), 0, -12).outcome(), AimImpulse.Outcome.UNCHANGED, "already at limit");
            h.assertValueEqual(t.apply(t.id(), Double.MIN_VALUE, 0).outcome(), AimImpulse.Outcome.UNCHANGED, "host precision");
            h.assertTrue(t.packets().isEmpty(), "unchanged aim sent a packet");
            var mount = t.cow(); t.owner.startRiding(mount); var position = t.owner.position();
            h.assertValueEqual(t.apply(t.id(), -4, 5).outcome(), AimImpulse.Outcome.APPLIED, "mounted player may still aim");
            h.assertTrue(t.owner.getVehicle() == mount, "rotation dismounted player"); h.assertValueEqual(t.owner.position(), position, "no mounted teleport");
            h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:aim_impulse_ray")
    public void realAimChangeRedirectsFollowingHitscanAndActualDamage(GameTestHelper h) throws Exception {
        try (var t = new ProjectileGameTest.Harness(h, "hitscan", data -> {
            var body = data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonArray("do");
            var replacement = new com.google.gson.JsonArray();
            replacement.add(JsonParser.parseString("{\"type\":\"chorus:apply_aim_impulse\",\"yaw\":{\"type\":\"chorus:constant\",\"value\":-90,\"unit\":\"degree\"},\"pitch\":{\"type\":\"chorus:constant\",\"value\":0,\"unit\":\"degree\"}}"));
            body.forEach(replacement::add); data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject().add("do", replacement);
        }, true)) {
            t.owner.setXRot(0); var old = t.cow(2.5, 40.5, 9.5); var next = t.cow(8.5, 40.5, 3.5);
            t.fire(); near(h, t.owner.getYRot(), -90, "actual look redirected");
            near(h, next.getHealth(), 70, "all three rays use new aim"); near(h, old.getHealth(), 100, "previous aim target untouched");
            h.assertTrue(t.runtime.failure().isEmpty(), "ray continuation failed"); h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:aim_impulse_eligibility")
    public void missingDeadSpectatorSleepingAndUnloadedRecipientsAreRejected(GameTestHelper h) {
        try (var t = new Harness(h)) {
            h.assertValueEqual(t.apply("missing", 1, 1).outcome(), AimImpulse.Outcome.MISSING_TARGET, "missing");
            t.owner.setGameMode(GameType.SPECTATOR); h.assertValueEqual(t.apply(t.id(), 1, 1).outcome(), AimImpulse.Outcome.SPECTATOR, "spectator"); t.owner.setGameMode(GameType.SURVIVAL);
            var cow = t.cow(); cow.setHealth(0); h.assertValueEqual(t.apply(cow.getUUID().toString(), 1, 1).outcome(), AimImpulse.Outcome.DEAD, "dead");
            var pos = t.owner.blockPosition(); var old = h.getLevel().getBlockState(pos); h.getLevel().setBlockAndUpdate(pos, Blocks.STRAW_BED.defaultBlockState());
            try { h.assertTrue(t.owner.startSleeping(pos), "sleep fixture"); h.assertValueEqual(t.apply(t.id(), 1, 1).outcome(), AimImpulse.Outcome.SLEEPING, "sleeping"); }
            finally { t.owner.stopSleeping(); h.getLevel().setBlockAndUpdate(pos, old); }
            var far = t.cow(); far.setPos(1_000_000, 70, 1_000_000);
            h.assertValueEqual(t.apply(far.getUUID().toString(), 1, 1).outcome(), AimImpulse.Outcome.UNLOADED, "unloaded");
            h.assertTrue(t.packets().isEmpty(), "rejection sent aim packets"); h.succeed();
        }
    }
    @GameCase(environment="chorus_gametest:aim_impulse_unknown", maxTicks=12)
    public void unknownRotationPreservesPaymentAndNeverResendsOrRunsCompletion(GameTestHelper h) {
        var t = new Harness(h);
        try {
            t.unknown = true; t.cast();
            h.runAfterDelay(4, () -> { try (t) {
                t.runtime.prepare(); h.assertTrue(t.runtime.failure().isPresent(), "unknown result retained");
                near(h, t.energy(), 0, "paid cost retained"); near(h, t.owner.getXRot(), -12, "world rotation retained"); near(h, t.owner.getHealth(), 10, "no later reaction");
                boolean rejected = false; try { t.runtime.useAbility(t.owner, "test:aim"); } catch (IllegalStateException expected) { rejected = true; }
                h.assertTrue(rejected, "failed session resumed"); h.assertValueEqual(t.receipts.size(), 1, "single write"); h.assertValueEqual(t.packets().size(), 1, "single packet"); h.succeed();
            }});
        } catch (Exception | Error error) { t.close(); throw error; }
    }
}
