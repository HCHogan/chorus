package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.effect.resource.ResourceState;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class DamageSnapshotTest {
    private static final EffectSource SOURCE = source("test:attack");
    private static DamageCommand attack() {
        return new DamageCommand("unknown-at-use", SOURCE.origin(), 10, "minecraft:generic", Set.of("test:projectile"), Set.of("test:weapon_kill"), false, Optional.of("test:attack_damage"));
    }
    private static EffectState power(EffectState state, double value) {
        return state.withResource(new ResourceState(new ResourceState.Key("player", "test:power"), value, 1, state.buffs().timeMicros()));
    }
    private static EffectState grant(CompiledEffects program, EffectState state, String buff, String target, int stacks) {
        return state.withBuffs(Buffs.grant(state.buffs(), program.buff("test:" + buff), target, target, SOURCE.origin(), stacks, 1, 30_000_000).store());
    }
    private static EffectState initial(CompiledEffects program) {
        return grant(program, power(EffectState.empty().withSource(SOURCE), .3), "empower", "player", 1);
    }
    private static double hit(CompiledEffects program, EffectState state, DamageSnapshot snapshot, String target) {
        return program.outgoing(state, snapshot.command(target), 10).orElseThrow().output().value();
    }
    @Test void sourceStateIsFrozenWhileTargetConditionAndDefenseAreReadAtImpact() throws Exception {
        var program = load("damage_snapshot"); var launch = initial(program); var snapshot = program.captureDamage(launch, attack());
        var impact = power(EffectState.empty(), 0);
        assertEquals(12, hit(program, impact, snapshot, "target"), 1e-9);
        impact = grant(program, impact, "marked", "target", 1);
        impact = grant(program, impact, "vulnerability", "target", 1);
        double outgoing = hit(program, impact, snapshot, "target");
        assertEquals(13.5, outgoing, 1e-9, "MAX(20%, frozen 30% + live target count 5%), not their product");
        assertEquals(20.25, program.defense(impact, snapshot.command("target"), outgoing).orElseThrow().output().value(), 1e-9);
        assertEquals(SOURCE.origin(), snapshot.command("target").source());
    }
    @Test void sameSnapshotCanHitDifferentVictimsWithoutMutatingOrLosingFrozenOperands() throws Exception {
        var program = load("damage_snapshot"); var state = initial(program); var snapshot = program.captureDamage(state, attack());
        assertEquals(snapshot, program.captureDamage(state, attack()), "Snapshots are comparable data");
        state = grant(program, grant(program, power(state.withoutSource(SOURCE.instance()), 0), "marked", "first", 1), "marked", "second", 2);
        assertEquals(13.5, hit(program, state, snapshot, "first"), 1e-9);
        assertEquals(14, hit(program, state, snapshot, "second"), 1e-9);
        assertEquals(13.5, hit(program, state, snapshot, "first"), 1e-9);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.contributions().clear());
    }
    @Test void renewedOrNewOnUseBonusesCannotEnterAnAlreadyCapturedAttack() throws Exception {
        var program = load("damage_snapshot"); var state = initial(program); var snapshot = program.captureDamage(state, attack());
        state = grant(program, state, "empower", "player", 2).withoutSource(SOURCE.instance())
                .withSource(new EffectSource("replacement", "test:replacement", "player", SOURCE.origin(), Set.of()));
        var result = program.outgoing(state, snapshot.command("target"), 10).orElseThrow();
        assertEquals(12, result.output().value(), 1e-9);
        assertEquals(1, result.trace().contributions().size(), "The frozen buff must not also be recollected live");
        assertEquals(19, program.outgoing(state, attack(), 10).orElseThrow().output().value(), 1e-9, "Uncaptured immediate queries remain live");
    }
    @Test void explicitOnHitContributionsMayAppearAndDisappearAfterLaunch() throws Exception {
        var program = load("damage_snapshot"); var snapshot = program.captureDamage(initial(program), attack());
        var live = grant(program, EffectState.empty(), "live", "player", 1);
        assertEquals(14, hit(program, live, snapshot, "target"), 1e-9);
        assertEquals(12, hit(program, EffectState.empty(), snapshot, "target"), 1e-9);
        var atUse = grant(program, initial(program), "live", "player", 1);
        assertEquals(12, hit(program, EffectState.empty(), program.captureDamage(atUse, attack()), "target"), 1e-9, "on_hit is not accidentally frozen");
    }
    @Test void sourceConditionThatWasFalseCannotBecomeTrueRetroactively() throws Exception {
        var program = load("damage_snapshot"); var snapshot = program.captureDamage(power(initial(program), 0), attack());
        var impact = grant(program, power(EffectState.empty(), 1), "marked", "target", 3);
        assertEquals(12, hit(program, impact, snapshot, "target"), 1e-9);
    }
    @Test void perStackContributionsRetainSeparateIdentitiesAfterBuffRemoval() throws Exception {
        var program = load("damage_snapshot"); var launch = grant(program, EffectState.empty(), "stacked", "player", 2);
        var snapshot = program.captureDamage(launch, attack());
        var result = program.outgoing(EffectState.empty(), snapshot.command("target"), 10).orElseThrow();
        assertEquals(12.1, result.output().value(), 1e-9);
        assertEquals(2, result.trace().contributions().size());
        assertEquals(2, result.trace().contributions().stream().map(c -> c.contribution().id()).distinct().count());
    }
    @Test void oldProfileSurvivesCatalogueReplacementAndIncompatibleLiveLayoutIsRejected() throws Exception {
        var old = load("damage_snapshot"); var snapshot = old.captureDamage(initial(old), attack());
        var data = com.google.gson.JsonParser.parseString(json("damage_snapshot").toString().replace("test-snapshot-v1", "test-snapshot-v2")).getAsJsonObject();
        data.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonObject("group").addProperty("reduction", "product");
        var current = compile(data); var state = EffectState.empty();
        var result = current.outgoing(state, snapshot.command("target"), 10).orElseThrow();
        assertEquals("test-snapshot-v1", result.trace().version()); assertEquals(12, result.output().value(), 1e-9);
        var withLive = grant(current, state, "live", "player", 1);
        assertThrows(IllegalArgumentException.class, () -> hit(current, withLive, snapshot, "target"));
    }
    @Test void compatibleNewVersionCanSupplyExplicitOnHitContributionsWithoutChangingPinnedProfile() throws Exception {
        var old = load("damage_snapshot"); var snapshot = old.captureDamage(initial(old), attack());
        var data = com.google.gson.JsonParser.parseString(json("damage_snapshot").toString().replace("test-snapshot-v1", "test-snapshot-v2")).getAsJsonObject();
        var current = compile(data); var impact = grant(current, EffectState.empty(), "live", "player", 1);
        var result = current.outgoing(impact, snapshot.command("target"), 10).orElseThrow();
        assertEquals("test-snapshot-v1", result.trace().version()); assertEquals(14, result.output().value(), 1e-9);
        assertEquals(Set.of("test-snapshot-v1", "test-snapshot-v2"), result.trace().contributions().stream()
                .map(c -> c.contribution().source().version()).collect(java.util.stream.Collectors.toSet()), "Each contribution retains its own catalogue version");
    }
    @Test void snapshotCannotBeReusedAsADifferentAttackOrRecapturedOrMovedBetweenModes() throws Exception {
        var program = load("damage_snapshot"); var state = initial(program); var snapshot = program.captureDamage(state, attack()); var command = snapshot.command("target");
        assertThrows(IllegalArgumentException.class, () -> program.captureDamage(state, command));
        assertThrows(IllegalArgumentException.class, () -> new DamageCommand(command.target(), command.source(), 20, command.damageType(), command.tags(), command.killTags(), false, command.scalingProfile(), Optional.of(snapshot)));
        assertThrows(IllegalArgumentException.class, () -> new DamageCommand(command.target(), command.source(), 10, command.damageType(), Set.of("different:credit"), command.killTags(), false, command.scalingProfile(), Optional.of(snapshot)));
        assertThrows(IllegalArgumentException.class, () -> hit(program, state.withMode(EffectState.Mode.PVP), snapshot, "target"));
        assertThrows(IllegalArgumentException.class, () -> program.captureDamage(state, new DamageCommand("target", command.source(), 10, command.damageType(), Set.of(), Set.of(), false)));
    }
    @Test void snapshotTimingRoundTripsAndUnsupportedTimingDoesNotSilentlyDefault() throws Exception {
        var program = load("damage_snapshot");
        assertEquals(program.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, EffectCodecs.PROGRAM.encodeStart(JsonOps.INSTANCE, program.program()).getOrThrow()).getOrThrow().program());
        var data = json("damage_snapshot"); data.getAsJsonArray("bundles").get(0).getAsJsonObject().getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("evaluate", "on_release");
        assertThrows(RuntimeException.class, () -> compile(data));
        Value extension = new Value() {
            public Unit unit(Validation v) { return Unit.DELTA; }
            public Measure evaluate(Evaluation e) { return new Measure(.1, Unit.DELTA); }
        };
        assertThrows(IllegalArgumentException.class, () -> extension.snapshot(null), "Unknown extension dependencies must not be guessed");
    }
}
