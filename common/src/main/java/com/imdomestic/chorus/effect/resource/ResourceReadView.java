package com.imdomestic.chorus.effect.resource;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.data.EffectProgram;
import java.util.Objects;

/** Frozen supervisor input for pure queries. No session, mutable directory or live lookup callback is retained. */
public final class ResourceReadView {
    private final ResourceAuthority.Worlds worlds;
    private final String domain;
    private final EffectProgram program;

    public ResourceReadView(ResourceAuthority.Worlds worlds, String domain, EffectProgram program) {
        this.worlds = Objects.requireNonNull(worlds); this.domain = Objects.requireNonNull(domain); this.program = Objects.requireNonNull(program);
        worlds.domain(domain);
        // A scope must use the catalogue pinned by every managed partition in its own domain.
        worlds.directory().holders().values().stream().filter(p -> p.lease().domain().equals(domain)).forEach(this::compatible);
    }
    private void compatible(ResourceAuthority.Partition owner) {
        if (!program.equals(owner.program())) throw new IllegalArgumentException("Resource query catalogue differs from its authority");
    }
    public String domain() { return domain; }
    public EffectState state() { return worlds.domain(domain); }
    /** A pure local action may query a proposed non-resource change; foreign snapshots and every balance stay frozen. */
    public ResourceReadView withLocalState(EffectState local) {
        if (local == state()) return this;
        if (local.buffs().timeMicros() != state().buffs().timeMicros() || !local.resources().equals(state().resources()))
            throw new IllegalArgumentException("A resource query projection cannot change balances or advance time");
        var domains = new java.util.TreeMap<>(worlds.domains()); domains.put(domain, local);
        return new ResourceReadView(new ResourceAuthority.Worlds(worlds.directory(), domains), domain, program);
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
        return partition.lease().domain().equals(domain) ? this : new ResourceReadView(worlds, partition.lease().domain(), program);
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
