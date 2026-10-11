package com.imdomestic.chorus.core.codec;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;

/** Lossless data trees for explicitly registered records/enums. Never loads a class named by input. */
public final class RecordTreeCodec {
    private record Schema(String id, Class<?> type, List<RecordComponent> fields, Constructor<?> constructor) {}
    private final Map<String, Schema> byId;
    private final Map<Class<?>, Schema> byClass;

    public RecordTreeCodec(Map<String, Class<?>> types) {
        var ids = new TreeMap<String, Schema>(); var classes = new HashMap<Class<?>, Schema>();
        types.forEach((id, type) -> {
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !(type.isRecord() || type.isEnum())
                    || !Modifier.isPublic(type.getModifiers()) || type.getTypeParameters().length != 0)
                throw new IllegalArgumentException("Invalid stored type: " + id);
            try {
                var fields = type.isRecord() ? List.of(type.getRecordComponents()) : List.<RecordComponent>of();
                var constructor = type.isRecord() ? type.getConstructor(fields.stream().map(RecordComponent::getType).toArray(Class<?>[]::new)) : null;
                var schema = new Schema(id, type, fields, constructor);
                if (ids.putIfAbsent(id, schema) != null || classes.putIfAbsent(type, schema) != null)
                    throw new IllegalArgumentException("Duplicate stored type: " + id);
            } catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Stored record needs a public constructor: " + id, error); }
        });
        byId = Map.copyOf(ids); byClass = Map.copyOf(classes);
    }
    public Set<Class<?>> types() { return byClass.keySet(); }
    public JsonElement encode(Object value) { return write(value, "$"); }
    public <T> T decode(JsonElement input, Class<T> type) {
        var value = read(input, "$"); require(type, value, "$"); return type.cast(value);
    }
    /** Field/enum inventory for detecting an accidental change to an established storage format. */
    public SortedMap<String, List<String>> schema() {
        var result = new TreeMap<String, List<String>>();
        byId.forEach((id, s) -> result.put(id, s.type().isEnum()
                ? Arrays.stream(s.type().getEnumConstants()).map(v -> ((Enum<?>) v).name()).toList()
                : s.fields().stream().map(f -> f.getName() + ":" + f.getGenericType().getTypeName()).toList()));
        return Collections.unmodifiableSortedMap(result);
    }
    private static JsonObject node(String kind, JsonElement value) {
        var result = new JsonObject(); result.addProperty("$", kind); result.add("v", value); return result;
    }
    private JsonElement write(Object value, String path) {
        try {
            Objects.requireNonNull(value, "Null stored value");
            if (value instanceof String string) return new JsonPrimitive(string);
            if (value instanceof Boolean bool) return new JsonPrimitive(bool);
            if (value instanceof Integer number) return node("int", new JsonPrimitive(number.toString()));
            if (value instanceof Long number) return node("long", new JsonPrimitive(number.toString()));
            if (value instanceof Double number) { finite(number); return node("double", new JsonPrimitive(number.toString())); }
            if (value instanceof Float number) { finite(number); return node("float", new JsonPrimitive(number.toString())); }
            if (value instanceof Optional<?> optional) return optional("optional", optional.stream().toList(), path);
            if (value instanceof OptionalInt optional) return optional("optional_int", optional.isPresent() ? List.of(optional.getAsInt()) : List.of(), path);
            if (value instanceof OptionalLong optional) return optional("optional_long", optional.isPresent() ? List.of(optional.getAsLong()) : List.of(), path);
            if (value instanceof OptionalDouble optional) return optional("optional_double", optional.isPresent() ? List.of(optional.getAsDouble()) : List.of(), path);
            if (value instanceof List<?> list) return node("list", sequence(list, path, false));
            if (value instanceof Set<?> set) return node("set", sequence(set, path, true));
            if (value instanceof Map<?, ?> map) {
                if (map instanceof SortedMap<?, ?> sorted && sorted.comparator() != null)
                    throw new IllegalArgumentException("Custom map comparators are not stored data");
                var entries = new ArrayList<JsonElement>();
                for (var entry : map.entrySet()) {
                    var pair = new JsonObject(); pair.add("key", write(entry.getKey(), path + ".key"));
                    pair.add("value", write(entry.getValue(), path + "[" + entry.getKey() + "]")); entries.add(pair);
                }
                entries.sort(Comparator.comparing(e -> e.getAsJsonObject().get("key").toString()));
                var array = new JsonArray(); entries.forEach(array::add);
                return node(map instanceof NavigableMap<?, ?> ? "sorted_map" : "map", array);
            }
            Class<?> type = value instanceof Enum<?> e ? e.getDeclaringClass() : value.getClass();
            var schema = byClass.get(type);
            if (schema == null) throw new IllegalArgumentException("Unregistered stored type: " + type.getName());
            if (value instanceof Enum<?> e) return node(schema.id(), new JsonPrimitive(e.name()));
            var fields = new JsonObject();
            for (var field : schema.fields()) {
                var child = field.getAccessor().invoke(value);
                require(field.getGenericType(), child, path + "." + field.getName());
                fields.add(field.getName(), write(child, path + "." + field.getName()));
            }
            return node(schema.id(), fields);
        } catch (ReflectiveOperationException | RuntimeException error) { throw error(path, error); }
    }
    private JsonObject optional(String kind, List<?> values, String path) { return node(kind, sequence(values, path, false)); }
    private JsonArray sequence(Collection<?> values, String path, boolean sort) {
        var elements = new ArrayList<JsonElement>(); int i = 0;
        for (var value : values) elements.add(write(value, path + "[" + i++ + "]"));
        if (sort) elements.sort(Comparator.comparing(JsonElement::toString));
        var array = new JsonArray(); elements.forEach(array::add); return array;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object read(JsonElement input, String path) {
        try {
            if (input instanceof JsonPrimitive primitive) {
                if (primitive.isString()) return primitive.getAsString();
                if (primitive.isBoolean()) return primitive.getAsBoolean();
                throw new IllegalArgumentException("Numbers require an explicit stored type");
            }
            var object = input.getAsJsonObject(); fields(object, Set.of("$", "v"));
            String kind = string(object.get("$")); var value = object.get("v");
            switch (kind) {
                case "int": return Integer.valueOf(string(value));
                case "long": return Long.valueOf(string(value));
                case "double": { double number = Double.parseDouble(string(value)); finite(number); return number; }
                case "float": { float number = Float.parseFloat(string(value)); finite(number); return number; }
                case "list", "set", "optional", "optional_int", "optional_long", "optional_double": {
                    var values = new ArrayList<Object>(); int i = 0;
                    for (var child : value.getAsJsonArray()) values.add(read(child, path + "[" + i++ + "]"));
                    if (kind.equals("list")) return List.copyOf(values);
                    if (kind.equals("set")) {
                        var set = new LinkedHashSet<>(values);
                        if (set.size() != values.size()) throw new IllegalArgumentException("Duplicate stored set member");
                        return Collections.unmodifiableSet(set);
                    }
                    if (values.size() > 1) throw new IllegalArgumentException("Optional has multiple values");
                    if (kind.equals("optional")) return values.stream().findFirst();
                    Class<?> type = kind.equals("optional_int") ? Integer.class : kind.equals("optional_long") ? Long.class : Double.class;
                    if (!values.isEmpty()) require(type, values.getFirst(), path);
                    return switch (kind) {
                        case "optional_int" -> values.isEmpty() ? OptionalInt.empty() : OptionalInt.of((Integer) values.getFirst());
                        case "optional_long" -> values.isEmpty() ? OptionalLong.empty() : OptionalLong.of((Long) values.getFirst());
                        default -> values.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of((Double) values.getFirst());
                    };
                }
                case "map", "sorted_map": {
                    Map<Object, Object> map = kind.equals("map") ? new LinkedHashMap<>() : new TreeMap<>(); int i = 0;
                    for (var entry : value.getAsJsonArray()) {
                        var pair = entry.getAsJsonObject(); fields(pair, Set.of("key", "value"));
                        var key = read(pair.get("key"), path + "[" + i + "].key");
                        var child = read(pair.get("value"), path + "[" + i++ + "].value");
                        if (map.putIfAbsent(key, child) != null) throw new IllegalArgumentException("Duplicate stored map key");
                    }
                    return map instanceof NavigableMap<?, ?> sorted ? Collections.unmodifiableNavigableMap(sorted) : Collections.unmodifiableMap(map);
                }
            }
            var schema = byId.get(kind);
            if (schema == null) throw new IllegalArgumentException("Unknown stored type: " + kind);
            if (schema.type().isEnum()) return Enum.valueOf((Class) schema.type(), string(value));
            var stored = value.getAsJsonObject();
            fields(stored, new HashSet<>(schema.fields().stream().map(RecordComponent::getName).toList()));
            var arguments = new Object[schema.fields().size()];
            for (int i = 0; i < arguments.length; i++) {
                var field = schema.fields().get(i); var at = path + "." + field.getName();
                arguments[i] = read(stored.get(field.getName()), at); require(field.getGenericType(), arguments[i], at);
            }
            return schema.constructor().newInstance(arguments);
        } catch (ReflectiveOperationException | RuntimeException error) { throw error(path, error); }
    }
    private static String string(JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) throw new IllegalArgumentException("Expected a string");
        return primitive.getAsString();
    }
    private static void finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite stored number"); }
    private static void fields(JsonObject object, Set<String> expected) {
        if (!object.keySet().equals(expected)) throw new IllegalArgumentException("Stored fields differ: expected " + new TreeSet<>(expected) + ", found " + object.keySet());
    }
    private static IllegalArgumentException error(String path, Exception error) {
        var cause = error instanceof InvocationTargetException invocation ? invocation.getCause() : error;
        return new IllegalArgumentException(path + ": " + cause.getMessage(), cause);
    }
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(int.class, Integer.class, long.class, Long.class,
            double.class, Double.class, float.class, Float.class, boolean.class, Boolean.class);
    private static void require(Type type, Object value, String path) {
        if (type instanceof Class<?> raw) {
            if (!BOXES.getOrDefault(raw, raw).isInstance(value)) throw new IllegalArgumentException(path + ": Expected " + raw.getTypeName());
        } else if (type instanceof ParameterizedType parameterized) {
            require(parameterized.getRawType(), value, path); var args = parameterized.getActualTypeArguments();
            if (value instanceof Map<?, ?> map) map.forEach((key, child) -> { require(args[0], key, path + ".key"); require(args[1], child, path + ".value"); });
            else if (value instanceof Collection<?> collection) collection.forEach(child -> require(args[0], child, path + "[]"));
            else if (value instanceof Optional<?> optional) optional.ifPresent(child -> require(args[0], child, path + "?"));
            else throw new IllegalArgumentException("Unsupported stored generic type: " + type);
        } else if (type instanceof WildcardType wildcard) {
            for (var bound : wildcard.getUpperBounds()) require(bound, value, path);
        } else throw new IllegalArgumentException("Unresolved stored type: " + type);
    }
}
