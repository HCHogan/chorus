package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class EquipmentEditorGameTest extends com.imdomestic.chorus.test.EquipmentEditorGameTest {
    @GameTest(structure="chorus_gametest:empty") @Override
    public void inspectionRoundTripsCostsAndEditableFlagsWithoutMutatingOrTransmittingHolderGrants(GameTestHelper h) throws Exception { super.inspectionRoundTripsCostsAndEditableFlagsWithoutMutatingOrTransmittingHolderGrants(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void onlyTheInspectedOwnedItemCanBeEditedAndRejectedOrReplayedDraftsDoNotMutateIt(GameTestHelper h) throws Exception { super.onlyTheInspectedOwnedItemCanBeEditedAndRejectedOrReplayedDraftsDoNotMutateIt(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void revocationAndRuntimeReplacementInvalidateDraftsButALegalReplacementRepairsTheItem(GameTestHelper h) throws Exception { super.revocationAndRuntimeReplacementInvalidateDraftsButALegalReplacementRepairsTheItem(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void unknownEditCleanupReturnsTheCommittedItemAndCannotBeReplayed(GameTestHelper h) throws Exception { super.unknownEditCleanupReturnsTheCommittedItemAndCannotBeReplayed(h); }
    @GameTest(structure="chorus_gametest:empty") @Override
    public void duplicateAndOversizedChoiceMapsAreRejectedBeforeARequestCanReachEquipment(GameTestHelper h) { super.duplicateAndOversizedChoiceMapsAreRejectedBeforeARequestCanReachEquipment(h); }
}
