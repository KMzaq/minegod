package com.sande.mythictrpg.recording.server;

import java.nio.file.*;

/** Synthetic build config only. No activation or operating settings changes. */
public final class RecordingEmbeddingSettingsTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory(Path.of(args[0]), "embedding-settings-");
        var path = directory.resolve("recording-embedding.json"); int checks = 0;
        if (RecordingEmbeddingSettings.load(path) != RecordingEmbeddingSettings.OFF || Files.exists(path)) throw new AssertionError("missing config must stay absent/OFF"); checks++;
        for (String mode : new String[]{"OFF", "SHADOW"}) {
            Files.writeString(path, "{\"schemaVersion\":1,\"embeddingMode\":\"" + mode + "\"}");
            if (!RecordingEmbeddingSettings.load(path).embeddingMode().name().equals(mode)) throw new AssertionError("explicit native mode"); checks++;
        }
        for (String invalid : new String[]{"{}", "{\"schemaVersion\":1,\"embeddingMode\":\"ON\"}",
                "{\"schemaVersion\":1.1,\"embeddingMode\":\"SHADOW\"}", "{\"schemaVersion\":\"1\",\"embeddingMode\":\"SHADOW\"}",
                "{\"schemaVersion\":1,\"embeddingMode\":true}", "{\"schemaVersion\":1,\"embeddingMode\":\"SHADOW\",\"extra\":0}"}) {
            Files.writeString(path, invalid); boolean rejected = false;
            try { RecordingEmbeddingSettings.load(path); } catch (java.io.IOException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("strict native opt-in"); checks++;
        }
        System.out.println("RecordingEmbeddingSettingsTest: " + checks + " checks passed");
    }
}
