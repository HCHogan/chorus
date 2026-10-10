package com.imdomestic.chorus.platform.minecraft;

import com.imdomestic.chorus.effect.data.*;
import com.mojang.serialization.Codec;
import java.util.*;

/** Registry entry: linked once during reload validation, before the new registry is published. */
public final class LoadedProgram {
    public static final Codec<LoadedProgram> CODEC = ProgramModule.CODEC.xmap(LoadedProgram::new, LoadedProgram::source);
    private final ProgramModule source;
    private volatile Optional<CompiledEffects> compiled;
    public LoadedProgram(ProgramModule source) { this.source = Objects.requireNonNull(source); }
    public ProgramModule source() { return source; }
    void prepare(Optional<CompiledEffects> result) {
        if (compiled != null) throw new IllegalStateException("Program registry entry already linked");
        if (result.isPresent() == source.fragment()) throw new IllegalArgumentException("Program entry kind differs from linked catalogue");
        compiled = result;
    }
    public Optional<CompiledEffects> compiled() {
        var result = compiled;
        if (result == null) throw new IllegalStateException("Program catalogue has not passed reload validation");
        return result;
    }
}
