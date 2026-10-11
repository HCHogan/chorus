package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class SharedStagesGameTest extends com.imdomestic.chorus.test.SharedStagesGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:shared_stages_delay",maxTicks=30) @Override
    public void storedSharedStageSurvivesSourceExpiryAndCombinesWithLiveVictimDefense(GameTestHelper h) throws Exception {super.storedSharedStageSurvivesSourceExpiryAndCombinesWithLiveVictimDefense(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:shared_stages_victim") @Override
    public void localAndImportedBonusesShareOneGroupAndCurrentVictimProvidersRemainEligible(GameTestHelper h) throws Exception {super.localAndImportedBonusesShareOneGroupAndCurrentVictimProvidersRemainEligible(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:shared_stages_unknown") @Override
    public void unknownSharedDamageKeepsHealthAndSavedSnapshotWithoutReplay(GameTestHelper h) throws Exception {super.unknownSharedDamageKeepsHealthAndSavedSnapshotWithoutReplay(h);}
}
