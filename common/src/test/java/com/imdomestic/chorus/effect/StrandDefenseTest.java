package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.imdomestic.chorus.effect.ability.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.rule.RuleEngine;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class StrandDefenseTest {
    static final String SEVER = "chorus_d2:sever", WOVEN = "chorus_d2:woven_mail";
    static final BuffInstance.Origin APPLIER = new BuffInstance.Origin("ally", "grant", "", "");
    static DamageCommand attack(String owner, boolean guardian, Set<String> tags) {
        var origin = new BuffInstance.Origin(owner, "gun", "weapon", "", guardian ? Set.of("chorus:guardian") : Set.of());
        return new DamageCommand("target", origin, 100, "minecraft:generic", tags, Set.of(), false, Optional.of("chorus_d2:outgoing"));
    }
    static EffectState grant(CompiledEffects p, EffectState s, String buff, String holder, long duration) {
        return s.withBuffs(Buffs.grant(s.buffs(), p.buff(buff), holder, holder, APPLIER, 1, 1, duration).store());
    }
    static double outgoing(CompiledEffects p, EffectState s, DamageCommand c) { return p.outgoing(s, c, 100).orElseThrow().output().value(); }
    static double incoming(CompiledEffects p, EffectState s, DamageCommand c, double amount) { return p.defense(s, c, amount).orElseThrow().output().value(); }

    @Test void severFollowsTheAffectedAttackerRatherThanItsApplierOrVictim() throws Exception {
        var p = load("strand_defense");
        for (var mode : EffectState.Mode.values()) {
            var s = grant(p, EffectState.empty().withMode(mode), SEVER, "attacker", 10_000_000);
            assertEquals(mode == EffectState.Mode.PVE ? 60 : 85, outgoing(p, s, attack("attacker", false, Set.of())), 1e-10);
            assertEquals(100, outgoing(p, s, attack("ally", false, Set.of())));
            assertEquals(100, outgoing(p, s, attack("other", false, Set.of())));
            assertEquals(100, outgoing(p, grant(p, EffectState.empty().withMode(mode), SEVER, "target", 10_000_000), attack("attacker", false, Set.of())));
            assertEquals(100, incoming(p, s, attack("attacker", false, Set.of()), 100));
        }
    }

    @Test void wovenBypassesOnlyItsOwnResistanceForGuardianPrecisionAndMelee() throws Exception {
        var data = json("strand_defense");
        data.getAsJsonArray("buffs").add(JsonParser.parseString("""
          {"definition":{"id":"test:other_dr","version":"compendium-2026-10-05","duration":10},"bundle":"test:other_dr"}
          """));
        data.getAsJsonArray("bundles").add(JsonParser.parseString("""
          {"id":"test:other_dr","scope":"buff","modifiers":[{"id":"resistance","profile":"chorus_d2:incoming","stage":"resistance","group":"resistance","op":"resist","stacking_key":"test:other_dr",
          "value":{"type":"chorus:constant","value":0.5,"unit":"resistance"},"reference":"Synthetic independent DR","confidence":"assumed"}]}
          """));
        var p = compile(data);
        for (var mode : EffectState.Mode.values()) for (boolean guardian : List.of(false, true)) {
            var s = grant(p, grant(p, EffectState.empty().withMode(mode), WOVEN, "target", 10_000_000), "test:other_dr", "target", 10_000_000);
            for (var tags : List.of(Set.<String>of(), Set.of("chorus:bodyshot"), Set.of("chorus:precision"), Set.of("chorus:melee_damage"))) {
                boolean bypass = guardian && (tags.contains("chorus:precision") || tags.contains("chorus:melee_damage"));
                assertEquals(50 * (bypass ? 1 : mode == EffectState.Mode.PVE ? .55 : .75), incoming(p, s, attack("attacker", guardian, tags), 100), 1e-10);
            }
            assertEquals(100, outgoing(p, s, attack("attacker", guardian, Set.of())));
        }
    }

    @Test void liveSeverAndTargetDefenseComposeAtImpactEvenForAnEarlierAttackSnapshot() throws Exception {
        var p = load("strand_defense"); var empty = EffectState.empty();
        var shot = p.captureDamage(empty, attack("attacker", false, Set.of()));
        var s = grant(p, grant(p, empty, SEVER, "attacker", 10_000_000), WOVEN, "target", 10_000_000);
        assertEquals(33, incoming(p, s, shot.command("target"), outgoing(p, s, shot.command("target"))), 1e-10);
        var severedShot = p.captureDamage(s, attack("attacker", false, Set.of()));
        var expired = s.withBuffs(Buffs.advanceStep(s.buffs(), 10_000_000).store());
        assertEquals(100, incoming(p, expired, severedShot.command("target"), outgoing(p, expired, severedShot.command("target"))));
        assertEquals(100, incoming(p, s, shot.command("ally"), 100));
    }

    @Test void sliceLinksTheSameSeverAndOnlyChangesTheDebuffedTargetsLaterOutput() throws Exception {
        var p = link("slice", "strand_defense"); var source = source("chorus_d2:slice");
        for (var mode : EffectState.Mode.values()) {
            var engine = engine(p); var state = send(engine, engine.initial(EffectState.empty().withSource(source).withMode(mode)), 0, "chorus:class_ability_used", event(source));
            var facts = DamageFacts.from(new DamageCommand("attacker", source.origin(), 1, "minecraft:generic", Set.of(), Set.of(), false),
                    new DamageReceipt("shot", DamageReceipt.Outcome.APPLIED, 0, 0, 1, Optional.empty(), false));
            var pending = send(engine, state.state(), 0, facts.getFirst().type(), (EffectEvent) facts.getFirst().payload());
            var check = (StatusResult.Check) pending.actions().getFirst().command();
            assertEquals(mode == EffectState.Mode.PVE ? 10_000_000 : 5_000_000, check.duration());
            var applied = complete(engine, pending, new StatusResult.Checked(check, StatusResult.Decision.ALLOWED));
            var s = applied.state().engine().domain();
            assertEquals(4, buff(applied.state(), "chorus_d2:slice", "player").count());
            assertEquals(mode == EffectState.Mode.PVE ? 60 : 85, outgoing(p, s, attack("attacker", false, Set.of())), 1e-10);
            assertEquals(100, outgoing(p, s.withoutSource(source.instance()), attack("player", false, Set.of())));
            assertEquals(mode == EffectState.Mode.PVE ? 60 : 85, outgoing(p, s.withoutSource(source.instance()), attack("attacker", false, Set.of())), 1e-10);
        }
    }

    @Test void repeatedGrantsUseOneInstanceAndExplicitDurationOverridesExpireExactly() throws Exception {
        var p = load("strand_defense"); var s = grant(p, EffectState.empty(), WOVEN, "target", 10_000_000);
        s = s.withBuffs(Buffs.advanceStep(s.buffs(), 2_000_000).store());
        s = grant(p, s, WOVEN, "target", 3_000_000);
        assertEquals(1, s.buffs().instances().size());
        assertEquals(5_000_000, s.buffs().instances().values().iterator().next().stacks().getFirst().expiresAt());
        assertEquals(55, incoming(p, s, attack("attacker", false, Set.of()), 100), 1e-10);
        var later = s.withBuffs(Buffs.advanceStep(s.buffs(), 4_999_999).store());
        assertEquals(55, incoming(p, later, attack("attacker", false, Set.of()), 100), 1e-10);
        later = later.withBuffs(Buffs.advanceStep(later.buffs(), 5_000_000).store());
        assertEquals(100, incoming(p, later, attack("attacker", false, Set.of()), 100));
    }

    @Test void onlyTheRecipientsAcceptedSuperRemovesMailGrantedByAnotherPlayer() throws Exception {
        var p = link("strand_defense", "strand_inputs"); var source = new EffectSource("grant", "test:strand_inputs", "ally", APPLIER, Set.of());
        var session = new EffectSession(engine(p), EffectState.empty().withSource(source), request -> { throw new AssertionError(request); });
        for (String owner : List.of("target", "ally")) session.start(0, new AbilityChange(owner, AbilityLoadout.EMPTY,
                new AbilityLoadout(Map.of("test:super", "test:super", "test:ordinary", "test:ordinary"))).signal());
        Runnable grant = () -> session.start(0, new RuleEngine.Signal("test:woven", new EffectEvent("ally", "target", APPLIER, Set.of(), Map.of())));
        grant.run(); use(session, "ally", "super", "1");
        assertEquals(1, session.state().engine().domain().buffs().instances().size());
        use(session, "target", "ordinary", "2");
        assertEquals(1, session.state().engine().domain().buffs().instances().size());
        use(session, "target", "super", "3");
        assertTrue(session.state().engine().domain().buffs().instances().isEmpty());
        grant.run(); use(session, "target", "super", "4");
        assertEquals(1, session.state().engine().domain().buffs().instances().size());
        assertTrue(session.state().idle() && session.state().engine().failure().isEmpty());
    }
    private static void use(EffectSession s, String owner, String ability, String cast) {
        s.start(0, new AbilityUse.Request(owner, "test:" + ability, cast, new EffectEvent(owner, owner,
                new BuffInstance.Origin(owner, "", "", ""), Set.of(), Map.of())).signal());
    }

    @Test void acceptedStartCommitsCostAndRemovesOldMailBeforeTheBodyCanGrantNewMail() throws Exception {
        var p = link("strand_defense", "strand_inputs");
        var initial = grant(p, EffectState.empty(), WOVEN, "target", 10_000_000);
        var sessionRef = new EffectSession[1]; var observed = new ArrayList<DamageCommand>();
        var session = new EffectSession(engine(p), initial, request -> {
            var damage = (DamageCommand) request.command(); observed.add(damage);
            var s = sessionRef[0].state().engine().domain();
            assertTrue(s.buffs().instances().isEmpty(), "start cleanup must precede first on_use world action");
            assertEquals(0, s.resources().get(new com.imdomestic.chorus.effect.resource.ResourceState.Key("target", "test:super_energy")).value());
            return new DamageReceipt(request.id().toString(), DamageReceipt.Outcome.APPLIED, 0, 0, 10, Optional.empty(), false);
        });
        sessionRef[0] = session;
        session.start(0, new AbilityChange("target", AbilityLoadout.EMPTY, new AbilityLoadout(Map.of("test:super", "test:renewing_super"))).signal());
        var request = new AbilityUse.Request("target", "test:super", "renew", new EffectEvent("target", "target", new BuffInstance.Origin("target", "", "", ""), Set.of(), Map.of()));
        var accepted = p.useAbility(session.state().engine().domain(), request);
        assertEquals(List.of("chorus:resource_spent", "chorus:resource_changed", AbilityUse.STARTED, "chorus:ability_used"), accepted.emitted().stream().map(RuleEngine.Signal::type).toList());
        var started = (AbilityUse.Started) accepted.emitted().get(2).payload();
        assertEquals(accepted.result(), started.receipt()); assertTrue(started.event().tags().contains("chorus:super_ability"));
        session.start(0, request.signal());
        assertEquals(1, observed.size());
        var newMail = buff(session.state(), WOVEN, "target"); assertEquals("target", newMail.origin().owner());
        var before = session.state().engine().domain();
        var rejected = p.useAbility(before, new AbilityUse.Request("target", "test:super", "reject", request.input()));
        assertTrue(rejected.emitted().isEmpty()); assertEquals(before, rejected.state());
        assertTrue(session.state().idle() && session.state().engine().failure().isEmpty());
    }

    @Test void underOverUsesActualWovenMailAndDoesNotCreateAHeadshotOrMeleeBonus() throws Exception {
        // Adapt only fixture profile/version wiring; the existing perk predicates and values are unchanged.
        var under = JsonParser.parseString(json("under_over").toString().replace("test-1", "compendium-2026-10-05")
                .replace("test:weapon_damage", "chorus_d2:outgoing")).getAsJsonObject();
        under.getAsJsonArray("profiles").remove(0);
        var p = CompiledEffects.link(List.of(EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("strand_defense")).getOrThrow(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, under).getOrThrow()));
        for (boolean enhanced : List.of(false, true)) {
            var body = attack("attacker", true, Set.of("chorus:weapon_direct", "chorus:guardian_target", "chorus:bodyshot"));
            var s = EffectState.empty().withMode(EffectState.Mode.PVP).withSource(new EffectSource("perk", "chorus_d2:under_over", "attacker", body.source(), enhanced ? Set.of("chorus:enhanced") : Set.of()));
            s = grant(p, s, WOVEN, "target", 10_000_000);
            assertEquals(enhanced ? 91.5 : 90, incoming(p, s, body, outgoing(p, s, body)), 1e-10);
            for (String tag : List.of("chorus:precision", "chorus:melee_damage")) {
                var c = attack("attacker", true, Set.of("chorus:guardian_target", tag));
                assertEquals(100, incoming(p, s, c, outgoing(p, s, c)));
            }
        }
    }

    @Test void linkedDefinitionsRoundTripAndSliceRequiresItsSharedDependency() throws Exception {
        var p = link("strand_defense", "strand_inputs", "slice");
        var encoded = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p.program(), EffectCodecs.COMPILED.parse(JsonOps.INSTANCE, encoded).getOrThrow().program());
        assertThrows(RuntimeException.class, () -> load("slice"));
        assertThrows(RuntimeException.class, () -> link("strand_defense", "strand_defense"));
    }
}
