package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.combat.HealingCommand;
import com.imdomestic.chorus.effect.data.EffectCodecs;
import com.imdomestic.chorus.effect.target.EntityQuery;
import com.imdomestic.chorus.platform.minecraft.MinecraftWorldActions;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public class EntityObservationGameTest {
    private static EntityQuery.Result inspect(MinecraftWorldActions world, String target) {
        return (EntityQuery.Result) world.apply(new RuleEngine.WorldRequest(new RuleEngine.OperationId(1, 0, 0), new EntityQuery(target)));
    }
    @GameCase public void observationsDistinguishPlayersDeadEntitiesMissingRemovedAndForeignDimensions(GameTestHelper h) {
        var cow = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 80, 2); var player = h.makeMockServerPlayerInLevel();
        try {
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); cow.setHealth(8);
            cow.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0)); cow.setAbsorptionAmount(3);
            var world = new MinecraftWorldActions(h.getLevel(), ref -> switch (ref) { case "cow" -> cow; case "player" -> player; default -> null; },
                    _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var before = inspect(world, "cow"); near(h, before.available().health(), 8, "native health"); near(h, before.available().maximumHealth(), 40, "native max health");
            near(h, before.available().absorption(), 3, "native absorption"); h.assertTrue(before.available().alive() && !before.available().player(), "living nonplayer");
            h.assertTrue(inspect(world, "player").available().player(), "real player identity");
            h.assertTrue(inspect(world, "unknown").view().isEmpty(), "missing identity");
            var other = Objects.requireNonNull(h.getLevel().getServer().getLevel(Level.NETHER));
            var wrongDimension = new MinecraftWorldActions(other, _ -> cow, _ -> other.damageSources().generic(), (_, _) -> true, _ -> {});
            h.assertTrue(inspect(wrongDimension, "cow").view().isEmpty(), "foreign dimension must be unavailable");
            cow.setHealth(0); var dead = inspect(world, "cow"); h.assertTrue(dead.view().isPresent() && !dead.available().alive(), "present dead entity is observable");
            near(h, dead.available().health(), 0, "dead health"); near(h, before.available().health(), 8, "old observation remains immutable");
            cow.discard(); h.assertTrue(inspect(world, "cow").view().isEmpty(), "removed entity must be unavailable");
        } finally { cow.discard(); player.discard(); }
        h.succeed();
    }
    @GameCase public void dependentHealingUsesObservedDeficitEvenIfWorldChangesBeforeResume(GameTestHelper h) throws Exception {
        var cow = h.spawnWithNoFreeWill(EntityTypes.COW, 2, 80, 2); cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20); cow.setHealth(8);
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/effects/entity_observation.json")), StandardCharsets.UTF_8)) {
            var program = EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            var source = new EffectSource("observer", "test:observe", "cow", new BuffInstance.Origin("cow", "observer", "", ""), Set.of());
            var world = new MinecraftWorldActions(h.getLevel(), _ -> cow, _ -> h.getLevel().damageSources().generic(), (_, _) -> true, _ -> {});
            var heals = new ArrayList<HealingCommand>();
            var session = new EffectSession(program.engine(new EffectClock((_, _) -> new EffectClock.Rate(0, List.of())), 1), EffectState.empty().withSource(source), request -> {
                var result = world.apply(request);
                if (result instanceof EntityQuery.Result) { cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); cow.setHealth(20); }
                if (request.command() instanceof HealingCommand heal) heals.add(heal);
                return result;
            });
            session.start(0, new RuleEngine.Signal("test:probe", new EffectEvent("cow", "cow", source.origin(), Set.of(), Map.of())));
            h.assertValueEqual(heals.size(), 1, "one dependent heal"); near(h, heals.getFirst().amount(), 12, "observed 20 minus 8 deficit");
            near(h, cow.getHealth(), 32, "world applied the fixed observed amount to current health"); h.assertTrue(session.state().idle(), "observation did not resume");
        } finally { cow.discard(); }
        h.succeed();
    }
}
