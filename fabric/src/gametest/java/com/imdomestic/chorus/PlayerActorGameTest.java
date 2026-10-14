package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PlayerActorGameTest extends com.imdomestic.chorus.test.PlayerActorGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_dimension",maxTicks=80) @Override
    public void crossDimensionReturnAfterRestoreAcceptsUpdatedConfigurationAndKeepsHistoricalRules(GameTestHelper h) { super.crossDimensionReturnAfterRestoreAcceptsUpdatedConfigurationAndKeepsHistoricalRules(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_commands",maxTicks=50) @Override
    public void commandsPublishPlayerAndDomainTogetherAndRejectUntrustedOrStaleConfiguration(GameTestHelper h) { super.commandsPublishPlayerAndDomainTogetherAndRejectUntrustedOrStaleConfiguration(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_nbt",maxTicks=50) @Override
    public void nativePlayerNbtReactivatesOnceOnFreshRuntimeAndMalformedDataCannotEraseConfiguration(GameTestHelper h) { super.nativePlayerNbtReactivatesOnceOnFreshRuntimeAndMalformedDataCannotEraseConfiguration(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_respawn",maxTicks=50) @Override
    public void actualDeathAndRespawnMoveSavedConfigurationWithoutReplayingRootInitialization(GameTestHelper h) { super.actualDeathAndRespawnMoveSavedConfigurationWithoutReplayingRootInitialization(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_restore",maxTicks=50) @Override
    public void checkpointValidatesPhysicalConfigurationAndRestoresTimersWithoutAttachReplay(GameTestHelper h) { super.checkpointValidatesPhysicalConfigurationAndRestoresTimersWithoutAttachReplay(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_unknown",maxTicks=50) @Override
    public void unknownNativeAttachmentPreservesPlayerSaveAndDomainWithoutRetryingHealing(GameTestHelper h) { super.unknownNativeAttachmentPreservesPlayerSaveAndDomainWithoutRetryingHealing(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_missing",maxTicks=50) @Override
    public void missingContentLeavesSavedConfigurationInactiveUntilItsDefinitionReturns(GameTestHelper h) { super.missingContentLeavesSavedConfigurationInactiveUntilItsDefinitionReturns(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_late_join",maxTicks=50) @Override
    public void playerJoiningAfterRestoreCannotReplaceCheckpointConfigurationBeforeValidation(GameTestHelper h) { super.playerJoiningAfterRestoreCannotReplaceCheckpointConfigurationBeforeValidation(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_actor_save_boundary",maxTicks=50) @Override
    public void frozenWorldSaveChecksPersistentPlayerConfigurationAsWellAsDomainState(GameTestHelper h) { super.frozenWorldSaveChecksPersistentPlayerConfigurationAsWellAsDomainState(h); }
}
