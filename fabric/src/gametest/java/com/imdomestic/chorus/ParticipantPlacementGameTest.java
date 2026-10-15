package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ParticipantPlacementGameTest extends com.imdomestic.chorus.test.ParticipantPlacementGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void resourceOnlyMoveAndRestoreLeaveNativeCharacterAndBuffPlacementUntilRealTeleport(GameTestHelper h){super.resourceOnlyMoveAndRestoreLeaveNativeCharacterAndBuffPlacementUntilRealTeleport(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void emptyBuffPartitionStillReceivesNewLeasesAcrossActualRoundTripAndRestore(GameTestHelper h){super.emptyBuffPartitionStillReceivesNewLeasesAcrossActualRoundTripAndRestore(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void carriedWeaponBuffGetsItsOwnPlacementAndFollowsActualPlayerThroughRestore(GameTestHelper h){super.carriedWeaponBuffGetsItsOwnPlacementAndFollowsActualPlayerThroughRestore(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void actualDeathInNetherRespawnsNewLifeInOverworldWithoutResettingPaidStateOrRevivingInput(GameTestHelper h)throws Exception{super.actualDeathInNetherRespawnsNewLifeInOverworldWithoutResettingPaidStateOrRevivingInput(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void keepAllRespawnReturnsAcrossDimensionsWithSameLifeAndConfigurationButNoOldInput(GameTestHelper h)throws Exception{super.keepAllRespawnReturnsAcrossDimensionsWithSameLifeAndConfigurationButNoOldInput(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void registeredCharacterCannotLeaveItsHostForAnUninstalledDimension(GameTestHelper h){super.registeredCharacterCannotLeaveItsHostForAnUninstalledDimension(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:resource_runtime") @Override
    public void respawnFailureAfterNativeRemovalKeepsUnknownPhysicalResultWithoutPublishingOrRetrying(GameTestHelper h){super.respawnFailureAfterNativeRemovalKeepsUnknownPhysicalResultWithoutPublishingOrRetrying(h);}
}
