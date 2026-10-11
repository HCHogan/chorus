package com.imdomestic.chorus.test;

import com.imdomestic.chorus.platform.Services;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.storage.*;

/** Actual upstream entity/section/chunk writers, with independent temporary storage for every injected failure. */
public class SaveSerializationGameTest {
    private static final ChunkPos POS = new ChunkPos(0, 0);
    private static final Codec<Integer> INVALID = Codec.INT.flatXmap(DataResult::success, value -> DataResult.error(() -> "expected incomplete field"));
    private static boolean neo() { return Services.PLATFORM.getPlatformName().equals("NeoForge"); }
    private static <T> T waitFor(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static RegionSaveJournal journal(SimpleRegionStorage storage) { return ((MinecraftRegionSaveAccess) storage.chunkScanner()).chorus$regionSaves(); }
    private static void remove(Path root) throws Exception { try (var files = Files.walk(root)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); } }
    private static void invalid(GameTestHelper h, RegionSaveJournal journal) throws Exception {
        var report = waitFor(journal.completion()); h.assertTrue(!report.successful(), "partial serialization became successful save evidence");
        h.assertTrue(report.failures().stream().anyMatch(f -> f.operation().kind() == RegionSaveJournal.Kind.ENCODE), "missing upstream encoding failure");
    }
    private static final class Region implements AutoCloseable {
        final Path root = Files.createTempDirectory("chorus-serialization-");
        final SimpleRegionStorage storage;
        Region(String type) throws Exception {
            storage = new SimpleRegionStorage(new RegionStorageInfo("fixture", Level.OVERWORLD, type), root, DataFixers.getDataFixer(), false, DataFixTypes.ENTITY_CHUNK);
        }
        @Override public void close() throws Exception { try { storage.close(); } finally { remove(root); } }
    }
    private static Cow cow(GameTestHelper h, int failure) {
        return new Cow(EntityTypes.COW, h.getLevel()) {
            @Override protected void addAdditionalSaveData(ValueOutput output) {
                super.addAdditionalSaveData(output);
                if (failure == 1) output.store("invalid", INVALID, 1);
                if (failure == 2) throw new IllegalStateException("expected entity serializer failure");
            }
        };
    }
    @GameCase public void completeEntitiesAndIntentionalSkippedEntitiesRemainValid(GameTestHelper h) throws Exception {
        try (var region = new Region("entities")) {
            var storage = new EntityStorage(region.storage, h.getLevel(), h.getLevel().getServer()); var entity = cow(h, 0);
            h.assertTrue(((MinecraftRegionStorageAccess) storage).chorus$regionStorage() == region.storage, "wrong entity backing store");
            storage.storeEntities(new ChunkEntities<>(POS, List.of(entity))); waitFor(region.storage.synchronize(true)); waitFor(journal(region.storage).completion()).requireSuccess();
            h.assertValueEqual(waitFor(region.storage.read(POS)).orElseThrow().getListOrEmpty("Entities").size(), 1, "complete entity persisted");
            entity.discard(); storage.storeEntities(new ChunkEntities<>(POS, List.of(entity))); waitFor(region.storage.synchronize(true));
            waitFor(journal(region.storage).completion()).requireSuccess();
            h.assertTrue(waitFor(region.storage.read(POS)).orElseThrow().getListOrEmpty("Entities").isEmpty(), "native intentional non-saving entity policy changed");
        }
        h.succeed();
    }
    @GameCase public void reportedEntityFieldErrorsInvalidateOtherwiseSuccessfulRegionWrites(GameTestHelper h) throws Exception {
        try (var region = new Region("entities")) {
            var storage = new EntityStorage(region.storage, h.getLevel(), h.getLevel().getServer()); var entity = cow(h, 1);
            storage.storeEntities(new ChunkEntities<>(POS, List.of(entity))); waitFor(region.storage.synchronize(true)); invalid(h, journal(region.storage));
            var entities = waitFor(region.storage.read(POS)).orElseThrow().getListOrEmpty("Entities");
            h.assertValueEqual(entities.size(), 1, "native partial entity still written"); h.assertTrue(!entities.getCompoundOrEmpty(0).contains("invalid"), "fixture codec unexpectedly serialized field");
            entity.discard();
        }
        h.succeed();
    }
    @GameCase public void thrownEntityErrorsAreRecordedBeforeNeoForgeCanDiscardTheEntity(GameTestHelper h) throws Exception {
        try (var region = new Region("entities")) {
            var storage = new EntityStorage(region.storage, h.getLevel(), h.getLevel().getServer()); var entity = cow(h, 2);
            boolean threw = false; try { storage.storeEntities(new ChunkEntities<>(POS, List.of(entity))); } catch (RuntimeException expected) { threw = true; }
            h.assertTrue(threw != neo(), "loader's original entity exception policy changed");
            waitFor(region.storage.synchronize(true)); invalid(h, journal(region.storage));
            if (neo()) h.assertTrue(waitFor(region.storage.read(POS)).orElseThrow().getListOrEmpty("Entities").isEmpty(), "NeoForge no longer omits failing entity");
            else h.assertTrue(waitFor(region.storage.read(POS)).isEmpty(), "Fabric wrote after propagated entity failure");
            entity.discard();
        }
        h.succeed();
    }
    private static final class Sections extends SectionStorage<Integer, Integer> {
        Sections(GameTestHelper h, SimpleRegionStorage region, Codec<Integer> codec, Function<Integer, Integer> packer) {
            super(region, codec, packer, (value, dirty) -> value, dirty -> 7, h.getLevel().registryAccess(), h.getLevel().getServer(), LevelHeightAccessor.create(0, 16));
        }
        void seed() { long pos = SectionPos.asLong(0, 0, 0); getOrCreate(pos); setDirty(pos); }
    }
    @GameCase public void sectionPartialAndMissingResultsRemainFailuresEvenAfterTheNativeDirtySetClears(GameTestHelper h) throws Exception {
        var partial = Codec.INT.flatXmap(DataResult::success, value -> DataResult.error(() -> "expected partial section", value));
        for (var codec : List.of(Codec.INT, INVALID, partial)) try (var region = new Region("poi")) {
            var sections = new Sections(h, region.storage, codec, Function.identity()); sections.seed();
            h.assertTrue(((MinecraftRegionStorageAccess) (Object) sections).chorus$regionStorage() == region.storage, "wrong section backing store");
            sections.flushAll(); waitFor(region.storage.synchronize(true));
            h.assertTrue(!sections.hasWork(), "native dirty chunks not cleared");
            var saved = waitFor(region.storage.read(POS)).orElseThrow().getCompoundOrEmpty("Sections");
            if (codec == Codec.INT) { waitFor(journal(region.storage).completion()).requireSuccess(); h.assertValueEqual(saved.getIntOr("0", -1), 7, "healthy section"); }
            else { invalid(h, journal(region.storage)); h.assertTrue(saved.contains("0") == (codec == partial), "partial/missing output behavior changed"); }
            sections.flushAll(); waitFor(region.storage.synchronize(true));
            if (codec != Codec.INT) invalid(h, journal(region.storage));
        }
        h.succeed();
    }
    @GameCase public void aSectionPackerExceptionBeforeWorkerSubmissionIsRetained(GameTestHelper h) throws Exception {
        try (var region = new Region("poi")) {
            var failure = new IllegalStateException("expected section packer failure");
            var sections = new Sections(h, region.storage, Codec.INT, _ -> { throw failure; }); sections.seed();
            Throwable observed = null; try { sections.flushAll(); } catch (IllegalStateException error) { observed = error; }
            h.assertTrue(observed == failure, "original packer exception changed");
            waitFor(region.storage.synchronize(true)); invalid(h, journal(region.storage));
            h.assertTrue(waitFor(region.storage.read(POS)).isEmpty(), "failed section packer submitted data");
        }
        h.succeed();
    }
    private static final class Chunks implements AutoCloseable {
        final Path root = Files.createTempDirectory("chorus-chunk-serialization-");
        final LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(root).createAccess("fixture");
        final ChunkMap map;
        @SuppressWarnings("unchecked") Chunks(GameTestHelper h) throws Exception {
            var level = h.getLevel(); var server = level.getServer();
            var field = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("mainThreadProcessor"); field.setAccessible(true);
            var loop = (net.minecraft.util.thread.BlockableEventLoop<Runnable>) field.get(level.getChunkSource());
            map = new ChunkMap(level, access, DataFixers.getDataFixer(), server.getStructureTemplateManager(), Util.backgroundExecutor(), loop,
                    level.getChunkSource(), level.getChunkSource().getGenerator(), (_, _) -> {}, new TicketStorage(), 2, false);
        }
        boolean save(LevelChunk chunk) throws Exception {
            chunk.markUnsaved(); var method = ChunkMap.class.getDeclaredMethod("save", net.minecraft.world.level.chunk.ChunkAccess.class);
            method.setAccessible(true); return (boolean) method.invoke(map, chunk);
        }
        @Override public void close() throws Exception { try { map.close(); } finally { try { access.close(); } finally { remove(root); } } }
    }
    private static LevelChunk chunk(GameTestHelper h, int failure) {
        var chunk = new LevelChunk(h.getLevel(), POS); var pos = new BlockPos(1, 1, 1); var state = Blocks.CHEST.defaultBlockState();
        chunk.setBlockState(pos, state, 0);
        chunk.setBlockEntity(new BlockEntity(BlockEntityTypes.CHEST, pos, state) {
            @Override protected void saveAdditional(ValueOutput output) {
                if (failure == 1) output.store("invalid", INVALID, 1);
                if (failure == 2) throw new IllegalStateException("expected block entity serializer failure");
                output.putInt("probe", 17);
            }
        });
        return chunk;
    }
    @GameCase public void chunkSnapshotsRetainBlockEntityWarningsAndNativeCaughtExceptions(GameTestHelper h) throws Exception {
        for (int failure = 0; failure <= 2; failure++) try (var chunks = new Chunks(h)) {
            boolean submitted = chunks.save(chunk(h, failure));
            h.assertTrue(submitted == (failure != 2 || neo()), "loader's original chunk/BlockEntity exception policy changed");
            waitFor(chunks.map.synchronize(true));
            if (failure == 0) {
                waitFor(journal(chunks.map).completion()).requireSuccess();
                h.assertValueEqual(waitFor(chunks.map.read(POS)).orElseThrow().getListOrEmpty("block_entities").getCompoundOrEmpty(0).getIntOr("probe", -1), 17, "actual chunk metadata");
            } else {
                invalid(h, journal(chunks.map));
                if (failure == 2 && neo()) h.assertTrue(waitFor(chunks.map.read(POS)).orElseThrow().getListOrEmpty("block_entities").isEmpty(), "NeoForge no longer omits failing block entity");
            }
        }
        h.succeed();
    }
}
