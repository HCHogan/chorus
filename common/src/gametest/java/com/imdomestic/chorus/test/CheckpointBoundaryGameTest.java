package com.imdomestic.chorus.test;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.persistence.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.Codec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Real server saving with a pinned set of rules; these cases do not claim a stopped/restarted process. */
public class CheckpointBoundaryGameTest {
    private static final Identifier PROGRAM = Identifier.parse("chorus_gametest:loaded");
    private static final String ENVIRONMENT = "chorus_gametest:checkpoint_boundary";
    private static void refused(GameTestHelper h, Runnable call) {
        boolean rejected = false; try { call.run(); } catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid checkpoint operation was accepted");
    }
    private static RuleEngine.Signal cast(String owner) {
        return new RuleEngine.Signal("chorus_gametest:cast", new EffectEvent(owner, owner, new BuffInstance.Origin(owner, "skill", "", ""), Set.of(), Map.of()));
    }
    private static void resources(MinecraftEffectRuntime runtime, String holder) {
        runtime.bind(new EffectSource("skill", "chorus_gametest:resources", holder, new BuffInstance.Origin(holder, "skill", "", ""), Set.of()));
    }
    private static final class Probe extends SavedData { Runnable encode = () -> {}; }
    private static final Codec<Probe> CODEC = Codec.INT.fieldOf("value").xmap(_ -> new Probe(), p -> { p.encode.run(); return 31; }).codec();
    private static final SavedDataType<Probe> PROBE = new SavedDataType<>(Identifier.parse("chorus_gametest:checkpoint_boundary_probe"), Probe::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    @GameCase(environment = ENVIRONMENT)
    public void pendingConstructRemovalInvalidatesPinnedArchiveBeforeAnyRulesExecute(GameTestHelper h) {
        try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVE)) {
            var p = h.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 40, 3.5));
            var point = new com.imdomestic.chorus.effect.target.WorldPosition(h.getLevel().dimension().identifier().toString(), p.x, p.y, p.z);
            var command = new com.imdomestic.chorus.effect.object.WorldConstruct.Spawn(Optional.of(point), "test:boundary_construct",
                    new BuffInstance.Origin("owner", "source", "", ""), new com.imdomestic.chorus.effect.object.WorldConstruct.Parameters(10, .5, .5, 10_000_000),
                    Set.of(), runtime.program().program().version());
            var world = new MinecraftWorldActions(h.getLevel(), _ -> null, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var spawned = (com.imdomestic.chorus.effect.object.WorldConstruct.Receipt) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(99, 0, 0), command));
            var entity = h.getLevel().getEntity(UUID.fromString(spawned.entity().orElseThrow()));
            var before = runtime.state();
            try (var boundary = MinecraftCheckpointBoundary.open(h.getLevel().getServer())) {
                entity.discard(); h.assertValueEqual(runtime.state(), before, "removal callback ran rules inside pinned boundary");
                refused(h, boundary::requireUnchanged); refused(h, boundary::archive);
                h.assertTrue(!boundary.issues().isEmpty(), "pending committed world fact did not invalidate archive");
            }
            runtime.prepare(); runtime.checkpoint();
            h.assertTrue(runtime.failure().isEmpty(), "releasing boundary did not allow normal pending fact processing");
        } h.succeed();
    }

    @GameCase(environment = ENVIRONMENT)
    public void capturesAllDefaultRuntimesAndSavesRealNativeFilesWithoutReplayingRules(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer(); var nether = server.getLevel(Level.NETHER);
        var player = NativeMeleeGameTest.player(h); player.setHealth(3); String holder = player.getUUID().toString();
        try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVP);
                var other = EffectPrograms.install(nether, PROGRAM, EffectState.Mode.PVE)) {
            resources(runtime, holder); runtime.start(cast(holder)); h.assertValueEqual(player.getHealth(), 4f, "paid cast before capture");
            var original = runtime.checkpoint(); var otherOriginal = other.checkpoint(); var beforeState = runtime.state();
            try (var boundary = MinecraftCheckpointBoundary.open(server)) {
                var archive = boundary.archive(); h.assertValueEqual(archive.dimensions().size(), 2, "all installed catalogue runtimes");
                for (var entry : archive.dimensions()) h.assertValueEqual(entry.effects(), entry.dimension().equals(h.getLevel().dimension().identifier().toString())
                        ? original.effects() : otherOriginal.effects(), "complete per-dimension session");
                var report = boundary.saveNative(); report.requireSuccess(); boundary.requireUnchanged();
                var paths = (MinecraftPlayerSaveAccess) MinecraftWorldSave.bindings(server).playerStorage();
                var savedPlayer = NbtIo.readCompressed(paths.chorus$playerFile(player.getUUID()), NbtAccounter.unlimitedHeap());
                h.assertValueEqual(savedPlayer.getFloatOr("Health", -1), 4f, "actual file matches already-paid rule action");
                h.assertValueEqual(runtime.state(), beforeState, "saving must not advance or initialize rule state");
                h.assertTrue(Files.isRegularFile(server.getWorldPath(LevelResource.LEVEL_DATA_FILE)), "actual level.dat missing");
            }
            h.assertValueEqual(runtime.checkpoint(), original, "release does not dispatch or advance anything");
            runtime.start(cast(holder)); h.assertValueEqual(player.getHealth(), 4f, "release did not refund paid energy");
        } finally { player.discard(); }
        h.succeed();
    }
    @GameCase(environment = ENVIRONMENT)
    public void caughtRuleCallsCannotChangeStateOrMakeTheSameBoundaryValidAgain(GameTestHelper h) {
        var player = NativeMeleeGameTest.player(h); player.setHealth(3); String holder = player.getUUID().toString();
        try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVE)) {
            resources(runtime, holder); var before = runtime.state();
            var boundary = MinecraftCheckpointBoundary.open(h.getLevel().getServer());
            try {
                refused(h, () -> runtime.start(cast(holder))); refused(h, runtime::close);
                h.assertTrue(MinecraftEffectRuntime.installed(h.getLevel()).orElseThrow() == runtime, "refused close removed runtime");
                h.assertValueEqual(runtime.state(), before, "rejected event changed rule state");
                h.assertValueEqual(player.getHealth(), 3f, "rejected event reached world healing");
                refused(h, boundary::requireUnchanged); refused(h, boundary::archive);
                h.assertTrue(!boundary.issues().isEmpty(), "caught exception erased boundary failure");
                h.assertTrue(runtime.failure().isEmpty(), "rejected direct API call poisoned a previously healthy runtime");
            } finally { boundary.close(); }
            refused(h, boundary::requireUnchanged);
            runtime.start(cast(holder)); h.assertValueEqual(player.getHealth(), 4f, "normal operation resumes only after release");
        } finally { player.discard(); }
        h.succeed();
    }
    @GameCase(environment = ENVIRONMENT)
    public void preventsNewRuntimeAndNestedBoundaryAndKeepsOffThreadCloseFromReleasingIt(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVE)) {
            var boundary = MinecraftCheckpointBoundary.open(server);
            try {
                try (var executor = Executors.newSingleThreadExecutor()) {
                    h.assertTrue(executor.submit(() -> { try { boundary.close(); return false; } catch (IllegalStateException expected) { return true; } }).get(5, TimeUnit.SECONDS), "off-thread release accepted");
                }
                boundary.requireUnchanged(); // A caller on another thread never entered the active boundary.
                refused(h, () -> EffectPrograms.install(server.getLevel(Level.NETHER), PROGRAM, EffectState.Mode.PVE));
                h.assertTrue(MinecraftEffectRuntime.installed(server.getLevel(Level.NETHER)).isEmpty(), "new runtime installed while sealed");
                refused(h, () -> MinecraftCheckpointBoundary.open(server)); refused(h, boundary::requireUnchanged);
            } finally { boundary.close(); }
            try (var next = MinecraftCheckpointBoundary.open(server)) { next.requireUnchanged(); boundary.close(); next.requireUnchanged(); }
        }
        h.succeed();
    }
    @GameCase(environment = ENVIRONMENT)
    public void aCaughtRuleOperationInsideNativeSerializationPreventsCheckpointConfirmation(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer(); var player = NativeMeleeGameTest.player(h); player.setHealth(3);
        var probe = new Probe(); server.getDataStorage().set(PROBE, probe); var attempted = new AtomicBoolean();
        var directory = Files.createTempDirectory("chorus-pinned-save-");
        try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVE)) {
            resources(runtime, player.getUUID().toString());
            var store = new WorldCheckpointStore(directory.resolve("checkpoint.json")); var session = store.begin();
            try (var boundary = MinecraftCheckpointBoundary.open(server)) {
                var pending = session.prepare(boundary.archive(), Set.of("native_state"));
                probe.encode = () -> {
                    attempted.set(true); refused(h, () -> runtime.start(cast(player.getUUID().toString())));
                };
                refused(h, () -> { boundary.saveNative().requireSuccess(); pending.confirm("native_state"); });
                h.assertTrue(attempted.get(), "native serializer did not exercise checkpoint guard");
                h.assertValueEqual(player.getHealth(), 3f, "serializer's rejected cast changed native state");
                h.assertTrue(!boundary.issues().isEmpty(), "serializer swallowed checkpoint violation");
                pending.failed(boundary.issues().toString());
                refused(h, () -> { try { pending.commit(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } });
                refused(h, () -> { try { store.begin(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } });
            }
            probe.encode = () -> {};
            try (var next = MinecraftCheckpointBoundary.open(server)) { next.saveNative().requireSuccess(); }
        } finally {
            probe.encode = () -> {}; player.discard();
            try (var files = Files.walk(directory)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        h.succeed();
    }
    @GameCase(environment = ENVIRONMENT)
    public void changedClockRemainsInvalidAfterItIsPutBack(GameTestHelper h) {
        var level = h.getLevel(); long original = level.getGameTime();
        try (var runtime = EffectPrograms.install(level, PROGRAM, EffectState.Mode.PVE);
                var boundary = MinecraftCheckpointBoundary.open(level.getServer())) {
            ((ServerLevelData) level.getLevelData()).setGameTime(original + 1); refused(h, boundary::requireUnchanged);
            ((ServerLevelData) level.getLevelData()).setGameTime(original); refused(h, boundary::requireUnchanged);
        } finally { ((ServerLevelData) level.getLevelData()).setGameTime(original); }
        h.succeed();
    }
    @GameCase(environment = ENVIRONMENT)
    public void pinnedNbtLoadPreservesProjectedHealthAndDetectsPhysicalEquipmentChanges(GameTestHelper h) {
        var level = h.getLevel(); var player = NativeMeleeGameTest.player(h);
        var program = Identifier.parse("chorus_gametest:checkpoint_equipment");
        try (var runtime = EffectPrograms.install(level, program, EffectState.Mode.PVE)) {
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
            item.set(com.imdomestic.chorus.registry.ChorusComponents.EQUIPMENT.get(),
                    new com.imdomestic.chorus.effect.equipment.Loadout.Gear("checkpoint-armor", "test:arms", Map.of()));
            player.getInventory().setItem(0, item); var equipment = PlayerEquipment.get(player);
            equipment.swap(player, "test:arms", 0, equipment.revision()); player.setHealth(27);
            h.assertValueEqual(player.getMaxHealth(), 30f, "live equipped maximum health");
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess()); player.saveWithoutId(output);
            var saved = output.buildResult(); var equipmentOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess()); equipment.save(equipmentOutput);
            var originalItems = equipmentOutput.buildResult(); var before = runtime.state();
            try (var boundary = MinecraftCheckpointBoundary.open(level.getServer())) {
                var cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false);
                var unindexed = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
                try {
                    unindexed.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
                    h.assertValueEqual(unindexed.getMaxHealth(), 30f, "pinned projection rebuilt before NBT health load");
                    h.assertValueEqual(unindexed.getHealth(), 27f, "NBT health not clamped to vanilla capacity");
                    h.assertValueEqual(runtime.state(), before, "NBT reconstruction executed no rule transition");
                    boundary.requireUnchanged();
                } finally { unindexed.discard(); }
                equipment.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), new CompoundTag()));
                refused(h, boundary::requireUnchanged);
                equipment.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), originalItems));
                refused(h, boundary::requireUnchanged);
                h.assertValueEqual(runtime.state(), before, "physical mismatch must not auto-equip or unbind sources");
            }
            try (var next = MinecraftCheckpointBoundary.open(level.getServer())) { next.requireUnchanged(); }
        } finally { player.discard(); }
        h.succeed();
    }
    @GameCase(environment = ENVIRONMENT)
    public void refusesCustomHostsBeforeOpeningAndLeavesExistingRuntimeUsable(GameTestHelper h) {
        var level = h.getLevel(); var program = EffectPrograms.find(level.getServer(), PROGRAM).orElseThrow();
        try (var custom = MinecraftEffectRuntime.install(level, program, EffectState.empty(),
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), _ -> { throw new AssertionError("unexpected world action"); }, MinecraftEffectRuntime::nativeSource)) {
            refused(h, () -> MinecraftCheckpointBoundary.open(level.getServer()));
            custom.start(new RuleEngine.Signal("chorus_gametest:no_op", RuleEngine.Empty.INSTANCE));
            h.assertTrue(custom.failure().isEmpty(), "preflight rejection stopped custom runtime");
        }
        try (var runtime = EffectPrograms.install(level, PROGRAM, EffectState.Mode.PVE);
                var boundary = MinecraftCheckpointBoundary.open(level.getServer())) { boundary.requireUnchanged(); }
        h.succeed();
    }
}
