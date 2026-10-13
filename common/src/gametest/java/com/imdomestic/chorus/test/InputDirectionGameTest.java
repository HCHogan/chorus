package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.motion.Impulse;
import com.imdomestic.chorus.effect.target.InputDirection;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;

/** Reads the real held-input state after vanilla packet handling and native movement restrictions. */
public class InputDirectionGameTest {
    static ProjectileGameTest.Harness open(GameTestHelper h) throws Exception {
        var t = new ProjectileGameTest.Harness(h, "input_direction", _ -> {}, true);
        try { t.owner.setGameMode(GameType.SURVIVAL); t.owner.setYRot(0); t.owner.setXRot(-90);
            t.runtime.abilities(new AbilityChange(id(t), AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:class", "test:input_dodge")))); return t;
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    static String id(ProjectileGameTest.Harness t) { return t.owner.getUUID().toString(); }
    static Input input(boolean f, boolean b, boolean l, boolean r) { return new Input(f, b, l, r, false, false, false); }
    static void send(ProjectileGameTest.Harness t, Input input) { t.owner.connection.handlePlayerInput(new ServerboundPlayerInputPacket(input)); }
    static InputDirection.Result query(ProjectileGameTest.Harness t, String target) { return (InputDirection.Result) t.world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), new InputDirection.Query(target))); }
    static void cast(ProjectileGameTest.Harness t) { t.h.assertValueEqual(t.runtime.useAbility(t.owner, "test:class").outcome(), AbilityUse.Outcome.ACCEPTED, "input action rejected"); }
    @GameCase public void realHeldInputsUseYawWithoutPitchVelocityOrImplicitForwardFallback(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            t.owner.setDeltaMovement(-2, 3, 4); send(t, input(true, false, false, true)); var d = query(t, id(t)).direction().orElseThrow();
            near(h, d.x(), -Math.sqrt(.5), "right plus forward x"); near(h, d.z(), Math.sqrt(.5), "right plus forward z"); near(h, d.y(), 0, "vertical pitch affected movement intent");
            t.owner.setYRot(90); send(t, input(false, true, false, false)); d = query(t, id(t)).direction().orElseThrow(); near(h, d.x(), 1, "backward relative to yaw"); near(h, d.z(), 0, "yaw-rotated backward");
            send(t, input(true, true, true, true)); var neutral = query(t, id(t)); h.assertTrue(neutral.observed() && neutral.neutral(), "opposing keys invented facing or velocity direction");
            send(t, Input.EMPTY); h.assertTrue(query(t, id(t)).neutral(), "idle input was not observed neutral");
            var cow = t.cow(4, 40, 3.5); h.assertValueEqual(query(t, cow.getUUID().toString()).outcome(), InputDirection.Outcome.UNSUPPORTED, "mob velocity treated as player input");
            h.assertValueEqual(query(t, UUID.randomUUID().toString()).outcome(), InputDirection.Outcome.MISSING_TARGET, "missing target treated as neutral");
            var far = new net.minecraft.core.BlockPos(19_000_000, 70, 19_000_000);
            while (h.getLevel().hasChunkAt(far)) far = far.offset(32, 0, 0);
            // NeoForge setPosRaw loads the destination of registered entities. A retained, unregistered
            // player handle exercises the adapter's unavailable-world guard without loading that world.
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "unloaded-input"), false);
            var unregistered = new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            try {
                unregistered.setPos(far.getX() + .5, far.getY(), far.getZ() + .5);
                h.assertTrue(!h.getLevel().hasChunkAt(unregistered.blockPosition()), "fixture target must be in an actually unloaded chunk");
                var adapter = new MinecraftWorldActions(h.getLevel(), ref -> ref.equals(unregistered.getUUID().toString()) ? unregistered : null,
                        _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
                var result = (InputDirection.Result) adapter.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(100, 0, 0), new InputDirection.Query(unregistered.getUUID().toString())));
                h.assertValueEqual(result.outcome(), InputDirection.Outcome.UNLOADED, "unloaded target manufactured input");
                h.assertTrue(!h.getLevel().hasChunkAt(unregistered.blockPosition()), "read-only input query loaded a chunk");
            } finally { unregistered.discard(); }
        } h.succeed();
    }
    @GameCase(environment="chorus_gametest:input_direction_capture", maxTicks=12)
    public void acceptedSkillRetainsCapturedInputForActualDelayedImpulseAfterTurnAndUnequip(GameTestHelper h) throws Exception {
        var t = open(h);
        try {
            var impulses = new ArrayList<Impulse.Command>(); int[] queries = {0};
            t.beforeWorld = r -> { if (r.command() instanceof InputDirection.Query) queries[0]++; if (r.command() instanceof Impulse.Command c) impulses.add(c); };
            send(t, input(false, false, true, false)); cast(t); h.assertTrue(impulses.isEmpty(), "delayed motion executed immediately");
            t.owner.setYRot(180); send(t, input(true, false, false, false)); t.runtime.abilities(new AbilityChange(id(t), new AbilityLoadout(Map.of("test:class", "test:input_dodge")), AbilityLoadout.EMPTY));
            t.finish(4, () -> { h.assertValueEqual(queries[0], 1, "timer reread changed input"); h.assertValueEqual(impulses.size(), 1, "captured impulse lost or repeated");
                var axis = impulses.getFirst().direction().orElseThrow(); near(h, axis.x(), 1, "original left direction not retained"); near(h, axis.z(), 0, "turn affected captured axis");
                h.assertTrue(t.owner.getDeltaMovement().x() > 0, "actual native velocity did not change"); h.assertValueEqual(impulses.getFirst().origin().ability(), "test:input_dodge", "accepted ability attribution lost");
            });
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void movementRestrictionFiltersHeldAndNewPacketsBeforeTheReadOnlyObservation(GameTestHelper h) throws Exception {
        try (var t = open(h)) {
            send(t, input(true, false, false, false)); h.assertTrue(query(t, id(t)).direction().isPresent(), "baseline held input missing");
            t.runtime.bind(new EffectSource("block", "test:block_move", id(t), new BuffInstance.Origin(id(t), "block", "", ""), Set.of()));
            h.assertValueEqual(t.owner.getLastClientInput(), Input.EMPTY, "restriction did not clear prior held input"); send(t, input(false, false, true, false)); h.assertTrue(query(t, id(t)).neutral(), "blocked packet bypassed query");
            cast(t); h.assertTrue(t.cues.stream().anyMatch(c -> c.cue().equals("test:neutral")), "neutral was treated as unavailable or implicit forward");
            t.runtime.unbind("block"); send(t, input(false, false, true, false)); near(h, query(t, id(t)).direction().orElseThrow().x(), 1, "new input after restriction removal");
        } h.succeed();
    }
}
