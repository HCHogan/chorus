package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.platform.minecraft.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Real hit/death pipeline; weapon identity and reload completion are explicitly supplied by this test host. */
public class VoltshotGameTest {
    private static final String WINDOW = "chorus_d2:voltshot_window", READY = "chorus_d2:voltshot_ready", JOLT = "chorus_d2:jolt";
    private static String id(LivingEntity entity) { return entity.getUUID().toString(); }
    private static final class Harness implements AutoCloseable {
        final GameTestHelper h;
        final List<LivingEntity> entities = new ArrayList<>();
        final LivingEntity owner, other, target, neighbor;
        final EffectSource a, b, foreign;
        final List<DamageCommand> chains = new ArrayList<>();
        final List<DamageReceipt> damage = new ArrayList<>();
        final List<StatusResult.Checked> checks = new ArrayList<>();
        final MinecraftEffectRuntime runtime;
        EffectSource current;
        boolean denied;
        Harness(GameTestHelper h, EffectState.Mode mode) throws Exception {
            this(h, mode, fixture());
        }
        private static CompiledEffects fixture() throws Exception {
            var parts = new ArrayList<EffectProgram>();
            for (String name : List.of("voltshot", "jolt")) try (var reader = new InputStreamReader(Objects.requireNonNull(VoltshotGameTest.class.getResourceAsStream("/effects/" + name + ".json")), StandardCharsets.UTF_8)) {
                parts.add(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
            }
            return CompiledEffects.link(parts);
        }
        Harness(GameTestHelper h, EffectState.Mode mode, CompiledEffects program) {
            this.h = h; owner = cow(1, 200); other = cow(2, 232); target = cow(4, 200); neighbor = cow(5, 200);
            a = source("normal", owner, false); b = source("enhanced", owner, true); foreign = source("foreign", other, false); current = a;
            var damageType = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("chorus_gametest:delayed")));
            var world = new MinecraftWorldActions(h.getLevel(), this::resolve, command -> new DamageSource(damageType, null, resolve(command.source().owner())), (_, _) -> !denied, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty().withMode(mode).withSource(a).withSource(b),
                    new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                        var result = world.apply(request);
                        if (request.command() instanceof DamageCommand command) { chains.add(command); damage.add((DamageReceipt) result); }
                        if (result instanceof StatusResult.Checked checked) checks.add(checked);
                        return result;
                    }, (victim, _, amount) -> new DamageCommand(id(victim), current.origin(), amount, "minecraft:generic", Set.of("chorus:weapon_damage"), Set.of("chorus:weapon_kill"), false));
        }
        EffectSource source(String weapon, LivingEntity owner, boolean enhanced) {
            return new EffectSource(weapon, "chorus_d2:voltshot", id(owner), new BuffInstance.Origin(id(owner), "perk-" + weapon, weapon, ""), enhanced ? Set.of("chorus:enhanced") : Set.of());
        }
        LivingEntity resolve(String ref) { return h.getLevel().getEntity(UUID.fromString(ref)) instanceof LivingEntity e ? e : null; }
        LivingEntity cow(int x, int y) {
            var e = h.spawnWithNoFreeWill(EntityTypes.COW, x, y, 4); e.setNoGravity(true); e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            e.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1); e.setHealth(100); entities.add(e);
            h.assertTrue(resolve(id(e)) == e, "test entity must be loaded"); return e;
        }
        boolean hit(LivingEntity victim, EffectSource source, float amount) {
            current = source; victim.damageCooldownTime = 0;
            boolean applied = victim.hurtServer(h.getLevel(), new DamageSource(h.getLevel().damageSources().generic().typeHolder(), null, resolve(source.origin().owner())), amount);
            settled(); return applied;
        }
        void kill(EffectSource source) { var victim = cow(3, 248); victim.setHealth(1); h.assertTrue(hit(victim, source, 2) && !victim.isAlive(), "real weapon kill required"); victim.discard(); }
        void signal(String type, EffectSource source) { runtime.start(new RuleEngine.Signal(type, new EffectEvent(source.holder(), source.holder(), source.origin(), Set.of(), Map.of()))); settled(); }
        void reload(EffectSource source) { signal("chorus:reload_finished", source); }
        void stow(EffectSource source) { signal("chorus:weapon_stowed", source); }
        void arm(EffectSource source) { kill(source); reload(source); }
        Optional<BuffInstance> buff(String definition, EffectSource source) { return runtime.state().engine().domain().buffs().instances().values().stream()
                .filter(v -> v.definition().id().equals(definition) && v.key().holder().equals(source.holder()) && v.origin().weapon().equals(source.origin().weapon())).findFirst(); }
        Optional<BuffInstance> jolt(LivingEntity target) { return runtime.state().engine().domain().buffs().instances().values().stream()
                .filter(v -> v.definition().id().equals(JOLT) && v.key().holder().equals(id(target))).findFirst(); }
        void settled() { h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "Voltshot runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); entities.forEach(LivingEntity::discard); }
    }
    /** Reuse real native damage acceptance with the exact immutable program returned by the data-pack catalogue. */
    static void verifyImportedCatalogue(GameTestHelper h, CompiledEffects program) {
        for (var mode : EffectState.Mode.values()) try (var test = new Harness(h, mode, program)) {
            h.assertTrue(test.runtime.program() == program, "import test must execute the loaded registry program");
            test.arm(test.a); h.assertTrue(test.buff(READY, test.a).isPresent(), "imported weapon rules armed");
            float threshold = mode == EffectState.Mode.PVP ? 4.5f : 11.5f; double chain = mode == EffectState.Mode.PVP ? 5.1 : 11.9;
            test.hit(test.target, test.a, 1); test.hit(test.target, test.foreign, threshold - 1);
            near(h, test.target.getHealth(), 100 - threshold - chain, "imported Jolt center damage");
            near(h, test.neighbor.getHealth(), 100 - chain, "imported Jolt neighbor damage");
            h.assertValueEqual(test.chains.size(), 2, "one imported shared definition produced two actual components");
            h.assertTrue(test.buff(READY, test.a).isEmpty(), "imported ready state consumed"); test.settled();
        }
    }
    @GameCase public void nativeKillReloadAndHitApplySharedJoltWhileKeepingAnotherWeaponsChargeAndTriggerOwnership(GameTestHelper h) throws Exception {
        for (var mode : EffectState.Mode.values()) try (var test = new Harness(h, mode)) {
            test.kill(test.a); test.reload(test.b); h.assertTrue(test.buff(READY, test.b).isEmpty(), "wrong weapon reload activated");
            test.stow(test.a); test.reload(test.a); test.arm(test.b); test.stow(test.a); test.stow(test.b);
            h.assertValueEqual(test.buff(READY, test.a).orElseThrow().deadline(), 7_000_000L, "normal ready lifetime");
            h.assertValueEqual(test.buff(READY, test.b).orElseThrow().deadline(), 8_000_000L, "enhanced ready lifetime");
            h.assertTrue(test.hit(test.target, test.a, 1), "applying shot rejected");
            h.assertTrue(test.buff(READY, test.a).isEmpty() && test.buff(READY, test.b).isPresent(), "only applying weapon spent its charge");
            near(h, test.jolt(test.target).orElseThrow().components().numbers().get("damage"), 1, "Voltshot's applying shot counts once");
            h.assertValueEqual(test.jolt(test.target).orElseThrow().deadline(), mode == EffectState.Mode.PVP ? 5_000_000L : 10_000_000L, "shared Jolt mode duration");
            float threshold = mode == EffectState.Mode.PVP ? 4.5f : 11.5f; double chain = mode == EffectState.Mode.PVP ? 5.1 : 11.9;
            h.assertTrue(test.hit(test.target, test.foreign, threshold - 1), "trigger shot rejected");
            near(h, test.target.getHealth(), 100 - threshold - chain, "actual center chain"); near(h, test.neighbor.getHealth(), 100 - chain, "actual neighbor chain");
            h.assertValueEqual(test.chains.size(), 2, "two chain components"); h.assertTrue(test.chains.stream().allMatch(c -> c.source().equals(test.foreign.origin()) && c.killTags().isEmpty() && !c.tags().contains("chorus:weapon_damage")), "Jolt attribution or credit wrong");
            h.assertValueEqual(test.jolt(test.target).orElseThrow().origin(), test.a.origin(), "Jolt applier unchanged");
            h.assertTrue(test.buff(READY, test.b).isPresent(), "Jolt consumed another weapon's charge");
        }
        h.succeed();
    }
    @GameCase public void cancellationPreservesReadyButDeniedOrLethalHitsConsumeItAndRealWeaponDeathReopensWindow(GameTestHelper h) throws Exception {
        String cancelled = "voltshot-cancel-" + UUID.randomUUID(); TestDamageHooks.ALLOW_DAMAGE.register((victim, _, _) -> !victim.entityTags().contains(cancelled));
        try (var test = new Harness(h, EffectState.Mode.PVE)) {
            test.arm(test.a); test.target.addTag(cancelled);
            h.assertTrue(!test.hit(test.target, test.a, 1), "cancelled hit applied"); h.assertTrue(test.buff(READY, test.a).isPresent() && test.checks.isEmpty(), "cancelled damage consumed ready or checked a status");
            test.target.removeTag(cancelled); test.denied = true; h.assertTrue(test.hit(test.target, test.a, 1), "denied-status hit should still damage");
            h.assertTrue(test.buff(READY, test.a).isEmpty() && test.jolt(test.target).isEmpty(), "denied status should spend the hit without applying Jolt");
            h.assertValueEqual(test.checks.getLast().decision(), StatusResult.Decision.DENIED, "native status policy");
            test.denied = false; test.reload(test.a); test.target.setHealth(1); h.assertTrue(test.hit(test.target, test.a, 2), "lethal hit rejected");
            h.assertTrue(!test.target.isAlive() && test.buff(READY, test.a).isEmpty() && test.jolt(test.target).isEmpty(), "fatal first application is consumed but not applied");
            h.assertValueEqual(test.checks.getLast().decision(), StatusResult.Decision.DEAD, "dead target qualification");
            h.assertTrue(test.buff(WINDOW, test.a).isPresent(), "actual weapon death did not open window"); test.reload(test.a);
            h.assertTrue(test.buff(READY, test.a).isPresent(), "reload after actual weapon death failed");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:voltshot_credit", maxTicks = 120)
    public void actualJoltKillAfterWeaponWindowExpiresCannotRearmVoltshot(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVE);
        try {
            test.arm(test.a); test.arm(test.b); test.stow(test.a); test.stow(test.b); test.neighbor.setHealth(1);
            h.runAfterDelay(106, () -> {
                try (test) {
                    h.assertValueEqual(test.runtime.nowMicros(), 5_300_000L, "exact kill-window deadline");
                    h.assertTrue(test.buff(WINDOW, test.a).isEmpty() && test.buff(WINDOW, test.b).isEmpty(), "weapon windows did not expire while stowed");
                    h.assertTrue(test.hit(test.target, test.a, 11.5f), "ready shot rejected");
                    h.assertTrue(!test.neighbor.isAlive() && test.damage.stream().anyMatch(DamageReceipt::lethal), "actual Jolt kill required");
                    h.assertTrue(test.buff(WINDOW, test.a).isEmpty(), "Jolt kill received weapon-kill credit");
                    h.assertTrue(test.buff(READY, test.a).isEmpty() && test.buff(READY, test.b).isPresent(), "wrong charge consumed");
                    test.reload(test.a); h.assertTrue(test.buff(READY, test.a).isEmpty(), "Jolt kill allowed rearming"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
    @GameCase(environment = "chorus_gametest:voltshot_expiry", maxTicks = 170)
    public void readyDurationsKeepRunningWhileStowedAndExpireSeparatelyOnRealTicks(GameTestHelper h) throws Exception {
        var test = new Harness(h, EffectState.Mode.PVE);
        try {
            test.arm(test.a); test.arm(test.b); test.stow(test.a); test.stow(test.b);
            h.runAfterDelay(139, () -> {
                try { test.settled(); h.assertTrue(test.buff(READY, test.a).isPresent() && test.buff(READY, test.b).isPresent(), "ready expired before seven seconds"); }
                catch (Throwable error) { test.close(); throw error; }
            });
            h.runAfterDelay(140, () -> {
                try {
                    h.assertValueEqual(test.runtime.nowMicros(), 7_000_000L, "normal exact deadline");
                    h.assertTrue(test.buff(READY, test.a).isEmpty() && test.buff(READY, test.b).isPresent(), "normal/enhanced duration separation");
                    h.assertTrue(test.hit(test.target, test.a, 1), "ordinary hit rejected"); h.assertTrue(test.jolt(test.target).isEmpty(), "expired normal charge applied Jolt");
                } catch (Throwable error) { test.close(); throw error; }
            });
            h.runAfterDelay(160, () -> {
                try (test) {
                    h.assertValueEqual(test.runtime.nowMicros(), 8_000_000L, "enhanced exact deadline");
                    h.assertTrue(test.buff(READY, test.b).isEmpty(), "stow paused enhanced duration");
                    h.assertTrue(test.hit(test.target, test.b, 1), "ordinary enhanced hit rejected"); h.assertTrue(test.jolt(test.target).isEmpty(), "expired enhanced charge applied Jolt"); h.succeed();
                }
            });
        } catch (Throwable error) { test.close(); throw error; }
    }
}
