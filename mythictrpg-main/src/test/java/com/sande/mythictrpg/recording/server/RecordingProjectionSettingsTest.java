package com.sande.mythictrpg.recording.server;

import java.nio.file.*;

/** Temporary synthetic config only; never reads or edits the operating server's settings. */
public final class RecordingProjectionSettingsTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory(Path.of(args[0]), "projection-config-");
        var config = directory.resolve("recording-projection.json");
        check(RecordingProjectionSettings.load(config).projectionMode() == RecordingProjectionSettings.Mode.OFF, "missing is OFF");
        check(!Files.exists(config), "loading missing config does not create it");
        Files.writeString(config, "{\"schemaVersion\":1,\"projectionMode\":\"ON\"}");
        check(RecordingProjectionSettings.load(config).projectionMode() == RecordingProjectionSettings.Mode.ON, "explicit ON");
        Files.writeString(config, "{\"schemaVersion\":1,\"projectionMode\":\"OFF\"}");
        check(RecordingProjectionSettings.load(config).projectionMode() == RecordingProjectionSettings.Mode.OFF, "explicit OFF");
        for (String invalid : new String[]{"{}", "{\"schemaVersion\":1.1,\"projectionMode\":\"ON\"}",
                "{\"schemaVersion\":1,\"projectionMode\":true}", "{\"schemaVersion\":\"1\",\"projectionMode\":\"ON\"}",
                "{\"schemaVersion\":1,\"projectionMode\":\"ON\",\"unknown\":0}",
                "{\"schemaVersion\":2,\"projectionMode\":\"ON\"}", "{\"schemaVersion\":1,\"projectionMode\":\"on\"}"}) {
            Files.writeString(config, invalid);
            boolean rejected = false; try { RecordingProjectionSettings.load(config); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "strict opt-in");
        }
        System.out.println("RecordingProjectionSettingsTest: " + checks + " checks passed");
    }
}
