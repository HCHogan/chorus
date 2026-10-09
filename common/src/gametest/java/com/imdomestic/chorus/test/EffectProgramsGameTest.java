package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.LevelResource;

public class EffectProgramsGameTest {
    private static final Identifier PROGRAM = Identifier.parse("chorus_gametest:loaded");
    private static CommandSourceStack source(GameTestHelper helper) { return helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel()).withSuppressedOutput(); }
    private static int command(CommandSourceStack source, String command) throws CommandSyntaxException { return source.getServer().getCommands().getDispatcher().execute(command, source); }
    private static void rejected(GameTestHelper helper, CommandSourceStack source, String text) {
        boolean failed = false;
        try { command(source, text); } catch (CommandSyntaxException expected) { failed = true; }
        helper.assertTrue(failed, "Command unexpectedly succeeded: " + text);
    }
    @GameCase public void dataPackProgramAndAdministrativeBindingsDriveNativeDamage(GameTestHelper helper) throws Exception {
        var level = helper.getLevel(); var server = level.getServer(); var source = source(helper);
        var loaded = EffectPrograms.find(server, PROGRAM).orElseThrow();
        helper.assertTrue(EffectPrograms.ids(server).contains(PROGRAM), "Reloadable registry omitted the data-pack program");
        rejected(helper, source.withPermission(PermissionSet.NO_PERMISSIONS), "chorus engine start " + PROGRAM);
        rejected(helper, source, "chorus engine start chorus_gametest:missing");
        helper.assertTrue(MinecraftEffectRuntime.installed(level).isEmpty(), "Rejected start installed a runtime");
        command(source, "chorus engine start " + PROGRAM + " pvp");
        try (var runtime = MinecraftEffectRuntime.installed(level).orElseThrow()) {
            helper.assertTrue(runtime.program() == loaded, "Runtime did not pin the loaded program");
            helper.assertValueEqual(runtime.state().engine().domain().mode(), com.imdomestic.chorus.effect.EffectState.Mode.PVP, "explicit activity mode");
            rejected(helper, source, "chorus engine start " + PROGRAM); // Must not erase the existing state.
            var actor = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); var victim = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2); actor.setHealth(2);
            String target = actor.getUUID().toString();
            command(source, "chorus engine attach " + target + " chorus_gametest:loaded passive"); near(helper, actor.getHealth(), 3, "source initialization");
            command(source, "chorus engine attach " + target + " chorus_gametest:loaded passive"); near(helper, actor.getHealth(), 3, "same binding is a no-op");
            rejected(helper, source, "chorus engine attach " + target + " chorus_gametest:missing invalid");
            helper.assertTrue(runtime.failure().isEmpty(), "Invalid bundle poisoned runtime");
            victim.hurtServer(level, level.damageSources().mobAttack(actor), 2); near(helper, actor.getHealth(), 4, "data-pack native-hit healing");
            helper.assertValueEqual(command(source, "chorus engine status"), 1, "status command");
            command(source, "chorus engine detach " + target + " passive");
            var secondVictim = helper.spawnWithNoFreeWill(EntityTypes.COW, 6, 2, 2);
            helper.assertTrue(secondVictim.hurtServer(level, level.damageSources().mobAttack(actor), 2), "Detached-source comparison hit did not apply");
            near(helper, actor.getHealth(), 4, "detached source did not trigger");
            command(source, "chorus engine attach " + target + " chorus_gametest:active active");
            near(helper, actor.getHealth(), 3, "catalogue world adapter resolved explicit damage type and healing");
            helper.assertTrue(runtime.failure().isEmpty(), "Catalogue world command failed");
            command(source, "chorus engine stop"); helper.assertTrue(MinecraftEffectRuntime.installed(level).isEmpty(), "Stop left observer installed");
        }
        helper.succeed();
    }
    @GameCase(maxTicks = 100) public void reloadOverridesNewCatalogueButPinsLiveRuntimeAndRejectsBrokenPack(GameTestHelper helper) throws Exception {
        var level = helper.getLevel(); var server = level.getServer(); var source = source(helper);
        var originalPacks = List.copyOf(server.getPackRepository().getSelectedIds());
        var original = EffectPrograms.find(server, PROGRAM).orElseThrow();
        Path directory = server.getWorldPath(LevelResource.DATAPACK_DIR); Files.createDirectories(directory);
        Path pack = Files.createTempDirectory(directory, "chorus-reload-test-");
        try {
            Files.writeString(pack.resolve("pack.mcmeta"), """
                {"pack":{"description":"Chorus reload test","min_format":121,"max_format":121}}
                """);
            Path entry = pack.resolve("data/chorus_gametest/chorus/effect_program/loaded.json"); Files.createDirectories(entry.getParent());
            com.google.gson.JsonObject replacement;
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/data/chorus_gametest/chorus/effect_program/loaded.json")), StandardCharsets.UTF_8)) {
                replacement = JsonParser.parseReader(reader).getAsJsonObject();
            }
            replacement.addProperty("version", "catalog-test-2");
            replacement.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject()
                    .getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("amount").addProperty("value", 3);
            Files.writeString(entry, replacement.toString()); server.getPackRepository().reload();
            var selected = new ArrayList<>(originalPacks); selected.add("file/" + pack.getFileName());
            command(source, "chorus engine start " + PROGRAM);
            var pinned = MinecraftEffectRuntime.installed(level).orElseThrow();
            server.reloadResources(selected).join();
            var reloaded = EffectPrograms.find(server, PROGRAM).orElseThrow();
            helper.assertTrue(reloaded != original, "Reload retained the old registry value");
            helper.assertValueEqual(reloaded.program().version(), "catalog-test-2", "higher-priority data pack override");
            helper.assertTrue(pinned.program() == original, "Reload changed a live program snapshot");
            var actor = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); actor.setHealth(1); String target = actor.getUUID().toString();
            command(source, "chorus engine attach " + target + " chorus_gametest:loaded old"); near(helper, actor.getHealth(), 2, "old runtime still uses old initialization");
            command(source, "chorus engine stop"); command(source, "chorus engine start " + PROGRAM);
            command(source, "chorus engine attach " + target + " chorus_gametest:loaded new"); near(helper, actor.getHealth(), 5, "new runtime uses override");
            var healthy = MinecraftEffectRuntime.installed(level).orElseThrow();
            replacement.addProperty("unknown_field", true); Files.writeString(entry, replacement.toString());
            boolean rejected = false;
            try { server.reloadResources(selected).join(); } catch (java.util.concurrent.CompletionException expected) { rejected = true; }
            helper.assertTrue(rejected, "Malformed program did not reject reload");
            helper.assertTrue(EffectPrograms.find(server, PROGRAM).orElseThrow() == reloaded, "Failed reload replaced the catalogue");
            helper.assertTrue(healthy.program() == reloaded && healthy.failure().isEmpty(), "Failed reload changed or failed the runtime");
            command(source, "chorus engine attach " + target + " chorus_gametest:loaded after_failure"); near(helper, actor.getHealth(), 8, "runtime remains executable after failed reload");
        } finally {
            MinecraftEffectRuntime.installed(level).ifPresent(MinecraftEffectRuntime::close);
            try { server.reloadResources(originalPacks).join(); }
            finally {
                try (var files = Files.walk(pack)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
                server.getPackRepository().reload();
            }
        }
        helper.succeed();
    }
    @GameCase public void dataPackResourceCostsSurviveDetachAndDoNotResetOnRebind(GameTestHelper helper) throws Exception {
        var source = source(helper); var actor = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); actor.setHealth(1);
        String target = actor.getUUID().toString(); var key = new ResourceState.Key(target, "chorus_gametest:energy");
        var cast = new RuleEngine.Signal("chorus_gametest:cast", new EffectEvent(target, target,
                new BuffInstance.Origin(target, "test:cast", "", ""), Set.of(), Map.of()));
        command(source, "chorus engine start " + PROGRAM);
        try (var runtime = MinecraftEffectRuntime.installed(helper.getLevel()).orElseThrow()) {
            command(source, "chorus engine attach " + target + " chorus_gametest:resources skill");
            helper.assertValueEqual(runtime.state().engine().domain().resources().get(key).value(), 1.0, "data-pack initial energy");
            runtime.start(cast); near(helper, actor.getHealth(), 2, "paid cast heals");
            helper.assertValueEqual(runtime.state().engine().domain().resources().get(key).value(), 0.0, "cast paid one charge");
            command(source, "chorus engine detach " + target + " skill");
            helper.assertTrue(runtime.state().engine().domain().resources().containsKey(key), "Detach erased entity account");
            command(source, "chorus engine attach " + target + " chorus_gametest:resources skill");
            runtime.start(cast); near(helper, actor.getHealth(), 2, "rebinding did not refill energy or allow a free cast");
            helper.assertTrue(runtime.failure().isEmpty(), "Resource cast failed runtime");
        }
        helper.succeed();
    }
}
