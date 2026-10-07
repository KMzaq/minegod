package com.sande.mythictrpg.recording.server;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** The five server-owned recording stores. Paths are not supplied by a model or a packet. */
public final class ManagedStoreRegistry {
    public static final String RECORDING = "recording-v2", ACTION_LEDGER = "action-ledger-v1",
            GOD_WATCH = "god-watch-v1", RUMOR = "rumor-state-v1", REPUTATION = "reputation-state-v1";
    private static final Map<String, String> RELATIVE = Map.of(
            RECORDING, "mythictrpg-recording-v2", ACTION_LEDGER, "mythictrpg-action-ledger-v1",
            GOD_WATCH, "mythictrpg-god-watch-v1", RUMOR, "data/mythictrpg_memory_rumor_v1.dat",
            REPUTATION, "data/mythictrpg_reputation_judgement_v1.dat");
    public record Measurement(long totalBytes, Map<String, Long> storeBytes) {
        public Measurement { storeBytes = Map.copyOf(storeBytes); }
    }
    private final Path worldRoot;
    private final Map<String, Path> roots;

    private ManagedStoreRegistry(Path worldRoot) throws IOException {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.worldRoot, LinkOption.NOFOLLOW_LINKS)
                || !this.worldRoot.toRealPath().equals(this.worldRoot)) throw new IOException("QUOTA_UNSAFE_WORLD_ROOT");
        var paths = new LinkedHashMap<String, Path>();
        for (String id : new TreeSet<>(RELATIVE.keySet())) {
            Path path = this.worldRoot.resolve(RELATIVE.get(id)).normalize();
            validate(path);
            if (paths.values().stream().anyMatch(other -> path.startsWith(other) || other.startsWith(path)))
                throw new IOException("QUOTA_OVERLAPPING_STORE");
            paths.put(id, path);
        }
        roots = Collections.unmodifiableMap(paths);
    }
    /** Read-only: absent store directories/files are not created. The world itself must exist. */
    public static ManagedStoreRegistry open(Path worldRoot) throws IOException { return new ManagedStoreRegistry(worldRoot); }
    public Path worldRoot() { return worldRoot; }
    public Set<String> storeIds() { return roots.keySet(); }
    public Path root(String storeId) {
        Path result = roots.get(storeId);
        if (result == null) throw new IllegalArgumentException("Unregistered recording store");
        return result;
    }
    public boolean owns(String storeId, Path path) {
        Path target = path.toAbsolutePath().normalize(), root = root(storeId);
        if (directoryStore(storeId)) return target.startsWith(root);
        return target.getParent().equals(root.getParent()) && target.getFileName().toString().startsWith(root.getFileName().toString());
    }
    /** Full physical file-length sum, including inactive copies, temporary files and abandoned recovery files. */
    public Measurement measure() throws IOException {
        // Atomic moves by another already-reserved writer can remove a listed temp between list/stat.
        // Retry the entire snapshot, never silently subtract the disappeared entry or ignore access errors.
        for (int attempt = 0; ; attempt++) {
            try { return measureOnce(); }
            catch (NoSuchFileException moved) { if (attempt >= 2) throw moved; }
        }
    }
    private Measurement measureOnce() throws IOException {
        var measured = new LinkedHashMap<String, Long>();
        var seen = new HashSet<Path>();
        long total = 0;
        try {
            for (var entry : roots.entrySet()) {
                String id = entry.getKey(); Path root = entry.getValue(); validate(root);
                long bytes = 0;
                if (directoryStore(id)) {
                    if (exists(root)) {
                        if (!Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
                            throw new IOException("QUOTA_STORE_NOT_DIRECTORY");
                        bytes = treeBytes(root, seen);
                    }
                } else {
                    // All same-name siblings are counted, not just today's .neoforge-tmp suffix.
                    Path parent = root.getParent(); validate(parent);
                    if (exists(parent)) {
                        try (var children = Files.newDirectoryStream(parent, root.getFileName() + "*")) {
                            for (Path child : children) bytes = Math.addExact(bytes, fileBytes(child, seen));
                        }
                    }
                }
                measured.put(id, bytes); total = Math.addExact(total, bytes);
            }
        } catch (ArithmeticException overflow) { throw new IOException("QUOTA_MEASUREMENT_OVERFLOW", overflow); }
        return new Measurement(total, measured);
    }
    private static boolean directoryStore(String id) { return !id.equals(RUMOR) && !id.equals(REPUTATION); }
    private long treeBytes(Path root, Set<Path> seen) throws IOException {
        final long[] bytes = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                validate(dir);
                if (attributes.isOther() || attributes.isSymbolicLink()) throw new IOException("QUOTA_UNSAFE_DIRECTORY");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                bytes[0] = Math.addExact(bytes[0], fileBytes(file, seen)); return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }
    private long fileBytes(Path path, Set<Path> seen) throws IOException {
        validate(path);
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || !seen.add(path.toRealPath())) throw new IOException("QUOTA_UNEXPECTED_OR_DUPLICATE_FILE");
        return attrs.size();
    }
    /** Rechecked before each measurement/write; links and Windows junction aliases are never traversed. */
    public void validate(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(worldRoot)) throw new IOException("QUOTA_PATH_OUTSIDE_WORLD");
        Path cursor = worldRoot;
        checkExisting(cursor);
        for (Path part : worldRoot.relativize(normalized)) { cursor = cursor.resolve(part); checkExisting(cursor); }
    }
    private void checkExisting(Path path) throws IOException {
        if (!exists(path)) return;
        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path)
                || !path.toRealPath().startsWith(worldRoot)) throw new IOException("QUOTA_SYMLINK_OR_JUNCTION");
    }
    private static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException absent) { return false; }
    }
}
