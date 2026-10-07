package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

/** Explicit native-only opt-in. Existing archive/legacy semantic settings never grant model work. */
public record RecordingEmbeddingSettings(Mode embeddingMode) {
    public enum Mode { OFF, SHADOW }
    public static final RecordingEmbeddingSettings OFF = new RecordingEmbeddingSettings(Mode.OFF);
    public RecordingEmbeddingSettings { java.util.Objects.requireNonNull(embeddingMode); }
    public static RecordingEmbeddingSettings load(Path path) throws IOException {
        if (!Files.exists(path)) return OFF;
        if (!Files.isRegularFile(path) || Files.size(path) > 4096) throw new IOException("INVALID_EMBEDDING_CONFIG");
        try {
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!json.keySet().equals(Set.of("schemaVersion", "embeddingMode"))
                    || !json.get("schemaVersion").isJsonPrimitive() || !json.get("schemaVersion").getAsJsonPrimitive().isNumber()
                    || json.get("schemaVersion").getAsBigDecimal().intValueExact() != 1
                    || !json.get("embeddingMode").isJsonPrimitive() || !json.get("embeddingMode").getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("INVALID_EMBEDDING_CONFIG");
            return new RecordingEmbeddingSettings(Mode.valueOf(json.get("embeddingMode").getAsString()));
        } catch (RuntimeException invalid) { throw new IOException("INVALID_EMBEDDING_CONFIG", invalid); }
    }
}
