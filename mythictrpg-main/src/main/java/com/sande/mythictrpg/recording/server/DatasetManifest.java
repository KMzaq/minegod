package com.sande.mythictrpg.recording.server;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

record DatasetManifest(int schemaVersion, UUID worldId, UUID datasetId, String createdAtUtc, String buildVersion,
        Map<String, Long> sourceHighWatermarks, List<String> excludedLegacyRoots, String completeness) {
    private static final Gson JSON = new Gson();
    private static final List<String> EXCLUDED = List.of("mythictrpg-ai-memory", "mythictrpg-ai-memory-lp-v1",
            "mythictrpg-ai-memory-rumor-test-v1", "mythictrpg-ai-test-logs", "mythictrpg-ai-room-logs", "legacy-server-ai-dialogue-logs");
    DatasetManifest {
        Objects.requireNonNull(worldId); Objects.requireNonNull(datasetId); Instant.parse(createdAtUtc);
        sourceHighWatermarks = Map.copyOf(sourceHighWatermarks); excludedLegacyRoots = List.copyOf(excludedLegacyRoots);
        if (schemaVersion != 2 || buildVersion == null || buildVersion.isBlank() || sourceHighWatermarks.values().stream().anyMatch(n -> n < 0)
                || !excludedLegacyRoots.equals(EXCLUDED) || !"NEW_DATASET_NO_LEGACY_IMPORT".equals(completeness))
            throw new IllegalArgumentException("INVALID_DATASET_MANIFEST");
    }
    static DatasetManifest create(UUID world, WorldRecordingService.CutoverBoundary boundary) {
        return new DatasetManifest(2, world, UUID.randomUUID(), Instant.now().toString(), boundary.buildVersion(),
                boundary.durableSourceHighWatermarks(), EXCLUDED, "NEW_DATASET_NO_LEGACY_IMPORT");
    }
    static DatasetManifest read(Path file, UUID world, WorldRecordingService.CutoverBoundary current) throws IOException {
        try {
            if (Files.size(file) > 65536) throw new IOException("MANIFEST_TOO_LARGE");
            var object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!object.keySet().equals(Set.of("schemaVersion", "worldId", "datasetId", "createdAtUtc", "buildVersion",
                    "sourceHighWatermarks", "excludedLegacyRoots", "completeness"))) throw new IOException("UNKNOWN_MANIFEST_FIELDS");
            var result = JSON.fromJson(object, DatasetManifest.class);
            if (!result.worldId().equals(world)) throw new IOException("WORLD_IDENTITY_MISMATCH");
            for (var source : result.sourceHighWatermarks().entrySet())
                if (current.durableSourceHighWatermarks().getOrDefault(source.getKey(), -1L) < source.getValue())
                    throw new IOException("SOURCE_CURSOR_REGRESSED_OR_UNAVAILABLE");
            return result;
        } catch (RuntimeException invalid) { throw new IOException("INVALID_DATASET_MANIFEST", invalid); }
    }
    void writeNew(Path file) throws IOException {
        byte[] bytes = JSON.toJson(this).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var temporary = file.resolveSibling("manifest.json.staging");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE); // Fail closed where atomic local rename is unavailable.
    }
}
