package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Exact, current game-owned receipt lookup. A received allegation is never an authoritative world fact.
 * This optional read neither publishes/delivers/reviews a rumor nor changes affinity or archive policy. */
public final class NativeRumorReadAccess {
    private static final Gson JSON = new Gson();
    private NativeRumorReadAccess() { }

    public enum AssessmentAvailability { UNKNOWN, UNASSESSED, ASSESSED }
    public record Assessment(AssessmentAvailability availability, Optional<ReputationLedger.Outcome> outcome, long version) {
        public Assessment {
            Objects.requireNonNull(availability); Objects.requireNonNull(outcome);
            if (availability == AssessmentAvailability.ASSESSED ? outcome.isEmpty() || version < 1 : outcome.isPresent() || version != 0)
                throw new IllegalArgumentException("Invalid rumor assessment availability");
        }
        public static Assessment unknown() { return new Assessment(AssessmentAvailability.UNKNOWN, Optional.empty(), 0); }
        public static Assessment unassessed() { return new Assessment(AssessmentAvailability.UNASSESSED, Optional.empty(), 0); }
    }
    /** No courier entity, witness payload, coordinates, base affinity or another God's judgment is exposed. */
    public record Snapshot(UUID worldId, UUID lineageId, UUID rootId, long claimRevision, UUID subjectPlayerId,
            String recipientGodId, UUID evidenceSourceId, long evidenceSourceRevision, String evidenceExcerptHash,
            String claim, String epithet, Set<UUID> disclosureAudience, String reception, Assessment assessment) {
        public Snapshot {
            Objects.requireNonNull(worldId); Objects.requireNonNull(lineageId); Objects.requireNonNull(rootId);
            Objects.requireNonNull(subjectPlayerId); Objects.requireNonNull(evidenceSourceId);
            CourierSettings.identifier(recipientGodId); Objects.requireNonNull(assessment);
            disclosureAudience = Set.copyOf(disclosureAudience);
            if (claimRevision < 1 || evidenceSourceRevision < 1 || evidenceExcerptHash == null
                    || !evidenceExcerptHash.matches("[a-f0-9]{64}") || claim == null || claim.isBlank() || claim.length() > 300
                    || epithet == null || epithet.length() > 60 || disclosureAudience.isEmpty() || disclosureAudience.size() > 16
                    || !disclosureAudience.contains(subjectPlayerId) || !Set.of("CAUTIOUS", "INTERESTED").contains(reception))
                throw new IllegalArgumentException("Invalid native rumor snapshot");
        }
        /** Exact capture-v1 canonical binding; assessment/reception are intentionally current game lookups. */
        public String sourceHash() {
            return CourierSettings.hash(JSON.toJson(List.of(lineageId, rootId, subjectPlayerId, evidenceSourceId,
                    evidenceSourceRevision, evidenceExcerptHash, claimRevision, claim, epithet,
                    disclosureAudience.stream().map(UUID::toString).sorted().toList())));
        }
    }

    public static Optional<Snapshot> read(MinecraftServer server, Request request, UUID rootId) {
        if (!server.isSameThread()) throw new IllegalStateException("Native rumor reads require game thread");
        if (request == null || rootId == null) return Optional.empty();
        try {
            if (MemoryFoundationSettings.mode() != MemoryFoundationSettings.Mode.RUMOR_TEST
                    || !ConversationRooms.INSTANCE.memoryReadCurrent(server, request)) return Optional.empty();
            var data = RumorSavedData.get(server);
            if (!data.ready()) return Optional.empty();
            var scope = new Scope(data.worldId(), request.speakerGodId().toString(), request.audiencePlayerIds(),
                    request.godIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    request.publicRoom());
            return read(scope, rootId, new Probe() {
                public boolean current() { return data.ready() && MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.RUMOR_TEST
                        && ConversationRooms.INSTANCE.memoryReadCurrent(server, request); }
                public Optional<UUID> lineage() { return data.recordingSnapshot().filter(v -> v.metadata().worldId().equals(scope.worldId()))
                        .map(v -> v.metadata().lineage()); }
                public RumorLedger.Evidence evidence(UUID root) { return data.access(server, ledger -> ledger.evidence(root)); }
                public Optional<RumorLedger.HeardRumor> heard(UUID subject, String god, UUID root, Set<UUID> audience) {
                    return CourierRumorService.heardOne(server, subject, god, root, audience);
                }
                public Assessment assessment(RumorLedger.Evidence evidence, RumorLedger.HeardRumor heard, String god, Set<UUID> audience) {
                    return ReputationService.nativeAssessment(server, evidence, heard, god, audience);
                }
            });
        } catch (RuntimeException unavailable) { return Optional.empty(); }
    }
    /** Re-read on the game thread after asynchronous archive work, including the independent reputation ledger. */
    public static boolean current(MinecraftServer server, Request request, Snapshot snapshot) {
        return server.isSameThread() && snapshot != null && read(server, request, snapshot.rootId()).filter(snapshot::equals).isPresent();
    }

