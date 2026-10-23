package com.sande.mythictrpg.understanding;

import com.google.gson.Gson;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Derived annotations in the existing quota-managed action journal. The referenced source stays authoritative. */
public final class PurposeLedger {
    private static final Gson JSON = new Gson();
    public static final String SUBJECT = "mythictrpg:purpose_evidence";
    private PurposeLedger() { }
    /** The game record determines independence, never a model's fresh UUID per sentence.
     * One authored run is one episode; otherwise one capture session / ten-minute game-time bucket.
     * A restart cannot join unrelated sessions, and spam inside a bucket cannot farm repetition. */
    public static UUID episodeOf(ActionRecord source) {
        var e=source.event();
        String episode=e.details()!=null&&e.details().runId()!=null?"run/"+e.details().runId()
                :"capture/"+e.captureSession()+"/"+(e.gameTick()/12000L);
        return UUID.nameUUIDFromBytes((source.worldId()+"/purpose-episode/"+episode).getBytes(StandardCharsets.UTF_8));
    }
    public record Slice(boolean active, long startOrder, long head, boolean complete, List<PurposeEvidence> evidence) {
        public Slice {
            evidence = List.copyOf(evidence);
            if (startOrder < 0 || head < startOrder || evidence.size() > PurposeState.MAX_EVIDENCE || !active && !evidence.isEmpty())
                throw new IllegalArgumentException("PURPOSE_SLICE");
        }
        public PurposeState.Projection project(UUID world, UUID player, java.util.function.Predicate<PurposeEvidence> permitted) {
            return PurposeState.project(world, player, startOrder, evidence, complete, permitted);
        }
    }
    public static PurposeEvidence decode(Map<String, String> payload) {
        if (!payload.keySet().equals(Set.of("purpose_v1"))) throw new IllegalArgumentException("PURPOSE_ANNOTATION_SCHEMA");
        return Objects.requireNonNull(JSON.fromJson(payload.get("purpose_v1"), PurposeEvidence.class));
    }
    /** No timestamps or game actor copied from an LLM. Annotation IDs are idempotent per source/goal/rules. */
    public static ActionRecord.Draft annotate(ActionRecord source, PurposeEvidence evidence) {
        validateSource(source, evidence);
        var e = source.event();
        UUID id = UUID.nameUUIDFromBytes((evidence.worldId() + "/purpose-v" + PurposeState.RULES_VERSION + "/" + evidence.identity()).getBytes(StandardCharsets.UTF_8));
        return new ActionRecord.Draft(id, e.captureSession(), e.captureOrder(), "mythictrpg:detail/player_purpose_evidence", 1,
                e.actorId(), new ActionRecord.Subject("ACTIVITY", SUBJECT, null), e.occurredAtUtc(), e.gameTick(), e.gameDayTime(),
                e.dimensionId(), e.position(), ActionRecord.Type.PLAYER_PURPOSE_EVIDENCE, "COMPLETED",
                Map.of("purpose_v1", JSON.toJson(evidence)), "mythictrpg:admin_only_unprojected", e.details());
    }
    public static void validateSource(ActionRecord source, PurposeEvidence evidence) {
        var e = source.event();
        if (!source.worldId().equals(evidence.worldId()) || !e.actorId().equals(evidence.playerId())
                || !e.occurrenceId().equals(evidence.sourceId()) || source.sequence() != evidence.sourceOrder()
                || !episodeOf(source).equals(evidence.episodeId())
                || e.type() == ActionRecord.Type.PLAYER_PURPOSE_EVIDENCE || e.type() == ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL
                || e.type() == ActionRecord.Type.PLAYER_ACTIVITY_FACT // Compact activity counts do not identify a purpose target or voluntariness.
                || evidence.origin() == PurposeEvidence.Origin.RUMOR)
            throw new IllegalArgumentException("PURPOSE_FOREIGN_OR_DERIVED_SOURCE");
        if (("true".equals(e.payload().get("test")) || e.subject().typeId().equals("mythictrpg:trial/sealed_guardian")
                || e.dimensionId().equals("mythictrpg:raid_trial")) && !evidence.test())
            throw new IllegalArgumentException("PURPOSE_TEST_LAUNDERING");
        boolean selfNote = e.type() == ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK
                && "purpose".equals(e.payload().get("key")) && "DECLARE".equals(e.payload().get("operation"));
        if ((evidence.origin() == PurposeEvidence.Origin.SELF_SPEECH) != selfNote)
            throw new IllegalArgumentException("PURPOSE_SOURCE_ORIGIN_MISMATCH");
        if (selfNote && !evidence.sourceDescription().equals(e.payload().get("text")))
            throw new IllegalArgumentException("PURPOSE_STATEMENT_TEXT_CHANGED");
        if (e.type() == ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK && !selfNote)
            throw new IllegalArgumentException("PURPOSE_NOT_A_STATEMENT");
        if (evidence.signal() == PurposeEvidence.Signal.COMPLETE && (evidence.goal().kind() != PurposeEvidence.Kind.DO_ACTIVITY
                || !evidence.goal().targetId().equals(e.subject().typeId()) || !(e.type() == ActionRecord.Type.QUEST_COMPLETED
                || e.type() == ActionRecord.Type.BATTLE_RESULT && "VICTORY".equals(e.payload().get("battle_result")))))
            throw new IllegalArgumentException("PURPOSE_COMPLETION_SOURCE");
    }
}
