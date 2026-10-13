package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.platform.minecraft.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Real input, healing and scheduled refunds; synthetic amounts do not specify Ascension behavior. */
public class AbilityCrossSlotCostGameTest {
    static final class Harness implements AutoCloseable {
        final GameTestHelper h; final ServerPlayer player; final String holder; final MinecraftEffectRuntime runtime;
        final List<HealingCommand> heals = new ArrayList<>(); boolean failWorld;
        Harness(GameTestHelper h) throws Exception {
            this.h = h; player = h.makeMockServerPlayerInLevel(); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setPos(h.absoluteVec(new Vec3(3, 40, 3))); player.setNoGravity(true); player.setOnGround(false);
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(10); holder = player.getUUID().toString();
            var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, ThreadedSpikeGameTest.json("ability_cross_slot_cost")).getOrThrow();
            var world = new MinecraftWorldActions(h.getLevel(), id -> holder.equals(id) ? player : null, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            runtime = MinecraftEffectRuntime.install(h.getLevel(), program, EffectState.empty(), new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), request -> {
                var result = world.apply(request);
                if (request.command() instanceof HealingCommand heal) { heals.add(heal); if (failWorld) throw new IllegalStateException("Unknown cross-slot healing result"); }
                return result;
            }, MinecraftEffectRuntime::nativeSource);
        }
        EffectState state() { return runtime.state().engine().domain(); }
        double energy(String resource) { return state().resources().get(new ResourceState.Key(holder, "test:" + resource)).value(); }
        void choose(String slot, String ability) {
            var before = state().abilities().getOrDefault(holder, AbilityLoadout.EMPTY); var slots = new HashMap<>(before.slots());
            if (ability == null) slots.remove("test:" + slot); else slots.put("test:" + slot, "test:" + ability);
            runtime.abilities(new AbilityChange(holder, before, new AbilityLoadout(slots)));
        }
        void bind(String bundle) { runtime.bind(new EffectSource(bundle, "test:" + bundle, holder, new BuffInstance.Origin(holder, bundle, "", ""), Set.of())); }
        AbilityUse.Receipt use() { return runtime.useAbility(player, "test:air_move"); }
        void healthy() { runtime.prepare(); h.assertTrue(runtime.failure().isEmpty() && runtime.state().idle(), "cross-slot runtime failed: " + runtime.failure()); }
        @Override public void close() { runtime.close(); player.discard(); }
    }
    @GameCase public void serverCommandPaysCurrentOtherSelectionWhileOnlyTheAirActionRuns(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.choose("air_move", "air_plain"); h.assertTrue(t.state().resources().isEmpty(), "air selection invented an account");
            t.choose("grenade", "a"); t.bind("chain"); var keys = t.state().resources().keySet();
            h.getLevel().getServer().getCommands().getDispatcher().execute("chorus ability use test:air_move", t.player.createCommandSourceStack().withSuppressedOutput().withPermission(PermissionSet.NO_PERMISSIONS));
            near(h, t.energy("energy"), 1, "declared base cost was not paid"); near(h, t.energy("converted_energy"), 2, "donor replacement changed account");
            t.choose("grenade", "b"); var receipt = t.use();
            h.assertValueEqual(receipt.slot(), "test:air_move", "cost selection changed input slot"); h.assertValueEqual(receipt.resolved(), "test:air_plain", "cost selection changed resolved ability");
            near(h, t.energy("other"), 1.5, "current donor cost not read"); near(h, t.player.getHealth(), 18, "donor effects also ran");
            h.assertTrue(t.heals.size() == 2 && t.heals.stream().allMatch(c -> c.source().ability().equals("test:air_plain")), "donor credit replaced air credit");
            h.assertValueEqual(t.state().resources().keySet(), keys, "cast invented extra pool"); t.healthy();
        } h.succeed();
    }
    @GameCase public void groundedMissingCostAndExhaustedInputsDoNotRunWorldEffects(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.choose("air_move", "air_plain");
            h.assertValueEqual(t.use().outcome(), AbilityUse.Outcome.NO_BASE_COST, "missing donor became free");
            for (String donor : List.of("free", "relay")) { t.choose("grenade", donor); h.assertValueEqual(t.use().outcome(), AbilityUse.Outcome.NO_BASE_COST, "costless/recursive policy became free"); }
            t.choose("grenade", "a"); t.player.setOnGround(true);
            h.assertValueEqual(t.use().outcome(), AbilityUse.Outcome.CONDITION, "grounded action accepted"); near(h, t.energy("energy"), 2, "grounded input paid");
            h.assertTrue(t.heals.isEmpty(), "rejection ran healing"); t.player.setOnGround(false); t.use(); t.use();
            h.assertValueEqual(t.use().outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "exhausted donor accepted");
            h.assertValueEqual(t.heals.size(), 2, "exhausted cast ran world effect"); near(h, t.energy("energy"), 0, "wrong total payment"); t.healthy();
        } h.succeed();
    }
    @GameCase(environment = "chorus_gametest:cross_slot_refund", maxTicks = 12)
    public void realTicksRefundThePaidAccountAfterBothSelectionsChange(GameTestHelper h) throws Exception {
        var t = new Harness(h);
        try {
            t.choose("grenade", "a"); t.choose("air_move", "air"); t.use(); near(h, t.energy("energy"), 1, "payment before delayed refund");
            t.choose("grenade", "b"); t.choose("air_move", null);
            h.runAfterDelay(5, () -> { try (t) {
                t.healthy(); near(h, t.energy("energy"), 2, "refund lost original account"); near(h, t.energy("other"), 2, "refund redirected to current donor");
                near(h, t.player.getHealth(), 14, "delayed payment reran action"); h.assertTrue(t.state().retainedCosts().isEmpty() && t.state().timers().isEmpty(), "refund right or timer leaked"); h.succeed();
            }});
        } catch (Exception | Error e) { t.close(); throw e; }
    }
    @GameCase public void unknownWorldResultKeepsPaidEnergyAndActualHealingWithoutReplay(GameTestHelper h) throws Exception {
        try (var t = new Harness(h)) {
            t.choose("grenade", "b"); t.choose("air_move", "air_plain"); t.failWorld = true; boolean failed = false;
            try { t.use(); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.runtime.failure().isPresent() && t.runtime.state().engine().pending().isPresent(), "unknown result did not stop runtime");
            near(h, t.energy("other"), 1.5, "unknown result undid payment"); near(h, t.player.getHealth(), 14, "actual world result lost");
            t.failWorld = false; failed = false; try { t.use(); } catch (IllegalStateException expected) { failed = true; }
            h.assertTrue(failed && t.heals.size() == 1, "unknown request replayed"); near(h, t.energy("other"), 1.5, "failed retry paid again");
        } h.succeed();
    }
    @GameCase public void airActionPaysRealDodgeAccountAndTriggersEquippedAttractionOnlyOnce(GameTestHelper h) throws Exception {
        try (var t = PowerfulAttractionGameTest.open(h, data -> {
            var ability = ThreadedSpikeGameTest.json("ability_cross_slot_cost").getAsJsonArray("abilities").asList().stream()
                    .map(v -> v.getAsJsonObject()).filter(a -> a.get("id").getAsString().equals("test:air_plain")).findFirst().orElseThrow().deepCopy();
            ability.addProperty("cost_slot", "chorus_d2:class"); var tags = new com.google.gson.JsonArray(); tags.add("chorus:class_ability"); ability.add("tags", tags);
            data.getAsJsonArray("abilities").add(ability);
        })) {
            PowerfulAttractionGameTest.equip(t, "powerful_attraction"); PowerfulAttractionGameTest.choose(t, "chorus_d2:marksman_dodge");
            String holder = t.owner.getUUID().toString(); var before = t.runtime.state().engine().domain().abilities().get(holder); var slots = new HashMap<>(before.slots()); slots.put("test:air_move", "test:air_plain");
            t.runtime.abilities(new AbilityChange(holder, before, new AbilityLoadout(slots))); t.owner.setOnGround(false);
            var orb = PowerfulAttractionGameTest.orb(t, 8); int[] queries = {0}; t.beforeWorld = q -> { if (q.command() instanceof com.imdomestic.chorus.effect.object.PickupCollection.Query) queries[0]++; };
            var r = t.runtime.useAbility(t.owner, "test:air_move"); ShieldThrowGameTest.settled(t);
            h.assertValueEqual(r.outcome(), AbilityUse.Outcome.ACCEPTED, "air action rejected"); h.assertValueEqual(r.slot(), "test:air_move", "input identity changed");
            near(h, t.runtime.state().engine().domain().resources().get(new ResourceState.Key(holder, "chorus_d2:marksman_dodge_energy")).value(), 0, "real class charge not paid");
            h.assertTrue(orb.isRemoved() && queries[0] == 1, "accepted class-tagged air action did not collect once"); near(h, t.owner.getHealth(), 104, "air effect did not run");
            h.assertValueEqual(t.runtime.useAbility(t.owner, "test:air_move").outcome(), AbilityUse.Outcome.INSUFFICIENT_ENERGY, "exhausted real charge accepted"); h.assertValueEqual(queries[0], 1, "rejection collected again");
        } h.succeed();
    }
}