    record Scope(UUID worldId, String god, Set<UUID> players, Set<String> gods, boolean publicRoom) {
        Scope { Objects.requireNonNull(worldId); Objects.requireNonNull(god); players = Set.copyOf(players); gods = Set.copyOf(gods); }
        boolean allowed() { return !publicRoom && !players.isEmpty() && players.size() <= 16 && gods.equals(Set.of(god)); }
    }
    /** Package-local pure fixture seam, never a public authority issuer or caller-provided game provider. */
    interface Probe {
        boolean current();
        Optional<UUID> lineage();
        RumorLedger.Evidence evidence(UUID root);
        Optional<RumorLedger.HeardRumor> heard(UUID subject, String god, UUID root, Set<UUID> audience);
        Assessment assessment(RumorLedger.Evidence evidence, RumorLedger.HeardRumor heard, String god, Set<UUID> audience);
    }
    static Optional<Snapshot> read(Scope scope, UUID root, Probe probe) {
        try {
            if (!scope.allowed() || root == null || !probe.current()) return Optional.empty();
            var lineage = probe.lineage(); if (lineage.isEmpty()) return Optional.empty();
            var evidence = probe.evidence(root);
            if (evidence == null || evidence.proof() == null || !evidence.id().equals(root)
                    || !scope.players().contains(evidence.subject()) || !evidence.receivers().contains(scope.god())
                    || !evidence.disclosureAudience().containsAll(scope.players())) return Optional.empty();
            var heard = probe.heard(evidence.subject(), scope.god(), root, scope.players()).orElse(null);
            if (heard == null || !heard.rootId().equals(root)) return Optional.empty();
            var assessment = probe.assessment(evidence, heard, scope.god(), scope.players());
            var proof = evidence.proof();
            var value = new Snapshot(scope.worldId(), lineage.orElseThrow(), root, heard.revision(), evidence.subject(),
                    scope.god(), proof.sourceId(), proof.sourceRevision(), proof.excerptHash(), heard.text(), heard.epithet(),
                    evidence.disclosureAudience(), heard.reception(), assessment);
            if (!probe.current() || !probe.lineage().equals(lineage) || !Objects.equals(probe.evidence(root), evidence)
                    || !probe.heard(evidence.subject(), scope.god(), root, scope.players()).filter(heard::equals).isPresent()
                    || !Objects.equals(probe.assessment(evidence, heard, scope.god(), scope.players()), assessment)) return Optional.empty();
            return Optional.of(value);
        } catch (RuntimeException unavailable) { return Optional.empty(); }
    }
    static boolean current(Scope scope, Snapshot snapshot, Probe probe) {
        return snapshot != null && read(scope, snapshot.rootId(), probe).filter(snapshot::equals).isPresent();
    }

    /** Shared point-assessment validation. Missing/unavailable authored policy is not an UNASSESSED judgment. */
    static Assessment assessment(UUID world, RumorLedger.Evidence evidence, RumorLedger.HeardRumor heard,
            String god, ReputationSettings.Rule policy, ReputationLedger.Entry entry) {
        if (evidence == null || evidence.proof() == null || heard == null || policy == null
                || !policy.godId().equals(god) || !policy.courierRuleId().equals(evidence.proof().ruleId())
                || !evidence.id().equals(heard.rootId())) return Assessment.unknown();
        if (entry == null) return Assessment.unassessed();
        var d = entry.decision();
        if (!d.worldId().equals(world) || !d.subject().equals(evidence.subject()) || !d.godId().equals(god)
                || !d.sourceId().equals(evidence.proof().sourceId()) || !d.rootId().equals(evidence.id())
                || d.rumorRevision() != heard.revision() || !d.policyId().equals(policy.id())
                || !d.policyFingerprint().equals(policy.fingerprint())) return Assessment.unknown();
        return new Assessment(AssessmentAvailability.ASSESSED, Optional.of(d.outcome()), entry.version());
    }
}
