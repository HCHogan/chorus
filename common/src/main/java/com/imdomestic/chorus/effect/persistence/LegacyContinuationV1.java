package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.*;
import com.imdomestic.chorus.rule.RuleEngine;
import java.util.*;

/** The original event clock remains authoritative for old continuations. */
public record LegacyContinuationV1(String id,String definition,String version,RuleEngine.Payload scope,RuleEngine.Event cause,
        Map<String,RuleEngine.ActionResult> bindings,Optional<EffectTimers.Owner> owner) {
    public LegacyContinuationV1 { new EffectContinuations.Pending(id,definition,version,scope,cause,bindings,owner); }
    public EffectContinuations.Pending current() { return new EffectContinuations.Pending(id,definition,version,scope,cause,bindings,owner); }
}
