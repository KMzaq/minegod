package com.sande.mythictrpg.gameplay.ledger;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Pure file/queue/bytecode tests. Does not bootstrap Minecraft, GameTest, a server, or an LLM. */
public final class ActionLedgerTest {
    private static int checks;
    private static final UUID WORLD = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final ActionLedgerStore.Limits LIMITS = new ActionLedgerStore.Limits(16 * 1024 * 1024, 20_000, 10_000);
    private static Path root;
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--crash")) {
            var store = new ActionLedgerStore(Path.of(args[1]), UUID.fromString(args[3]), LIMITS, at -> {
                if (at.equals(args[2])) Runtime.getRuntime().halt(17); // Only this isolated fixture JVM, never a game process.
            });
            store.append(draft(UUID.randomUUID(), A, 1));
            throw new AssertionError("Crash fixture did not reach the boundary");
        }
        Files.createDirectories(Path.of(args[0])); root = Files.createTempDirectory(Path.of(args[0]), "ledger-stage02-");
        contract(); orderRestart(); faultRecovery(); processCrash(); corruption(); capacity(); asynchronous(); warnings(); settings(); hookBoundaries(); legacyPipelineUnchanged();
        for (int players : new int[]{1,4,6}) fixture(players);
        System.out.println("ActionLedgerTest: PASS (" + checks + " checks); artifacts=" + root);
        System.out.println("No Minecraft/GameTest/server/LLM started; hooks verified statically, not an in-game compatibility test.");
    }
    private static ActionRecord.Draft draft(UUID id, UUID actor, long order) {
        return new ActionRecord.Draft(id, SESSION, order, "mythictrpg:crop_remove_commit", 1, actor,
                new ActionRecord.Subject("BLOCK", "minecraft:wheat", null), 1_800_000_000_000L + order,
                42, 23_999, "minecraft:overworld", new ActionRecord.Position(1, 64, 2),
                ActionRecord.Type.MATURE_CROP_REMOVED, "COMPLETED", Map.of("quantity", "1", "result", "block_removed_not_item_acquisition",
                        "gameMode", "SURVIVAL_OR_ADVENTURE"), "mythictrpg:admin_only_unprojected");
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void fails(Throwing operation, String label) throws Exception {
        try { operation.run(); } catch (Exception expected) { check(true, label); return; }
        throw new AssertionError("Expected failure: " + label);
    }
    private static void contract() throws Exception {
        var first = draft(UUID.randomUUID(), A, 1);
        check(!first.dedupKey().equals(draft(UUID.randomUUID(), A, 1).dedupKey()), "same tick/location are distinct occurrences");
        fails(() -> first.payload().put("unsafe", "mutable"), "immutable payload");
        fails(() -> new ActionRecord(2, WORLD, 1, first), "unknown schema");
        fails(() -> new ActionRecord(1, WORLD, 0, first), "invalid committed order");
        fails(() -> new ActionRecord.Subject("GOD", "minecraft:zombie", A), "no invented god observer");
        fails(() -> new ActionRecord.Subject("ENTITY", "minecraft:zombie", null), "entity id required");
        fails(() -> new ActionLedgerStore.Cursor(null, 0), "cursor world required");
        check(new Gson().fromJson(new Gson().toJson(first), ActionRecord.Draft.class).equals(first), "wire roundtrip");
    }
    private static void orderRestart() throws Exception {
        Path path = root.resolve("order"); var entries = new ArrayList<ActionRecord.Draft>();
        try (var store = new ActionLedgerStore(path, WORLD, LIMITS)) {
            for (int i = 1; i <= 50; i++) {
                var input = draft(UUID.randomUUID(), i % 2 == 0 ? A : B, i); entries.add(input);
                var output = store.append(input);
                check(output.sequence() == i && output.event().equals(input), "individual count/order/raw immutable fact");
            }
            check(store.size() == 50, "same tick/subject not coalesced");
            check(store.append(entries.getFirst()).sequence() == 1 && store.size() == 50, "duplicate receipt idempotence");
            fails(() -> store.append(draft(entries.getFirst().occurrenceId(), A, 51)), "id collision different content rejected");
            var page = store.after(new ActionLedgerStore.Cursor(WORLD, 0), A, 7);
            check(page.records().size() == 7 && page.records().stream().allMatch(r -> r.event().actorId().equals(A)), "actor index isolation");
            check(page.next().sequence() == 14 && page.durableHead().sequence() == 50, "bounded pagination cursor");
            check(store.after(new ActionLedgerStore.Cursor(WORLD, 49), A, 10).records().size() == 1, "cursor resume");
            fails(() -> store.after(new ActionLedgerStore.Cursor(UUID.randomUUID(), 0), A, 10), "world scope rejected");
            fails(() -> store.after(new ActionLedgerStore.Cursor(WORLD, 51), A, 10), "future cursor rejected");
            fails(() -> store.after(new ActionLedgerStore.Cursor(WORLD, 0), A, 101), "read budget");
            check(segmentFiles(path).size() > 1, "segment rotation");
            check(store.usedBytes() == directoryBytes(path), "accounted physical storage bytes");
        }
        try (var store = new ActionLedgerStore(path, WORLD, LIMITS)) {
            check(!store.recoveredUnclean() && store.previousCheckpointUtc() > 0, "normal close/restart and unobserved interval anchor");
            check(store.size() == 50 && store.append(entries.get(4)).sequence() == 5, "restart dedup restored");
            check(store.append(draft(UUID.randomUUID(), A, 51)).sequence() == 51, "order survives restart");
            check(store.find(entries.getFirst().occurrenceId()).orElseThrow().event().equals(entries.getFirst()), "durable source lookup");
        }
        fails(() -> { try (var ignored = new ActionLedgerStore(path, UUID.randomUUID(), LIMITS)) {} }, "world directory mismatch refuses adoption");
        try (var other = new ActionLedgerStore(root.resolve("other-world"), UUID.randomUUID(), LIMITS)) {
            check(other.find(entries.getFirst().occurrenceId()).isEmpty(), "different world not global index");
        }
    }
    private static void faultRecovery() throws Exception {
        AtomicInteger sharingFailures = new AtomicInteger(2);
        try (var store = new ActionLedgerStore(root.resolve("sharing-retry"), WORLD, LIMITS, at -> {
            if (at.equals("beforeControlMove") && sharingFailures.getAndDecrement() > 0)
                throw new AccessDeniedException("simulated-sharing-violation");
        })) {
            check(store.append(draft(UUID.randomUUID(), A, 1)).sequence() == 1, "bounded sharing violation retry");
        }
        for (String point : List.of("beforeWrite", "afterWrite", "afterForce", "beforeCheckpoint")) {
            Path path = root.resolve("fault-" + point); AtomicBoolean armed = new AtomicBoolean(false);
            var store = new ActionLedgerStore(path, WORLD, LIMITS, at -> {
                if (at.equals(point) && armed.compareAndSet(true, false)) throw new IOException("INJECTED_" + point);
            });
            var input = draft(UUID.randomUUID(), A, 1); armed.set(true);
            fails(() -> store.append(input), "write failure isn't a successful receipt: " + point);
            store.abort();
            try (var recovered = new ActionLedgerStore(path, WORLD, LIMITS)) {
                check(recovered.recoveredUnclean(), "unclean lifecycle visible: " + point);
                int expected = point.equals("beforeWrite") ? 0 : 1;
                check(recovered.size() == expected, "recover only complete valid frames: " + point);
                check(recovered.append(input).sequence() == 1 && recovered.size() == 1, "retry does not replay game or duplicate: " + point);
            }
        }
        Path path = root.resolve("gaps");
        var unclean = new ActionLedgerStore(path, WORLD, LIMITS);
        unclean.updateGaps(new ActionLedgerStore.GapSummary(3, 100, 200, "QUEUE_FULL")); unclean.abort();
        try (var store = new ActionLedgerStore(path, WORLD, LIMITS)) {
            check(store.gaps().rejected() == 3 && store.gaps().firstUtc() == 100 && store.gaps().lastUtc() == 200, "durable gap diagnostic interval");
        }
    }
    private static List<Path> segmentFiles(Path path) throws IOException {
        try (var files = Files.list(path)) { return files.filter(p -> p.toString().endsWith(".alog")).sorted().toList(); }
    }
    private static void processCrash() throws Exception {
        for (String point : List.of("beforeWrite", "afterForce")) {
            Path path = root.resolve("process-crash-" + point);
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), ActionLedgerTest.class.getName(), "--crash", path.toString(), point, WORLD.toString())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Isolated crash fixture timed out"); }
            check(process.exitValue() == 17, "isolated JVM abrupt termination at " + point);
            try (var store = new ActionLedgerStore(path, WORLD, LIMITS)) {
                check(store.recoveredUnclean() && store.size() == (point.equals("beforeWrite") ? 0 : 1), "pending loss/forced recovery after JVM halt");
            }
        }
    }
    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void corruption() throws Exception {
        for (String damage : List.of("truncate", "checksum", "huge", "metadata", "checkpoint", "futureSchema")) {
            Path path = root.resolve("corrupt-" + damage);
            try (var store = new ActionLedgerStore(path, WORLD, LIMITS)) { store.append(draft(UUID.randomUUID(), A, 1)); }
            Path file = segmentFiles(path).getFirst(); byte[] original = Files.readAllBytes(file);
            if (damage.equals("truncate")) Files.write(file, Arrays.copyOf(original, original.length - 3));
            if (damage.equals("checksum")) { original[original.length - 1] ^= 1; Files.write(file, original); }
            if (damage.equals("huge")) { ByteBuffer.wrap(original).putInt(Integer.MAX_VALUE); Files.write(file, original); }
            if (damage.equals("metadata")) Files.writeString(path.resolve("metadata.json"), "broken");
            if (damage.equals("checkpoint")) Files.writeString(path.resolve("checkpoint.json"), "broken");
            if (damage.equals("futureSchema")) Files.writeString(path.resolve("metadata.json"),
                    "{\"version\":2,\"worldId\":\"" + WORLD + "\"}");
            String before = digest(file), meta = digest(path.resolve("metadata.json")), checkpoint = digest(path.resolve("checkpoint.json"));
            fails(() -> { try (var ignored = new ActionLedgerStore(path, WORLD, LIMITS)) {} }, "quarantine " + damage);
            check(before.equals(digest(file)) && meta.equals(digest(path.resolve("metadata.json")))
                    && checkpoint.equals(digest(path.resolve("checkpoint.json"))), "preserve damaged source/control: " + damage);
        }
        Path path = root.resolve("exclusive");
        try (var store = new ActionLedgerStore(path, WORLD, LIMITS)) {
            fails(() -> { try (var ignored = new ActionLedgerStore(path, WORLD, LIMITS)) {} }, "two writers refused");
            check(store.append(draft(UUID.randomUUID(), A, 1)).sequence() == 1, "failed second writer does not release first lock");
        }
    }
    private static void capacity() throws Exception {
        Path path = root.resolve("capacity"); var limits = new ActionLedgerStore.Limits(131_072, 20_000, 10_000);
        int count = 0; boolean warned = false;
        try (var store = new ActionLedgerStore(path, WORLD, limits)) {
            var warning = new LedgerCapacityWarning();
            while (true) {
                try { store.append(draft(UUID.randomUUID(), A, count + 1)); count++; }
                catch (IOException failure) { check(failure.getMessage().equals("CAPACITY_LIMIT"), "explicit capacity failure"); break; }
                warned |= warning.shouldNotify(store.usedBytes(), limits.maxBytes(), count);
            }
            check(count > 10 && warned, "90 percent alert precedes quota guard");
            check(store.usedBytes() <= limits.maxBytes() && store.size() == count, "quota does not delete or silently succeed");
        }
        try (var store = new ActionLedgerStore(path, WORLD, LIMITS)) {
            check(store.size() == count && store.append(draft(UUID.randomUUID(), B, count + 1)).sequence() == count + 1, "increase quota/restart retains all history");
        }
        try (var store = new ActionLedgerStore(root.resolve("index-limit"), WORLD, new ActionLedgerStore.Limits(131_072,20_000,1))) {
            var first = draft(UUID.randomUUID(), A, 1); store.append(first);
            fails(() -> store.append(draft(UUID.randomUUID(), A, 2)), "bounded index");
            check(store.append(first).sequence() == 1, "duplicate allowed at limit");
        }
    }
    private static void asynchronous() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var block = new AtomicBoolean(false);
        var ledger = new AsyncActionLedger(root.resolve("queue"), WORLD, LIMITS, 1, point -> {
            if (point.equals("beforeWrite") && block.compareAndSet(true, false)) {
                entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            }
        });
        ledger.ready().get(10, TimeUnit.SECONDS); block.set(true);
        var first = draft(UUID.randomUUID(), A, 1); var receipt = ledger.submit(first);
        check(receipt.acceptance() == AsyncActionLedger.Acceptance.PENDING_NOT_DURABLE, "pending is not durable");
        check(entered.await(5, TimeUnit.SECONDS), "writer blocked for deterministic queue test");
        check(!receipt.durable().isDone() && ledger.status().committedSequence() == 0, "no receipt/cursor before write");
        check(ledger.submit(first).durable() == receipt.durable(), "pending retry uses same receipt");
        var second = ledger.submit(draft(UUID.randomUUID(), B, 2));
        long start = System.nanoTime(); var rejected = ledger.submit(draft(UUID.randomUUID(), A, 3));
        check(rejected.acceptance() == AsyncActionLedger.Acceptance.QUEUE_FULL, "bounded queue explicit refusal");
        check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "blocked disk never locks producer");
        check(!ledger.awaitClose(10), "bounded stop timeout is not a clean-close claim");
        check(ledger.submit(draft(UUID.randomUUID(), A, 4)).acceptance() == AsyncActionLedger.Acceptance.UNAVAILABLE, "closing refuses new action");
        release.countDown();
        check(receipt.durable().get(10, TimeUnit.SECONDS).sequence() == 1, "first commit");
        check(second.durable().get(10, TimeUnit.SECONDS).sequence() == 2, "queue commit order");
        ledger.stopped().get(10, TimeUnit.SECONDS);
        try (var store = new ActionLedgerStore(root.resolve("queue"), WORLD, LIMITS)) {
            check(store.size() == 2 && store.gaps().rejected() >= 2, "normal drain and durable rejection diagnostics");
        }
        var armed = new AtomicBoolean(false);
        var bad = new AsyncActionLedger(root.resolve("async-failure"), WORLD, LIMITS, 8, point -> {
            if (point.equals("beforeWrite") && armed.get()) throw new IOException("DISK_FAILURE");
        });
        bad.ready().get(10, TimeUnit.SECONDS); armed.set(true);
        var failed = bad.submit(draft(UUID.randomUUID(), A, 1));
        fails(() -> failed.durable().get(10, TimeUnit.SECONDS), "disk failed receipt");
        fails(() -> bad.stopped().get(10, TimeUnit.SECONDS), "failed stop exposed");
        check(bad.status().state() == AsyncActionLedger.State.FAILED, "writer failure visible");
        fails(() -> bad.after(new ActionLedgerStore.Cursor(WORLD, 0), A, 10).get(), "unavailable not empty results");
    }
    private static void warnings() {
        var warning = new LedgerCapacityWarning();
        check(!warning.shouldNotify(89,100,0), "no alert below90");
        check(warning.shouldNotify(90,100,1), "at90 alert");
        check(!warning.shouldNotify(99,100,2), "no per-action spam");
        check(warning.shouldNotify(99,100,1_800_002), "30min reminder");
        check(!warning.shouldNotify(90,200,1_800_003), "increase limit resets threshold");
        check(warning.shouldNotify(180,200,1_800_004), "new crossing alert");
        check(!warning.shouldNotify(10,0,2_000_000), "OFF limit no division alert");
    }
    private static void settings() throws Exception {
        Path file = root.resolve("config.json");
        check(!ActionLedgerSettings.load(file).settings().enabled(), "missing config OFF");
        Files.writeString(file, "{\"schemaVersion\":1,\"enabled\":true}");
        check(!ActionLedgerSettings.load(file).settings().enabled(), "capacity must be explicit");
        Files.writeString(file, new Gson().toJson(new ActionLedgerSettings(1,true,131_072,20_000,100,16)));
        check(ActionLedgerSettings.load(file).settings().enabled(), "valid explicit settings");
        Files.writeString(file, "x".repeat(5000)); check(!ActionLedgerSettings.load(file).settings().enabled(), "oversize OFF");
        Files.writeString(file, "{\"schemaVersion\":9,\"enabled\":true}"); check(!ActionLedgerSettings.load(file).settings().enabled(), "future schema OFF");
    }
    private static ClassNode bytecode(String name) throws IOException {
        try (var stream = ActionLedgerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new IOException("Missing class resource: " + name);
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static void hookBoundaries() throws Exception {
        var gameMode = bytecode("net/minecraft/server/level/ServerPlayerGameMode");
        var remove = method(gameMode, "removeBlock");
        check(remove.desc.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Z"), "pinned actual removal hook descriptor");
        check((remove.access & Opcodes.ACC_PRIVATE) != 0, "server-only removal implementation");
        check(Arrays.stream(remove.instructions.toArray()).anyMatch(n -> n instanceof MethodInsnNode m && m.name.equals("onDestroyedByPlayer")), "uses actual removal return, not break attempt");
        var die = method(bytecode("net/minecraft/world/entity/LivingEntity"), "die");
        int cancelledCall = -1, deadCommit = -1, writes = 0;
        for (int i = 0; i < die.instructions.size(); i++) {
            var instruction = die.instructions.get(i);
            if (instruction instanceof MethodInsnNode m && m.name.equals("onLivingDeath")) cancelledCall = i;
            if (instruction instanceof FieldInsnNode f && f.name.equals("dead") && f.getOpcode() == Opcodes.PUTFIELD) { deadCommit = i; writes++; }
        }
        check(cancelledCall >= 0 && deadCommit > cancelledCall && writes == 1, "one death-commit hook after cancellable event");
        for (String name : List.of("ServerPlayerGameModeLedgerMixin", "LivingEntityLedgerMixin")) {
            var node = bytecode("com/sande/mythictrpg/mixin/" + name);
            check(node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                    .noneMatch(n -> n instanceof MethodInsnNode m && (m.name.equals("cancel") || m.name.equals("setReturnValue"))), "read-only non-cancelling hook " + name);
        }
        for (String name : List.of("ActionLedgerStore", "AsyncActionLedger")) {
            var node = bytecode("com/sande/mythictrpg/gameplay/ledger/" + name);
            check(node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                    .noneMatch(n -> n instanceof MethodInsnNode m && (m.owner.contains("/quest/") || m.owner.contains("/story/")
                            || m.owner.contains("GameplayIngress") || m.owner.contains("/ai/") || m.owner.contains("/rumor/"))), "no replay consumer/AI execution " + name);
        }
        var service = bytecode("com/sande/mythictrpg/gameplay/ledger/server/ActionLedgerService");
        check(Arrays.stream(method(service, "onServerTickPost").instructions.toArray())
                .anyMatch(n -> n instanceof MethodInsnNode m && m.name.equals("broadcastSystemMessage")), "capacity notification wired to server broadcast");
    }
    private static void legacyPipelineUnchanged() throws Exception {
        try (var jar = new JarFile("build/libs/mythictrpg-1.0.2.jar")) {
            for (String name : List.of("GameplayIngressService", "GameplayObservationAdapters")) {
                String path = "com/sande/mythictrpg/gameplay/observation/" + name;
                ClassNode original = new ClassNode(), current = new ClassNode();
                try (var stream = jar.getInputStream(jar.getJarEntry(path + ".class"))) { new ClassReader(stream).accept(original, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
                try (var stream = ActionLedgerTest.class.getClassLoader().getResourceAsStream(path + ".class")) { new ClassReader(stream).accept(current, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
                for (String methodName : name.equals("GameplayIngressService") ? List.of("accept", "drain") : List.of("onMatureCropBreak", "onLivingDeath", "emit")) {
                    check(Arrays.equals(methodBytes(method(original, methodName)), methodBytes(method(current, methodName))), "legacy consumer/coalescing behavior unchanged: " + methodName);
                }
            }
        }
    }
    private static byte[] methodBytes(MethodNode method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Fixture", null, "java/lang/Object", null);
        method.accept(writer.visitMethod(method.access, method.name, method.desc, method.signature, method.exceptions.toArray(String[]::new)));
        writer.visitEnd(); return writer.toByteArray();
    }
    private static long directoryBytes(Path directory) throws IOException {
        try (var paths = Files.list(directory)) { long total = 0; for (var p : paths.toList()) total += Files.size(p); return total; }
    }
    private static void fixture(int players) throws Exception {
        Path path = root.resolve("fixture-" + players); var ledger = new AsyncActionLedger(path, WORLD, LIMITS, 1024);
        ledger.ready().get(10, TimeUnit.SECONDS);
        var executor = Executors.newFixedThreadPool(players); var receipts = new ConcurrentLinkedQueue<CompletableFuture<ActionRecord>>();
        var tasks = new ArrayList<Future<?>>(); long start = System.nanoTime(); int perPlayer = 40;
        for (int p = 0; p < players; p++) {
            UUID actor = UUID.randomUUID();
            tasks.add(executor.submit(() -> {
                for (int i = 1; i <= perPlayer; i++) {
                    var input = draft(UUID.randomUUID(), actor, i); var submitted = ledger.submit(input); receipts.add(submitted.durable());
                }
            }));
        }
        for (var task : tasks) task.get(10, TimeUnit.SECONDS); executor.shutdown();
        var seen = new HashSet<Long>();
        for (var receipt : receipts) { var record = receipt.get(30, TimeUnit.SECONDS); check(seen.add(record.sequence()), "concurrent unique durable order"); }
        var status = ledger.status(); check(status.indexedEvents() == players * perPlayer && status.rejected() == 0, "concurrent no silent drop");
        check(ledger.awaitClose(5000), "fixture clean close");
        long bytes = directoryBytes(path);
        System.out.printf(Locale.ROOT, "Offline file fixture writers=%d events=%d bytes=%d bytes/event=%.1f elapsed=%.1fms (NOT live server/tick/LLM load)%n",
                players, players * perPlayer, bytes, (double)bytes/(players*perPlayer), (System.nanoTime()-start)/1_000_000.0);
    }
}
