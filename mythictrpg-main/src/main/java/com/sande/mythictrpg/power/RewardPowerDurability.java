package com.sande.mythictrpg.power;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Session fence for independently saved inventory/receipts/evidence; uncertainty invalidates coverage, not rewards. */
final class RewardPowerDurability {
    static final String MARKER = "mythictrpg_reward_power_session.json";
    private RewardPowerDurability() { }
    static boolean previouslyClean(Path directory, UUID savedSession) throws IOException {
        Path marker = directory.resolve(MARKER); checkDirectory(directory);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegular(marker);
        if (Files.size(marker) > 1024) return false;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8)).getAsJsonObject();
            return savedSession != null && json.keySet().equals(java.util.Set.of("schemaVersion", "session", "clean"))
                    && json.get("schemaVersion").getAsInt() == 1
                    && json.get("clean").getAsJsonPrimitive().isBoolean() && json.get("clean").getAsBoolean()
                    && UUID.fromString(json.get("session").getAsString()).equals(savedSession);
        } catch (RuntimeException invalid) { return false; }
    }
    static void write(Path directory, UUID session, boolean clean) throws IOException {
        checkDirectory(directory);
        Path marker = directory.resolve(MARKER), temp = directory.resolve(MARKER + ".tmp");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) requireRegular(marker);
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) requireRegular(temp);
        JsonObject json = new JsonObject(); json.addProperty("schemaVersion", 1);
        json.addProperty("session", session.toString()); json.addProperty("clean", clean);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel file = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
        }
        // Unsupported atomic replacement must fail closed; never silently fall back to an unverifiable fence.
        Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void checkDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        for (Path current = absolute; current != null; current = current.getParent())
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(current)
                    || Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isOther())) throw new IOException("Power session directory redirects");
        Files.createDirectories(absolute);
        if (!absolute.toRealPath().equals(absolute)) throw new IOException("Power session directory redirects");
    }
    private static void requireRegular(Path path) throws IOException {
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Power marker is not a regular file");
    }
}
