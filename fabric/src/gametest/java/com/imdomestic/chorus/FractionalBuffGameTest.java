package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FractionalBuffGameTest extends com.imdomestic.chorus.test.FractionalBuffGameTest {
    @GameTest(environment = "chorus_gametest:fractional_threshold", maxTicks = 50) @Override
    public void ninetyNineAndAHalfDoesNotIgniteUntilTheLastHalfArrives(GameTestHelper h) throws Exception { super.ninetyNineAndAHalfDoesNotIgniteUntilTheLastHalfArrives(h); }
    @GameTest(environment = "chorus_gametest:fractional_tail", maxTicks = 80) @Override
    public void subunitScorchIsPresentDamagesAndDecaysWithoutInventingAWholeStack(GameTestHelper h) throws Exception { super.subunitScorchIsPresentDamagesAndDecaysWithoutInventingAWholeStack(h); }
    @GameTest(environment = "chorus_gametest:fractional_guardian", maxTicks = 30) @Override
    public void actualGuardianReceivesSevenAndAHalfStackGrantsWithoutRounding(GameTestHelper h) throws Exception { super.actualGuardianReceivesSevenAndAHalfStackGrantsWithoutRounding(h); }
    @GameTest(environment = "chorus_gametest:fractional_serialization", maxTicks = 30) @Override
    public void nativeFractionalStateSerializesAllQuantityAndSourceFields(GameTestHelper h) throws Exception { super.nativeFractionalStateSerializesAllQuantityAndSourceFields(h); }
    @GameTest(environment = "chorus_gametest:fractional_unknown", maxTicks = 35) @Override
    public void unknownFractionalNativeTickKeepsAppliedDamageAndDoesNotReplay(GameTestHelper h) throws Exception { super.unknownFractionalNativeTickKeepsAppliedDamageAndDoesNotReplay(h); }
}
