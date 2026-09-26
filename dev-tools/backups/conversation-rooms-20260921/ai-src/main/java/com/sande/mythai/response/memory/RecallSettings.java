package com.sande.mythai.response.memory;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Separate, fail-closed opt-in. Missing/invalid configuration preserves the 0.1.3 path. */
public record RecallSettings(boolean enabled, TimeBasis timeBasis) {
    public enum TimeBasis { UNSPECIFIED, REAL_KST }
    public static final RecallSettings OFF = new RecallSettings(false, TimeBasis.UNSPECIFIED);
    public static RecallSettings load(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 4096) return OFF;
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (json.get("schemaVersion").getAsInt() != 1 || !json.get("recallV2").getAsJsonPrimitive().isBoolean()) return OFF;
            return new RecallSettings(json.get("recallV2").getAsBoolean(),
                    TimeBasis.valueOf(json.get("timeBasis").getAsString()));
        } catch (Exception invalid) { return OFF; }
    }
}
