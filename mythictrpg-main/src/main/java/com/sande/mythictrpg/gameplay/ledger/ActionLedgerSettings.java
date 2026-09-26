package com.sande.mythictrpg.gameplay.ledger;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;

/** No operational storage/retention budget is silently chosen. Explicit capacity required to enable. */
public record ActionLedgerSettings(int schemaVersion, boolean enabled, long maxStorageBytes,
                                   int segmentBytes, int maxIndexedEvents, int queueCapacity) {
    public static final ActionLedgerSettings OFF = new ActionLedgerSettings(1, false, 0, 4 * 1024 * 1024, 100_000, 256);
    public record Loaded(ActionLedgerSettings settings, String reason) {}
    public ActionLedgerSettings {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported schema");
        if (enabled) {
            new ActionLedgerStore.Limits(maxStorageBytes, segmentBytes, maxIndexedEvents);
            if (queueCapacity < 1 || queueCapacity > 4096) throw new IllegalArgumentException("queue capacity");
        }
    }
    public ActionLedgerStore.Limits limits() {
        return new ActionLedgerStore.Limits(maxStorageBytes, segmentBytes, maxIndexedEvents);
    }
    /** Worker only; never called in a per-action callback or server tick. */
    public static Loaded load(Path path) {
        try {
            if (!Files.exists(path)) return new Loaded(OFF, "OFF_NO_CONFIG");
            if (Files.size(path) > 4096) return new Loaded(OFF, "OFF_INVALID_CONFIG_SIZE");
            ActionLedgerSettings settings = new Gson().fromJson(Files.readString(path), ActionLedgerSettings.class);
            if (settings == null) return new Loaded(OFF, "OFF_INVALID_CONFIG");
            return new Loaded(settings, settings.enabled ? "CONFIGURED" : "OFF_CONFIGURED");
        } catch (Exception failure) { return new Loaded(OFF, "OFF_INVALID_CONFIG"); }
    }
}
