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
    public record Snapshot(int version, UUID worldId, long revision, List<Entry> entries) {}
    private record Key(UUID subject,String god,UUID source) {}
    private final UUID worldId;
    private final Map<Key,Entry> entries=new LinkedHashMap<>();
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
    public Snapshot snapshot(){return new Snapshot(VERSION,worldId,revision,entries());}
    public static ReputationLedger restore(Snapshot snapshot) {
        if(snapshot.version()!=VERSION||snapshot.revision()<0||snapshot.entries().size()>LIMIT||snapshot.revision()<snapshot.entries().size())throw new IllegalArgumentException("Reputation schema/budget");
        var result=new ReputationLedger(snapshot.worldId());var ids=new HashSet<UUID>();long versions=0;
        for(var entry:snapshot.entries()) {
            var d=entry.decision();var key=new Key(d.subject(),d.godId(),d.sourceId());
            if(!d.worldId().equals(snapshot.worldId())||!ids.add(d.id())||result.entries.putIfAbsent(key,entry)!=null)throw new IllegalArgumentException("Reputation scope/duplicate");
            versions=Math.addExact(versions,entry.version());
        }
        if(versions!=snapshot.revision())throw new IllegalArgumentException("Reputation revision mismatch");
        result.revision=snapshot.revision();return result;
    }
}
