package com.sande.mythictrpg.gameplay.ledger;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-owned raw facts. Not an observation, public knowledge, reward, or AI input. */
public record ActionRecord(int schemaVersion, UUID worldId, long sequence, Draft event) {
    public ActionRecord {
        if (schemaVersion != 1 || sequence < 1) throw new IllegalArgumentException("Unsupported record/order");
        Objects.requireNonNull(worldId); Objects.requireNonNull(event);
    }
    public enum Type { MATURE_CROP_REMOVED, ENTITY_KILLED, POSITION_SAMPLE, DIMENSION_CHANGED, TELEPORT_RESULT,
        BLOCK_INTERACTION, BLOCK_BREAK_ATTEMPT, BLOCK_BREAK_RESULT, BLOCK_REMOVED,
        ATTACK_ATTEMPT, ATTACK_RESULT, DAMAGE_APPLIED, ITEM_PICKED_UP, PLAYER_DIED, QUEST_COMPLETED, QUEST_EVALUATED,
        BATTLE_RESULT, QUEST_TRANSITION, ADVANCEMENT_EARNED, OBSERVED_ACTIVITY_SUMMARY }
    public record Position(int x, int y, int z) {}
    public record Subject(String kind, String typeId, UUID entityId) {
        public Subject {
            if (!java.util.Set.of("BLOCK","ENTITY","ITEM","LOCATION","QUEST","BATTLE","ADVANCEMENT","ACTIVITY").contains(kind)) throw new IllegalArgumentException("subject kind");
            id(typeId);
            if ("ENTITY".equals(kind) != (entityId != null)) throw new IllegalArgumentException("subject identity");
        }
    }
    /** Optional extension for v1 journals. Null on historical records means unknown, never today's biome/roster. */
    public record Details(String biomeId, String locationStatus, UUID runId, java.util.Set<UUID> participants) {
        public Details {
            if (biomeId != null) id(biomeId);
            if (!java.util.Set.of("AT_TRANSITION", "OFFLINE_UNKNOWN", "LEGACY_UNKNOWN").contains(locationStatus))
                throw new IllegalArgumentException("location status");
            participants = java.util.Set.copyOf(participants);
            if (participants.size() > 64) throw new IllegalArgumentException("participant budget");
        }
    }
    /** Idempotent authoritative transition notification: retain FIRST capture, never replace its time/location. */
    public static boolean sameTransition(Draft first,Draft retry) {
        return java.util.Set.of(Type.QUEST_TRANSITION,Type.ADVANCEMENT_EARNED,Type.BATTLE_RESULT).contains(first.type())
                &&first.occurrenceId().equals(retry.occurrenceId())&&first.actorId().equals(retry.actorId())
                &&first.type()==retry.type()&&first.sourceRef().equals(retry.sourceRef())&&first.sourceRevision()==retry.sourceRevision()
                &&first.subject().equals(retry.subject())&&first.outcome().equals(retry.outcome())&&first.payload().equals(retry.payload())
                &&first.details()!=null&&retry.details()!=null&&Objects.equals(first.details().runId(),retry.details().runId())
                &&first.details().participants().equals(retry.details().participants());
    }
    public record Draft(UUID occurrenceId, UUID captureSession, long captureOrder, String sourceRef,
                        int sourceRevision, UUID actorId, Subject subject, long occurredAtUtc,
                        long gameTick, long gameDayTime, String dimensionId, Position position,
                        Type type, String outcome, Map<String, String> payload, String visibilityRef, Details details) {
        public Draft(UUID occurrenceId, UUID captureSession, long captureOrder, String sourceRef,
                int sourceRevision, UUID actorId, Subject subject, long occurredAtUtc,
                long gameTick, long gameDayTime, String dimensionId, Position position,
                Type type, String outcome, Map<String,String> payload, String visibilityRef) {
            this(occurrenceId,captureSession,captureOrder,sourceRef,sourceRevision,actorId,subject,occurredAtUtc,
                    gameTick,gameDayTime,dimensionId,position,type,outcome,payload,visibilityRef,null);
        }
        public Draft {
            Objects.requireNonNull(occurrenceId); Objects.requireNonNull(captureSession);
            Objects.requireNonNull(actorId); Objects.requireNonNull(subject);
            if (position == null && (details == null || !"OFFLINE_UNKNOWN".equals(details.locationStatus())))
                throw new IllegalArgumentException("Missing position without explicit offline status");
            Objects.requireNonNull(type); id(dimensionId); id(sourceRef);
            if (captureOrder < 1 || sourceRevision != 1 || occurredAtUtc < 0 || gameTick < 0
                    || !validOutcome(type, outcome) || !"mythictrpg:admin_only_unprojected".equals(visibilityRef)) {
                throw new IllegalArgumentException("Invalid raw fact metadata");
            }
            payload = Map.copyOf(payload);
            if (payload.size() > 8 || payload.entrySet().stream().anyMatch(e -> e.getKey().length() > 64
                    || e.getValue().length() > 256)) throw new IllegalArgumentException("Payload budget");
            if (type == Type.MATURE_CROP_REMOVED && (!subject.kind().equals("BLOCK")
                    || !sourceRef.equals("mythictrpg:crop_remove_commit"))) throw new IllegalArgumentException("Crop source");
            if (type == Type.ENTITY_KILLED && (!subject.kind().equals("ENTITY")
                    || !sourceRef.equals("mythictrpg:living_death_commit"))) throw new IllegalArgumentException("Death source");
            if (type != Type.MATURE_CROP_REMOVED && type != Type.ENTITY_KILLED
                    && !sourceRef.equals("mythictrpg:detail/" + type.name().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Detail source");
            String expectedKind=switch(type) {
                case MATURE_CROP_REMOVED,BLOCK_INTERACTION,BLOCK_BREAK_ATTEMPT,BLOCK_BREAK_RESULT,BLOCK_REMOVED -> "BLOCK";
                case ENTITY_KILLED,ATTACK_ATTEMPT,ATTACK_RESULT,DAMAGE_APPLIED,PLAYER_DIED -> "ENTITY";
                case POSITION_SAMPLE,DIMENSION_CHANGED,TELEPORT_RESULT -> "LOCATION";
                case ITEM_PICKED_UP -> "ITEM";
                case QUEST_COMPLETED,QUEST_EVALUATED,QUEST_TRANSITION -> "QUEST";
                case BATTLE_RESULT -> "BATTLE";
                case ADVANCEMENT_EARNED -> "ADVANCEMENT";
                case OBSERVED_ACTIVITY_SUMMARY -> "ACTIVITY";
            };
            if(!subject.kind().equals(expectedKind))throw new IllegalArgumentException("Type/subject mismatch");
        }
        /** Identifies one occurrence, not an action type, player, position, or tick. Reuse on retry. */
        public String dedupKey() { return sourceRef + "/" + occurrenceId + "/" + sourceRevision; }
    }
    private static boolean validOutcome(Type type, String value) {
        return switch(type) {
            case BLOCK_INTERACTION -> java.util.Set.of("REQUEST_ACCEPTED","CANCELLED","ABORTED").contains(value);
            case BLOCK_BREAK_ATTEMPT, ATTACK_ATTEMPT -> "ATTEMPTED".equals(value);
            case BLOCK_BREAK_RESULT -> java.util.Set.of("REMOVED","CANCELLED","NOT_REMOVED").contains(value);
            case ATTACK_RESULT -> java.util.Set.of("CANCELLED","DENIED_BY_ITEM","RETURNED_NO_CONFIRMED_HEALTH_LOSS","HEALTH_LOSS_OBSERVED").contains(value);
            case TELEPORT_RESULT -> java.util.Set.of("POSITION_CHANGED","NO_POSITION_CHANGE").contains(value);
            default -> "COMPLETED".equals(value);
        };
    }
    private static void id(String value) {
        if (value == null || value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid resource id");
    }
}
