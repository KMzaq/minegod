package com.sande.mythictrpg.story.presentation;

import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.*;
import com.sande.mythictrpg.story.runtime.StoryDisclosureService;
import com.sande.mythictrpg.story.runtime.StoryHookService;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.KnowledgeRecord;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.PresentationOpportunity;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Builds the only Story data shape an AI implementation is allowed to see. */
public final class StoryPresentationSnapshotService {
    public static final StoryPresentationSnapshotService INSTANCE = new StoryPresentationSnapshotService();
    private StoryPresentationSnapshotService() {}

    public BuildResult forOpportunity(ServerPlayer player, PresentationOpportunity opportunity) {
        var definitions = StoryDefinitionManager.INSTANCE;
        PresentationDefinition presentation = definitions.presentation(opportunity.presentationId()).orElse(null);
        if (presentation == null || presentation.speakerActorId().isEmpty())
            return BuildResult.blocked("Presentation or speaker is unavailable");
        ResourceLocation actorId = presentation.speakerActorId().orElseThrow();
        ResourceLocation godId = definitions.actor(actorId).flatMap(ActorDefinition::godId).orElse(null);
        var event = StoryRuntimeState.get(player.server).eventInstance(opportunity.eventInstanceId()).orElse(null);
        if (godId == null || event == null) return BuildResult.blocked("Speaker or source event is unavailable");
        return build(player, UUID.randomUUID(), actorId, godId, presentation.kind(),
                presentation.generationPolicy(), presentation.factRequests(), presentation.performanceTags(),
                presentation.offeredHookIds(), presentation.maximumAiTurns(), event.scope(),
                opportunity.eventInstanceId());
    }

    /** Context query for a live player/God conversation; shortlist is bounded and speaker-knowledge-only. */
    public Optional<Snapshot> forConversation(ServerPlayer player, ResourceLocation speakerGodId, String topic) {
        var actor = StoryDefinitionManager.INSTANCE.snapshot().actors().values().stream()
                .filter(value -> value.godId().filter(speakerGodId::equals).isPresent()).findFirst().orElse(null);
        if (actor == null) return Optional.empty();
        String normalizedTopic = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
        List<KnowledgeRecord> known = StoryRuntimeState.get(player.server).knowledge().values().stream()
                .filter(value -> value.key().holder().equals(StoryKnowledgeHolder.actor(actor.id())))
                .sorted(Comparator.<KnowledgeRecord>comparingInt(value -> topicScore(value, normalizedTopic))
                        .reversed().thenComparing(Comparator.comparingLong(
                                KnowledgeRecord::learnedAtGameTime).reversed())).limit(8).toList();
        List<FactDisclosureRequest> requests = known.stream()
                .map(value -> new FactDisclosureRequest(value.key().factId(), value.knownLevel(), false)).toList();
        BuildResult result = build(player, UUID.randomUUID(), actor.id(), speakerGodId,
                PresentationKind.CONVERSATION_CONTEXT, GenerationPolicy.AI_PARAPHRASE, requests,
                Set.of(), availableHooks(actor.id()), 4, StoryScopeKey.player(player.getUUID()), "conversation");
        return result.snapshot();
    }

