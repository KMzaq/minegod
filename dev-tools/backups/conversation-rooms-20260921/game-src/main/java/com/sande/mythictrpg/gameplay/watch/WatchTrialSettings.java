package com.sande.mythictrpg.gameplay.watch;

import com.google.gson.Gson;
import java.nio.file.*;

/** Server-local development trial, NOT production relationship/authority content. Missing config is OFF. */
public record WatchTrialSettings(int schemaVersion, boolean enabled, long maxStorageBytes, int maxEntries, int queueCapacity) {
    public static final WatchTrialSettings OFF = new WatchTrialSettings(1, false, 0, 10_000, 128);
    public WatchTrialSettings { if (schemaVersion != 1) throw new IllegalArgumentException("watch schema"); if (enabled) new AsyncGodWatch.Limits(maxStorageBytes, maxEntries, queueCapacity); }
    public AsyncGodWatch.Limits limits() { return new AsyncGodWatch.Limits(maxStorageBytes, maxEntries, queueCapacity); }
    public static WatchTrialSettings load(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 4096) return OFF;
            var settings = new Gson().fromJson(Files.readString(path), WatchTrialSettings.class);
            return settings == null ? OFF : settings;
        } catch (Exception invalid) { return OFF; }
    }
}
