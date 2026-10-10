package com.imdomestic.chorus.effect;

import static com.imdomestic.chorus.effect.EffectTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.imdomestic.chorus.effect.buff.*;
import com.imdomestic.chorus.effect.data.*;
import com.imdomestic.chorus.stat.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import org.junit.jupiter.api.Test;

class SharedWeaponStatsTest {
    @Test void surplusAndPugilistLinkOnceAndAddBeforeTheSharedHandlingCap() throws Exception {
        var h = new SurplusTest.Harness(false, 1, 1, 1);
        var program = CompiledEffects.link(List.of(h.program.program(), EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, json("pugilist")).getOrThrow()));
        var origin = new BuffInstance.Origin("player", "pugilist", "a", "");
        var state = h.state().withBuffs(Buffs.grant(h.state().buffs(), program.buff("chorus_d2:pugilist_handling"), "player", "player", origin, 1, 1, 3_000_000).store());
        var result = program.calculate(state, "player", new EffectEvent("player", "player", origin, Set.of(), Map.of()), "chorus_d2:weapon_handling", new Measure(10, Unit.STAT_POINT), List.of());
        assertEquals(105, result.trace().stages().get("perks").value()); assertEquals(100, result.output().value());
        assertEquals(2, result.trace().contributions().stream().filter(c -> c.selected()).count());
        var other = new EffectEvent("player", "player", new BuffInstance.Origin("player", "query", "b", ""), Set.of(), Map.of());
        assertEquals(70, program.calculate(state, "player", other, "chorus_d2:weapon_handling", new Measure(10, Unit.STAT_POINT), List.of()).output().value());
    }
}
