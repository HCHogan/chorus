package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import com.imdomestic.chorus.effect.buff.BuffObservation;
import com.imdomestic.chorus.effect.buff.Buffs;
import com.imdomestic.chorus.effect.combat.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** Pure combat accounting against explicit Buff placement, independent of character and resource ownership. */
public final class CombatDomains {
    private CombatDomains() {}

    public record Group(String domain, DamageGroups.Group value) {
        public Group {
            new ResourceAuthority.Lease(domain, 1);
            Objects.requireNonNull(value).handle().requireIssuer(domain);
        }
    }
    /** An unmanaged holder is pinned to the physical caller and must remain unmanaged until settlement. */
    public record BuffPin(String domain, Optional<ParticipantPlacement.Lease> lease) {
        public BuffPin {
            new ResourceAuthority.Lease(domain, 1);
            Objects.requireNonNull(lease).ifPresent(value -> value.require(domain));
        }
    }
    /** Only group identity is captured; grants always come from the issuer's current state. */
    public record GroupPin(String domain, DamageGroups.Handle handle) {
        public GroupPin {
            new ResourceAuthority.Lease(domain, 1); Objects.requireNonNull(handle);
            if (!handle.issuer().equals(Optional.of(domain))) throw new IllegalArgumentException("Unqualified combat group pin");
        }
    }
    /** Physical domain and id identify a reservation. Every participant's Buff lease is captured explicitly. */
    public record Claim(String domain, String id, DamageCommand command, Map<String, BuffPin> buffs, Optional<GroupPin> group) {
        public Claim {
            new ResourceAuthority.Lease(domain, 1); Objects.requireNonNull(command);
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing combat reservation identity");
            buffs = Collections.unmodifiableMap(new TreeMap<>(buffs)); Objects.requireNonNull(group);
            if (!buffs.keySet().equals(holders(command))) throw new IllegalArgumentException("Incomplete combat Buff pins");
            for (var pin : buffs.values()) {
                Objects.requireNonNull(pin);
                if (pin.lease().isEmpty() && !pin.domain().equals(domain)) throw new IllegalArgumentException("Foreign unmanaged combat Buff pin");
            }
            var expected = command.group().map(handle -> {
                String issuer = handle.issuer().orElse(domain);
                return new GroupPin(issuer, handle.issuedBy(issuer));
            });
            if (!group.equals(expected)) throw new IllegalArgumentException("Combat group pin differs from command");
        }
        public BuffPin buff(String holder) {
            var pin = buffs.get(holder);
            if (pin == null) throw new IllegalArgumentException("Uncaptured combat Buff holder: " + holder);
            return pin;
        }
    }
    public record Finished(ResourceAuthority.Worlds worlds, DamageReceipt receipt,
            Map<String, List<RuleEngine.Signal>> factsByDomain) {
        public Finished {
            Objects.requireNonNull(worlds); Objects.requireNonNull(receipt);
            var copied = new TreeMap<String, List<RuleEngine.Signal>>();
            factsByDomain.forEach((domain, facts) -> {
                worlds.domain(domain);
                if (!facts.isEmpty()) copied.put(domain, List.copyOf(facts));
            });
            factsByDomain = Collections.unmodifiableMap(copied);
        }
    }
    /** Unqualified legacy references have only their explicit caller's local meaning. */
    public static Group group(ResourceAuthority.Worlds worlds, String caller, DamageGroups.Handle handle) {
        worlds.domain(caller); Objects.requireNonNull(handle);
        String issuer = handle.issuer().orElse(caller);
        return new Group(issuer, DamageGroups.require(worlds.domain(issuer), handle));
    }
    private static Set<String> holders(DamageCommand command) {
        var result = new TreeSet<String>();
        if (!command.source().owner().isBlank()) result.add(command.source().owner());
        result.add(command.target());
        command.consumptions().forEach(candidate -> result.add(candidate.key().holder()));
        return result;
    }
    /** Capture before native execution. Neither resource partitions nor state scans establish Buff authority. */
    public static Claim capture(ResourceAuthority.Worlds worlds, ParticipantPlacement.Directory placement,
            String caller, String id, DamageCommand command) {
        worlds.domain(caller); Objects.requireNonNull(command); ParticipantPlacement.validate(placement, worlds.domains());
        var buffs = new TreeMap<String, BuffPin>();
        for (String holder : holders(command)) {
            var lease = Optional.ofNullable(placement.buffs().get(holder));
            buffs.put(holder, new BuffPin(lease.map(ParticipantPlacement.Lease::domain).orElse(caller), lease));
        }
        var group = command.group().map(handle -> {
            var resolved = group(worlds, caller, handle);
            return new GroupPin(resolved.domain(), handle.issuedBy(resolved.domain()));
        });
        return new Claim(caller, id, command, buffs, group);
    }
    private static void current(ResourceAuthority.Worlds worlds, ParticipantPlacement.Directory placement, Claim claim) {
        Objects.requireNonNull(claim); worlds.domain(claim.domain()); ParticipantPlacement.validate(placement, worlds.domains());
        claim.buffs().forEach((holder, pin) -> {
            worlds.domain(pin.domain());
            if (!pin.lease().equals(Optional.ofNullable(placement.buffs().get(holder))))
                throw new IllegalStateException("Stale combat Buff placement for " + holder);
        });
    }
    private static void receipt(Claim claim, DamageReceipt receipt) {
        if (!claim.id().equals(receipt.damageId())) throw new IllegalArgumentException("Combat receipt identity differs from claim");
    }
    private static Group group(ResourceAuthority.Worlds worlds, GroupPin pin) {
        return new Group(pin.domain(), DamageGroups.require(worlds.domain(pin.domain()), pin.handle()));
    }
    private static ResourceAuthority.Worlds updated(ResourceAuthority.Worlds before, Map<String, EffectState> domains) {
        return domains.equals(before.domains()) ? before : new ResourceAuthority.Worlds(before.directory(), domains);
    }

