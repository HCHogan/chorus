package com.imdomestic.chorus.effect.data;

import static com.imdomestic.chorus.core.codec.DataCodecs.*;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;

/** A data-pack source, before its named imports and cross-fragment references are resolved. */
public record ProgramModule(EffectProgram program, List<String> imports, boolean fragment) {
    public ProgramModule {
        Objects.requireNonNull(program); imports = List.copyOf(imports);
        if (program.version().isBlank()) throw new IllegalArgumentException("Missing module ruleset version");
        if (new HashSet<>(imports).size() != imports.size()) throw new IllegalArgumentException("Duplicate module import");
        imports.forEach(ProgramModule::requireId);
    }
    static void requireId(String id) {
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid module identity: " + id);
    }
    private record Header(List<String> imports, boolean fragment) {}
    private static final Codec<Header> HEADER = RecordCodecBuilder.create(i -> i.group(
            ID.listOf().optionalFieldOf("imports", List.of()).forGetter(Header::imports),
            Codec.BOOL.optionalFieldOf("fragment", false).forGetter(Header::fragment)
    ).apply(i, Header::new));

    /** Keep the existing program JSON shape; only these two module fields are stripped before strict decoding. */
    public static Codec<ProgramModule> codec(Codec<EffectProgram> programCodec) {
        return new Codec<>() {
            @Override public <T> DataResult<Pair<ProgramModule, T>> decode(DynamicOps<T> ops, T input) {
                return HEADER.parse(ops, input).flatMap(header -> programCodec.parse(ops, ops.remove(ops.remove(input, "imports"), "fragment"))
                        .flatMap(program -> safe(() -> new ProgramModule(program, header.imports(), header.fragment()))))
                        .map(module -> Pair.of(module, ops.empty()));
            }
            @Override public <T> DataResult<T> encode(ProgramModule module, DynamicOps<T> ops, T prefix) {
                return programCodec.encode(module.program(), ops, prefix)
                        .flatMap(encoded -> HEADER.encode(new Header(module.imports(), module.fragment()), ops, encoded));
            }
        };
    }
    public static final Codec<ProgramModule> CODEC = codec(EffectCodecs.PROGRAM);
}
