package com.imdomestic.chorus.test;

import static com.imdomestic.chorus.test.HealingGameTest.near;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;

/** Current capacity, overflow and captured capacity cooperate with real ticks and native world receipts. */
public class AmmoCapacityGameTest {
    private static int capacity(AmmoGameTest.Harness t, String weapon) { return t.program.ammoCapacity(t.runtime.state().engine().domain(), weapon).capacity(); }
    @GameCase public void currentCapacityControlsRefillsAndPercentagesBeforeNativeActions(GameTestHelper h) throws Exception {
        try (var t = new AmmoGameTest.Harness(h, "ammo_capacity")) {
            t.initialize("a"); t.initialize("b"); t.send("test:expand", "a", 0, 0);
            t.send("test:refill", "a", 0, 0); t.send("test:refill", "b", 0, 0);
            h.assertValueEqual(t.ammo("a").magazine(), 10, "A uses doubled capacity"); h.assertValueEqual(t.ammo("b").magazine(), 5, "B retains own capacity");
            near(h, t.player.getHealth(), 23, "actual nine and four transferred rounds drive healing");
            t.send("test:spend", "a", 3, 0); t.send("test:percent", "a", 0, 0);
            near(h, t.player.getHealth(), 26, "ceil of 25 percent of current ten-round capacity");
            h.assertValueEqual(t.magazines, List.of(10, 5, 10), "world calls see committed magazines");
            h.assertValueEqual(t.ammo("a").reserve().orElseThrow().rounds(), 41, "generation does not consume reserves");
            h.assertValueEqual(t.ammo("a").capacity(), 5, "profile input remains five"); h.assertValueEqual(capacity(t, "a"), 10, "query still resolves ten");
        }
        h.succeed();
    }
    @GameCase(environment = "chorus_gametest:ammo_capacity_delay", maxTicks = 30)
    public void realExpiryPreservesOverflowAndDetachedCapacitySnapshot(GameTestHelper h) throws Exception {
        var t = new AmmoGameTest.Harness(h, "ammo_capacity");
        try {
            t.initialize("a"); t.initialize("b"); t.send("test:expand", "a", 0, 0); t.send("test:expand", "b", 0, 0);
            t.send("test:overflow", "a", 0, 0); t.send("test:freeze", "b", 0, 0); t.runtime.unbind("b");
            h.runAfterDelay(10, () -> {
                try {
                    t.runtime.prepare(); h.assertValueEqual(capacity(t, "a"), 5, "expired A capacity"); h.assertValueEqual(capacity(t, "b"), 5, "expired B capacity");
                    h.assertValueEqual(t.ammo("a").magazine(), 20, "existing overflow survives expiry");
                    h.assertValueEqual(t.ammo("b").magazine(), 10, "detached action used captured capacity after expiry");
                    near(h, t.player.getHealth(), 38, "nineteen transferred immediately plus nine delayed");
                    t.send("test:refill", "a", 0, 0); near(h, t.heals.getLast().amount(), 0, "new refill uses current lower capacity");
                    h.assertTrue(t.runtime.failure().isEmpty() && t.runtime.state().idle(), "dynamic capacity runtime failed"); h.succeed();
                } finally { t.close(); }
            });
        } catch (Exception | Error failure) { t.close(); throw failure; }
    }
}
