package com.sande.mythictrpg.story.api;

import com.sande.mythictrpg.story.definition.StoryDefinitions.AvailabilityState;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ExistenceState;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ScopeType;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only Story state boundary used by conditions and optional presentation modules. */
public interface StoryStateView {
    StoryStateView UNAVAILABLE = new StoryStateView() {
        @Override public boolean isReady() { return false; }
        @Override public boolean fact(StoryScopeKey scope, ResourceLocation factId) { return false; }
        @Override public Optional<StoryActorView> actor(ResourceLocation actorId) { return Optional.empty(); }
        @Override public Optional<StoryEventView> latestEvent(ResourceLocation eventId, StoryScopeKey scope) {
            return Optional.empty();
        }
        @Override public int knowledgeLevel(StoryKnowledgeHolder holder, ResourceLocation factId) { return 0; }
        @Override public Set<ResourceLocation> actorsAt(ResourceLocation locationId) { return Set.of(); }
    };

    boolean isReady();
    boolean fact(StoryScopeKey scope, ResourceLocation factId);
    Optional<StoryActorView> actor(ResourceLocation actorId);
    Optional<StoryEventView> latestEvent(ResourceLocation eventId, StoryScopeKey scope);
    int knowledgeLevel(StoryKnowledgeHolder holder, ResourceLocation factId);
    Set<ResourceLocation> actorsAt(ResourceLocation locationId);

    static StoryStateView unavailable() {
        return UNAVAILABLE;
    }

    record StoryScopeKey(ScopeType type, String key) implements Comparable<StoryScopeKey> {
        public StoryScopeKey {
            if (type == null || key == null || key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException("Invalid Story scope key");
            }
            key = key.trim();
        }

        public static StoryScopeKey server() { return new StoryScopeKey(ScopeType.SERVER, "server"); }
        public static StoryScopeKey player(UUID id) { return new StoryScopeKey(ScopeType.PLAYER, id.toString()); }
        public static StoryScopeKey team(UUID id) { return new StoryScopeKey(ScopeType.TEAM, id.toString()); }
        @Override public int compareTo(StoryScopeKey other) {
            int typeResult = type.compareTo(other.type);
            return typeResult != 0 ? typeResult : key.compareTo(other.key);
        }
    }

    record StoryActorView(ResourceLocation actorId, ExistenceState existence,
            AvailabilityState availability, Optional<ResourceLocation> locationId, long revision) {
    }

    record StoryEventView(ResourceLocation eventId, String instanceId, StoryScopeKey scope,
            String status, Optional<ResourceLocation> selectedOutcomeId, long revision) {
    }

    enum HolderType { PLAYER, ACTOR }

    record StoryKnowledgeHolder(HolderType type, String id) {
        public StoryKnowledgeHolder {
            if (type == null || id == null || id.isBlank() || id.length() > 256) {
                throw new IllegalArgumentException("Invalid Story knowledge holder");
            }
            id = id.trim();
        }

        public static StoryKnowledgeHolder player(UUID playerId) {
            return new StoryKnowledgeHolder(HolderType.PLAYER, playerId.toString());
        }

        public static StoryKnowledgeHolder actor(ResourceLocation actorId) {
            return new StoryKnowledgeHolder(HolderType.ACTOR, actorId.toString());
        }
    }
}
