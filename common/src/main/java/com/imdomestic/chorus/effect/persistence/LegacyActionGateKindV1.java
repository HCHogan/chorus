package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.input.ActionGate;

/** Exact pre-aim restriction names. Old enum values are validated before migration. */
public enum LegacyActionGateKindV1 { ABILITY_USE, WEAPON_FIRE, WEAPON_RELOAD, RANGED_ATTACK, MELEE_ATTACK, MOVEMENT_INPUT, JUMP, HORIZONTAL_MOTION, VERTICAL_MOTION, PROJECTILE_TRACKING;
    public ActionGate.Kind current(){return ActionGate.Kind.valueOf(name());}
}
