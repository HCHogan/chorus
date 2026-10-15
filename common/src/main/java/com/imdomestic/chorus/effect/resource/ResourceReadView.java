package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.combat.DamageGroups;
import com.imdomestic.chorus.effect.data.EffectProgram;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Frozen supervisor input for pure queries. No session, mutable directory or live lookup callback is retained. */
public final class ResourceReadView {
    private final ResourceAuthority.Worlds worlds;
    private final String domain;
    private final EffectProgram program;
    private final Optional<Participants> participants;
    private record Participants(String physicalDomain, ParticipantPlacement.Directory placement,
            Map<String, EffectProgram> programs) {
        private Participants {
            Objects.requireNonNull(physicalDomain); Objects.requireNonNull(placement); programs = Map.copyOf(programs);
        }
    }

    public ResourceReadView(ResourceAuthority.Worlds worlds, String domain, EffectProgram program) {
        this(worlds, domain, program, Optional.empty());
    }
    private ResourceReadView(ResourceAuthority.Worlds worlds, String domain, EffectProgram program, Optional<Participants> participants) {
        this.worlds = Objects.requireNonNull(worlds); this.domain = Objects.requireNonNull(domain); this.program = Objects.requireNonNull(program);
        this.participants = Objects.requireNonNull(participants);
        worlds.domain(domain);
        participants.ifPresent(capture -> {
            worlds.domain(capture.physicalDomain());
            if (!capture.programs().keySet().equals(worlds.domains().keySet()))
                throw new IllegalArgumentException("Participant query needs every domain's pinned catalogue");
            ParticipantPlacement.validate(capture.placement(), worlds.domains());
            compatibleDomain(domain);
        });
        // A scope must use the catalogue pinned by every managed partition in its own domain.
        worlds.directory().holders().values().stream().filter(p -> p.lease().domain().equals(domain)).forEach(this::compatible);
    }
    private void compatible(ResourceAuthority.Partition owner) {
        if (!program.equals(owner.program())) throw new IllegalArgumentException("Resource query catalogue differs from its authority");
    }
    private void compatibleDomain(String selected) {
        participants.ifPresent(capture -> {
            if (!program.equals(capture.programs().get(selected)))
                throw new IllegalArgumentException("Participant query catalogue differs from its domain: " + selected);
        });
    }
    public String domain() { return domain; }
    public EffectState state() { return worlds.domain(domain); }
    /**
     * Explicit facet queries require their independently captured placement and domain catalogues.
     * The native host must additionally pin entity life and physical binding through settlement.
     * Ordinary views retain local non-resource reads; this view neither authorizes nor commits damage.
     */
    public ResourceReadView combatParticipants(ParticipantPlacement.Directory placement, Map<String, EffectProgram> programs) {
        if (participants.isPresent()) {
            var existing = participants.orElseThrow();
            if (!existing.placement().equals(placement) || !existing.programs().equals(programs))
                throw new IllegalArgumentException("A participant query cannot replace its captured authority");
            return this;
        }
        return new ResourceReadView(worlds, domain, program, Optional.of(new Participants(domain, placement, programs)));
    }
    public boolean combatParticipantsEnabled() { return participants.isPresent(); }
    private String participantDomain(ParticipantPlacement.Facet facet, String holder) {
        if (participants.isEmpty()) return domain;
        var capture = participants.orElseThrow();
        var lease = capture.placement().facet(facet).get(holder);
        String selected = lease == null ? capture.physicalDomain() : lease.domain();
        compatibleDomain(selected); return selected;
    }
    public String characterDomain(String holder) { return participantDomain(ParticipantPlacement.Facet.CHARACTER, holder); }
    public EffectState characterState(String holder) { return worlds.domain(characterDomain(holder)); }
    public ResourceReadView characterView(String holder) { return inDomain(characterDomain(holder)); }
    public String buffDomain(String holder) { return participantDomain(ParticipantPlacement.Facet.BUFFS, holder); }
    public EffectState buffState(String holder) { return worlds.domain(buffDomain(holder)); }
    public ResourceReadView buffView(String holder) { return inDomain(buffDomain(holder)); }
    /** A legacy group belongs only to the physical caller, even inside a nested participant query. */
    public String damageGroupDomain(DamageGroups.Handle handle) {
        String issuer = handle.issuer().orElseGet(() -> participants.map(Participants::physicalDomain).orElse(domain));
        compatibleDomain(issuer); return issuer;
    }
    public DamageGroups.Group damageGroup(DamageGroups.Handle handle) {
        String issuer = damageGroupDomain(handle);
        handle.requireIssuer(issuer);
        var value = DamageGroups.require(worlds.domain(issuer), handle);
        value.handle().requireIssuer(issuer);
        return value;
    }
    private ResourceReadView inDomain(String selected) {
        return selected.equals(domain) ? this : new ResourceReadView(worlds, selected, program, participants);
    }
    /** A pure local action may query a proposed non-resource change; foreign snapshots and every balance stay frozen. */
    public ResourceReadView withLocalState(EffectState local) {
        if (local == state()) return this;
        if (local.buffs().timeMicros() != state().buffs().timeMicros() || !local.resources().equals(state().resources()))
            throw new IllegalArgumentException("A resource query projection cannot change balances or advance time");
        var domains = new java.util.TreeMap<>(worlds.domains()); domains.put(domain, local);
        return new ResourceReadView(new ResourceAuthority.Worlds(worlds.directory(), domains), domain, program, participants);
    }
    public void require(EffectProgram expectedProgram, EffectState expectedState) {
        if (!program.equals(expectedProgram) || state() != expectedState)
            throw new IllegalArgumentException("Resource query view requires its exact domain snapshot and catalogue");
    }
    /** Unmanaged accounts keep ordinary local semantics. Managed holders never fall back to a local replica. */
    public ResourceReadView owner(String holder) {
        var partition = worlds.directory().holders().get(holder);
        if (partition == null) return this;
        compatible(partition);
        return inDomain(partition.lease().domain());
    }
    public ResourceState account(ResourceState.Key key) {
        var account = owner(key.holder()).state().resources().get(key);
        if (account == null) throw new IllegalArgumentException("Missing resource account: " + key);
        return account;
    }
    /** A read observation cannot authorize recreating or mutating somebody else's authoritative partition. */
    public void requireLocal(ResourceState.Key key) {
        if (!owner(key.holder()).domain().equals(domain)) throw new IllegalStateException("Remote resource mutation requires an addressed request: " + key);
    }
}
