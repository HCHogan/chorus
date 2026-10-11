package com.imdomestic.chorus.core.codec;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Replaces one UTF-8 JSON file; no cross-file transaction or non-atomic fallback. */
public final class AtomicJsonFile {
    private AtomicJsonFile() {}
    public static void write(Path path, JsonElement value) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        Path target = path.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".chorus-checkpoint-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
