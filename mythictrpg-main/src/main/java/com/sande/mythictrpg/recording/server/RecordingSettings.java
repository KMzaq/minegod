package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

/** Separate from legacy retention settings. Loading does not create a config, world identity or database. */
public record RecordingSettings(Mode archiveMode, long worldRecordingLimitBytes, long maintenanceHeadroomBytes,
        double warningRatio, double deferBackgroundRatio) {
    public enum Mode { OFF, RECORD_ONLY, SHADOW }
    public static final long DEFAULT_LIMIT = 100_000_000_000L, DEFAULT_HEADROOM = 1_000_000_000L;
    public RecordingSettings {
        if (archiveMode == null || worldRecordingLimitBytes <= 0 || maintenanceHeadroomBytes < 0
                || maintenanceHeadroomBytes >= worldRecordingLimitBytes || !Double.isFinite(warningRatio)
                || !Double.isFinite(deferBackgroundRatio) || warningRatio <= 0 || warningRatio >= deferBackgroundRatio
                || deferBackgroundRatio >= 1) throw new IllegalArgumentException("INVALID_RECORDING_SETTINGS");
    }
    public static RecordingSettings off() { return new RecordingSettings(Mode.OFF, DEFAULT_LIMIT, DEFAULT_HEADROOM, .90, .95); }
    public static RecordingSettings load(Path config) throws IOException {
        if (!Files.exists(config)) return off();
        if (Files.size(config) > 65536) throw new IOException("RECORDING_CONFIG_TOO_LARGE");
        try {
            var json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            var fields = Set.of("schemaVersion", "archiveMode", "worldRecordingLimitBytes", "maintenanceHeadroomBytes",
                    "warningRatio", "deferBackgroundRatio", "overflowPolicy", "automaticRawDeletion", "importLegacyTestData");
            if (!json.keySet().equals(fields)) throw new IllegalArgumentException("INVALID_RECORDING_FIELDS");
            for (String number : Set.of("schemaVersion", "worldRecordingLimitBytes", "maintenanceHeadroomBytes", "warningRatio", "deferBackgroundRatio"))
                if (!json.get(number).isJsonPrimitive() || !json.get(number).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("EXPECTED_NUMBER");
            for (String bool : Set.of("automaticRawDeletion", "importLegacyTestData"))
                if (!json.get(bool).isJsonPrimitive() || !json.get(bool).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("EXPECTED_BOOLEAN");
            for (String string : Set.of("archiveMode", "overflowPolicy"))
                if (!json.get(string).isJsonPrimitive() || !json.get(string).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("EXPECTED_STRING");
            if (json.get("schemaVersion").getAsBigDecimal().longValueExact() != 2
                    || !"STOP_NEW_RECORDS".equals(json.get("overflowPolicy").getAsString())
                    || json.get("automaticRawDeletion").getAsBoolean() || json.get("importLegacyTestData").getAsBoolean())
                throw new IllegalArgumentException("UNSUPPORTED_RECORDING_POLICY");
            return new RecordingSettings(Mode.valueOf(json.get("archiveMode").getAsString()),
                    json.get("worldRecordingLimitBytes").getAsBigDecimal().longValueExact(),
                    json.get("maintenanceHeadroomBytes").getAsBigDecimal().longValueExact(),
                    json.get("warningRatio").getAsDouble(), json.get("deferBackgroundRatio").getAsDouble());
        } catch (RuntimeException invalid) { throw new IOException("INVALID_RECORDING_CONFIG", invalid); }
    }
}
