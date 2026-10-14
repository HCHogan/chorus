package com.imdomestic.chorus;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PlayerAbilitiesGameTest extends com.imdomestic.chorus.test.PlayerAbilitiesGameTest {
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_commands",maxTicks=50) @Override
    public void commandsCommitCompleteSelectionsPreserveSpentEnergyAndCancelOldHeldInput(GameTestHelper h) { super.commandsCommitCompleteSelectionsPreserveSpentEnergyAndCancelOldHeldInput(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_nbt",maxTicks=50) @Override
    public void realPlayerNbtRetainsSelectionAndMissingOrMalformedContentCannotEraseIt(GameTestHelper h) { super.realPlayerNbtRetainsSelectionAndMissingOrMalformedContentCannotEraseIt(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_respawn",maxTicks=50) @Override
    public void actualRespawnRetainsBaseSelectionAndSpentPoolWithoutReplayingAttachment(GameTestHelper h) { super.actualRespawnRetainsBaseSelectionAndSpentPoolWithoutReplayingAttachment(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_restore",maxTicks=50) @Override
    public void checkpointMatchesPlayerSelectionAndRestoresTimerAndSpentPoolWithoutInitialization(GameTestHelper h) { super.checkpointMatchesPlayerSelectionAndRestoresTimerAndSpentPoolWithoutInitialization(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_unknown",maxTicks=50) @Override
    public void unknownAttachmentRetainsPlayerSelectionAndDomainWithoutRetryOrRefund(GameTestHelper h) { super.unknownAttachmentRetainsPlayerSelectionAndDomainWithoutRetryOrRefund(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_late_join",maxTicks=50) @Override
    public void lateLoginMustMatchSavedSelectionBeforeAnyCastOrAutomaticReplacement(GameTestHelper h) { super.lateLoginMustMatchSavedSelectionBeforeAnyCastOrAutomaticReplacement(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_dimension",maxTicks=80) @Override
    public void returningAfterAnotherDimensionSelectionUsesCurrentPlayerFile(GameTestHelper h) { super.returningAfterAnotherDimensionSelectionUsesCurrentPlayerFile(h); }
    @GameTest(structure="chorus_gametest:empty",environment="chorus_gametest:player_abilities_save_boundary",maxTicks=50) @Override
    public void frozenWorldSaveDetectsChangedPlayerSelectionWithoutReplayingEffects(GameTestHelper h) { super.frozenWorldSaveDetectsChangedPlayerSelectionWithoutReplayingEffects(h); }
}
