package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Bounded async room diagnostics. Only an already-authorized recording turn may enqueue data. */
final class RoomDialogueLog implements AutoCloseable {
    private ThreadPoolExecutor writer;
    private final java.util.Map<String, Path> transcripts = new java.util.HashMap<>();
    /** Actual game publication, including dialogue without a request/LLM. Spectators are not duplicated into player logs. */
    synchronized CompletableFuture<Boolean> published(Path serverDirectory, RoomDialogueEvent event) {
        if (!event.recordingScope().recordingAllowed()) return CompletableFuture.completedFuture(false);
        var writes = new java.util.ArrayList<CompletableFuture<Boolean>>();
        String gods = event.godIds().stream().sorted().map(id -> id.substring(id.indexOf(':') + 1))
                .collect(java.util.stream.Collectors.joining("_"));
        for (var participant : event.participantNames().entrySet()) {
            var delivery = event.deliveries().get(participant.getKey());
            boolean author = "PLAYER".equals(event.role()) && participant.getKey().toString().equals(event.speakerId());
            if (delivery == null && !author) continue;
            String kind = "PLAYER".equals(event.role()) ? "PLAYER" : "DELIVERED";
            String content = "[message=" + event.messageId() + "][room=" + event.roomId() + "][revision=" + event.revision()
                    + "][sourceTurn=" + event.turnId().map(UUID::toString).orElse("NONE") + "]"
                    + "[chat=" + (delivery != null && delivery.chatDispatched()) + "][hudPages="
                    + (delivery == null ? 0 : delivery.hudPagesDispatched()) + "]\n" + event.speakerId() + ": " + event.text();
            // Never silently truncate an accepted transcript. This is still a bounded legacy TXT writer, not M1 RAW.
            if (content.length() > 131072) return CompletableFuture.failedFuture(new IOException("ROOM_TRANSCRIPT_TOO_LARGE"));
            writes.add(transcript(serverDirectory, event.roomId(), participant.getKey(), participant.getValue(), gods,
                    event.turnId().orElse(event.messageId()), true, kind, content));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).thenApply(ignored -> !writes.isEmpty());
    }
    synchronized CompletableFuture<Boolean> append(Path directory, UUID room, UUID player, UUID turn,
            boolean recording, String kind, String text) {
        if (!recording) return CompletableFuture.completedFuture(false);
        Path file = directory.resolve(room.toString()).resolve(player.toString()).resolve("room-" + LocalDate.now() + ".txt");
        return write(file, turn, kind, text);
    }
    synchronized CompletableFuture<Boolean> transcript(Path serverDirectory, UUID room, UUID player, String playerName,
            String godNames, UUID turn, boolean recording, String kind, String text) {
        if (!recording) return CompletableFuture.completedFuture(false);
        String key = room + "/" + player;
        Path file = transcripts.computeIfAbsent(key, ignored -> serverDirectory.resolve("ai-dialogue-logs")
                .resolve(segment(playerName, player.toString()))
                .resolve(LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                        + "_" + segment(godNames, "gods") + "_" + room + ".txt"));
        return write(file, turn, kind, text);
    }
    private static String segment(String value, String fallback) {
        String safe = value == null ? "" : value.replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return safe.isBlank() ? fallback : safe.substring(0, Math.min(120, safe.length()));
    }
    private CompletableFuture<Boolean> write(Path file, UUID turn, String kind, String text) {
        if (writer == null || writer.isShutdown()) writer = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), action -> { var t = new Thread(action, "mythai-room-log"); t.setDaemon(true); return t; });
        var result = new CompletableFuture<Boolean>();
        String safe = text == null ? "" : text.substring(0, Math.min(131072, text.length()));
        String block = "[" + Instant.now() + "][" + kind + "][turn=" + turn + "]\n" + safe + "\n[END " + kind + "]\n";
        try { writer.execute(() -> {
            try { Files.createDirectories(file.getParent()); Files.writeString(file, block, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND); result.complete(true); }
            catch (IOException failure) { result.completeExceptionally(failure); }
        }); } catch (RejectedExecutionException busy) { result.completeExceptionally(busy); }
        return result;
    }
    @Override public synchronized void close() {
        if (writer != null) { writer.shutdown(); writer = null; }
        transcripts.clear();
    }
}
