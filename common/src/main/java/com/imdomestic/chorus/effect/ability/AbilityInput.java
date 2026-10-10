package com.imdomestic.chorus.effect.ability;

import java.util.*;

/** Host-observed input edges; held duration is measured by the authoritative host, not the client. */
public final class AbilityInput {
    private AbilityInput() {}
    public enum Edge { PRESS, RELEASE, CANCEL }
    public enum Outcome { PRESSED, ALREADY_HELD, NO_PRESS, STALE, EMPTY_SLOT, RESTRICTED, CANCELLED, RELEASED }
    public record Receipt(Outcome outcome, long heldMicros, Optional<AbilityUse.Receipt> cast) {
        public Receipt {
            Objects.requireNonNull(outcome); Objects.requireNonNull(cast);
            if (heldMicros < 0 || (outcome == Outcome.RELEASED) != cast.isPresent()) throw new IllegalArgumentException("Invalid ability input receipt");
        }
        public static Receipt input(Outcome outcome, long heldMicros) { return new Receipt(outcome, heldMicros, Optional.empty()); }
    }
}
