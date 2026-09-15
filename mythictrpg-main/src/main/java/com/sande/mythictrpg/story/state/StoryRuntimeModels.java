package com.sande.mythictrpg.story.state;

import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitions.AvailabilityState;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ExistenceState;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

public final class StoryRuntimeModels {
    private StoryRuntimeModels() {}

    public enum EventStatus {
        TRIGGERED,
        WAITING_FOR_CHOICE,
        DECIDED,
        COMMITTED,
        EXTERNAL_EFFECT_PENDING,
        RESOLVED,
        CANCELLED,
        RECOVERY_REQUIRED
    }

    public enum PresentationStatus {
        PENDING, GENERATING, DELIVERING_AI, DELIVERING_FALLBACK,
        DELIVERED, BLOCKED_DISCLOSURE, JOURNALED, CANCELLED
    }

    public record ScopedFactKey(StoryScopeKey scope, ResourceLocation factId)
            implements Comparable<ScopedFactKey> {
        public ScopedFactKey {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(factId, "factId");
        }
        @Override public int compareTo(ScopedFactKey other) {
            int scopeResult = scope.compareTo(other.scope);
            return scopeResult != 0 ? scopeResult : factId.compareTo(other.factId);
        }
    }

    public record ActorState(ResourceLocation actorId, ExistenceState existence,
            AvailabilityState availability, Optional<ResourceLocation> locationId, long revision,
            long lastChangedGameTime, ResourceLocation lastCauseId) {
        public ActorState {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(existence, "existence");
            Objects.requireNonNull(availability, "availability");
            locationId = Objects.requireNonNull(locationId, "locationId");
            Objects.requireNonNull(lastCauseId, "lastCauseId");
            if (revision < 0 || lastChangedGameTime < 0) throw new IllegalArgumentException("Invalid Actor state time");
            if ((existence == ExistenceState.SEALED || existence == ExistenceState.DEFEATED
                    || existence == ExistenceState.DESTROYED) && availability != AvailabilityState.ABSENT) {
                throw new IllegalArgumentException("Non-active Story actor must be absent: " + actorId);
            }
        }
    }

    public record EventInstance(String instanceId, ResourceLocation eventId, StoryScopeKey scope,
            Set<UUID> frozenAudiencePlayerIds, Optional<UUID> initiatingPlayerId, UUID triggerSignalId,
            EventStatus status, long revision, String definitionFingerprint,
            Optional<ResourceLocation> selectedOutcomeId, OptionalLong deterministicRoll,
            long triggeredAtGameTime, OptionalLong decidedAtGameTime,
            Set<ResourceLocation> appliedEffectIds, int sequence) {
        public EventInstance {
            instanceId = bounded(instanceId, "instanceId", 256);
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(scope, "scope");
            frozenAudiencePlayerIds = Set.copyOf(frozenAudiencePlayerIds);
            initiatingPlayerId = Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
            Objects.requireNonNull(triggerSignalId, "triggerSignalId");
            Objects.requireNonNull(status, "status");
            definitionFingerprint = bounded(definitionFingerprint, "definitionFingerprint", 128);
            selectedOutcomeId = Objects.requireNonNull(selectedOutcomeId, "selectedOutcomeId");
            deterministicRoll = Objects.requireNonNull(deterministicRoll, "deterministicRoll");
            decidedAtGameTime = Objects.requireNonNull(decidedAtGameTime, "decidedAtGameTime");
            appliedEffectIds = Set.copyOf(appliedEffectIds);
            if (revision < 0 || triggeredAtGameTime < 0 || sequence < 1)
                throw new IllegalArgumentException("Invalid Story event instance counters");
        }

        public EventInstance withDecision(ResourceLocation outcomeId, OptionalLong roll,
                EventStatus nextStatus, long gameTime) {
            return new EventInstance(instanceId, eventId, scope, frozenAudiencePlayerIds, initiatingPlayerId,
                    triggerSignalId, nextStatus, revision + 1, definitionFingerprint,
                    Optional.of(outcomeId), roll, triggeredAtGameTime, OptionalLong.of(gameTime),
                    appliedEffectIds, sequence);
        }

        public EventInstance withStatus(EventStatus nextStatus) {
            return new EventInstance(instanceId, eventId, scope, frozenAudiencePlayerIds, initiatingPlayerId,
                    triggerSignalId, nextStatus, revision + 1, definitionFingerprint, selectedOutcomeId,
                    deterministicRoll, triggeredAtGameTime, decidedAtGameTime, appliedEffectIds, sequence);
        }

        public EventInstance withAppliedEffects(Set<ResourceLocation> effects, EventStatus nextStatus) {
            return new EventInstance(instanceId, eventId, scope, frozenAudiencePlayerIds, initiatingPlayerId,
                    triggerSignalId, nextStatus, revision + 1, definitionFingerprint, selectedOutcomeId,
                    deterministicRoll, triggeredAtGameTime, decidedAtGameTime, effects, sequence);
        }
    }

