package com.imdomestic.chorus.test.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.IOException;
import java.nio.file.*;
import net.minecraft.gametest.framework.GameTestMainUtil;
import org.spongepowered.asm.mixin.Mixin;

/** NeoForge's vanilla GameTest launcher normally deletes the universe at every process start. */
@Mixin(GameTestMainUtil.class)
abstract class RestartUniverseMixin {
    @WrapMethod(method = "createOrResetDir")
    private static void chorus$preserveRestoreFixture(String universe, Operation<Void> original) throws IOException {
        String phase = System.getProperty("chorus.test.restart.phase", "");
        if (phase.startsWith("restore")) {
            if (!Files.isRegularFile(Path.of(universe).resolve("gametestworld/level.dat")))
                throw new IOException("No existing restart fixture world to preserve");
            return;
        }
        original.call(universe);
    }
}
