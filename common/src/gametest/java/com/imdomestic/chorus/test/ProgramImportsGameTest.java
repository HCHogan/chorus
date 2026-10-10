package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.*;
import com.imdomestic.chorus.platform.minecraft.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.LevelResource;

/** Real reload validation, atomic failure and dependency override on both loaders. */
public class ProgramImportsGameTest {
    private static final Identifier ROOT = Identifier.parse("chorus_gametest:imports/root");
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static final class Pack implements AutoCloseable {
        final GameTestHelper h; final MinecraftServer server; final Path directory; final List<String> original, selected;
        Pack(GameTestHelper h) throws Exception {
            this.h = h; server = h.getLevel().getServer(); original = List.copyOf(server.getPackRepository().getSelectedIds());
            var packs = server.getWorldPath(LevelResource.DATAPACK_DIR); Files.createDirectories(packs); directory = Files.createTempDirectory(packs, "chorus-import-test-");
            Files.writeString(directory.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Chorus imports test\",\"min_format\":121,\"max_format\":121}}");
            selected = new ArrayList<>(original); selected.add("file/" + directory.getFileName());
        }
        void put(String name, JsonObject value) throws Exception {
            var file = directory.resolve("data/chorus_gametest/chorus/effect_program/imports/" + name + ".json");
            Files.createDirectories(file.getParent()); Files.writeString(file, value.toString());
        }
        void reload() { server.getPackRepository().reload(); server.reloadResources(selected).join(); }
        void command(String text) throws Exception {
            var source = server.createCommandSourceStack().withLevel(h.getLevel()).withSuppressedOutput();
            server.getCommands().getDispatcher().execute(text, source);
        }
        @Override public void close() throws Exception {
            MinecraftEffectRuntime.installed(h.getLevel()).ifPresent(MinecraftEffectRuntime::close);
            try { server.reloadResources(original).join(); }
            finally {
                try (var paths = Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
                server.getPackRepository().reload();
            }
        }
    }
    @GameCase(maxTicks = 100) public void importedVoltshotAndJoltExecuteFromTheReloadedCatalogue(GameTestHelper h) throws Exception {
        try (var pack = new Pack(h)) {
            for (var name : List.of("jolt", "voltshot")) {
                try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/" + name + ".json")), StandardCharsets.UTF_8)) {
                    var fragment = JsonParser.parseReader(reader).getAsJsonObject(); fragment.addProperty("fragment", true);
                    if (name.equals("voltshot")) fragment.add("imports", JsonParser.parseString("[\"chorus_gametest:imports/jolt\"]"));
                    pack.put(name, fragment);
                }
            }
            // Jolt is imported directly and transitively. It must be linked once.
            pack.put("root", json("{\"version\":\"test-jolt-v1\",\"imports\":[\"chorus_gametest:imports/jolt\",\"chorus_gametest:imports/voltshot\"]}"));
            pack.reload(); var loaded = EffectPrograms.find(pack.server, ROOT).orElseThrow();
            h.assertValueEqual(loaded.program().buffs().size(), 4, "diamond import shares Jolt definitions");
            h.assertTrue(EffectPrograms.ids(pack.server).contains(ROOT), "root is startable");
            h.assertTrue(!EffectPrograms.ids(pack.server).contains(Identifier.parse("chorus_gametest:imports/jolt")), "fragment must not appear as an executable program");
            h.assertTrue(EffectPrograms.find(pack.server, Identifier.parse("chorus_gametest:imports/voltshot")).isEmpty(), "fragment lookup must not compile it independently");
            boolean rejected = false; try { pack.command("chorus engine start chorus_gametest:imports/voltshot"); } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { rejected = true; }
            h.assertTrue(rejected && MinecraftEffectRuntime.installed(h.getLevel()).isEmpty(), "fragment cannot install a runtime");
            VoltshotGameTest.verifyImportedCatalogue(h, loaded);
        } h.succeed();
    }
    @GameCase(maxTicks = 150) public void dependencyOverridePinsLiveRuntimeAndBrokenImportsRejectWholeReload(GameTestHelper h) throws Exception {
        try (var pack = new Pack(h)) {
            var root = json("""
                    {"version":"imports-v1","imports":["chorus_gametest:imports/shared"],"bundles":[
                    {"id":"test:imported","rules":[{"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},"do":[
                    {"type":"chorus:grant_buff","buff":"test:imported"},
                    {"type":"chorus:heal","amount":{"type":"chorus:component","buff":"test:imported","component":"benefit"}}]}]}]}
                    """);
            var shared = json("""
                    {"version":"imports-v1","fragment":true,"buffs":[{"definition":{"id":"test:imported","version":"imports-v1","duration":1,
                    "components":{"numbers":{"benefit":{"initial":1,"unit":"damage"}}}}}]}
                    """);
            pack.put("root", root); pack.put("shared", shared); pack.reload();
            var original = EffectPrograms.find(pack.server, ROOT).orElseThrow(); pack.command("chorus engine start " + ROOT);
            var pinned = MinecraftEffectRuntime.installed(h.getLevel()).orElseThrow();
            var benefit = shared.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").getAsJsonObject("components").getAsJsonObject("numbers").getAsJsonObject("benefit");
            benefit.addProperty("initial", 3); pack.put("shared", shared); pack.reload();
            var latest = EffectPrograms.find(pack.server, ROOT).orElseThrow(); h.assertTrue(latest != original && pinned.program() == original, "only new roots relink after dependency override");
            var actor = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); actor.setHealth(1); String id = actor.getUUID().toString();
            pack.command("chorus engine attach " + id + " test:imported old"); near(h, actor.getHealth(), 2, "old runtime keeps old dependency");
            pack.command("chorus engine stop"); pack.command("chorus engine start " + ROOT);
            pack.command("chorus engine attach " + id + " test:imported new"); near(h, actor.getHealth(), 5, "new runtime uses new dependency");
            var healthy = MinecraftEffectRuntime.installed(h.getLevel()).orElseThrow();
            for (String kind : List.of("missing", "version", "unit", "definition", "duplicate", "typo")) {
                var brokenRoot = root.deepCopy(); var brokenShared = shared.deepCopy();
                switch (kind) {
                    case "missing" -> brokenRoot.add("imports", JsonParser.parseString("[\"chorus_gametest:imports/missing\"]"));
                    case "version" -> brokenShared.addProperty("version", "imports-v2");
                    case "unit" -> brokenShared.getAsJsonArray("buffs").get(0).getAsJsonObject().getAsJsonObject("definition").getAsJsonObject("components").getAsJsonObject("numbers").getAsJsonObject("benefit").addProperty("unit", "second");
                    case "definition" -> brokenShared.add("buffs", new JsonArray());
                    case "duplicate" -> brokenRoot.add("buffs", shared.getAsJsonArray("buffs").deepCopy());
                    case "typo" -> brokenShared.addProperty("unknown_field", true);
                    default -> throw new AssertionError(kind);
                }
                pack.put("root", brokenRoot); pack.put("shared", brokenShared); boolean rejected = false;
                try { pack.reload(); } catch (CompletionException expected) { rejected = true; }
                h.assertTrue(rejected, "broken " + kind + " module was published");
                h.assertTrue(EffectPrograms.find(pack.server, ROOT).orElseThrow() == latest, "failed " + kind + " reload replaced catalogue");
                h.assertTrue(healthy.program() == latest && healthy.failure().isEmpty(), "failed reload poisoned pinned runtime");
            }
            pack.command("chorus engine attach " + id + " test:imported after_failure"); near(h, actor.getHealth(), 8, "runtime executes after rejected imports");
        } h.succeed();
    }
}