    public record KnowledgeKey(StoryKnowledgeHolder holder, ResourceLocation factId)
            implements Comparable<KnowledgeKey> {
        public KnowledgeKey {
            Objects.requireNonNull(holder, "holder");
            Objects.requireNonNull(factId, "factId");
        }
        @Override public int compareTo(KnowledgeKey other) {
            int type = holder.type().compareTo(other.holder.type());
            if (type != 0) return type;
            int id = holder.id().compareTo(other.holder.id());
            return id != 0 ? id : factId.compareTo(other.factId);
        }
    }

    public record KnowledgeRecord(KnowledgeKey key, int knownLevel, ResourceLocation disclosurePolicyId,
            String sourceInstanceId, long learnedAtGameTime, long revision) {
        public KnowledgeRecord {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(disclosurePolicyId, "disclosurePolicyId");
            sourceInstanceId = bounded(sourceInstanceId, "sourceInstanceId", 256);
            if (knownLevel < 1 || knownLevel > 32 || learnedAtGameTime < 0 || revision < 1)
                throw new IllegalArgumentException("Invalid Story knowledge record");
        }
    }

    public record ScheduledSignal(UUID scheduleId, ResourceLocation targetEventId, StoryScopeKey scope,
            Set<UUID> frozenAudiencePlayerIds, Optional<UUID> initiatingPlayerId, long dueGameTime,
            String causeInstanceId, ResourceLocation causeEffectId) {
        public ScheduledSignal {
            Objects.requireNonNull(scheduleId, "scheduleId");
            Objects.requireNonNull(targetEventId, "targetEventId");
            Objects.requireNonNull(scope, "scope");
            frozenAudiencePlayerIds = Set.copyOf(frozenAudiencePlayerIds);
            initiatingPlayerId = Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
            causeInstanceId = bounded(causeInstanceId, "causeInstanceId", 256);
            Objects.requireNonNull(causeEffectId, "causeEffectId");
            if (dueGameTime < 0) throw new IllegalArgumentException("Invalid Story schedule due time");
        }
    }

    public record LatchedTrigger(String key, ResourceLocation eventId, StoryScopeKey scope,
            Set<UUID> frozenAudiencePlayerIds, Optional<UUID> initiatingPlayerId, UUID signalId,
            Optional<ResourceLocation> subjectId, long createdGameTime, OptionalLong expiresAtGameTime) {
        public LatchedTrigger {
            key = bounded(key, "latchedTrigger.key", 512);
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(scope, "scope");
            frozenAudiencePlayerIds = Set.copyOf(frozenAudiencePlayerIds);
            initiatingPlayerId = Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
            Objects.requireNonNull(signalId, "signalId");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            expiresAtGameTime = Objects.requireNonNull(expiresAtGameTime, "expiresAtGameTime");
            if (createdGameTime < 0) throw new IllegalArgumentException("Invalid latched trigger time");
        }
    }

    public record PresentationOpportunity(UUID opportunityId, String eventInstanceId, long eventRevision,
            ResourceLocation presentationId, UUID audiencePlayerId, PresentationStatus status,
            long createdAtGameTime) {
        public PresentationOpportunity {
            Objects.requireNonNull(opportunityId, "opportunityId");
            eventInstanceId = bounded(eventInstanceId, "eventInstanceId", 256);
            Objects.requireNonNull(presentationId, "presentationId");
            Objects.requireNonNull(audiencePlayerId, "audiencePlayerId");
            Objects.requireNonNull(status, "status");
            if (eventRevision < 0 || createdAtGameTime < 0) throw new IllegalArgumentException("Invalid presentation");
        }
    }

    public record HookAcceptanceRecord(String key, ResourceLocation hookId, String scopeKey,
            int count, long lastAcceptedGameTime) {
        public HookAcceptanceRecord {
            key = bounded(key, "hookAcceptance.key", 512);
            Objects.requireNonNull(hookId, "hookId");
            scopeKey = bounded(scopeKey, "hookAcceptance.scopeKey", 256);
            if (count < 1 || count > 1000 || lastAcceptedGameTime < 0)
                throw new IllegalArgumentException("Invalid Story Hook acceptance record");
        }
    }

    public record AuditEntry(long gameTime, String code, String detail) {
        public AuditEntry {
            if (gameTime < 0) throw new IllegalArgumentException("Invalid audit time");
            code = bounded(code, "code", 64);
            detail = bounded(detail, "detail", 512);
        }
    }

    private static String bounded(String value, String field, int maximum) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty() || value.length() > maximum)
            throw new IllegalArgumentException(field + " must be 1.." + maximum + " characters");
        return value;
    }
}
