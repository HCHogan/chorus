package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.combat.DamageCommand;
import com.imdomestic.chorus.effect.combat.DamageReceipt;
import com.imdomestic.chorus.effect.projectile.ProjectileFlight;
import com.imdomestic.chorus.effect.projectile.ShotGroups;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Host-only contact authority and actual evidence. These values are never checkpoint DATA. */
public final class ShotDamageAccounting {
    private ShotDamageAccounting() {}

    public record Pin(String caller, RuleEngine.OperationId operation, String issuer,
            ProjectileFlight.Impact impact, DamageCommand command) {
        public Pin {
            new ResourceAuthority.Lease(caller, 1); new ResourceAuthority.Lease(issuer, 1);
            Objects.requireNonNull(operation); Objects.requireNonNull(impact); Objects.requireNonNull(command);
            if(operation.frame()<1||operation.pc()<0||operation.invocation()<1)
                throw new IllegalArgumentException("Invalid shot operation identity");
            var handle = impact.member().orElseThrow().shot();
            if (!handle.issuer().equals(Optional.of(issuer))) throw new IllegalArgumentException("Unqualified shot contact pin");
        }
        public ShotGroups.Handle handle() { return impact.member().orElseThrow().shot(); }
    }
    public record Settled(Pin pin, DamageReceipt receipt) {
        public Settled { Objects.requireNonNull(pin); Objects.requireNonNull(receipt); }
        public void requireActual(DamageReceipt actual) {
            // Native completion appends consumption/progress facts after recording the physical observation.
            if (!receipt.withConsumptionFacts(List.of()).equals(actual.withConsumptionFacts(List.of())))
                throw new IllegalArgumentException("Shot operation has a conflicting actual damage receipt");
        }
    }
    public record Applied(ResourceAuthority.Worlds worlds, Settled settled, List<RuleEngine.Signal> facts) {
        public Applied { Objects.requireNonNull(worlds); Objects.requireNonNull(settled); facts = List.copyOf(facts); }
    }

    public static Pin capture(ResourceAuthority.Worlds worlds, String caller, RuleEngine.OperationId operation,
            ProjectileFlight.Impact impact, DamageCommand command) {
        worlds.domain(caller);
        var member = impact.member().orElseThrow(); String issuer = member.shot().issuer().orElse(caller);
        var state = worlds.domain(issuer); var group = state.shotGroups().get(member.shot().id());
        if (group == null || !group.handle().matches(member.shot())) throw new IllegalArgumentException("Missing or changed shot contact ledger");
        group.handle().requireIssuer(issuer);
        var canonical = impact.withMember(Optional.of(new ShotGroups.Member(group.handle().issuedBy(issuer), member.pellet())));
        ShotGroups.validateDamage(state, canonical, command);
        return new Pin(caller, operation, issuer, canonical, command);
    }

    public static Applied settle(ResourceAuthority.Worlds worlds, Pin pin, DamageReceipt actual, Optional<Settled> previous) {
        Objects.requireNonNull(previous);
        if (previous.isPresent()) {
            var settled = previous.orElseThrow();
            if (!settled.pin().equals(pin)) throw new IllegalArgumentException("Shot settlement changed its prepared contact");
            settled.requireActual(actual);
            return new Applied(worlds, settled, List.of());
        }
        var before = worlds.domain(pin.issuer());
        var after = ShotGroups.damage(before, pin.impact(), pin.command(), actual);
        var facts = ShotGroups.progress(before, after, pin.impact().member().orElseThrow());
        return new Applied(ResourceAuthority.publish(worlds, pin.issuer(), before, after), new Settled(pin, actual), facts);
    }

    /** Only the exact replay of pinned, confirmed contacts can authorize native shot-ledger changes. */
    public static void validateLedger(String domain, EffectState before, EffectState after, List<Settled> actuals) {
        var expected = before; var identities = new HashSet<Identity>();
        for (var actual : actuals) {
            var pin = actual.pin(); if (!pin.issuer().equals(domain)) continue;
            var identity = new Identity(pin.caller(),pin.operation());
            if (!identities.add(identity)) throw new IllegalArgumentException("Duplicate native shot settlement evidence");
            expected = ShotGroups.damage(expected, pin.impact(), pin.command(), actual.receipt());
        }
        if (!expected.shotGroups().equals(after.shotGroups()))
            throw new IllegalArgumentException("Combat publication lacks exact shot contact evidence");
    }
    private record Identity(String caller,RuleEngine.OperationId operation) {}

    /** Project just the legacy Buff/DamageGroup delta; complete shot evidence is retained separately. */
    public static EffectState restoreLedger(EffectState before, EffectState after) {
        var result = after;
        for (String id : after.shotGroups().keySet()) result = result.withoutShotGroup(id);
        for (var group : before.shotGroups().values()) result = result.withShotGroup(group);
        return result;
    }
}
