package com.sande.mythictrpg.ai.experiencecontract;

import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.watch.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.objectweb.asm.*;

/** Real files/queues and pure game projection, no Minecraft server or model. */
public final class ExperienceStageTest {
    static int checks;
    static final UUID WORLD = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID(), BOOT = UUID.randomUUID();
    static final String GOD = "mythictrpg:fortuna";
    static final Ref RULE = new Ref("test:owner", 1), POLICY = new Ref("test:policy", 1), ELIGIBILITY = new Ref("test:eligibility", 1);
    static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(10, TimeUnit.SECONDS); }
    static ActionRecord.Draft draft(long order) {
        return new ActionRecord.Draft(UUID.randomUUID(), BOOT, order, "mythictrpg:crop_remove_commit", 1, PLAYER,
                new ActionRecord.Subject("BLOCK", "minecraft:wheat", null), 1234, 50, 6000, "minecraft:overworld",
                new ActionRecord.Position(0, 64, 0), ActionRecord.Type.MATURE_CROP_REMOVED, "COMPLETED", Map.of("secret", "PRIVATE_PAYLOAD"), "mythictrpg:admin_only_unprojected");
    }
    static Scene scene(ActionRecord.Draft d) {
        Map<Field, Visibility> fields = new EnumMap<>(Field.class);
        for (Field f : List.of(Field.ACTOR, Field.ACTION, Field.SUBJECT_TYPE, Field.OUTCOME)) fields.put(f, new Visibility(true, Map.of(PLAYER, RULE)));
        return new Scene(d.occurrenceId(), BOOT, d.captureOrder(), new Ref("test:scene", 1), Set.of("test:power"), Set.of("test:domain"), false, false, fields);
    }
    static Approval approval() {
        return new Approval(WORLD, new Key(GOD, PLAYER), ELIGIBILITY, new Ref("test:approved", 1), new Policy(POLICY, GOD, "test:power", "test:domain",
                List.of(new Area("minecraft:overworld", -1, 0, -1, 1, 100, 1)), Set.of(Field.values())));
    }
    static Audience audience(String god, Set<UUID> people) { return new Audience(WORLD, new Key(god, PLAYER), people, new Ref("test:conversation", 1)); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "experience-stage04-");
        Path rawPath = root.resolve("raw"), watchPath = root.resolve("watch");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean block = new AtomicBoolean();
        var limits = new AsyncGodWatch.Limits(4_000_000, 5000, 64);
        try (var raw = new AsyncActionLedger(rawPath, WORLD, new ActionLedgerStore.Limits(4_000_000, 128_000, 5000), 64, point -> {
            if (point.equals("beforeWrite") && block.compareAndSet(true, false)) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException x) { throw new java.io.IOException(x); } }
        })) {
            get(raw.ready());
            try (var watch = new AsyncGodWatch(watchPath, WORLD, BOOT, raw, limits)) {
                get(watch.ready()); get(watch.disclose(new Disclosure(RULE, Set.of(PLAYER)))); get(watch.start(UUID.randomUUID(), approval()));
                var d = draft(1); var receipt = raw.submit(d); get(watch.observeSubmitted(d, scene(d), receipt));
                check(raw.status().committedSequence() == 1, "fanout does not submit raw twice");
                Audience a = audience(GOD, Set.of(PLAYER)); var snapshot = get(watch.read(a, 16));
                var view = ExperienceProjection.project(snapshot.view(), PLAYER, new ExperienceView.Relationship(true, 150));
                check(view.events().size() == 1 && view.relationship().affinity() == 150, "authorized crop and actual affinity");
                check(view.events().getFirst().gameTime().equals("NOT_DISCLOSED"), "no reconstruction of hidden time");
                check(!view.toString().contains("PRIVATE_PAYLOAD") && !view.toString().contains("minecraft:overworld"), "no raw payload/location leak");
                check(view.events().getFirst().outcome().equals("BLOCK_REMOVED_NOT_ITEM_ACQUISITION"), "not loot/quest/reward");
                check(ExperienceProjection.project(get(watch.read(audience("mythictrpg:amphitrite", Set.of(PLAYER)),16)).view(), PLAYER, ExperienceView.Relationship.UNKNOWN).events().isEmpty(), "unwatched god has no experience");
                check(ExperienceProjection.project(get(watch.read(audience(GOD, Set.of(PLAYER, OTHER)),16)).view(), PLAYER, ExperienceView.Relationship.UNKNOWN).events().isEmpty(), "new listener cannot see private experience");
                check(watch.current(snapshot, a), "current lease guard");
                var lease = new ExperienceLease(view, ids -> watch.current(snapshot, a));
                check(!lease.current(Set.of(UUID.randomUUID())), "lease cannot add evidence");
                check(lease.current(Set.of(view.events().getFirst().observationId())), "issued observation accepted");
                block.set(true); var d2 = draft(2); var wait = watch.capture(d2, scene(d2));
                check(entered.await(2, TimeUnit.SECONDS), "block worker behind raw pending");
                long before = System.nanoTime(); var revoked = watch.revokeEvent(d.occurrenceId());
                check(!revoked.isDone() && !watch.current(snapshot, a), "pending revoke invalidates before disk commit");
                check(System.nanoTime() - before < 100_000_000L, "final guard no IO/tick wait");
                release.countDown(); get(wait.observed()); get(revoked);
                var fresh = get(watch.read(a,16));
                var unrelated = watch.revokeEvent(UUID.randomUUID());
                check(watch.current(fresh, a), "unrelated event invalidation does not stale current proof"); get(unrelated);
                var change = watch.disclose(new Disclosure(new Ref(RULE.id(), 2), Set.of(PLAYER, OTHER)));
                check(!watch.current(fresh, a), "pending disclosure change cannot widen a lease"); get(change);
                check(!watch.current(fresh, new Audience(WORLD, a.key(), a.players(), new Ref("test:conversation",2))), "new conversation invalidates old lease");
            } finally { release.countDown(); }
        }
        try (var raw = new AsyncActionLedger(rawPath, WORLD, new ActionLedgerStore.Limits(4_000_000,128_000,5000),64)) {
            get(raw.ready());
            try (var watch = new AsyncGodWatch(watchPath,WORLD,UUID.randomUUID(),raw,limits)) {
                get(watch.ready()); check(get(watch.states()).getFirst().state() == State.PAUSED, "clean restart preserves knowledge but requires watch revalidation");
                check(get(watch.read(audience(GOD,Set.of(PLAYER)),16)).view().proofs().isEmpty(), "disclosure change and source revocation persisted");
            }
        }
        check(WatchTrialSettings.load(root.resolve("missing.json")).equals(WatchTrialSettings.OFF), "trial absent defaults OFF");
        Files.writeString(root.resolve("bad.json"), "{\"schemaVersion\":99,\"enabled\":true}");
        check(!WatchTrialSettings.load(root.resolve("bad.json")).enabled(), "invalid trial config OFF");
        check(ExperienceLease.class.getConstructors().length == 0, "AI cannot construct game lease");
        boolean[] calls = new boolean[2];
        try (var in = ExperienceStageTest.class.getResourceAsStream("/com/sande/mythictrpg/gameplay/ledger/server/ActionLedgerCapture.class")) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("publish")) return null;
                    return new MethodVisitor(Opcodes.ASM9) { @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf) {
                        if (owner.endsWith("AsyncActionLedger") && method.equals("submit")) { check(!calls[0], "one raw submit bytecode"); calls[0]=true; }
                        if (owner.endsWith("GodWatchRuntime") && method.equals("observed")) { check(calls[0], "fanout after submit"); calls[1]=true; }
                    }};
                }
            }, ClassReader.SKIP_DEBUG);
        }
        check(calls[0] && calls[1], "real completion hook compiled to fanout");
        System.out.println("ExperienceStageTest: PASS ("+checks+" checks); artifacts="+root+"; NO server/GameTest/LLM");
    }
}
