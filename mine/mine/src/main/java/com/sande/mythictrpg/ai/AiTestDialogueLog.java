package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test-only asynchronous transcript writer. It owns no gameplay or conversation state. */
final class AiTestDialogueLog {
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mythictrpg-ai-test-log");
        thread.setDaemon(true);
        return thread;
    });

    void append(MinecraftServer server, UUID playerId, String kind, String text) {
        if (server == null || playerId == null) {
            return;
        }
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("mythictrpg-ai-test-logs")
                .resolve(playerId.toString()).resolve("ai-test-" + LocalDate.now() + ".log");
        String line = "[" + java.time.Instant.now() + "][" + kind + "] "
                + (text == null ? "" : text.replace('\r', ' ').replace('\n', ' ')) + System.lineSeparator();
        write(file, line);
    }

    void appendBlock(MinecraftServer server, UUID playerId, String kind, String text) {
        if (server == null || playerId == null) {
            return;
        }
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("mythictrpg-ai-test-logs")
                .resolve(playerId.toString()).resolve("ai-test-" + LocalDate.now() + ".log");
        String block = "\n[" + java.time.Instant.now() + "][" + kind + "]\n"
                + (text == null ? "" : text) + "\n[END " + kind + "]\n";
        write(file, block);
    }

    private void write(Path file, String text) {
        writer.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException exception) {
                MythicTrpg.LOGGER.warn("Could not write AI test dialogue log {}", file, exception);
            }
        });
    }

    void close() {
        writer.shutdownNow();
    }
}
