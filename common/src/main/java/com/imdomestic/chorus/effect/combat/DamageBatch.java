package com.imdomestic.chorus.effect.combat;

import com.imdomestic.chorus.rule.RuleEngine;
import java.util.Objects;

/** Content-declared logical damage membership. It does not imply a shared root, shot, tick or transaction. */
public record DamageBatch(String id, String owner) implements RuleEngine.ActionResult {
    public DamageBatch {
        Objects.requireNonNull(id); Objects.requireNonNull(owner);
        if (id.isBlank()) throw new IllegalArgumentException("Missing damage batch identity");
    }
    public String reference() { return "batch/" + owner.length() + ":" + owner + "/" + id; }
    public static String reference(DamageCommand command, DamageReceipt receipt) {
        // Distinct namespaces prevent an explicitly named batch from colliding with a standalone receipt.
        return command.batch().map(DamageBatch::reference).orElseGet(() -> "damage/" + receipt.damageId());
    }
}
