package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class AbilityGameTest {
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer player; final String holder; final MinecraftEffectRuntime runtime;
        final List<HealingCommand> heals = new ArrayList<>(); final List<Double> balances = new ArrayList<>(); boolean failWorld;
        Harness(GameTestHelper h) throws Exception {
            this.h = h; player = h.makeMockServerPlayerInLevel(); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(3, 2, 3))); player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(10); holder = player.getUUID().toString();
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/abilities.json")), StandardCharsets.UTF_8)) {
                program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            }
            var world = new MinecraftWorldActions(h.getLevel(), id -> holder.equals(id) ? player : null, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                if (request.command() instanceof HealingCommand heal) { heals.add(heal); balances.add(energy()); }
                var result = world.apply(request); if (failWorld) throw new IllegalStateException("Injected unknown ability world outcome"); return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        EffectState state() { return runtime.state().engine().domain(); }
        double energy() { return state().resources().get(new ResourceState.Key(holder, "test:energy")).value(); }
        void choose(String id) { runtime.abilities(new AbilityChange(holder, state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY), new AbilityLoadout(Map.of("test:grenade", "test:" + id)))); }
        void bind(String id, String bundle) { runtime.bind(new EffectSource(id, bundle, holder, new BuffInstance.Origin(holder, id, "", ""), Set.of())); }
        int command(String command, PermissionSet permission) throws CommandSyntaxException {
            return h.getLevel().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack().withSuppressedOutput().withPermission(permission));
        }
        @Override public void close() { runtime.close(); player.discard(); }
    }
    @GameCase public void selfInputRequiresAuthorizedSelectionAndConsumesBeforeActualHealing(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            boolean denied = false; try { t.command("chorus ability choose test:grenade test:pulse", PermissionSet.NO_PERMISSIONS); } catch (CommandSyntaxException expected) { denied = true; }
            h.assertTrue(denied && t.state().abilities().isEmpty(), "unprivileged setup selected arbitrary skill");
            t.command("chorus ability choose test:grenade test:pulse", PermissionSet.ALL_PERMISSIONS);
            t.command("chorus ability use test:grenade", PermissionSet.NO_PERMISSIONS); t.command("chorus ability use test:grenade", PermissionSet.NO_PERMISSIONS);
            near(h, t.player.getHealth(), 18, "two accepted casts heal"); h.assertValueEqual(t.balances, List.of(1.0, 0.0), "cost committed before world actions");
            denied = false; try { t.command("chorus ability use test:grenade", PermissionSet.NO_PERMISSIONS); } catch (CommandSyntaxException expected) { denied = true; }
            h.assertTrue(denied && t.heals.size() == 2 && t.runtime.failure().isEmpty(), "insufficient energy ran effect or failed runtime");
            h.assertTrue(t.heals.stream().allMatch(heal -> heal.source().owner().equals(t.holder) && heal.source().ability().equals("test:pulse")), "ability credit missing");
            t.command("chorus ability clear test:grenade", PermissionSet.ALL_PERMISSIONS); t.command("chorus ability choose test:grenade test:strong", PermissionSet.ALL_PERMISSIONS);
            near(h, t.energy(), 0, "changing selection did not refill energy");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:ability_delay", maxTicks = 12)
    public void realTicksRegenerateAndDetachedCastKeepsAcceptedParametersAfterSelectionAndSourceChanges(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.choose("echo"); t.bind("boost", "test:boost"); var receipt = t.runtime.useAbility(t.player, "test:grenade");
            h.assertValueEqual(receipt.outcome(), AbilityUse.Outcome.ACCEPTED, "accepted delayed ability");
            t.choose("strong"); t.runtime.unbind("boost");
            h.runAtTickTime(4, () -> {
                try {
                    t.runtime.prepare(); near(h, t.player.getHealth(), 18, "accepted doubled parameter survives source/selection change");
                    h.assertValueEqual(t.heals.size(), 1, "detached cast ran exactly once"); h.assertValueEqual(t.heals.getFirst().source().ability(), "test:echo", "delayed ability credit");
                    near(h, t.energy(), 1 + t.runtime.nowMicros() / 1_000_000.0, "real tick resource regeneration");
                    h.assertValueEqual(t.state().abilities().get(t.holder).slots().get("test:grenade"), "test:strong", "clock retained current selection"); h.succeed();
                } finally { t.close(); }
            });
        } catch (Exception | Error failure) { t.close(); throw failure; }
    }
    @GameCase public void serverMovementFactsChooseTemporaryReplacementAndDeadPlayersCannotSpend(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.choose("pulse"); t.bind("replace", "test:replacement"); t.player.setSprinting(true);
            var receipt = t.runtime.useAbility(t.player, "test:grenade"); h.assertValueEqual(receipt.resolved(), "test:strong", "server sprint state selected replacement"); near(h, t.player.getHealth(), 18, "replacement effect");
            h.assertValueEqual(t.state().abilities().get(t.holder).slots().get("test:grenade"), "test:pulse", "base selection preserved");
            t.player.setHealth(0); double before = t.energy(); boolean rejected = false;
            try { t.runtime.useAbility(t.player, "test:grenade"); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected && t.heals.size() == 1 && t.energy() == before, "dead input paid or acted");
        }
        h.succeed();
    }
    @GameCase public void unknownWorldOutcomeRetainsPaidCostAndCannotReplayTheAcceptedCast(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.choose("pulse"); t.failWorld = true; boolean failed = false;
            try { t.runtime.useAbility(t.player, "test:grenade"); } catch (RuntimeException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown world result lost pending operation");
            near(h, t.player.getHealth(), 14, "world operation already applied"); near(h, t.energy(), 1, "accepted cost retained");
            t.failWorld = false; failed = false;
            try { t.runtime.useAbility(t.player, "test:grenade"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.heals.size() == 1, "failed runtime replayed world healing"); near(h, t.energy(), 1, "failed retry did not pay again");
        }
        h.succeed();
    }
}
