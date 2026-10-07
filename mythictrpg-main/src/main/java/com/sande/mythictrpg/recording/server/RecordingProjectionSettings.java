package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Independent opt-in: creating the archive or enabling SHADOW never schedules model work. */
public record RecordingProjectionSettings(Mode projectionMode) {
    public enum Mode { OFF, ON }
    public static final RecordingProjectionSettings OFF = new RecordingProjectionSettings(Mode.OFF);
    public RecordingProjectionSettings { java.util.Objects.requireNonNull(projectionMode); }

    public static RecordingProjectionSettings load(Path path) throws IOException {
        if (!Files.exists(path)) return OFF;
        if (!Files.isRegularFile(path) || Files.size(path) > 4096) throw new IOException("INVALID_PROJECTION_CONFIG");
        try {
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!json.keySet().equals(Set.of("schemaVersion", "projectionMode"))
                    || !json.get("schemaVersion").isJsonPrimitive() || !json.get("schemaVersion").getAsJsonPrimitive().isNumber()
                    || json.get("schemaVersion").getAsBigDecimal().intValueExact() != 1
                    || !json.get("projectionMode").isJsonPrimitive() || !json.get("projectionMode").getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("INVALID_PROJECTION_CONFIG");
            return new RecordingProjectionSettings(Mode.valueOf(json.get("projectionMode").getAsString()));
        } catch (RuntimeException invalid) { throw new IOException("INVALID_PROJECTION_CONFIG", invalid); }
    }
}
