package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityObservationGameTest extends com.imdomestic.chorus.test.EntityObservationGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void observationsDistinguishPlayersDeadEntitiesMissingRemovedAndForeignDimensions(GameTestHelper h) { super.observationsDistinguishPlayersDeadEntitiesMissingRemovedAndForeignDimensions(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void dependentHealingUsesObservedDeficitEvenIfWorldChangesBeforeResume(GameTestHelper h) throws Exception { super.dependentHealingUsesObservedDeficitEvenIfWorldChangesBeforeResume(h); }
}