    private BuildResult build(ServerPlayer player, UUID requestId, ResourceLocation actorId,
            ResourceLocation godId, PresentationKind kind, GenerationPolicy generationPolicy,
            List<FactDisclosureRequest> requests, Set<ResourceLocation> performanceTags,
            List<ResourceLocation> hookIds, int maximumTurns, StoryScopeKey scope, String sourceInstance) {
        List<AllowedStatement> statements = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();
        for (FactDisclosureRequest request : requests) {
            var disclosure = StoryDisclosureService.INSTANCE.resolve(player.server, actorId, player.getUUID(),
                    request.factId(), scope);
            if (disclosure.kind() == StoryDisclosureService.DisclosureKind.TRUE_FACT) {
                int level = Math.min(request.maximumLevel(), disclosure.disclosedLevel());
                List<String> keys = disclosure.canonicalTranslationKeys().subList(0,
                        Math.min(level, disclosure.canonicalTranslationKeys().size()));
                for (String key : keys) {
                    String semantic = translated(key);
                    if (semantic == null) return BuildResult.blocked("Canonical prompt text is not translated");
                    statements.add(new AllowedStatement(semantic, true));
                }
                KnowledgeRecord knowledge = StoryRuntimeState.get(player.server)
                        .knowledgeRecord(StoryKnowledgeHolder.actor(actorId), request.factId()).orElseThrow();
                transfers.add(new Transfer(request.factId(), level, knowledge.disclosurePolicyId(), sourceInstance));
            } else if (disclosure.kind() == StoryDisclosureService.DisclosureKind.AUTHORED_COVER_STORY) {
                for (String key : disclosure.canonicalTranslationKeys()) {
                    String semantic = translated(key);
                    if (semantic == null) return BuildResult.blocked("Cover Story prompt text is not translated");
                    statements.add(new AllowedStatement(semantic, true));
                }
            } else if (request.required()) {
                return BuildResult.blocked(disclosure.reason());
            }
        }
        List<String> directives = performanceTags.stream().limit(8)
                .map(id -> id.getPath().replace('_', ' ')).toList();
        List<HookOffer> hookOffers = new ArrayList<>();
        var scopeInfo = AiConversationRuntimeService.INSTANCE.currentActionScope(player);
        if (scopeInfo.isPresent() && scopeInfo.orElseThrow().actingGodId().equals(godId)) {
            int index = 1;
            for (ResourceLocation hookId : hookIds) {
                if (hookOffers.size() >= 3) break;
                var hook = StoryDefinitionManager.INSTANCE.hook(hookId).orElse(null);
                if (hook == null || !StoryHookService.INSTANCE.preview(player, hookId, actorId).accepted()) continue;
                String title = translated(hook.titleTranslationKey());
                String summary = translated(hook.summaryTranslationKey());
                if (title == null || summary == null) continue;
                String alias = StoryAiHookTokenService.INSTANCE.issue(player, requestId, hookId, actorId, godId,
                        scopeInfo.orElseThrow().sessionId(), index++);
                hookOffers.add(new HookOffer(alias, title, summary));
            }
        }
        // Never transfer knowledge for statements silently dropped by the model context budget.
        if (statements.size() > 8) return BuildResult.blocked("Canonical statements exceed the presentation budget");
        Snapshot snapshot = new Snapshot(requestId, player.getUUID(), godId, kind, generationPolicy,
                statements, directives, hookOffers, maximumTurns);
        return new BuildResult(Optional.of(snapshot), List.copyOf(transfers), "");
    }

    private static List<ResourceLocation> availableHooks(ResourceLocation actorId) {
        return StoryDefinitionManager.INSTANCE.snapshot().hooks().values().stream()
                .filter(value -> value.allowedSpeakerActorIds().contains(actorId)).map(HookDefinition::id).toList();
    }

    private static int topicScore(KnowledgeRecord knowledge, String topic) {
        if (topic.isBlank()) return 0;
        FactDefinition fact = StoryDefinitionManager.INSTANCE.fact(knowledge.key().factId()).orElse(null);
        if (fact == null) return 0;
        return (int) fact.keywords().stream().map(ResourceLocation::getPath)
                .filter(keyword -> topic.contains(keyword.replace('_', ' ')) || topic.contains(keyword))
                .count();
    }

    private static String translated(String key) {
        String value = Component.translatable(key).getString().trim();
        return value.isEmpty() || value.equals(key) ? null : value;
    }

    public record Transfer(ResourceLocation factId, int level, ResourceLocation disclosurePolicyId,
            String sourceInstanceId) {}
    public record BuildResult(Optional<Snapshot> snapshot, List<Transfer> transfers, String reason) {
        public BuildResult { snapshot = Objects.requireNonNull(snapshot); transfers = List.copyOf(transfers); }
        static BuildResult blocked(String reason) { return new BuildResult(Optional.empty(), List.of(), reason); }
    }
}