    /** Sample pinned Buff stores on their own clocks, before consumption; the observation belongs to the physical event. */
    public static DamageReceipt observe(ResourceAuthority.Worlds worlds, ParticipantPlacement.Directory placement, Claim claim, DamageReceipt receipt) {
        Objects.requireNonNull(receipt);
        if (receipt.consumptionSettled()) return receipt;
        current(worlds, placement, claim); receipt(claim, receipt);
        if (receipt.observedBuffs().isPresent()) return receipt;
        var holders = new TreeMap<String, List<BuffObservation.Entry>>();
        for (String holder : List.of(claim.command().source().owner(), claim.command().target())) if (!holder.isBlank()) {
            var store = worlds.domain(claim.buff(holder).domain()).buffs();
            holders.put(holder, BuffObservation.capture(store, holder).require(holder));
        }
        return receipt.withObservedBuffs(new BuffObservation(worlds.domain(claim.domain()).buffs().timeMicros(), holders));
    }

    /**
     * Only an actual receipt authorizes consumption. All returned domains must be published together.
     * Settled receipts pass through; their marker prevents local completion from charging or emitting facts again.
     */
    public static Finished finish(ResourceAuthority.Worlds worlds, ParticipantPlacement.Directory placement, Claim claim, DamageReceipt receipt) {
        Objects.requireNonNull(receipt);
        if (receipt.consumptionSettled()) return new Finished(worlds, receipt, Map.of());
        receipt = observe(worlds, placement, claim, receipt);
        var domains = new TreeMap<>(worlds.domains());
        var facts = new TreeMap<String, List<RuleEngine.Signal>>();
        boolean hit = receipt.outcome() != DamageReceipt.Outcome.CANCELLED && receipt.outcome() != DamageReceipt.Outcome.FAILED;
        for (var candidate : claim.command().consumptions()) {
            if (!hit || candidate.when() == BuffConsumption.When.EFFECTIVE_DAMAGE && receipt.effective(true) <= 0) continue;
            if (candidate.shared().isPresent()) {
                var current = group(updated(worlds, domains), claim.group().orElseThrow());
                if (current.value().grants().containsKey(candidate.key())) continue;
                var issuer = domains.get(current.domain());
                domains.put(current.domain(), issuer.withDamageGroup(current.value().retain(candidate.shared().orElseThrow())));
            }
            String domain = claim.buff(candidate.key().holder()).domain();
            var state = domains.get(domain); var active = state.buffs().active(candidate.key());
            // Captured eligibility never charges a newer application or a paused Buff.
            if (active.isEmpty() || active.orElseThrow().generation() != candidate.generation()
                    || active.orElseThrow().pausedAt().isPresent()) continue;
            var consumed = Buffs.consume(state.buffs(), candidate.key(), candidate.stacks());
            domains.put(domain, state.withBuffs(consumed.store()));
            if (!consumed.signals().isEmpty()) facts.computeIfAbsent(domain, _ -> new ArrayList<>()).addAll(consumed.signals());
        }
        return new Finished(updated(worlds, domains), receipt.withConsumptionFacts(List.of()), facts);
    }

    private record Identity(String domain, String id) {
        Identity(Claim claim) { this(claim.domain(), claim.id()); }
    }
    private record Shared(GroupPin group, BuffInstance.Key key, long generation) {}
    /**
     * Read-only outgoing eligibility. Other unfinished hits reserve their captured costs without
     * lifecycle facts or group writes. Defense queries keep the committed worlds.
     */
    public static ResourceAuthority.Worlds view(ResourceAuthority.Worlds committed, ParticipantPlacement.Directory placement,
            Claim attack, List<Claim> open) {
        current(committed, placement, attack);
        attack.group().ifPresent(pin -> group(committed, pin));
        var domains = new TreeMap<>(committed.domains()); var shared = new HashSet<Shared>();
        var identities = new HashMap<Identity, Claim>();
        // The querying claim participates in duplicate checking too; an older pin cannot masquerade as its own revision.
        identities.put(new Identity(attack), attack);
        for (var claim : open) {
            current(committed, placement, claim);
            var identity = new Identity(claim); var previous = identities.putIfAbsent(identity, claim);
            if (previous != null) {
                if (!previous.equals(claim)) throw new IllegalArgumentException("Conflicting combat reservation identity");
                continue;
            }
            for (var candidate : claim.command().consumptions()) {
                if (!candidate.key().holder().equals(attack.command().source().owner())) continue;
                GroupPin claimGroup = null;
                if (candidate.shared().isPresent()) {
                    claimGroup = claim.group().orElseThrow();
                    if (attack.group().equals(Optional.of(claimGroup))) continue;
                    if (group(committed, claimGroup).value().grants().containsKey(candidate.key())) continue;
                }
                String domain = claim.buff(candidate.key().holder()).domain();
                var state = domains.get(domain); var active = state.buffs().active(candidate.key());
                if (active.isEmpty() || active.orElseThrow().generation() != candidate.generation()
                        || active.orElseThrow().pausedAt().isPresent()) continue;
                if (claimGroup != null && !shared.add(new Shared(claimGroup, candidate.key(), candidate.generation()))) continue;
                var consumed = Buffs.consume(state.buffs(), candidate.key(), candidate.stacks());
                domains.put(domain, state.withBuffs(consumed.store()));
            }
        }
        return updated(committed, domains);
    }
}
