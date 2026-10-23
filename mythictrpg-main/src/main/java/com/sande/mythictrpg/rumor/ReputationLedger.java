package com.sande.mythictrpg.rumor;

import java.util.*;

/** Game-owned assessment receipts, NOT an affinity database. No irreversible subtraction or quest effects. */
public final class ReputationLedger {
    public static final int VERSION=1, LIMIT=4096;
    public enum Outcome { ACCEPTED, DOUBTFUL, IGNORED, DISPUTED, RECOVERED, RETRACTED }
    public enum DirectImpact { NOT_APPLIED, ALREADY_APPLIED, UNKNOWN }
    public enum ApprovalKind { ADMIN_REVIEW, GAME_RESULT, DIALOGUE_REVIEW, RECEPTION_REVIEW }
    public enum Result { APPLIED, DUPLICATE, STALE, TERMINAL, CAPACITY, REJECTED }
    /** A reference to an already-approved game result/review, never an AI assertion of success. */
    public record Approval(UUID id, ApprovalKind kind, String reasonId) {
        public Approval {Objects.requireNonNull(id);Objects.requireNonNull(kind);CourierSettings.identifier(reasonId);}
    }
    public record Decision(UUID id, UUID worldId, UUID subject, String godId, UUID sourceId, UUID rootId,
            long rumorRevision, String policyId, String policyFingerprint, long expectedVersion,
            Outcome outcome, DirectImpact directImpact, Approval approval) {
        public Decision {
            Objects.requireNonNull(id);Objects.requireNonNull(worldId);Objects.requireNonNull(subject);Objects.requireNonNull(sourceId);Objects.requireNonNull(rootId);
            CourierSettings.identifier(godId);CourierSettings.identifier(policyId);Objects.requireNonNull(outcome);Objects.requireNonNull(directImpact);Objects.requireNonNull(approval);
            if(rumorRevision<1||expectedVersion<0||expectedVersion==Long.MAX_VALUE||policyFingerprint==null||!policyFingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Reputation decision");
        }
    }
    public record Entry(Decision decision, long version, Approval initialApproval) {
        public Entry {
            Objects.requireNonNull(decision);Objects.requireNonNull(initialApproval);
            if(version!=decision.expectedVersion()+1||version==1&&(!initialApproval.equals(decision.approval())
                    ||decision.outcome()==Outcome.RECOVERED||decision.outcome()==Outcome.RETRACTED))throw new IllegalArgumentException("Assessment version");
        }
        public boolean terminal(){return decision.outcome()==Outcome.RECOVERED||decision.outcome()==Outcome.RETRACTED;}
    }
    /** The game result is single-use even when there was nothing to recover at the time. */
    public record Recovery(UUID id, UUID subject, String godId, String policyId, String reasonId) {
        public Recovery {
            Objects.requireNonNull(id); Objects.requireNonNull(subject);
            CourierSettings.identifier(godId); CourierSettings.identifier(policyId); CourierSettings.identifier(reasonId);
        }
    }
    public record RecoveryReceipt(Recovery request, List<UUID> sources) {
        public RecoveryReceipt {
            Objects.requireNonNull(request); sources=List.copyOf(sources);
            if(sources.size()>LIMIT||new HashSet<>(sources).size()!=sources.size())throw new IllegalArgumentException("Recovery sources");
        }
    }
    public record Snapshot(int version, UUID worldId, long revision, List<Entry> entries, List<RecoveryReceipt> recoveries) {
        public Snapshot {
            entries=List.copyOf(entries);
            // Old v1 saves predate game-result batch receipts.
            recoveries=recoveries==null?List.of():List.copyOf(recoveries);
        }
        public Snapshot(int version,UUID worldId,long revision,List<Entry> entries){this(version,worldId,revision,entries,List.of());}
    }
    private record Key(UUID subject,String god,UUID source) {}
    private final UUID worldId;
    private final Map<Key,Entry> entries=new LinkedHashMap<>();
    private final Map<UUID,RecoveryReceipt> recoveries=new LinkedHashMap<>();
    private long revision;
    private java.util.function.BooleanSupplier quotaEnabled;
    private java.util.function.BiPredicate<Snapshot, Boolean> mutationGate;
    void mutationGate(java.util.function.BooleanSupplier enabled, java.util.function.BiPredicate<Snapshot, Boolean> gate) {
        quotaEnabled = enabled; mutationGate = gate;
    }
    public ReputationLedger(UUID worldId){this.worldId=Objects.requireNonNull(worldId);}
    public UUID worldId(){return worldId;}
    public long revision(){return revision;}
    public int size(){return entries.size();}
    public Entry find(UUID subject,String god,UUID source){return entries.get(new Key(subject,god,source));}
    public List<Entry> entries(){return List.copyOf(entries.values());}
    public boolean recovered(Recovery request) {
        var receipt=recoveries.get(request.id());
        return receipt!=null&&receipt.request().equals(request);
    }
    /** One draft, quota admission and commit for the entire captured set and its replay receipt. */
    Result recover(Recovery request) {
        return recover(request,entry->true);
    }
    Result recover(Recovery request,java.util.function.Predicate<Entry> included) {
        var receipt=recoveries.get(request.id());
        if(receipt!=null)return receipt.request().equals(request)?Result.DUPLICATE:Result.REJECTED;
        if(recoveries.size()>=LIMIT)return Result.CAPACITY;
        var draft=restore(snapshot());
        var sources=new ArrayList<UUID>();
        for(var entry:entries()) {
            var old=entry.decision();
            if(entry.terminal()||!old.subject().equals(request.subject())||!old.godId().equals(request.godId())
                    ||!old.policyId().equals(request.policyId())||!included.test(entry))continue;
            var id=UUID.nameUUIDFromBytes((request.id()+"/"+old.sourceId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var result=draft.apply(new Decision(id,worldId,old.subject(),old.godId(),old.sourceId(),old.rootId(),
                    old.rumorRevision(),old.policyId(),old.policyFingerprint(),entry.version(),Outcome.RECOVERED,
                    old.directImpact(),new Approval(request.id(),ApprovalKind.GAME_RESULT,request.reasonId())));
            if(result!=Result.APPLIED)return result;
            sources.add(old.sourceId());
        }
        if(draft.revision==Long.MAX_VALUE)return Result.REJECTED;
        draft.recoveries.put(request.id(),new RecoveryReceipt(request,sources));
        draft.revision++;
        if(mutationGate!=null&&quotaEnabled.getAsBoolean()&&!mutationGate.test(draft.snapshot(),true))return Result.CAPACITY;
        entries.clear();entries.putAll(draft.entries);
        recoveries.clear();recoveries.putAll(draft.recoveries);revision=draft.revision;
        return Result.APPLIED;
    }
    Result apply(Decision decision) {
        if (mutationGate != null && quotaEnabled.getAsBoolean()) {
            ReputationLedger draft = restore(snapshot());
            Result result = draft.apply(decision);
            if (result != Result.APPLIED) return result;
            boolean maintenance = decision.outcome() == Outcome.RECOVERED || decision.outcome() == Outcome.RETRACTED;
            if (!mutationGate.test(draft.snapshot(), maintenance)) return Result.CAPACITY;
            entries.clear(); entries.putAll(draft.entries); revision = draft.revision; return result;
        }
        if(!worldId.equals(decision.worldId())||revision==Long.MAX_VALUE)return Result.REJECTED;
        var key=new Key(decision.subject(),decision.godId(),decision.sourceId());var old=entries.get(key);
        if(old!=null&&old.decision().equals(decision))return Result.DUPLICATE;
        if(entries.values().stream().anyMatch(e->e.decision().id().equals(decision.id())))return Result.REJECTED;
        if(old!=null&&old.terminal())return Result.TERMINAL;
        if(decision.expectedVersion()!=(old==null?0:old.version()))return Result.STALE;
        if(old==null&&(decision.outcome()==Outcome.RECOVERED||decision.outcome()==Outcome.RETRACTED))return Result.REJECTED;
        if(old==null&&entries.size()>=LIMIT)return Result.CAPACITY;
        if(old!=null&&(!old.decision().rootId().equals(decision.rootId())||old.decision().rumorRevision()!=decision.rumorRevision()))return Result.REJECTED;
        // Recovery is an in-place terminal transition, so a full ledger never blocks removing an old effect.
        entries.put(key,new Entry(decision,decision.expectedVersion()+1,old==null?decision.approval():old.initialApproval()));revision++;
        return Result.APPLIED;
    }
    public Snapshot snapshot(){return new Snapshot(VERSION,worldId,revision,entries(),List.copyOf(recoveries.values()));}
    public static ReputationLedger restore(Snapshot snapshot) {
        if(snapshot.version()!=VERSION||snapshot.revision()<0||snapshot.entries().size()>LIMIT||snapshot.recoveries().size()>LIMIT
                ||snapshot.revision()<snapshot.entries().size())throw new IllegalArgumentException("Reputation schema/budget");
        var result=new ReputationLedger(snapshot.worldId());var ids=new HashSet<UUID>();long versions=0;
        for(var entry:snapshot.entries()) {
            var d=entry.decision();var key=new Key(d.subject(),d.godId(),d.sourceId());
            if(!d.worldId().equals(snapshot.worldId())||!ids.add(d.id())||result.entries.putIfAbsent(key,entry)!=null)throw new IllegalArgumentException("Reputation scope/duplicate");
            versions=Math.addExact(versions,entry.version());
        }
        for(var receipt:snapshot.recoveries()) {
            var request=receipt.request();
            if(result.recoveries.putIfAbsent(request.id(),receipt)!=null)throw new IllegalArgumentException("Duplicate recovery receipt");
            for(var source:receipt.sources()) {
                var entry=result.find(request.subject(),request.godId(),source);
                if(entry==null||entry.decision().outcome()!=Outcome.RECOVERED
                        ||!entry.decision().policyId().equals(request.policyId())
                        ||!entry.decision().approval().equals(new Approval(request.id(),ApprovalKind.GAME_RESULT,request.reasonId())))
                    throw new IllegalArgumentException("Recovery receipt does not match assessment");
            }
        }
        if(Math.addExact(versions,snapshot.recoveries().size())!=snapshot.revision())throw new IllegalArgumentException("Reputation revision mismatch");
        result.revision=snapshot.revision();return result;
    }
}
