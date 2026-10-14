package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.NativeAttributeGameTest.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.item.*;

/** Runtime replacement against a matching live world, not a simulated server restart. */
public class RuntimeRestoreGameTest {
    private static final Identifier PROGRAM = Identifier.parse("chorus_gametest:loaded");
    private static EffectClock clock() { return new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())); }
    private static void refused(GameTestHelper h, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid runtime checkpoint or restore was accepted");
    }
    private static MinecraftWorldActions world(GameTestHelper h) {
        return new MinecraftWorldActions(h.getLevel(), id -> h.getLevel().getEntity(UUID.fromString(id)) instanceof LivingEntity e ? e : null,
                _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
    }
    private static MinecraftEffectRuntime restore(GameTestHelper h, MinecraftEffectRuntime.Checkpoint saved, MinecraftWorldActions world) {
        return MinecraftEffectRuntime.restore(h.getLevel(), new CompiledEffects(saved.effects().program()), saved, clock(), world, MinecraftEffectRuntime::nativeSource);
    }
    @GameCase public void catalogueRestorePreservesPaidEnergyAndDoesNotRepeatSourceInitialization(GameTestHelper h) {
        var actor = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); actor.setHealth(1);
        var victim = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
        String owner = id(actor); var origin = new BuffInstance.Origin(owner, "skill", "", "");
        var cast = new RuleEngine.Signal("chorus_gametest:cast", new EffectEvent(owner, owner, origin, Set.of(), Map.of()));
        MinecraftEffectRuntime.Checkpoint saved;
        try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVP)) {
            runtime.bind(new EffectSource("skill", "chorus_gametest:resources", owner, origin, Set.of()));
            runtime.bind(new EffectSource("passive", "chorus_gametest:loaded", owner, origin, Set.of()));
            near(h, actor.getHealth(), 2, "source initialization"); runtime.start(cast); near(h, actor.getHealth(), 3, "paid cast");
            saved = runtime.checkpoint();
        }
        try (var runtime = EffectPrograms.restore(h.getLevel(), PROGRAM, saved)) {
            h.assertValueEqual(runtime.checkpoint(), saved, "complete domain, RNG, mode, clocks and identity counters");
            near(h, actor.getHealth(), 3, "restore repeated no source initialization or cast");
            runtime.start(cast); near(h, actor.getHealth(), 3, "no free cast after restore");
            near(h, runtime.state().engine().domain().resources().get(new ResourceState.Key(owner, "chorus_gametest:energy")).value(), 0, "paid energy preserved");
            h.assertTrue(victim.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(actor), 2), "native hit applied");
            near(h, actor.getHealth(), 4, "restored native damage observer drove passive healing");
            h.assertTrue(runtime.failure().isEmpty(), "restored runtime failed");
        }
        h.succeed();
    }
    @GameCase public void archivedCatalogueMetadataRestoresAfterExplicitEntityFileConfirmations(GameTestHelper h) throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("chorus-world-archive-");
        var actor = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); actor.setHealth(1);
        var victim = h.spawnWithNoFreeWill(EntityTypes.COW, 4, 2, 2);
        try {
            var storage = new com.imdomestic.chorus.effect.persistence.WorldCheckpointStore(directory.resolve("checkpoint.json"));
            var session = storage.begin();
            com.imdomestic.chorus.effect.persistence.CheckpointArchive archive;
            try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVP)) {
                runtime.bind(new EffectSource("passive", "chorus_gametest:loaded", id(actor), new BuffInstance.Origin(id(actor), "passive", "", ""), Set.of()));
                archive = new com.imdomestic.chorus.effect.persistence.CheckpointArchive(List.of(EffectPrograms.checkpoint(h.getLevel(), PROGRAM)));
                refused(h, () -> EffectPrograms.checkpoint(h.getLevel(), Identifier.parse("chorus_gametest:missing")));
                var pending = session.prepare(archive, Set.of("actor_nbt", "victim_nbt"));
                refused(h, () -> { try { storage.begin(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } });
                for (var entity : List.of(actor, victim)) {
                    var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
                    entity.saveWithoutId(output); String part = entity == actor ? "actor_nbt" : "victim_nbt";
                    var path = directory.resolve(part + ".dat"); net.minecraft.nbt.NbtIo.writeCompressed(output.buildResult(), path);
                    h.assertValueEqual(net.minecraft.nbt.NbtIo.readCompressed(path, net.minecraft.nbt.NbtAccounter.unlimitedHeap()), output.buildResult(), "actual fixture entity file");
                    pending.confirm(part);
                    if (entity == actor) refused(h, () -> { try { pending.commit(); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } });
                }
                pending.commit();
            }
            var recovered = storage.begin().recovered().orElseThrow(); h.assertValueEqual(recovered, archive, "full archive file round-trip");
            try (var runtime = EffectPrograms.restore(h.getLevel(), recovered.dimensions().getFirst())) {
                h.assertValueEqual(EffectPrograms.checkpoint(h.getLevel(), PROGRAM), archive.dimensions().getFirst(), "catalogue and host metadata preserved");
                near(h, actor.getHealth(), 2, "source initialization not repeated");
                victim.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(actor), 2); near(h, actor.getHealth(), 3, "restored native observer works");
            }
        } finally {
            MinecraftEffectRuntime.installed(h.getLevel()).ifPresent(MinecraftEffectRuntime::close);
            try (var files = java.nio.file.Files.list(directory)) { for (var path : files.toList()) java.nio.file.Files.delete(path); }
            java.nio.file.Files.delete(directory);
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:runtime_restore_clock", maxTicks = 14)
    public void restoredProjectionsPreserveForeignModifiersAndExpireOnTheOriginalClock(GameTestHelper h) throws Exception {
        var t = harness(h); MinecraftEffectRuntime restored = null;
        try {
            var maximum = t.owner.getAttribute(Attributes.MAX_HEALTH); maximum.setBaseValue(20); t.owner.setHealth(19);
            var speed = t.owner.getAttribute(Attributes.MOVEMENT_SPEED); double base = speed.getBaseValue();
            var external = new AttributeModifier(Identifier.parse("test:restore_external"), .05, AttributeModifier.Operation.ADD_VALUE);
            speed.addPermanentModifier(external);
            bind(t, "inputs", "inputs", t.owner); bind(t, "fast", "fast", t.owner); signal(t, "health", t.owner);
            near(h, maximum.getValue(), 30, "pre-save maximum"); near(h, t.owner.getHealth(), 30, "pre-save healing");
            var target = t.cow(2.5, 43, 3.5); double gravity = target.getAttributeValue(Attributes.GRAVITY);
            signal(t, "slow", target); var saved = t.runtime.checkpoint(); t.runtime.close();
            near(h, maximum.getValue(), 20, "close removed owned projection"); t.owner.setHealth(7);
            restored = restore(h, saved, t.world); var runtime = restored;
            h.assertValueEqual(runtime.checkpoint(), saved, "restore neither advanced nor initialized domain");
            near(h, maximum.getValue(), 30, "maximum projection rebuilt"); near(h, t.owner.getHealth(), 7, "healing was not replayed");
            near(h, speed.getValue(), (base + .05) * 1.5, "foreign modifier composed once");
            near(h, target.getAttributeValue(Attributes.GRAVITY), gravity * .5, "recipient gravity rebuilt");
            h.runAfterDelay(6, () -> {
                try (runtime; t) {
                    runtime.prepare(); h.assertTrue(runtime.failure().isEmpty(), "restored timeline failed");
                    h.assertValueEqual(runtime.nowMicros(), saved.effects().timeline().timeMicros() + (h.getLevel().getGameTime() - saved.gameTime()) * 50_000, "world and logical clock relationship");
                    near(h, maximum.getValue(), 20, "original buff deadline"); near(h, t.owner.getHealth(), 7, "no replay at deadline");
                    near(h, target.getAttributeValue(Attributes.GRAVITY), gravity, "recipient buff expired");
                    near(h, speed.getValue(), (base + .05) * 1.5, "untimed source remains");
                    runtime.close(); near(h, speed.getValue(), base + .05, "foreign modifier survives second close");
                    h.assertTrue(speed.hasModifier(external.id()), "foreign modifier was deleted"); h.succeed();
                }
            });
        } catch (Exception | Error failure) { if (restored != null) restored.close(); t.close(); throw failure; }
    }
    @GameCase public void wrongWorldCatalogueAndDuplicateRuntimeAreRejectedBeforeAttachment(GameTestHelper h) {
        MinecraftEffectRuntime.Checkpoint saved;
        try (var runtime = EffectPrograms.install(h.getLevel(), PROGRAM, EffectState.Mode.PVE)) {
            saved = runtime.checkpoint();
            refused(h, () -> EffectPrograms.restore(h.getLevel(), PROGRAM, saved));
            h.assertTrue(MinecraftEffectRuntime.installed(h.getLevel()).orElseThrow() == runtime, "duplicate replaced live runtime");
            h.assertValueEqual(runtime.checkpoint(), saved, "duplicate changed live state");
        }
        var wrongDimension = new MinecraftEffectRuntime.Checkpoint("minecraft:the_nether", saved.gameTime(), saved.effects(), saved.equipmentOwners(), saved.actorOwners());
        var wrongTime = new MinecraftEffectRuntime.Checkpoint(saved.dimension(), saved.gameTime() + 1, saved.effects(), saved.equipmentOwners(), saved.actorOwners());
        refused(h, () -> EffectPrograms.restore(h.getLevel(), PROGRAM, wrongDimension));
        refused(h, () -> EffectPrograms.restore(h.getLevel(), PROGRAM, wrongTime));
        var data = EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, saved.effects().program()).getOrThrow().getAsJsonObject();
        data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject()
                .getAsJsonArray("do").get(0).getAsJsonObject().getAsJsonObject("amount").addProperty("value", 3);
        var changed = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow();
        refused(h, () -> MinecraftEffectRuntime.restore(h.getLevel(), changed, saved, clock(), world(h), MinecraftEffectRuntime::nativeSource));
        h.assertTrue(MinecraftEffectRuntime.installed(h.getLevel()).isEmpty(), "rejected restore attached a runtime");
        try (var runtime = EffectPrograms.restore(h.getLevel(), PROGRAM, saved)) {
            h.assertValueEqual(runtime.checkpoint(), saved, "rejections did not damage valid checkpoint");
        }
        // Equal DSL alone cannot prove that a caller-supplied Java executor/clock has the default host semantics.
        try (var custom = MinecraftEffectRuntime.install(h.getLevel(), EffectPrograms.find(h.getLevel().getServer(), PROGRAM).orElseThrow(),
                EffectState.empty(), clock(), world(h), MinecraftEffectRuntime::nativeSource)) {
            refused(h, () -> EffectPrograms.checkpoint(h.getLevel(), PROGRAM));
            h.assertTrue(custom.failure().isEmpty(), "archive preflight poisoned a custom host"); custom.checkpoint();
        }
        h.succeed();
    }
    @GameCase public void sourceDescriptionAndWorldExecutionCannotCaptureAnUnsettledCheckpoint(GameTestHelper h) throws Exception {
        var target = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2); String holder = id(target);
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("healing")).getOrThrow();
        var origin = new BuffInstance.Origin(holder, "healer", "", ""); var world = world(h);
        var current = new MinecraftEffectRuntime[1]; int[] calls = {0, 0};
        current[0] = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), clock(), request -> {
            refused(h, current[0]::checkpoint); calls[0]++; return world.apply(request);
        }, (victim, source, amount) -> {
            refused(h, current[0]::checkpoint); calls[1]++; return MinecraftEffectRuntime.nativeSource(victim, source, amount);
        });
        try (var runtime = current[0]) {
            runtime.bind(new EffectSource("healer", "test:healing", holder, origin, Set.of()));
            runtime.start(new RuleEngine.Signal("test:heal", new EffectEvent(holder, holder, origin, Set.of(), Map.of("amount", new Measure(3, Unit.DAMAGE)))));
            near(h, target.getHealth(), 5, "actual healing still executes once");
            h.assertTrue(target.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1), "native damage applied");
            h.assertTrue(calls[0] > 0 && calls[1] > 0, "callbacks were not exercised");
            h.assertTrue(runtime.failure().isEmpty(), "checkpoint rejection poisoned healthy execution");
            runtime.checkpoint();
        }
        h.succeed();
    }
    @GameCase public void unknownWorldHealingCannotBecomeARestorableHostCheckpoint(GameTestHelper h) throws Exception {
        try (var t = harness(h)) {
            t.owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20); t.owner.setHealth(19);
            bind(t, "inputs", "inputs", t.owner); t.failAfterHealing = true;
            refused(h, () -> signal(t, "health", t.owner)); near(h, t.owner.getHealth(), 30, "unacknowledged actual healing retained");
            h.assertTrue(t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown result evidence lost");
            refused(h, t.runtime::checkpoint); refused(h, t.runtime::checkpoint);
            near(h, t.owner.getHealth(), 30, "checkpoint attempts did not repeat healing");
        }
        h.succeed();
    }
    @GameCase public void physicalEquipmentMustMatchWithoutMovingItemsOrRepeatingAttachEffects(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h)) {
            var player = t.player(); var equipment = PlayerEquipment.get(player);
            var gear = new Loadout.Gear("restored-rifle", "test:rifle", Map.of("perk", "normal"));
            var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), gear);
            player.getInventory().setItem(0, stack); equipment.swap(player, "test:weapon_a", 0, equipment.revision());
            t.settled(); var saved = t.runtime.checkpoint(); float health = player.getHealth();
            h.assertTrue(saved.equipmentOwners().contains(player.getUUID()), "physical ownership tracking was not saved");
            t.runtime.close(); var storage = new PlayerEquipment(); storage.transferFrom(equipment);
            refused(h, () -> restore(h, saved, world(h)));
            h.assertTrue(MinecraftEffectRuntime.installed(h.getLevel()).isEmpty(), "mismatched physical loadout attached runtime");
            h.assertTrue(equipment.isEmpty() && !storage.isEmpty(), "rejection moved physical items");
            near(h, player.getHealth(), health, "rejection replayed no equipment reactions");
            equipment.transferFrom(storage); t.runtime = restore(h, saved, world(h));
            h.assertValueEqual(t.runtime.checkpoint(), saved, "equipment sources and ownership recovered without changes");
            near(h, player.getHealth(), health, "restore replayed no attach healing");
            h.assertValueEqual(equipment.snapshot().items().get("test:weapon_a").get(ChorusComponents.EQUIPMENT.get()), gear, "physical item retained");
            // The saved tracking set also handles a now-empty container on the next reconciliation.
            storage.transferFrom(equipment); t.runtime.prepare(); t.settled();
            h.assertValueEqual(t.runtime.state().engine().domain().equipment().getOrDefault(id(player), Loadout.EMPTY), Loadout.EMPTY, "empty tracked container reconciled");
        }
        h.succeed();
    }
    @GameCase public void trustedExternalEquipmentHostsAreNotReplacedByThePlayerContainer(GameTestHelper h) throws Exception {
        try (var t = new PlayerEquipmentGameTest.Harness(h)) {
            var player = t.player(); var loadout = new Loadout(Map.of("test:weapon_a", new Loadout.Gear("external", "test:rifle", Map.of("perk", "normal"))), Optional.empty());
            t.runtime.equip(new EquipmentChange(id(player), Loadout.EMPTY, loadout)); var saved = t.runtime.checkpoint(); float health = player.getHealth();
            h.assertTrue(saved.equipmentOwners().isEmpty(), "external host was incorrectly tracked as a PlayerEquipment container");
            t.runtime.close(); t.runtime = restore(h, saved, world(h)); t.runtime.prepare();
            h.assertValueEqual(t.runtime.checkpoint(), saved, "external equipment host preserved");
            near(h, player.getHealth(), health, "external host was not detached and initialized");
        }
        h.succeed();
    }
    @GameCase public void closingAndRestoringDoesNotRecreatePickupsOrInventRewards(GameTestHelper h) throws Exception {
        try (var t = new PickupGameTest.Harness(h)) {
            t.spawn(); h.assertTrue(!t.objects.isEmpty(), "pickup was not spawned"); var saved = t.runtime.checkpoint();
            int rewards = t.heals.size(), cues = t.cues.size(); t.runtime.close();
            h.assertTrue(t.objects.stream().allMatch(EffectObject::isRemoved), "old runtime pickup survived until another tick");
            try (var runtime = restore(h, saved, t.world)) {
                runtime.prepare(); h.assertValueEqual(runtime.checkpoint(), saved, "no fabricated pickup consumption or expiry");
                h.assertValueEqual(t.heals.size(), rewards, "close and restore granted no reward"); h.assertValueEqual(t.cues.size(), cues, "close and restore emitted no cue");
                for (var entity : h.getLevel().getAllEntities()) h.assertTrue(!(entity instanceof EffectObject), "restore respawned a transient pickup");
            }
        }
        h.succeed();
    }
}
