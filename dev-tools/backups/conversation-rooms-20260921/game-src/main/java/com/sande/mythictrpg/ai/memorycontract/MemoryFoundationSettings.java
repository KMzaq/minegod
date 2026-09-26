package com.sande.mythictrpg.ai.memorycontract;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Files;
import java.util.Locale;

/** Restart-only opt-in; absence never creates or rewrites the user's configuration. */
public final class MemoryFoundationSettings {
    public enum Mode { OFF, PERSONAL, RUMOR_TEST }
    private static Mode mode;
    private MemoryFoundationSettings() {}

    public static synchronized Mode mode() {
        if (mode != null) return mode;
        mode = Mode.OFF;
        var path = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/ai-memory-foundation.json");
        try {
            if (!Files.exists(path)) return mode;
            if (Files.size(path) > 4096) throw new IllegalArgumentException("Configuration too large");
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (json.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException("Unknown schema");
            mode = Mode.valueOf(json.get("mode").getAsString().toUpperCase(Locale.ROOT));
        } catch (Exception failure) {
            MythicTrpg.LOGGER.error("Memory foundation disabled: invalid configuration", failure);
        }
        return mode;
    }

    public static synchronized void reset() { mode = null; }
    static synchronized void withModeForTest(Mode value, Runnable test) {
        Mode previous = mode;
        try { mode = value; test.run(); } finally { mode = previous; }
    }
}
