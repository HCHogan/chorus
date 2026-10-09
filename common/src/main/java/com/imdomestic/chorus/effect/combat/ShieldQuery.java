package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.buff.BuffInstance;
import java.util.Objects;

/** Query-only layer metadata. Layer tags must never become attack tags or kill credit. */
public record ShieldQuery(EffectEvent event, BuffInstance layer) implements EffectEvent.Carrier {
    public ShieldQuery {
        Objects.requireNonNull(event); Objects.requireNonNull(layer);
        if (!event.victim().equals(layer.key().holder())) throw new IllegalArgumentException("Shield query target differs from layer holder");
    }
}
