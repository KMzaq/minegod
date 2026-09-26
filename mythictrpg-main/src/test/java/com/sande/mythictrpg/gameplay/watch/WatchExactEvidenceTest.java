package com.sande.mythictrpg.gameplay.watch;

import java.nio.file.*;
import java.util.*;
import static com.sande.mythictrpg.gameplay.watch.GodWatchTest.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;

/** Exact persistent watch evidence: old IDs, current disclosure, source isolation, restart and immediate revocation. */
public final class WatchExactEvidenceTest {
    public static void main(String[] args) throws Exception {
        GodWatchTest.root = Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "watch-exact-");
        int before = checks;
        Path directory; UUID world; UUID first; AsyncGodWatch.ReadSnapshot old;
        try (Fixture f = new Fixture("beyond-window")) {
            f.rules(); f.start(GOD_A, A);
            var original = f.capture(A).getFirst(); first = original.id();
            var audience = audience(f.world, GOD_A, A, A);
            old = get(f.watch.readExact(audience, Set.of(first)));
            for (int i = 0; i < 110; i++) f.capture(A);
            check(f.view(GOD_A, A, A).proofs().stream().noneMatch(p -> p.id().equals(first)), "fixture is beyond recent 100");
            check(get(f.watch.readExact(audience, Set.of(first))).view().proofs().equals(old.view().proofs()), "exact ID retrieves old original proof");
            check(get(f.watch.revalidate(old, audience)), "old revalidation no longer depends on recent 100");
            check(f.watch.current(old, audience), "prepared exact guard is synchronous and current");
            check(!get(f.watch.readExact(audience(f.world, GOD_B, A, A), Set.of(first))).view().available(), "foreign observer God rejected");
            check(!get(f.watch.readExact(audience(f.world, GOD_A, B, A), Set.of(first))).view().available(), "foreign observed subject rejected");
            check(!get(f.watch.readExact(audience(UUID.randomUUID(), GOD_A, A, A), Set.of(first))).view().available(), "foreign world rejected");
            check(!get(f.watch.readExact(audience, Set.of(first, UUID.randomUUID()))).view().available(), "missing requested ID rejects whole set");
            check(!get(f.watch.readExact(audience(f.world, GOD_A, A, A, B), Set.of(first))).view().available(), "current audience must satisfy every field subject rule");
            directory = f.dir; world = f.world;
        }
        try (Fixture resumed = new Fixture(directory, world, UUID.randomUUID(), LIMITS, point -> {})) {
            var audience = audience(world, GOD_A, A, A);
            var restored = get(resumed.watch.readExact(audience, Set.of(first)));
            check(restored.view().proofs().equals(old.view().proofs()), "restart replays exact immutable observation and projection");
            check(restored.eligibilityRefs().equals(old.eligibilityRefs()), "restart restores original eligibility reference");
            check(resumed.watch.current(restored, audience), "fresh preparation after restart enables final guard");
            var pending = resumed.watch.revokeRef(restored.eligibilityRefs().get(first));
            check(!resumed.watch.current(restored, audience), "eligibility revoked immediately before async commit");
            get(pending);
            check(!get(resumed.watch.readExact(audience, Set.of(first))).view().available(), "revoked eligibility not resurrected by exact lookup");
        }
        try (Fixture f = new Fixture("disclosure-revocation")) {
            f.rules(); f.start(GOD_A, A); var proof = f.capture(A).getFirst();
            var audience = audience(f.world, GOD_A, A, A);
            var prepared = get(f.watch.readExact(audience, Set.of(proof.id())));
            var pending = f.watch.disclose(new Disclosure(new Ref(RULE_A.id(), RULE_A.revision()+1), Set.of()));
            check(!f.watch.current(prepared, audience), "disclosure revision denies in-flight prepared reference immediately");
            get(pending);
            check(!get(f.watch.readExact(audience, Set.of(proof.id()))).view().available(), "revised disclosure cannot silently recreate old access");
        }
        System.out.println("WatchExactEvidenceTest: " + (checks-before) + " checks PASS; artifacts=" + GodWatchTest.root);
    }
}
