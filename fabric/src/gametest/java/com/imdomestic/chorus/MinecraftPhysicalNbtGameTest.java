package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class MinecraftPhysicalNbtGameTest extends com.imdomestic.chorus.test.MinecraftPhysicalNbtGameTest {
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void compoundOrderIsCanonicalAtTheRootAndInsideLists(GameTestHelper h) { super.compoundOrderIsCanonicalAtTheRootAndInsideLists(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void listOrderLengthAndEveryMixedElementTypeArePreserved(GameTestHelper h) { super.listOrderLengthAndEveryMixedElementTypeArePreserved(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void allNumericTagTypesRemainDistinctEvenForTheSameNumber(GameTestHelper h) { super.allNumericTagTypesRemainDistinctEvenForTheSameNumber(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void stringAndKeyFramingPreservesUnicodeAndCannotMergeAdjacentValues(GameTestHelper h) { super.stringAndKeyFramingPreservesUnicodeAndCannotMergeAdjacentValues(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void integerBoundariesAndValuesAboveDoublePrecisionAreExact(GameTestHelper h) { super.integerBoundariesAndValuesAboveDoublePrecisionAreExact(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void floatingBitsPreserveSignedZeroSubnormalsInfinitiesAndNanPayloads(GameTestHelper h) { super.floatingBitsPreserveSignedZeroSubnormalsInfinitiesAndNanPayloads(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void arraysPreserveElementTypeLengthOrderAndFullIntegerWidth(GameTestHelper h) { super.arraysPreserveElementTypeLengthOrderAndFullIntegerWidth(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void onlyTheRootMarkerIsExcludedAndNestedNamesRemainPartOfTheBody(GameTestHelper h) { super.onlyTheRootMarkerIsExcludedAndNestedNamesRemainPartOfTheBody(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void digestDoesNotMutateInputAndWithoutMarkerReturnsAnIsolatedDeepCopy(GameTestHelper h) { super.digestDoesNotMutateInputAndWithoutMarkerReturnsAnIsolatedDeepCopy(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void digestHasAnExplicitFormatAndStableLowercaseSha256Width(GameTestHelper h) { super.digestHasAnExplicitFormatAndStableLowercaseSha256Width(h); }
}
