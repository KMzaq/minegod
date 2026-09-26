package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.CoverStoryDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.DisclosureMode;
import com.sande.mythictrpg.story.definition.StoryDefinitions.DisclosurePolicy;
import com.sande.mythictrpg.story.definition.StoryDefinitions.FactDefinition;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.KnowledgeRecord;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Resolves what one actor may tell one player without exposing withheld truth. */
public final class StoryDisclosureService {
    public static final StoryDisclosureService INSTANCE = new StoryDisclosureService();
    private StoryDisclosureService() {}

    public DisclosureResult resolve(MinecraftServer server, ResourceLocation speakerActorId,
            UUID audiencePlayerId, ResourceLocation factId, StoryScopeKey storyScope) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        if (!state.isReady()) return DisclosureResult.withheld("Story state unavailable");
        StoryKnowledgeHolder speaker = StoryKnowledgeHolder.actor(speakerActorId);
        KnowledgeRecord knowledge = state.knowledgeRecord(speaker, factId).orElse(null);
        FactDefinition fact = StoryDefinitionManager.INSTANCE.fact(factId).orElse(null);
        if (knowledge == null || fact == null) return DisclosureResult.unknown();
        return evaluate(server, speakerActorId, audiencePlayerId, storyScope, fact, knowledge,
                knowledge.disclosurePolicyId(), new LinkedHashSet<>());
    }

    public boolean commitTransfer(MinecraftServer server, UUID audiencePlayerId, ResourceLocation factId,
            int level, ResourceLocation disclosurePolicyId, String sourceInstanceId) {
        if (!server.isSameThread()) throw new IllegalStateException("Story disclosure must run on server thread");
        FactDefinition fact = StoryDefinitionManager.INSTANCE.fact(factId).orElse(null);
        if (fact == null || level < 1 || level > fact.maximumLevel()) return false;
        var state = StoryRuntimeState.get(server);
        if (state.knowledgeLevel(StoryKnowledgeHolder.player(audiencePlayerId), factId) >= level) return true;
        state.grantKnowledge(StoryKnowledgeHolder.player(audiencePlayerId), factId,
                level, disclosurePolicyId, sourceInstanceId, server.overworld().getGameTime());
        return true;
    }

    private DisclosureResult evaluate(MinecraftServer server, ResourceLocation speakerActorId,
            UUID audiencePlayerId, StoryScopeKey scope, FactDefinition fact, KnowledgeRecord knowledge,
            ResourceLocation policyId, Set<ResourceLocation> visited) {
        if (!visited.add(policyId) || visited.size() > 16) return DisclosureResult.withheld("Disclosure policy cycle");
        DisclosurePolicy policy = StoryDefinitionManager.INSTANCE.disclosure(policyId).orElse(null);
        if (policy == null) return DisclosureResult.withheld("Disclosure policy missing");
        return switch (policy.mode()) {
            case WITHHOLD -> DisclosureResult.withheld("Authored disclosure policy withholds this fact");
            case FULL -> trueFact(fact, knowledge.knownLevel(), policy.maximumDisclosedLevel().orElse(knowledge.knownLevel()));
            case PARTIAL -> trueFact(fact, knowledge.knownLevel(), policy.maximumDisclosedLevel().orElse(1));
            case CONDITIONAL -> {
                ConditionResult condition = policy.condition().map(value -> ConditionEngine.INSTANCE.evaluate(value,
                        ConditionContexts.forStory(server, Optional.of(audiencePlayerId), scope)))
                        .orElse(ConditionResult.UNKNOWN);
                if (condition == ConditionResult.MATCH) {
                    yield trueFact(fact, knowledge.knownLevel(),
                            policy.maximumDisclosedLevel().orElse(knowledge.knownLevel()));
                }
                ResourceLocation locked = policy.lockedPolicyId().orElse(null);
                yield locked == null ? DisclosureResult.withheld("Conditional disclosure is locked")
                        : evaluate(server, speakerActorId, audiencePlayerId, scope, fact, knowledge,
                                locked, visited);
            }
            case COVER_STORY -> {
                CoverStoryDefinition cover = policy.coverStoryId()
                        .flatMap(id -> Optional.ofNullable(StoryDefinitionManager.INSTANCE.snapshot()
                                .coverStories().get(id))).orElse(null);
                if (cover == null || !cover.allowedSpeakerActorIds().contains(speakerActorId))
                    yield DisclosureResult.withheld("Cover Story is unavailable for this speaker");
                yield new DisclosureResult(DisclosureKind.AUTHORED_COVER_STORY, 0,
                        cover.canonicalTranslationKeys(), "", Optional.of(cover.id()));
            }
        };
    }

    private static DisclosureResult trueFact(FactDefinition fact, int knownLevel, int policyMaximum) {
        int level = Math.min(fact.maximumLevel(), Math.min(knownLevel, policyMaximum));
        if (level < 1) return DisclosureResult.withheld("No discloseable fact level");
        List<String> keys = fact.levels().subList(0, level).stream()
                .map(value -> value.canonicalTranslationKey()).toList();
        return new DisclosureResult(DisclosureKind.TRUE_FACT, level, keys, "");
    }

    public enum DisclosureKind { TRUE_FACT, AUTHORED_COVER_STORY, WITHHELD, UNKNOWN }

    public record DisclosureResult(DisclosureKind kind, int disclosedLevel,
            List<String> canonicalTranslationKeys, String reason, Optional<ResourceLocation> coverStoryId) {
        public DisclosureResult(DisclosureKind kind, int disclosedLevel, List<String> keys, String reason) {
            this(kind, disclosedLevel, keys, reason, Optional.empty());
        }
        public DisclosureResult {
            canonicalTranslationKeys = List.copyOf(canonicalTranslationKeys);
            reason = reason == null ? "" : reason;
            coverStoryId = java.util.Objects.requireNonNull(coverStoryId);
        }
        static DisclosureResult withheld(String reason) {
            return new DisclosureResult(DisclosureKind.WITHHELD, 0, List.of(), reason);
        }
        static DisclosureResult unknown() {
            return new DisclosureResult(DisclosureKind.UNKNOWN, 0, List.of(), "Speaker does not know the fact");
        }
    }
}
