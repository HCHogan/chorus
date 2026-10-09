package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ProjectileGameTest extends com.imdomestic.chorus.test.ProjectileGameTest {
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:projectile_entity", maxTicks = 60) @Override
    public void paidAbilityLaunchesPhysicalProjectileAndRetainsCapturedPowerAfterOwnerMovesAndSourceDetaches(GameTestHelper h) throws Exception { super.paidAbilityLaunchesPhysicalProjectileAndRetainsCapturedPowerAfterOwnerMovesAndSourceDetaches(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:projectile_block", maxTicks = 60) @Override
    public void sweptBlockCollisionStopsDirectFlightAndUsesExactContactForAreaActions(GameTestHelper h) throws Exception { super.sweptBlockCollisionStopsDirectFlightAndUsesExactContactForAreaActions(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:projectile_expiry", maxTicks = 60) @Override
    public void gravityAndDragAdvanceActualEntityAndExpiryRunsOnceWithoutInventingAnEntityHit(GameTestHelper h) throws Exception { super.gravityAndDragAdvanceActualEntityAndExpiryRunsOnceWithoutInventingAnEntityHit(h); }
    @GameTest(structure = "chorus_gametest:empty") @Override
    public void absentGeometryAndUnknownChunksRejectLaunchWhileStoppedRuntimeCannotExecuteOldFlight(GameTestHelper h) throws Exception { super.absentGeometryAndUnknownChunksRejectLaunchWhileStoppedRuntimeCannotExecuteOldFlight(h); }
    @GameTest(structure = "chorus_gametest:empty", environment = "chorus_gametest:projectile_failure", maxTicks = 60) @Override
    public void unknownImpactOutcomeRetainsCommittedDamageAndConsumesProjectileWithoutReplay(GameTestHelper h) throws Exception { super.unknownImpactOutcomeRetainsCommittedDamageAndConsumesProjectileWithoutReplay(h); }
}
