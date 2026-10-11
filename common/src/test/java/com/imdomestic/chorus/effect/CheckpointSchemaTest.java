package com.imdomestic.chorus.effect;

import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Audit reachability and freeze v1 field/enum shapes; changing the fixture alone is not a migration. */
class CheckpointSchemaTest {
    @Test void allReachableBuiltInDataTypesAreRegisteredAndExistingStorageShapesStayStable() throws Exception {
        Path root = Path.of(EffectState.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var candidates = new ArrayList<Class<?>>();
        try (var paths = Files.walk(root.resolve("com/imdomestic/chorus"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = root.relativize(path).toString().replace('/', '.').replace(".class", "");
                if (!(name.contains(".effect.") || name.contains(".stat.") || name.contains(".rule."))) continue;
                var type = Class.forName(name, false, EffectState.class.getClassLoader());
                if ((type.isRecord() || type.isEnum()) && Modifier.isPublic(type.getModifiers()) && type.getTypeParameters().length == 0) candidates.add(type);
            }
        }
        var found = new TreeSet<Class<?>>(Comparator.comparing(Class::getName));
        var leaf = Set.of(String.class, boolean.class, int.class, long.class, double.class, float.class, Boolean.class, Integer.class, Long.class, Double.class, Float.class, OptionalLong.class, OptionalDouble.class, OptionalInt.class);
        var unknown = new TreeSet<String>();
        java.util.function.Consumer<Type> visit = new java.util.function.Consumer<>() {
            public void accept(Type input) {
                if (input instanceof ParameterizedType p) {
                    if (!(p.getRawType() instanceof Class<?> raw) || !(Map.class.isAssignableFrom(raw) || Collection.class.isAssignableFrom(raw) || raw == Optional.class))
                        unknown.add(input.toString());
                    for (var arg : p.getActualTypeArguments()) accept(arg); return;
                }
                if (input instanceof WildcardType w) { for (var bound : w.getUpperBounds()) accept(bound); return; }
                if (!(input instanceof Class<?> type)) { unknown.add(input.toString()); return; }
                if (leaf.contains(type)) return;
                if (type.isInterface()) {
                    var implementations = candidates.stream().filter(type::isAssignableFrom).toList();
                    if (implementations.isEmpty()) unknown.add(type.getName());
                    implementations.forEach(this); return;
                }
                if (type.isRecord() || type.isEnum()) {
                    if (!found.add(type)) return;
                    if (type.isRecord()) for (var field : type.getRecordComponents()) accept(field.getGenericType());
                } else unknown.add(type.getName());
            }
        };
        visit.accept(EffectState.class); visit.accept(com.imdomestic.chorus.effect.data.EffectProgram.class);
        assertTrue(unknown.isEmpty(), "Unsupported stored field types: " + unknown);
        var codec = com.imdomestic.chorus.effect.persistence.EffectCheckpointCodec.DATA;
        assertEquals(found, codec.types());
        try (var reader = new java.io.InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/persistence/checkpoint-v1-schema.json")), java.nio.charset.StandardCharsets.UTF_8)) {
            assertEquals(com.google.gson.JsonParser.parseReader(reader), new com.google.gson.Gson().toJsonTree(codec.schema()),
                    "An existing storage shape changed: implement format migration instead of silently rewriting v1");
        }
    }
}
