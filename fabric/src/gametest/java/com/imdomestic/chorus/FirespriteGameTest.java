package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FirespriteGameTest extends com.imdomestic.chorus.test.FirespriteGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void nativeKillsBuffNearbyAlliesThenPrivateCollectionUsesLiveGrenadeAfterUnequip(GameTestHelper h) throws Exception {super.nativeKillsBuffNearbyAlliesThenPrivateCollectionUsesLiveGrenadeAfterUnequip(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:firesprite_cooldown",maxTicks=125) @Override
    public void physicalSpawnsShareFiveSecondCooldownAcrossBothWeapons(GameTestHelper h) throws Exception {super.physicalSpawnsShareFiveSecondCooldownAcrossBothWeapons(h);}
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:firesprite_expiry",maxTicks=530) @Override
    public void originalTwentyFiveSecondLifetimeExpiresWithoutCollectionFact(GameTestHelper h) throws Exception {super.originalTwentyFiveSecondLifetimeExpiresWithoutCollectionFact(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownPickupListenerOutcomeNeverReplaysAlreadyCommittedEnergy(GameTestHelper h) throws Exception {super.unknownPickupListenerOutcomeNeverReplaysAlreadyCommittedEnergy(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void temperingKillCreatesAndCollectsFirespriteAtReceiptPositionAfterCorpseCleanup(GameTestHelper h)throws Exception{super.temperingKillCreatesAndCollectsFirespriteAtReceiptPositionAfterCorpseCleanup(h);}
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unobservedMissingForeignAndUnloadedPositionsCannotCreateOrStartCooldown(GameTestHelper h)throws Exception{super.unobservedMissingForeignAndUnloadedPositionsCannotCreateOrStartCooldown(h);}
}
