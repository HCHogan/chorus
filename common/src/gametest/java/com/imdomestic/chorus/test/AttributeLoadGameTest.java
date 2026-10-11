package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.NativeAttributeGameTest.*;
import com.imdomestic.chorus.effect.EffectClock;
import com.imdomestic.chorus.platform.minecraft.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.storage.*;

/** Real Entity.load ordering: UUID and permanent attributes precede projected maximum health. */
public class AttributeLoadGameTest {
    private static CompoundTag save(GameTestHelper h, LivingEntity entity) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        entity.saveWithoutId(output); return output.buildResult();
    }
    private static void load(GameTestHelper h, LivingEntity entity, CompoundTag data) {
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), data));
    }
    private static LivingEntity cow(GameTestHelper h) { return Objects.requireNonNull(EntityTypes.COW.create(h.getLevel(), EntitySpawnReason.LOAD)); }
    private static MinecraftEffectRuntime restore(GameTestHelper h, ProjectileGameTest.Harness t, MinecraftEffectRuntime.Checkpoint saved) {
        return MinecraftEffectRuntime.restore(h.getLevel(), t.runtime.program(), saved,
                new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), t.world, MinecraftEffectRuntime::nativeSource);
    }
    @GameCase public void unloadedMobRegainsMaximumBeforeSavedHealthIsClampedWithoutSerializingTransientModifiers(GameTestHelper h) throws Exception {
        try (var t = harness(h)) {
            var target = t.cow(3, 43, 3); var maximum = target.getAttribute(Attributes.MAX_HEALTH); maximum.setBaseValue(20);
            var foreign = new AttributeModifier(Identifier.parse("test:permanent_capacity"), 2, AttributeModifier.Operation.ADD_VALUE);
            maximum.addPermanentModifier(foreign); target.setHealth(19); bind(t, "inputs", "inputs", t.owner); signal(t, "health", target);
            near(h, target.getHealth(), 32, "pre-save health includes temporary and permanent capacity");
            var nbt = save(h, target); var saved = t.runtime.checkpoint();
            h.assertTrue(!nbt.getListOrEmpty("attributes").toString().contains("projection/"), "Chorus transient modifier leaked into entity NBT");
            t.runtime.close(); target.discard();
            var control = cow(h); load(h, control, nbt); near(h, control.getHealth(), 22, "vanilla control clamps without restored rules"); control.discard();
            try (var runtime = restore(h, t, saved)) {
                var replacement = cow(h); t.entities.add(replacement);
                h.assertTrue(h.getLevel().getEntity(target.getUUID()) == null, "old entity is still indexed");
                var before = runtime.state(); load(h, replacement, nbt);
                h.assertValueEqual(replacement.getUUID(), target.getUUID(), "NBT restored stable holder identity before projection");
                h.assertTrue(h.getLevel().getEntity(replacement.getUUID()) == null, "test did not exercise an unindexed entity");
                near(h, replacement.getMaxHealth(), 32, "maximum rebuilt before native health load"); near(h, replacement.getHealth(), 32, "saved health retained");
                h.assertValueEqual(runtime.state(), before, "loading ran no rule, timer or healing action");
                var restoredMaximum = replacement.getAttribute(Attributes.MAX_HEALTH);
                h.assertTrue(restoredMaximum.hasModifier(foreign.id()), "native permanent modifier lost");
                h.assertValueEqual(projections(restoredMaximum).size(), 1, "one reconstructed modifier");
                h.assertTrue(h.getLevel().addFreshEntity(replacement), "replacement could not join world"); runtime.prepare();
                near(h, replacement.getHealth(), 32, "indexed reconciliation did not replay initialization");
                runtime.close(); near(h, restoredMaximum.getValue(), 22, "close removed only reconstructed temporary modifier");
                h.assertTrue(restoredMaximum.hasModifier(foreign.id()), "close removed foreign modifier");
            }
        }
        h.succeed();
    }
    @GameCase public void serverPlayerNbtKeepsBonusHealthBeforeLoginIndexesThePlayer(GameTestHelper h) throws Exception {
        try (var t = harness(h)) {
            t.owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20); t.owner.setHealth(19);
            bind(t, "inputs", "inputs", t.owner); signal(t, "health", t.owner);
            var nbt = save(h, t.owner); var saved = t.runtime.checkpoint();
            var cookie = CommonListenerCookie.createInitial(t.owner.getGameProfile(), false);
            t.runtime.close(); t.owner.discard();
            try (var runtime = restore(h, t, saved)) {
                var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
                try {
                    load(h, player, nbt); near(h, player.getMaxHealth(), 30, "player maximum before login"); near(h, player.getHealth(), 30, "player saved health before login");
                    h.assertTrue(h.getLevel().getEntity(player.getUUID()) == null, "player was already indexed");
                    h.assertValueEqual(runtime.state().engine().domain(), saved.effects().timeline().domain(), "player NBT did not run source initialization");
                    runtime.close(); near(h, player.getMaxHealth(), 20, "unindexed player still participates in projection cleanup");
                } finally { player.discard(); }
            }
        }
        h.succeed();
    }
    @GameCase public void repeatedEntityDataLoadsUseSavedLivenessAndDoNotStackOrHeal(GameTestHelper h) throws Exception {
        try (var t = harness(h)) {
            var target = t.cow(3, 43, 3); target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20); target.setHealth(19);
            bind(t, "inputs", "inputs", t.owner); signal(t, "health", target); target.setHealth(27);
            var data = save(h, target); var before = t.runtime.state();
            for (int i = 0; i < 3; i++) {
                target.setHealth(1); load(h, target, data); near(h, target.getHealth(), 27, "saved health rather than initialization heal");
                near(h, target.getMaxHealth(), 30, "repeated load did not stack capacity");
                h.assertValueEqual(projections(target.getAttribute(Attributes.MAX_HEALTH)).size(), 1, "one owned modifier after reloading attributes");
            }
            var dead = data.copy(); dead.putFloat("Health", 0); load(h, target, dead);
            near(h, target.getHealth(), 0, "dead NBT remained dead"); near(h, target.getMaxHealth(), 20, "no live projection for saved dead entity");
            // Data replacement may start from a currently dead object; constructor/current HP must not decide saved liveness.
            load(h, target, data); near(h, target.getHealth(), 27, "live saved data recovered bonus capacity from a zero-health object");
            h.assertValueEqual(t.runtime.state(), before, "data loads emitted no synthetic healing or death facts"); h.assertTrue(t.runtime.failure().isEmpty(), "data load failed runtime");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:attribute_load_expiry", maxTicks = 14)
    public void entityLoadedAfterBuffExpiryDoesNotReacquireItsSavedCapacity(GameTestHelper h) throws Exception {
        var t = harness(h); MinecraftEffectRuntime restored = null;
        try {
            var target = t.cow(3, 43, 3); target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20); target.setHealth(19);
            bind(t, "inputs", "inputs", t.owner); signal(t, "health", target); var data = save(h, target); var saved = t.runtime.checkpoint();
            t.runtime.close(); target.discard(); restored = restore(h, t, saved); var runtime = restored;
            h.runAfterDelay(6, () -> {
                try (runtime; t) {
                    runtime.prepare(); var before = runtime.state(); var replacement = cow(h); t.entities.add(replacement); load(h, replacement, data);
                    near(h, replacement.getMaxHealth(), 20, "expired state does not project old capacity"); near(h, replacement.getHealth(), 20, "vanilla clamped against current maximum");
                    h.assertValueEqual(runtime.state(), before, "late load did not resurrect expired buff");
                    h.assertTrue(runtime.failure().isEmpty(), "late load failed runtime"); h.succeed();
                }
            });
        } catch (Exception | Error error) { if (restored != null) restored.close(); t.close(); throw error; }
    }
    @GameCase public void loadingOneEntityDoesNotRewriteAnotherEntityOrActivateAnUnregisteredHolder(GameTestHelper h) throws Exception {
        try (var t = harness(h)) {
            bind(t, "fast", "fast", t.owner); var speed = t.owner.getAttribute(Attributes.MOVEMENT_SPEED);
            var original = projections(speed).getFirst(); var overwritten = new AttributeModifier(original.id(), .75, original.operation());
            speed.removeModifier(original.id()); speed.addTransientModifier(overwritten);
            var unrelated = cow(h); t.entities.add(unrelated); var data = save(h, unrelated); data.putFloat("Health", 80);
            load(h, unrelated, data); near(h, unrelated.getHealth(), unrelated.getMaxHealth(), "unregistered holder follows vanilla health loading");
            h.assertTrue(projections(unrelated.getAttribute(Attributes.MAX_HEALTH)).isEmpty(), "unregistered holder acquired attributes");
            h.assertValueEqual(speed.getModifier(original.id()), overwritten, "targeted load overwrote a foreign change on another actor");
            h.assertTrue(t.runtime.failure().isEmpty(), "unrelated projection was evaluated during target load");
            t.runtime.prepare(); h.assertTrue(t.runtime.failure().isPresent(), "normal full reconciliation still detects the conflict");
            h.assertValueEqual(speed.getModifier(original.id()), overwritten, "conflict overwrote foreign modifier");
            load(h, unrelated, data); near(h, unrelated.getHealth(), unrelated.getMaxHealth(), "failed unrelated runtime does not block vanilla load");
            var affected = save(h, t.owner); affected.putFloat("Health", 90); float health = t.owner.getHealth(); boolean rejected = false;
            try { load(h, t.owner, affected); } catch (net.minecraft.ReportedException expected) { rejected = true; }
            h.assertTrue(rejected, "failed runtime invented a valid maximum for its own holder");
            near(h, t.owner.getHealth(), health, "failed projected load did not overwrite health");
        }
        h.succeed();
    }
}
