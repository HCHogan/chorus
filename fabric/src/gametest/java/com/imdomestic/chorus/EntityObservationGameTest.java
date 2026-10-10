package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityObservationGameTest extends com.imdomestic.chorus.test.EntityObservationGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void observationsDistinguishPlayersDeadEntitiesMissingRemovedAndForeignDimensions(GameTestHelper h) { super.observationsDistinguishPlayersDeadEntitiesMissingRemovedAndForeignDimensions(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void dependentHealingUsesObservedDeficitEvenIfWorldChangesBeforeResume(GameTestHelper h) throws Exception { super.dependentHealingUsesObservedDeficitEvenIfWorldChangesBeforeResume(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void nativeEntityTagsAndDatapackTypeTagsRemainSeparateAcrossMutationDeathAndRemoval(GameTestHelper h) { super.nativeEntityTagsAndDatapackTypeTagsRemainSeparateAcrossMutationDeathAndRemoval(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:classified_burst", maxTicks = 30) @Override
    public void actualDeathsSelectObservedRadiiAfterTagChangesSourceDetachAndCorpseRemoval(GameTestHelper h) throws Exception { super.actualDeathsSelectObservedRadiiAfterTagChangesSourceDetachAndCorpseRemoval(h); }
}
