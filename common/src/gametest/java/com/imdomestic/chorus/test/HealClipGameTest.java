package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import static com.imdomestic.chorus.test.AcrobatDodgeGameTest.ready;
import com.google.gson.*;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.equipment.Loadout;
import com.imdomestic.chorus.platform.minecraft.PlayerEquipment;
import com.imdomestic.chorus.registry.ChorusComponents;
import com.imdomestic.chorus.rule.RuleEngine;
import com.imdomestic.chorus.stat.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

/** Real owned ammunition, projectile deaths, reload completions, recipient HP and shared fragment credit. */
public class HealClipGameTest {
    static final String D = "chorus_d2:";
    static void prepare(JsonObject data, boolean incremental) {
        BenevolenceGameTest.prepareBenevolence(data);
        var weapon = ThreadedSpikeGameTest.json("heal_clip_weapon");
        data.add("equipment", weapon.get("equipment")); data.add("weapons", weapon.get("weapons"));
        if (incremental) {
            var insert = ThreadedSpikeGameTest.json("incremental_reload_settings").getAsJsonObject("insert");
            insert.getAsJsonObject("repeat").getAsJsonObject("value").addProperty("value", 1.2);
            data.getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("reload").add("insert", insert);
        }
        for (String section : List.of("profiles", "bundles", "buffs")) {
            var incoming = weapon.getAsJsonArray(section); var identities = new HashSet<String>();
            for (var value : incoming) identities.add(identity(value, section));
            var combined = new JsonArray(); for (var value : data.getAsJsonArray(section)) if (!identities.contains(identity(value, section))) combined.add(value);
            incoming.forEach(combined::add); data.add(section, combined);
        }
        GamblersDodgeGameTest.merge(data, ThreadedSpikeGameTest.json("heal_clip")); AscensionGameTest.version(data);
    }
    static String identity(JsonElement value, String section) {
        var object = value.getAsJsonObject(); return (section.equals("buffs") ? object.getAsJsonObject("definition") : object).get("id").getAsString();
    }
    static final class Scenario implements AutoCloseable {
        final GameTestHelper h; final ProjectileGameTest.Harness t; final PlayerTeam team;
        final LivingEntity ally, outside, enemy; final List<HealingCommand> heals = new ArrayList<>();
        Scenario(GameTestHelper h, boolean incremental) throws Exception {
            this.h = h; t = new ProjectileGameTest.Harness(h, "ascension", data -> prepare(data, incremental), true);
            var board = h.getLevel().getScoreboard(); team = board.addPlayerTeam("heal-clip-" + UUID.randomUUID());
            try {
                t.owner.setGameMode(GameType.SURVIVAL); t.owner.setHealth(20); board.addPlayerToTeam(t.owner.getScoreboardName(), team);
                ally = AcrobatDodgeGameTest.target(t, 15); outside = AcrobatDodgeGameTest.target(t, 15.001); enemy = AcrobatDodgeGameTest.target(t, 4);
                for (var e : List.of(ally, outside, enemy)) e.setHealth(20);
                board.addPlayerToTeam(ally.getScoreboardName(), team); board.addPlayerToTeam(outside.getScoreboardName(), team);
                var values = Map.of("window_seconds", new Measure(5.3, Unit.SECOND), "enhanced_window_seconds", new Measure(6.3, Unit.SECOND), "receipt_seconds", new Measure(60, Unit.SECOND));
                var equipment = PlayerEquipment.get(t.owner);
                for (String id : List.of("a", "b")) {
                    var stack = new ItemStack(Items.DIAMOND_SWORD); stack.set(ChorusComponents.EQUIPMENT.get(), new Loadout.Gear(id, "test:rifle", Map.of("perk", id.equals("a") ? "normal" : "enhanced"), values));
                    t.owner.getInventory().setItem(0, stack); equipment.swap(t.owner, id.equals("a") ? "test:primary" : "test:secondary", 0, equipment.revision());
                }
                equipment.draw(t.owner, Optional.of("test:primary"), equipment.revision());
                t.runtime.bind(BenevolenceGameTest.source(t.owner, D + "ember_of_benevolence", false));
                t.runtime.bind(BenevolenceGameTest.source(t.owner, "test:heal_clip_inputs", false));
                t.beforeWorld = r -> { if (r.command() instanceof HealingCommand c && c.tags().contains(D + "cure")) heals.add(c); };
            } catch (Exception | Error e) { close(); throw e; }
        }
        String owner() { return t.owner.getUUID().toString(); }
        EffectState state() { return t.runtime.state().engine().domain(); }
        Optional<BuffInstance> window() { return state().buffs().instances().values().stream().filter(b -> b.key().definition().equals(D + "heal_clip_window") && b.origin().weapon().equals("a")).findFirst(); }
        void input(String event, String weapon) { t.runtime.start(new RuleEngine.Signal("test:" + event, new EffectEvent(owner(), owner(), new BuffInstance.Origin(owner(), "test-input", weapon, ""), Set.of(), Map.of()))); ShieldThrowGameTest.settled(t); }
        LivingEntity shoot() {
            var victim = t.cow(2.5, 46, 3.5); victim.setHealth(5); h.assertTrue(t.resolve(victim.getUUID().toString()) == victim, "shot victim index");
            t.runtime.fire(t.owner); ShieldThrowGameTest.settled(t); return victim;
        }
        void afterKill(LivingEntity victim, Runnable action) {
            later(10, () -> { ShieldThrowGameTest.settled(t); h.assertTrue(!victim.isAlive() && t.receipts.getLast().lethal() && window().isPresent(), "actual lethal weapon receipt opens its window"); victim.discard(); action.run(); });
        }
        void later(int ticks, Runnable action) { h.runAfterDelay(ticks, () -> { try { action.run(); } catch (RuntimeException | Error e) { close(); throw e; } }); }
        void finish(int ticks, Runnable checks) { later(ticks, () -> { try { ShieldThrowGameTest.settled(t); checks.run(); h.succeed(); } finally { close(); } }); }
        @Override public void close() { h.getLevel().getScoreboard().removePlayerTeam(team); t.close(); }
    }
    @GameCase(environment = "chorus_gametest:heal_clip_chain", maxTicks = 100)
    public void physicalShotKillReloadAndAlliedCureComposeWithBenevolenceAtTheRadiusBoundary(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h, false);
            try { s.afterKill(s.shoot(), () -> {
                s.t.runtime.reload(s.t.owner); s.finish(8, () -> {
                    near(h, s.t.owner.getHealth(), 32, "self Cure x2"); near(h, s.ally.getHealth(), 26, "ally Cure x1 at 15 m");
                    near(h, s.outside.getHealth(), 20, "outside 15 m"); near(h, s.enemy.getHealth(), 20, "enemy excluded");
                    h.assertValueEqual(s.state().ammunition().get("a").magazine(), 5, "real magazine committed");
                    h.assertTrue(s.window().isEmpty() && BenevolenceGameTest.benefit(s.t, s.t.owner).isPresent(), "consumed window and confirmed allied benefit");
                    h.assertTrue(s.heals.stream().allMatch(c -> c.source().owner().equals(s.owner()) && c.source().weapon().equals("a")), "delayed Cure retains actual owner and weapon");
                });
            }); } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:heal_clip_refill", maxTicks = 100)
    public void ordinaryRefillAfterAnActualKillCannotImpersonateACompletedReload(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h, false);
            try { s.afterKill(s.shoot(), () -> { s.input("refill", "a"); s.finish(8, () -> {
                h.assertValueEqual(s.state().ammunition().get("a").magazine(), 5, "ordinary transfer happened"); h.assertTrue(s.window().isPresent() && s.heals.isEmpty(), "refill cannot consume window or heal"); near(h, s.ally.getHealth(), 20, "no invented Cure");
            }); }); } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:heal_clip_instant", maxTicks = 100)
    public void explicitlyQualifiedZeroTransferReloadUsesTheSameCureAndWeaponWindow(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h, false);
            try { s.afterKill(s.shoot(), () -> {
                s.input("refill", "a"); s.input("refill", "b"); int reserve = s.state().ammunition().get("a").reserve().orElseThrow().rounds();
                s.input("heal_clip_instant", ""); s.finish(4, () -> {
                    h.assertValueEqual(s.state().ammunition().get("a").reserve().orElseThrow().rounds(), reserve, "zero transfer spent no reserves");
                    h.assertValueEqual(s.heals.size(), 4, "only weapon with kill granted two pulses per recipient"); near(h, s.t.owner.getHealth(), 32, "self Cure"); near(h, s.ally.getHealth(), 26, "ally Cure");
                });
            }); } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:heal_clip_unknown", maxTicks = 100)
    public void unknownNativeCureKeepsCommittedAmmoAndHealthWithoutReplayingOrInventingCredit(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h, false);
            try { s.afterKill(s.shoot(), () -> {
                s.t.failAfterHealing = true; s.t.runtime.reload(s.t.owner);
                s.later(8, () -> { try {
                    h.assertTrue(s.t.runtime.failure().isPresent(), "unknown healing stopped execution"); h.assertValueEqual(s.heals.size(), 1, "no repeated or second healing pulse");
                    var command = s.heals.getFirst(); near(h, s.t.resolve(command.target()).getHealth(), 20 + command.amount(), "native write remains committed");
                    h.assertValueEqual(s.state().ammunition().get("a").magazine(), 5, "ammo remains committed"); h.assertTrue(s.window().isEmpty() && BenevolenceGameTest.benefit(s.t, s.t.owner).isEmpty(), "no window replay or unconfirmed benefit"); h.succeed();
                } finally { s.close(); } });
            }); } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
    @GameCase(environment = "chorus_gametest:heal_clip_insert", maxTicks = 100)
    public void incrementalReloadConsumesOneWindowAndLaterInsertionsDoNotRepeatThatKill(GameTestHelper h) {
        ready(h, () -> {
            var s = new Scenario(h, true);
            try { s.afterKill(s.shoot(), () -> {
                s.t.runtime.reload(s.t.owner); s.finish(33, () -> {
                    h.assertValueEqual(s.state().ammunition().get("a").magazine(), 2, "two actual insertions after emptying magazine");
                    h.assertValueEqual(s.heals.size(), 4, "one kill gives one self and ally Cure activation"); near(h, s.t.owner.getHealth(), 32, "no healing from later insertion alone");
                });
            }); } catch (Exception | Error e) { s.close(); throw e; }
        });
    }
}
