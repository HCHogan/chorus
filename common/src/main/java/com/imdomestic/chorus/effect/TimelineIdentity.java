package com.imdomestic.chorus.effect;

import java.util.Objects;
import java.util.UUID;

/** Persistent identity of one logical clock; ownership transfers do not replace its history. */
public record TimelineIdentity(String value) {
    public TimelineIdentity {
        Objects.requireNonNull(value);
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Expected a canonical timeline UUID");
    }
    public static TimelineIdentity fresh() { return new TimelineIdentity(UUID.randomUUID().toString()); }
}
