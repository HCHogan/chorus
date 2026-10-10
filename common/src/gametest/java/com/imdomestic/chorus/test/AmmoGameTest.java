package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ammo.AmmoState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Logical weapon accounts and dependent native actions; this fixture does not simulate gun input. */
public class AmmoGameTest {
    private static final class Harness implements AutoCloseable {
        final ServerPlayer player; final String holder; final MinecraftEffectRuntime runtime;
        final List<HealingCommand> heals = new ArrayList<>(); final List<Integer> magazines = new ArrayList<>();
        final List<String> cues = new ArrayList<>(); boolean failWorld;
        Harness(GameTestHelper h) throws Exception {
            player = h.makeMockServerPlayerInLevel(); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setPos(h.absoluteVec(new Vec3(3, 2, 3))); player.setNoGravity(true);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(10); holder = player.getUUID().toString();
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/ammunition.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var world = new MinecraftWorldActions(h.getLevel(), id -> holder.equals(id) ? player : null,
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof HealingCommand heal) { heals.add(heal); magazines.add(ammo(heal.source().weapon()).magazine()); }
                if (request.command() instanceof Action.CueCommand cue) cues.add(cue.cue());
                var result = world.apply(request);
                if (failWorld && request.command() instanceof HealingCommand) throw new IllegalStateException("Injected unknown ammunition world outcome");
                return result;
            }, MinecraftEffectRuntime::nativeSource);
            for (String weapon : List.of("a", "b")) runtime.bind(source(weapon));
        }
        EffectSource source(String weapon) { return new EffectSource(weapon, "test:ammo", holder, new BuffInstance.Origin(holder, "perk-" + weapon, weapon, ""), Set.of()); }
        AmmoState ammo(String weapon) { return runtime.state().engine().domain().ammunition().get(weapon); }
        void send(String type, String weapon, double rounds, double ceiling) {
            runtime.start(new RuleEngine.Signal(type, new EffectEvent(holder, holder, source(weapon).origin(), Set.of(),
                    Map.of("rounds", new Measure(rounds, Unit.ROUND), "ceiling", new Measure(ceiling, Unit.ROUND)))));
        }
        void initialize(String weapon) { send("test:initialize", weapon, 0, 0); }
        @Override public void close() { runtime.close(); player.discard(); }
    }
    @GameCase public void independentWeaponsCommitConservedRefillsBeforeNativeActionsWithoutFakingReload(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.initialize("a"); t.initialize("b"); t.send("test:refill", "a", 99, 12); t.send("test:generate", "b", 2, 6);
            near(h, t.player.getHealth(), 22, "actual refill and generation amounts drive native healing");
            h.assertValueEqual(t.magazines, List.of(12, 4), "ammo committed before world commands");
            h.assertValueEqual(t.ammo("a").reserve().orElseThrow().rounds(), 0, "refill consumes finite reserves");
            h.assertValueEqual(t.ammo("b").reserve().orElseThrow().rounds(), 10, "generation preserves reserves");
            t.send("test:spend", "b", 5, 0);
            h.assertValueEqual(t.heals.size(), 2, "insufficient magazine cannot execute paid action");
            h.assertValueEqual(t.cues, List.of("test:ammo_changed", "test:ammo_changed"), "ammo facts without a reload fact");
            t.send("chorus:reload_finished", "a", 0, 0); h.assertValueEqual(t.cues.getLast(), "test:reload", "only explicit reload fact triggers reload reaction");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:ammunition_delay", maxTicks = 60)
    public void detachedRefillRunsOnRealTicksAfterUnbindingAndRetainsExistingOverflow(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.initialize("a"); t.send("test:defer", "a", 99, 12); t.runtime.unbind("a");
            h.runAfterDelay(24, () -> {
                try {
                    t.runtime.prepare(); h.assertValueEqual(t.ammo("a").magazine(), 12, "detached refill after real second");
                    h.assertValueEqual(t.ammo("a").reserve().orElseThrow().rounds(), 0, "finite transfer after source unbind");
                    near(h, t.player.getHealth(), 20, "delayed actual transfer healed once");
                    t.runtime.bind(t.source("a")); t.initialize("a"); t.send("test:generate", "a", 2, 6);
                    h.assertValueEqual(t.ammo("a").magazine(), 12, "rebind and lower ceiling preserve overflow");
                    near(h, t.heals.getLast().amount(), 0, "no credit beyond ceiling");
                    h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "delayed ammunition boundary did not settle"); h.succeed();
                } finally { t.close(); }
            });
        } catch (Exception | Error failure) { t.close(); throw failure; }
    }
    @GameCase public void unknownWorldOutcomeRetainsTransferredAmmoAndCannotReplay(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.initialize("a"); t.failWorld = true; boolean failed = false;
            try { t.send("test:refill", "a", 99, 12); } catch (RuntimeException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown result lost pending operation");
            h.assertValueEqual(t.ammo("a").magazine(), 12, "committed magazine retained");
            h.assertValueEqual(t.ammo("a").reserve().orElseThrow().rounds(), 0, "committed reserve debit retained");
            near(h, t.player.getHealth(), 20, "world healing already applied"); t.failWorld = false; failed = false;
            try { t.send("test:refill", "a", 99, 12); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.heals.size() == 1 && t.cues.isEmpty(), "failed boundary replayed healing or completed queued reactions");
        }
        h.succeed();
    }
}
