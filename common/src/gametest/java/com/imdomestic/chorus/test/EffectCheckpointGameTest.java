package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.equipment.*;
import com.imdomestic.chorus.effect.resource.*;
import com.imdomestic.chorus.effect.persistence.CheckpointArchive;
import com.imdomestic.chorus.effect.weapon.WeaponFire;
import com.imdomestic.chorus.platform.minecraft.MinecraftHealingExecutor;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;

/** File round-trip and session restore against the same live world; world-save coordination is not simulated. */
public class EffectCheckpointGameTest {
    private static EffectClock clock() { return new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())); }
    private static RuleEngine.Signal signal(EffectSource source, String type) {
        return new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(),
                Map.of("tier", new Measure(1, Unit.COUNT))));
    }
    private static void refused(GameTestHelper helper, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Unsettled session was accepted");
    }
    /** Explicit coordinated sessions and the real healing adapter; this does not install a native coordinated runtime. */
    private static final class RoutedWeapon {
        static final String A = "test:weapon", B = "test:resources";
        final GameTestHelper helper; final net.minecraft.world.entity.LivingEntity target; final String holder;
        final CompiledEffects program; final Map<String, EffectSession> sessions = new TreeMap<>();
        ResourceSessionAuthority host; int heals; boolean loseReceipt;
        RoutedWeapon(GameTestHelper helper) {
            this.helper = helper; target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2); holder = target.getUUID().toString();
            var data = com.google.gson.JsonParser.parseString("""
                {"version":"coordinated-weapon-world","resources":[{"id":"test:energy","capacity":1,"initial":0.25}],
                 "equipment":{"slots":[{"id":"test:weapon","accepts":["test:weapon"],"weapon":true}],"items":[{"id":"test:gun","tags":["test:weapon"]}]},
                 "weapons":[{"item":"test:gun","ammunition":{"capacity":4,"magazine":4,"reserves":"unlimited"},
                  "reload":{"value":{"type":"chorus:constant","value":0.25,"unit":"second"}},
                  "fire":{"cost":{"type":"chorus:scale","of":{"type":"chorus:resource","resource":"test:energy"},"factor":4,"from":"charge_fraction","to":"round"},
                    "interval":{"type":"chorus:scale","of":{"type":"chorus:resource","resource":"test:energy"},"factor":1,"from":"charge_fraction","to":"second"},
                    "on_fire":[{"type":"chorus:heal","amount":{"type":"chorus:scale","of":{"type":"chorus:resource","resource":"test:energy"},"factor":4,"from":"charge_fraction","to":"damage"}}]}}]}
                """);
            program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, data).getOrThrow().withResourceRouting();
            sessions.put(A, new EffectSession(program, clock(), 1, EffectState.empty(), r -> world(A, r)));
            sessions.put(B, new EffectSession(program, clock(), 1, EffectState.empty().withResource(new ResourceState(key(), .25, 1, 0)), r -> world(B, r)));
            var bindings = new TreeMap<String, ResourceSessionAuthority.Binding>(); sessions.forEach((id, s) -> bindings.put(id, new ResourceSessionAuthority.Binding(program, s)));
            host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, bindings); host.claim(holder, B);
            var gear = new Loadout(Map.of("test:weapon", new Loadout.Gear("gun", "test:gun", Map.of())), Optional.of("test:weapon"));
            sessions.get(A).start(0, new EquipmentChange(holder, Loadout.EMPTY, gear).signal());
        }
        ResourceState.Key key() { return new ResourceState.Key(holder, "test:energy"); }
        EffectState state() { return sessions.get(A).state().engine().domain(); }
        void fire(long time, String token) { sessions.get(A).start(time, new WeaponFire.Request(holder, token).signal()); }
        RuleEngine.ActionResult world(String domain, RuleEngine.WorldRequest request) {
            if (request.command() instanceof ResourceRequests.Request) return host.execute(domain, request);
            var command = (HealingCommand) request.command(); helper.assertValueEqual(command.target(), holder, "original weapon owner");
            var result = MinecraftHealingExecutor.execute(request.id().toString(), target, command); heals++;
            if (loseReceipt) throw new IllegalStateException("Injected unknown physical healing result after weapon payment");
            return result;
        }
        void restore() {
            var saved = host.checkpoint();
            var entries = saved.domains().entrySet().stream().map(e -> new CheckpointArchive.Entry("test:weapon", e.getKey(), 0, e.getValue(), Set.of())).toList();
            var decoded = CheckpointArchive.decode(CheckpointArchive.coordinated(entries, saved).encode()).resourceCheckpoint().orElseThrow();
            var policies = new TreeMap<String, ResourceSessionAuthority.RestoreBinding>();
            for (String domain : sessions.keySet()) policies.put(domain, new ResourceSessionAuthority.RestoreBinding(program, clock(), 1, r -> world(domain, r)));
            var restored = ResourceSessionAuthority.restore(decoded, policies); host = restored.authority(); restored.bindings().forEach((id, b) -> sessions.put(id, b.session()));
        }
    }
    @GameCase public void coordinatedWeaponRestoresItsPaymentAndUsesRemoteResourcesForLaterPhysicalHealing(GameTestHelper helper) {
        var f = new RoutedWeapon(helper);
        try {
            f.fire(0, "first"); near(helper, f.target.getHealth(), 3, "first actual heal");
            helper.assertValueEqual(f.state().ammunition().get("gun").magazine(), 3, "first payment");
            f.restore(); near(helper, f.target.getHealth(), 3, "restore does not replay native healing");
            f.host.grant(f.key(), .25, .25); f.fire(250_000, "second");
            near(helper, f.target.getHealth(), 5, "new remote balance determines actual heal");
            helper.assertValueEqual(f.state().ammunition().get("gun").magazine(), 1, "second payment uses current remote balance");
            helper.assertValueEqual(f.heals, 2, "exact native write count");
            helper.assertTrue(f.state().resources().isEmpty(), "remote balance copied into weapon domain");
            f.host.checkpoint(); helper.succeed();
        } finally { f.target.discard(); }
    }
    @GameCase public void coordinatedWeaponLostNativeReceiptRetainsAmmoPaymentAndNeverRepeatsHealing(GameTestHelper helper) {
        var f = new RoutedWeapon(helper);
        try {
            f.loseReceipt = true; refused(helper, () -> f.fire(0, "first"));
            near(helper, f.target.getHealth(), 3, "actual heal committed before lost receipt");
            helper.assertValueEqual(f.state().ammunition().get("gun").magazine(), 3, "payment retained");
            refused(helper, f.host::checkpoint); refused(helper, () -> f.fire(250_000, "retry"));
            helper.assertValueEqual(f.heals, 1, "native healing not replayed"); helper.succeed();
        } finally { f.target.discard(); }
    }
    @GameCase public void restoredPausedPeriodicHealingUsesOriginalRemainderAndDoesNotRepeatCompletedHealing(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2);
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("periodic")).getOrThrow();
        String id = target.getUUID().toString();
        var source = new EffectSource("perk", "test:periodic", id, new BuffInstance.Origin(id, "perk", "weapon", ""), Set.of());
        var calls = new ArrayList<RuleEngine.OperationId>();
        java.util.function.Function<RuleEngine.WorldRequest, RuleEngine.ActionResult> world = request -> {
            calls.add(request.id());
            var command = (HealingCommand) request.command();
            helper.assertValueEqual(command.target(), id, "retained recipient");
            return MinecraftHealingExecutor.execute(request.id().toString(), target, command);
        };
        var session = new EffectSession(program, clock(), 1, EffectState.empty().withSource(source), world);
        session.start(0, signal(source, "test:apply")); session.observe(70_001, List.of()); near(helper, target.getHealth(), 3, "first pulse");
        session.start(100_000, signal(source, "chorus:weapon_stowed"));
        var beforeSave = session.checkpoint();
        var directory = java.nio.file.Files.createTempDirectory("chorus-checkpoint-world-");
        EffectSession.Checkpoint saved;
        try {
            var path = directory.resolve("session.json");
            com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.write(path, beforeSave);
            saved = com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.read(path);
            helper.assertValueEqual(saved, beforeSave, "complete checkpoint file round-trip");
        } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("session.json")); java.nio.file.Files.delete(directory);
        }
        var restored = EffectSession.restore(new CompiledEffects(saved.program()), clock(), 256, saved, world);
        helper.assertValueEqual(restored.checkpoint(), saved, "restored state and identity counters");
        near(helper, target.getHealth(), 3, "restore performed no world action");
        restored.observe(200_000, List.of()); near(helper, target.getHealth(), 3, "paused pulse did not catch up");
        restored.start(200_000, signal(source, "chorus:weapon_drawn"));
        restored.observe(240_001, List.of()); near(helper, target.getHealth(), 3, "remainder has not elapsed");
        restored.observe(240_002, List.of()); near(helper, target.getHealth(), 4, "resumed pulse");
        helper.assertValueEqual(calls.size(), 2, "only two actual world writes");
        helper.assertTrue(calls.getLast().frame() >= saved.timeline().nextFrame(), "Restored frame ID reused old space");
        helper.succeed();
    }
    @GameCase public void actualHealingWithLostAcknowledgementCannotBeSavedAsSettledOrRepeated(GameTestHelper helper) throws Exception {
        var target = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 2, 2); target.setHealth(2);
        var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("periodic")).getOrThrow();
        String id = target.getUUID().toString();
        var source = new EffectSource("perk", "test:periodic", id, new BuffInstance.Origin(id, "perk", "weapon", ""), Set.of());
        var calls = new ArrayList<RuleEngine.OperationId>();
        var session = new EffectSession(program, clock(), 1, EffectState.empty().withSource(source), request -> {
            calls.add(request.id()); MinecraftHealingExecutor.execute(request.id().toString(), target, (HealingCommand) request.command());
            throw new IllegalStateException("Lost acknowledgement after actual healing");
        });
        session.start(0, signal(source, "test:apply")); refused(helper, () -> session.observe(70_001, List.of()));
        near(helper, target.getHealth(), 3, "unknown operation still changed the world");
        helper.assertTrue(session.state().engine().pending().isPresent(), "Pending command evidence was lost");
        refused(helper, session::checkpoint); refused(helper, () -> session.observe(140_002, List.of()));
        helper.assertValueEqual(calls.size(), 1, "no automatic replay"); near(helper, target.getHealth(), 3, "no repeated healing");
        helper.succeed();
    }
}
