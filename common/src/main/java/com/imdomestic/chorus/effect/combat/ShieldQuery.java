package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.Objects;

/** Query-only layer metadata. Layer tags must never become attack tags or kill credit. */
public record ShieldQuery(EffectEvent event, BuffInstance layer, java.util.Optional<DamageGroups.Handle> group) implements EffectEvent.Carrier {
    public ShieldQuery {
        Objects.requireNonNull(event); Objects.requireNonNull(layer); Objects.requireNonNull(group);
        if (!event.victim().equals(layer.key().holder())) throw new IllegalArgumentException("Shield query target differs from layer holder");
    }
    public ShieldQuery(EffectEvent event, BuffInstance layer) { this(event, layer, java.util.Optional.empty()); }
}
