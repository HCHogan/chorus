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
    @GameCase public void conversionCommandsSpendTheSelectedPoolWhileCreditUsesTheResolvedAbility(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, "ability_cost_selection")) {
            t.choose("a"); t.bind("convert", "test:convert");
            t.command("chorus ability use test:grenade", PermissionSet.NO_PERMISSIONS);
            near(h, t.player.getHealth(), 14, "conversion healing"); near(h, t.energy(), 1, "selected cost");
            near(h, t.state().resources().get(new ResourceState.Key(t.holder, "test:converted_energy")).value(), 2, "conversion used its nominal pool");
            t.choose("b"); t.command("chorus ability use test:grenade", PermissionSet.NO_PERMISSIONS);
            near(h, t.player.getHealth(), 18, "second conversion healing");
            near(h, t.state().resources().get(new ResourceState.Key(t.holder, "test:other")).value(), 1.5, "second selected cost");
            h.assertTrue(t.heals.stream().allMatch(c -> c.source().ability().equals("test:converted")), "conversion lost resolved credit");
            h.assertValueEqual(t.state().abilities().get(t.holder).slots().get("test:grenade"), "test:b", "conversion rewrote base selection");
        }
        h.succeed();
    }
    @GameCase(environment="chorus_gametest:ability_selected_refund", maxTicks=15)
    public void conversionRefundAcrossRealTicksRetainsOriginalAccountAfterSelectionChanges(GameTestHelper h) throws Exception {
        var t = new Harness(h, "ability_cost_selection");
        try {
            t.choose("a"); t.bind("convert", "test:refund_override"); t.runtime.useAbility(t.player, "test:grenade");
            near(h, t.energy(), 1, "initial inherited payment"); t.choose("b"); t.runtime.unbind("convert");
            h.runAfterDelay(5, () -> { try (t) {
                t.runtime.prepare(); near(h, t.energy(), 2, "retained refund did not restore original account");
                near(h, t.state().resources().get(new ResourceState.Key(t.holder, "test:other")).value(), 2, "refund leaked into new selection");
                near(h, t.player.getHealth(), 14, "accepted conversion repeated");
                h.assertTrue(t.runtime.failure().isEmpty() && t.state().timers().isEmpty(), "refund failed or retained timer"); h.succeed();
            }});
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void conversionMissingCostAndUnknownWorldOutcomeNeverFallBackToAnotherPool(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, "ability_cost_selection")) {
            t.choose("free"); t.bind("convert", "test:missing_override"); var before = t.state();
            h.assertValueEqual(t.runtime.useAbility(t.player, "test:grenade").outcome(), AbilityUse.Outcome.NO_BASE_COST, "missing base cost should reject before missing parameter");
            h.assertValueEqual(t.state(), before, "missing cost changed state");
            t.runtime.unbind("convert"); t.choose("a"); t.bind("convert", "test:convert"); t.failWorld = true;
            boolean failed = false; try { t.runtime.useAbility(t.player, "test:grenade"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown conversion outcome did not stop runtime");
            near(h, t.player.getHealth(), 14, "actual healing rolled back"); near(h, t.energy(), 1, "inherited cost refunded");
            near(h, t.state().resources().get(new ResourceState.Key(t.holder, "test:converted_energy")).value(), 2, "fallback cost charged");
            failed = false; try { t.runtime.useAbility(t.player, "test:grenade"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.heals.size() == 1, "unknown conversion replayed");
        }
        h.succeed();
    }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer player; final String holder; final MinecraftEffectRuntime runtime;
        final List<HealingCommand> heals = new ArrayList<>(); final List<Double> balances = new ArrayList<>(); boolean failWorld;
        Harness(GameTestHelper h) throws Exception { this(h, "abilities"); }
        Harness(GameTestHelper h, String fixture) throws Exception {
            this.h = h; player = h.makeMockServerPlayerInLevel(); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(3, 2, 3))); player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(10); holder = player.getUUID().toString();
            CompiledEffects program;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + fixture + ".json")), StandardCharsets.UTF_8)) {
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
    @GameCase public void selectionCommandsOwnPassiveSourcesWhileReplacementCastsKeepTheBaseSelection(GameTestHelper h) throws Exception {
        try (var t = new Harness(h, "ability_effects")) {
            boolean denied = false; try { t.command("chorus ability choose test:grenade test:a", PermissionSet.NO_PERMISSIONS); } catch (CommandSyntaxException expected) { denied = true; }
            h.assertTrue(denied && t.state().sources().isEmpty(), "unprivileged selection attached effects");
            t.command("chorus ability choose test:grenade test:a", PermissionSet.ALL_PERMISSIONS); near(h, t.player.getHealth(), 11, "selection attach healing");
            var sources = t.state().sources(); var timers = t.state().timers(); t.choose("a");
            h.assertValueEqual(t.state().sources(), sources, "same selection replaced sources"); h.assertValueEqual(t.state().timers(), timers, "same selection reset timers");
            var passive = sources.values().iterator().next(); boolean rejected = false;
            try { t.runtime.unbind(passive.instance()); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected && t.state().sources().equals(sources), "managed source removed without selection");
            t.bind("override", "test:override"); var before = t.state().sources(); var receipt = t.runtime.useAbility(t.player, "test:grenade");
            h.assertValueEqual(receipt.resolved(), "test:replacement", "temporary replacement cast"); near(h, t.player.getHealth(), 13, "replacement actual healing");
            h.assertValueEqual(t.state().sources(), before, "replacement installed its own passives");
            t.command("chorus ability clear test:grenade", PermissionSet.ALL_PERMISSIONS); near(h, t.player.getHealth(), 14, "old passive cleanup");
            h.assertValueEqual(t.state().sources().keySet(), Set.of("override"), "clear removed unrelated source or kept passive");
            h.assertTrue(t.state().timers().isEmpty() && t.runtime.failure().isEmpty(), "clear leaked timer or failed");
        }
        try (var t = new Harness(h, "ability_effects")) {
            t.failWorld = true; boolean failed = false; try { t.choose("a"); } catch (RuntimeException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent(), "unknown attach outcome not retained"); near(h, t.player.getHealth(), 11, "attach already healed before failure");
            h.assertValueEqual(t.state().abilities().get(t.holder).slots(), Map.of("test:grenade", "test:a"), "failed reaction rolled back accepted selection");
            h.assertValueEqual(t.state().sources().size(), 1, "failed reaction lost accepted source");
            t.failWorld = false; failed = false; try { t.choose("a"); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.heals.size() == 1, "unknown attach retried");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:ability_effects", maxTicks = 16)
    public void selectedPassivesScaleRealTickRegenerationAndSwapTimersWithOldCleanup(GameTestHelper h) throws Exception {
        var t = new Harness(h, "ability_effects");
        try {
            t.choose("a"); long start = t.runtime.nowMicros();
            h.runAtTickTime(4, () -> {
                try {
                    t.runtime.prepare(); long switched = t.runtime.nowMicros(); near(h, t.energy(), 2 * (switched - start) / 1_000_000.0, "old selection regeneration");
                    t.choose("b");
                    h.runAtTickTime(9, () -> {
                        try {
                            t.runtime.prepare(); double expected = (2.0 * (switched - start) + 4.0 * (t.runtime.nowMicros() - switched)) / 1_000_000.0;
                            near(h, t.energy(), expected, "new selection regeneration after split");
                            h.assertValueEqual(t.heals.stream().map(HealingCommand::amount).toList(), List.of(1.0, 10.0, 1.0, 3.0, 30.0), "actual attach old cleanup and selected timers");
                            near(h, t.player.getHealth(), 55, "selected passive world healing");
                            t.command("chorus ability clear test:grenade", PermissionSet.ALL_PERMISSIONS); near(h, t.energy(), expected, "clear did not refill");
                            h.assertTrue(t.state().sources().isEmpty() && t.runtime.failure().isEmpty() && t.runtime.state().idle(), "passives not removed cleanly"); h.succeed();
                        } catch (Exception error) { throw new RuntimeException(error); } finally { t.close(); }
                    });
                } catch (Exception | Error error) { t.close(); throw new RuntimeException(error); }
            });
        } catch (Exception | Error error) { t.close(); throw error; }
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
