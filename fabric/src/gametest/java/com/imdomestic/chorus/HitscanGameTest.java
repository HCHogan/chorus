package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class HitscanGameTest extends com.imdomestic.chorus.test.HitscanGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_perks",maxTicks=50) @Override
    public void instantPrecisionShotsGenerateTripleTapAndFourthTimeAmmunition(GameTestHelper h)throws Exception{super.instantPrecisionShotsGenerateTripleTapAndFourthTimeAmmunition(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_geometry",maxTicks=100) @Override
    public void nearestActualBoundsWinAndWallBeforeThemBlocksTheRay(GameTestHelper h)throws Exception{super.nearestActualBoundsWinAndWallBeforeThemBlocksTheRay(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_endpoint",maxTicks=100) @Override
    public void closedRangeEndpointAndExplicitInflationHaveDistinctGeometry(GameTestHelper h)throws Exception{super.closedRangeEndpointAndExplicitInflationHaveDistinctGeometry(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_slab",maxTicks=100) @Override
    public void slabUsesItsCollisionShapeAndFluidsDoNotBlock(GameTestHelper h)throws Exception{super.slabUsesItsCollisionShapeAndFluidsDoNotBlock(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_group",maxTicks=100) @Override
    public void realThreeMemberRayGroupResolvesAfterThreeNativeDamageReceipts(GameTestHelper h)throws Exception{super.realThreeMemberRayGroupResolvesAfterThreeNativeDamageReceipts(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_unloaded",maxTicks=100) @Override
    public void unknownTerrainAndMissingCapturesNeverLoadOrInventAContact(GameTestHelper h)throws Exception{super.unknownTerrainAndMissingCapturesNeverLoadOrInventAContact(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_unknown",maxTicks=100) @Override
    public void unknownDamageKeepsAcceptedAmmoAndStopsWithoutReplay(GameTestHelper h)throws Exception{super.unknownDamageKeepsAcceptedAmmoAndStopsWithoutReplay(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:hitscan_prefix",maxTicks=100) @Override
    public void aKnownContactBeforeUnknownTerrainSurvivesTheUnavailableEndpoint(GameTestHelper h)throws Exception{super.aKnownContactBeforeUnknownTerrainSurvivesTheUnavailableEndpoint(h);}
}
