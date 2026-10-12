package com.imdomestic.chorus.effect.persistence;

import com.imdomestic.chorus.effect.equipment.Loadout;
import java.util.*;

/** Old equipment snapshots do not imply any holder grants. */
public record LegacyLoadoutV1(Map<String, Loadout.Gear> slots, Optional<String> drawn) {
    public LegacyLoadoutV1 { new Loadout(slots, drawn); }
    public Loadout current() { return new Loadout(slots, drawn); }
}
