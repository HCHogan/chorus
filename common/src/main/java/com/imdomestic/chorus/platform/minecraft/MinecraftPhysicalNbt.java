package com.imdomestic.chorus.platform.minecraft;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Objects;
import net.minecraft.nbt.*;

/** A typed digest of the native body, not proof that a restored entity applied that body. */
public final class MinecraftPhysicalNbt {
    public static final String KEY = "chorus_physical";
    public static final int DIGEST_FORMAT = 1;

    private MinecraftPhysicalNbt() {}

    /** Deep copy with only the root protocol marker removed. Nested keys are ordinary native data. */
    public static CompoundTag withoutMarker(CompoundTag nativeTag) {
        var result = Objects.requireNonNull(nativeTag).copy();
        result.remove(KEY);
        return result;
    }

    /**
     * Format 1: modified-UTF domain separator, int format, then recursively typed values. Compounds
     * have an int count and sorted modified-UTF keys; lists have an int count and typed elements.
     * Other payloads use native Tag.write, except floats retain their exact IEEE bits, including
     * signed zero and NaN payloads. This is a canonical digest encoding, not a replacement NBT file.
     * The caller must keep the input stable while it is read.
     */
    public static String digest(CompoundTag nativeTag) {
        Objects.requireNonNull(nativeTag);
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Missing SHA-256", impossible); }
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            output.writeUTF("chorus:physical_nbt");
            output.writeInt(DIGEST_FORMAT);
            write(nativeTag, output, true, 0);
        } catch (IOException error) {
            throw new IllegalArgumentException("Native NBT cannot be encoded for its physical digest", error);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void write(Tag tag, DataOutput output, boolean root, int depth) throws IOException {
        if ((tag instanceof CompoundTag || tag instanceof ListTag) && depth >= Tag.MAX_DEPTH)
            throw new IllegalArgumentException("Native NBT exceeds its maximum depth");
        output.writeByte(tag.getId());
        switch (tag) {
            case CompoundTag compound -> {
                var keys = new ArrayList<>(compound.keySet());
                if (root) keys.remove(KEY);
                keys.sort(String::compareTo);
                output.writeInt(keys.size());
                for (var key : keys) {
                    output.writeUTF(key);
                    write(Objects.requireNonNull(compound.get(key)), output, false, depth + 1);
                }
            }
            case ListTag list -> {
                output.writeInt(list.size());
                for (var value : list) write(value, output, false, depth + 1);
            }
            case FloatTag value -> output.writeInt(Float.floatToRawIntBits(value.value()));
            case DoubleTag value -> output.writeLong(Double.doubleToRawLongBits(value.value()));
            default -> tag.write(output);
        }
    }
}
