package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class InputDirectionGameTest extends com.imdomestic.chorus.test.InputDirectionGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void realHeldInputsUseYawWithoutPitchVelocityOrImplicitForwardFallback(GameTestHelper h) throws Exception { super.realHeldInputsUseYawWithoutPitchVelocityOrImplicitForwardFallback(h); }
    @GameTest(structure="chorus_gametest:empty", environment="chorus_gametest:input_direction_capture", maxTicks=12) @Override
    public void acceptedSkillRetainsCapturedInputForActualDelayedImpulseAfterTurnAndUnequip(GameTestHelper h) throws Exception { super.acceptedSkillRetainsCapturedInputForActualDelayedImpulseAfterTurnAndUnequip(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void movementRestrictionFiltersHeldAndNewPacketsBeforeTheReadOnlyObservation(GameTestHelper h) throws Exception { super.movementRestrictionFiltersHeldAndNewPacketsBeforeTheReadOnlyObservation(h); }
}
