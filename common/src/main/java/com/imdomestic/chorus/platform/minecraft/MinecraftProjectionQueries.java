package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.EffectEvent;
import com.imdomestic.chorus.effect.EffectState;
import com.imdomestic.chorus.effect.attribute.NativeAttributeBinding;
import com.imdomestic.chorus.effect.data.CompiledEffects;
import com.imdomestic.chorus.effect.input.ActionGate;
import com.imdomestic.chorus.effect.motion.HorizontalSpeedLimit;
import com.imdomestic.chorus.effect.resource.ResourceReadView;
import java.util.List;
import java.util.Optional;

/** One reconciliation's frozen query input; never retained by a projection or stored in an entity. */
record MinecraftProjectionQueries(CompiledEffects program, EffectState state, Optional<ResourceReadView> resources) {
    MinecraftProjectionQueries {
        if (program.resourceRouting() && resources.isEmpty())
            throw new IllegalStateException("Coordinated native projections require an explicit resource view");
        resources.ifPresent(view -> view.require(program.program(), state));
    }
    MinecraftProjectionQueries(CompiledEffects program, EffectState state) { this(program, state, Optional.empty()); }
    MinecraftProjectionQueries(CompiledEffects program, ResourceReadView view) { this(program, view.state(), Optional.of(view)); }
    List<NativeAttributeBinding.Calculated> nativeAttributes(String holder) {
        return resources.map(view -> program.nativeAttributes(view, holder)).orElseGet(() -> program.nativeAttributes(state, holder));
    }
    ActionGate.Decision checkAction(ActionGate.Kind kind, ActionGate.Phase phase, EffectEvent input) {
        return resources.map(view -> program.checkAction(view, kind, phase, input)).orElseGet(() -> program.checkAction(state, kind, phase, input));
    }
    HorizontalSpeedLimit.Decision horizontalSpeedLimit(EffectEvent input) {
        return resources.map(view -> program.horizontalSpeedLimit(view, input)).orElseGet(() -> program.horizontalSpeedLimit(state, input));
    }
}
