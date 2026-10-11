package com.imdomestic.chorus.mixin;

import com.imdomestic.chorus.platform.minecraft.SaveSerializationCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.ProblemReporter;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ProblemReporter.ScopedCollector.class)
abstract class SerializationProblemMixin {
    @WrapMethod(method = "close")
    private void chorus$problems(Operation<Void> original) {
        var reporter = (ProblemReporter.ScopedCollector) (Object) this;
        if (SaveSerializationCapture.active() && !reporter.isEmpty()) SaveSerializationCapture.problem(reporter.getReport());
        original.call();
    }
}
