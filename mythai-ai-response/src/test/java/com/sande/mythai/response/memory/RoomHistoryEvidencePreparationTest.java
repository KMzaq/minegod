package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import com.sande.mythictrpg.recording.api.NativeInterpretationEvidence;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure actual-receipt discovery; no game/world/model/proof I/O and no synthetic authority grant. */
public final class RoomHistoryEvidencePreparationTest {
    private static final UUID WORLD = UUID.randomUUID(), ROOM = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final String GOD = "test:athena", OTHER_GOD = "test:hermes";
    private static int checks;
    public static void main(String[] args) {
        coldAndActualOnly(); typedInterpretations(); scopeIsolation(); malformedGraphs(); budgets(); immutableAndNoGrant();
        System.out.println("RoomHistoryEvidencePreparationTest: " + checks + " checks passed; pure discovery only");
    }
    private static RoomMemoryStore.Scope scope() { return new RoomMemoryStore.Scope(WORLD, GOD, PLAYER, Set.of(PLAYER), Set.of(GOD), false); }
    private static RoomMemoryEvidence.Receipt receipt(UUID id, List<RoomEvidenceReference> refs, Set<UUID> parents) {
        return new RoomMemoryEvidence.Receipt(id, WORLD, ROOM, 0, Set.of(PLAYER), Set.of(GOD), false, refs, parents);
    }
    private static RoomEvidenceReference nativeRef() {
        return NativeMemoryEvidence.encode(new NativeMemoryEvidence.Reference(1, WORLD, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)));
    }
    private static RoomEvidenceReference watchRef(String identifier) {
        return new RoomEvidenceReference(ExperienceRoomEvidence.KIND, "{\"testOpaqueHandle\":\"" + identifier + "\"}");
    }
    private static void typedInterpretations() {
        UUID parent=UUID.randomUUID(),child=UUID.randomUUID();
        var ref=NativeInterpretationEvidence.encode(new NativeInterpretationEvidence.Reference(1,WORLD,UUID.randomUUID(),UUID.randomUUID(),"b".repeat(64)));
        var rows=Map.of(parent,receipt(parent,List.of(ref),Set.of()),child,receipt(child,List.of(),Set.of(parent)));
        var selection=RoomHistoryEvidencePreparation.discover(scope(),List.of(child),rows::get);
        check(selection.references().equals(List.of(ref))&&selection.eligibleRoots().equals(Set.of(child)),"typed ancestor is discovered without dropping its candidate provenance");
        check(!RoomMemoryEvidence.current(scope(),List.of(),Set.of(child),rows::get,r->false),"typed syntax/discovery never grants actual persisted job authority");
        check(RoomMemoryEvidence.current(scope(),List.of(),Set.of(child),rows::get,ref::equals),"history still requires separately validated typed predicate");
        var mixed=receipt(child,List.of(nativeRef(),ref),Set.of());
        check(discover(child,mixed).references().size()==2,"RAW and typed refs remain separate kinds, not one retyped proof");
        var malformed=new RoomEvidenceReference(NativeInterpretationEvidence.KIND,"{\"authority\":\"FACT\"}");
        check(discover(child,receipt(child,List.of(malformed),Set.of())).eligibleRoots().isEmpty(),"malformed typed receipt atomically rejects root");
        var duplicate=new RoomEvidenceReference(NativeInterpretationEvidence.KIND,ref.payload().replace("\"version\":1","\"version\":1,\"version\":1"));
        check(discover(child,receipt(child,List.of(duplicate),Set.of())).references().isEmpty(),"duplicate typed fields cannot warm authority");
        var unknown=new RoomEvidenceReference("RECORDED_NATIVE_INTERPRETATION_V2",ref.payload());
        check(discover(child,receipt(child,List.of(unknown),Set.of())).references().isEmpty(),"unknown version is not sent to typed owner as V1");
        check(!RoomMemoryEvidence.current(scope(),List.of(),Set.of(child),id->receipt(child,List.of(unknown),Set.of()),ref::equals),"unknown kind still fails final history current");
        var differentScope=new RoomMemoryStore.Scope(WORLD,GOD,PLAYER,Set.of(PLAYER,OTHER),Set.of(GOD),false);
        check(RoomHistoryEvidencePreparation.discover(differentScope,List.of(child),rows::get).references().isEmpty(),"typed private ancestry not warmed for a new listener");
    }
    private static void coldAndActualOnly() {
        UUID old = UUID.randomUUID(), answer = UUID.randomUUID(); var nativeRef = nativeRef(); var watch = watchRef("old");
        var rows = Map.of(old, receipt(old, List.of(nativeRef, watch), Set.of()), answer, receipt(answer, List.of(), Set.of(old)));
        // Existing cold current checks cannot permit native refs; discovery may warm them, not approve them.
        check(!RoomMemoryEvidence.current(scope(), List.of(), Set.of(answer), rows::get, ref -> false), "cold refs are not already valid");
        var discovered = RoomHistoryEvidencePreparation.discover(scope(), List.of(answer), rows::get);
        check(discovered.eligibleRoots().equals(Set.of(answer)) && !discovered.partial(), "actual visible receipt closure can be prepared before prune");
        check(Set.copyOf(discovered.references()).equals(Set.of(nativeRef, watch)), "complete parent refs discovered without source text parsing");
        check(!RoomMemoryEvidence.current(scope(), List.of(), Set.of(answer), rows::get, ref -> false), "discovery does not alter current authority");
        check(RoomMemoryEvidence.current(scope(), List.of(), Set.of(answer), rows::get, Set.of(nativeRef, watch)::contains), "final current traversal still needs actual prepared reference predicates");
        UUID fabricated = UUID.randomUUID();
        var fake = RoomHistoryEvidencePreparation.discover(scope(), List.of(fabricated), rows::get);
        check(fake.references().isEmpty() && fake.eligibleRoots().isEmpty() && fake.partial(), "history/model IDs without receipt cannot cause proof requests");
        var legacy = new RoomEvidenceReference("LEGACY_REFERENCE", "legacy opaque");
        var content = new RoomEvidenceReference("CONTENT_REFERENCE", "content opaque");
        var mixed = receipt(answer, List.of(legacy, content, nativeRef), Set.of());
        var selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(answer), id -> mixed);
        check(selection.references().equals(List.of(nativeRef)), "only asynchronous native/Watch types warm; synchronous unknown kinds are not minted/approved");
        check(!RoomMemoryEvidence.current(scope(), List.of(), Set.of(answer), id -> mixed, Set.of(nativeRef)::contains), "unprepared unrelated reference still denies final source");
        var count = new AtomicInteger();
        RoomHistoryEvidencePreparation.discover(scope(), List.of(answer, old, answer), id -> { count.incrementAndGet(); return rows.get(id); });
        check(count.get() == 2, "shared actual receipts read once per discovery, not once per root");
    }
    private static void scopeIsolation() {
        UUID id = UUID.randomUUID(); var ref = nativeRef();
        var foreignWorld = new RoomMemoryEvidence.Receipt(id, UUID.randomUUID(), ROOM, 0, Set.of(PLAYER), Set.of(GOD), false, List.of(ref), Set.of());
        check(discover(id, foreignWorld).references().isEmpty(), "foreign world cannot warm references");
        var otherPlayer = new RoomMemoryEvidence.Receipt(id, WORLD, ROOM, 0, Set.of(OTHER), Set.of(GOD), false, List.of(ref), Set.of());
        check(discover(id, otherPlayer).references().isEmpty(), "private player audience missing requester denied");
        var otherGod = new RoomMemoryEvidence.Receipt(id, WORLD, ROOM, 0, Set.of(PLAYER), Set.of(OTHER_GOD), false, List.of(ref), Set.of());
        check(discover(id, otherGod).references().isEmpty(), "unheard God cannot warm actual other-God receipt");
        var privateRow = receipt(id, List.of(ref), Set.of());
        var extraListener = new RoomMemoryStore.Scope(WORLD, GOD, PLAYER, Set.of(PLAYER, OTHER), Set.of(GOD), false);
        check(RoomHistoryEvidencePreparation.discover(extraListener, List.of(id), key -> privateRow).references().isEmpty(), "new untrusted listener is not a warm-up authorization");
        var extraGod = new RoomMemoryStore.Scope(WORLD, GOD, PLAYER, Set.of(PLAYER), Set.of(GOD, OTHER_GOD), false);
        check(RoomHistoryEvidencePreparation.discover(extraGod, List.of(id), key -> privateRow).references().isEmpty(), "unknown private God denies discovery before preparation");
        var publicScope = new RoomMemoryStore.Scope(WORLD, GOD, PLAYER, Set.of(PLAYER), Set.of(GOD), true);
        check(RoomHistoryEvidencePreparation.discover(publicScope, List.of(id), key -> privateRow).references().isEmpty(), "private source never warmed into public history");
        var publicRow = new RoomMemoryEvidence.Receipt(id, WORLD, ROOM, 0, Set.of(PLAYER), Set.of(GOD), true, List.of(ref), Set.of());
        check(RoomHistoryEvidencePreparation.discover(extraListener, List.of(id), key -> publicRow).references().equals(List.of(ref)), "public-source discovery retains existing visibility policy, while proof current remains separate");
        UUID parent = UUID.randomUUID(); var branch = Map.of(id, receipt(id, List.of(ref), Set.of(parent)), parent, otherPlayer);
        check(RoomHistoryEvidencePreparation.discover(scope(), List.of(id), branch::get).references().isEmpty(), "one inaccessible ancestor excludes complete root and all tentative refs");
        var shared = new RoomMemoryEvidence.Receipt(id, WORLD, ROOM, 0, Set.of(PLAYER, OTHER), Set.of(GOD, OTHER_GOD), false, List.of(ref), Set.of());
        var otherScope = new RoomMemoryStore.Scope(WORLD, OTHER_GOD, OTHER, Set.of(OTHER), Set.of(OTHER_GOD), false);
        check(RoomHistoryEvidencePreparation.discover(otherScope, List.of(id), key -> shared).eligibleRoots().equals(Set.of(id)), "actual other audience participant can warm its own scope, not reuse permission cache");
    }
    private static RoomHistoryEvidencePreparation.Selection discover(UUID id, RoomMemoryEvidence.Receipt row) {
        return RoomHistoryEvidencePreparation.discover(scope(), List.of(id), key -> row);
    }
    private static void malformedGraphs() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), valid = UUID.randomUUID(); var ref = nativeRef();
        var cycle = Map.of(first, receipt(first, List.of(nativeRef()), Set.of(second)), second, receipt(second, List.of(), Set.of(first)), valid, receipt(valid, List.of(ref), Set.of()));
        var selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(first, valid), cycle::get);
        check(selection.references().equals(List.of(ref)) && selection.eligibleRoots().equals(Set.of(valid)) && selection.partial(), "cycle contributes nothing and does not poison unrelated valid root");
        var missing = Map.of(first, receipt(first, List.of(nativeRef()), Set.of(second)), valid, cycle.get(valid));
        selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(first, valid), missing::get);
        check(selection.references().equals(List.of(ref)) && selection.partial(), "missing ancestor preserves complete unrelated root only");
        check(discover(first, receipt(second, List.of(ref), Set.of())).references().isEmpty(), "lookup returning wrong source identity rejected");
        check(discover(first, receipt(first, List.of(ref), Set.of(first))).references().isEmpty(), "self cycle rejected");
        var malformed = new RoomEvidenceReference(NativeMemoryEvidence.KIND, "{\"grant\":\"all\"}");
        var malformedRows = Map.of(first, receipt(first, List.of(malformed), Set.of()), valid, cycle.get(valid));
        selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(first, valid), malformedRows::get);
        check(selection.references().equals(List.of(ref)) && selection.eligibleRoots().equals(Set.of(valid)), "malformed native descriptor is atomic and cannot poison unrelated root");
        selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(first, valid), key -> { if (key.equals(first)) throw new IllegalStateException("private details"); return cycle.get(key); });
        check(selection.references().equals(List.of(ref)) && !selection.toString().contains("private details"), "lookup errors fail one root closed with no diagnostic leak");
        var negative = new RoomMemoryEvidence.Receipt(first, WORLD, ROOM, -1, Set.of(PLAYER), Set.of(GOD), false, List.of(ref), Set.of());
        check(discover(first, negative).eligibleRoots().isEmpty(), "malformed receipt revision cannot warm sources");
        selection = RoomHistoryEvidencePreparation.discover(scope(), Arrays.asList(null, valid), cycle::get);
        check(selection.eligibleRoots().equals(Set.of(valid)) && selection.partial(), "missing history ID excluded without fabricating an identity");
        var watch = watchRef("descriptor-owned-by-watch");
        check(discover(first, receipt(first, List.of(watch), Set.of())).references().equals(List.of(watch)), "Watch descriptor/proof parsing stays with existing asynchronous owner; discovery is not grant");
    }
    private static void budgets() {
        var count = new AtomicInteger(); var manyRoots = new ArrayList<UUID>(); for (int i = 0; i < 129; i++) manyRoots.add(UUID.randomUUID());
        var selection = RoomHistoryEvidencePreparation.discover(scope(), manyRoots, key -> { count.incrementAndGet(); return null; });
        check(selection.references().isEmpty() && selection.partial() && count.get() == 0, "129-root request fails bounded without any lookup");
        var rows = new HashMap<UUID,RoomMemoryEvidence.Receipt>(); var chain = new ArrayList<UUID>();
        for (int i = 0; i < 513; i++) chain.add(UUID.randomUUID());
        for (int i = 0; i < chain.size(); i++) rows.put(chain.get(i), receipt(chain.get(i), i == 512 ? List.of(nativeRef()) : List.of(), i == 512 ? Set.of() : Set.of(chain.get(i + 1))));
        count.set(0);
        selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(chain.getFirst()), key -> { count.incrementAndGet(); return rows.get(key); });
        check(selection.references().isEmpty() && selection.partial() && count.get() == 512, "513-node lineage stops at actual lookup budget and grants no partial root");
        rows.put(chain.get(511), receipt(chain.get(511), List.of(nativeRef()), Set.of())); count.set(0);
        selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(chain.getFirst()), key -> { count.incrementAndGet(); return rows.get(key); });
        check(selection.references().size() == 1 && !selection.partial() && count.get() == 512, "512-node existing lineage remains iterative and supported");
        var refs = new ArrayList<RoomEvidenceReference>(); for (int i = 0; i < 65; i++) refs.add(nativeRef());
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        check(discover(first, receipt(first, refs, Set.of())).references().isEmpty(), "65 references on one malformed receipt rejected");
        var pair = Map.of(first, receipt(first, refs.subList(0, 64), Set.of()), second, receipt(second, List.of(refs.get(64)), Set.of()));
        selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(first, second), pair::get);
        check(selection.references().size() == 64 && selection.eligibleRoots().equals(Set.of(first)) && selection.partial(), "aggregate 65th reference drops its entire root rather than partial permission group");
        pair = Map.of(first, receipt(first, refs.subList(0, 64), Set.of()), second, receipt(second, List.of(refs.getFirst()), Set.of()));
        selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(first, second), pair::get);
        check(selection.references().size() == 64 && selection.eligibleRoots().equals(Set.of(first, second)) && !selection.partial(), "same already-counted reference can support another complete root");
        var parents = new HashSet<UUID>(); for (int i = 0; i < 257; i++) parents.add(UUID.randomUUID());
        check(discover(first, receipt(first, List.of(), parents)).eligibleRoots().isEmpty(), "malformed overwide source graph denied before traversal");
    }
    private static void immutableAndNoGrant() {
        UUID source = UUID.randomUUID(); var ref = nativeRef(); var immutable = discover(source, receipt(source, List.of(ref), Set.of()));
        rejects(() -> immutable.references().clear(), "references immutable"); rejects(() -> immutable.eligibleRoots().clear(), "roots immutable");
        check(!immutable.toString().contains(source.toString()) && !immutable.toString().contains(ref.payload()), "diagnostic only counts, not private IDs/payloads");
        var rows = new HashMap<UUID,RoomMemoryEvidence.Receipt>(); rows.put(source, receipt(source, List.of(ref), Set.of()));
        var selection = RoomHistoryEvidencePreparation.discover(scope(), List.of(source), rows::get); rows.clear();
        check(selection.references().size() == 1 && !RoomMemoryEvidence.current(scope(), List.of(), selection.eligibleRoots(), rows::get, key -> true), "withdrawal after discovery still denies final current proof; no cached permission merging");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable code, String message) { try { code.run(); } catch (RuntimeException expected) { checks++; return; } throw new AssertionError(message); }
}
