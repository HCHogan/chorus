package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EntityAttachmentTransferGameTest extends com.imdomestic.chorus.test.EntityAttachmentTransferGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:attachment_native_follow",maxTicks=60) @Override
    public void relocatedSourceObservesNativeCorpseAndConcurrentFixAtOriginalEntityWorld(GameTestHelper h){
        super.relocatedSourceObservesNativeCorpseAndConcurrentFixAtOriginalEntityWorld(h);
    }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:attachment_native_restore",maxTicks=60) @Override
    public void jointArchiveRestoresBuffOwnedWaitAndSameLifeNativeReloadWithoutReplay(GameTestHelper h){
        super.jointArchiveRestoresBuffOwnedWaitAndSameLifeNativeReloadWithoutReplay(h);
    }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:attachment_native_end",maxTicks=60) @Override
    public void originalIssuerEndsOnNativeDeathAndEntityDimensionRemoval(GameTestHelper h){
        super.originalIssuerEndsOnNativeDeathAndEntityDimensionRemoval(h);
    }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:attachment_native_unknown",maxTicks=60) @Override
    public void lostForwardedQueryReceiptKeepsActualRetirementAndCallerPendingWithoutReplay(GameTestHelper h){
        super.lostForwardedQueryReceiptKeepsActualRetirementAndCallerPendingWithoutReplay(h);
    }
}
