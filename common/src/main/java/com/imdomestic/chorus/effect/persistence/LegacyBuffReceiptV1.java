package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.combat.StatusResult;
import java.util.*;

/** Original integer-only storage shape; migration preserves exact whole quantities. */
public record LegacyBuffReceiptV1(int requested, int credited, int storedDelta, boolean applied) {
    public com.imdomestic.chorus.effect.buff.Buffs.Receipt current() { return new com.imdomestic.chorus.effect.buff.Buffs.Receipt(requested, credited, storedDelta, applied); }
}
