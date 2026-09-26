package com.sande.mythictrpg.story.presentation;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.relation.GodRelationRoomContext;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.*;
import com.sande.mythictrpg.story.runtime.StoryDisclosureService;
import com.sande.mythictrpg.story.runtime.StoryHookService;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.*;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/** Game-owned disclosure capabilities. Model aliases are never facts or authorization themselves. */
public final class StoryRoomConversationService {
    public static final StoryRoomConversationService INSTANCE = new StoryRoomConversationService();
    public static final String EVIDENCE_KIND = "STORY_DISCLOSURE_V1";
    private static final Gson JSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(Optional.class, (JsonSerializer<Optional<?>>)(value, type, context) ->
                    value.map(context::serialize).orElse(JsonNull.INSTANCE))
            .registerTypeAdapter(OptionalInt.class, (JsonSerializer<OptionalInt>)(value, type, context) ->
                    value.isPresent() ? new JsonPrimitive(value.getAsInt()) : JsonNull.INSTANCE)
            .registerTypeAdapter(ResourceLocation.class, (JsonSerializer<ResourceLocation>)(value, type, context) ->
                    new JsonPrimitive(value.toString())).create();
    private final Map<UUID, Context> contexts = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> prepared = new HashMap<>();
    private final Set<UUID> activePresentationDeliveries = new HashSet<>();
    private StoryRoomConversationService() { }

    public Optional<Context> snapshot(ServerPlayer player, UUID roomId, long revision,
            ResourceLocation speakerGodId, String topic) {
        return snapshot(player, roomId, revision, speakerGodId, topic, true);
    }

    public Optional<Context> snapshot(ServerPlayer player, UUID roomId, long revision,
            ResourceLocation speakerGodId, String topic, boolean allowHooks) {
        requireThread(player);
        var room = room(player, roomId, revision, speakerGodId);
        var definitions = StoryDefinitionManager.INSTANCE.snapshot();
        var state = StoryRuntimeState.get(player.server);
        var actor = definitions.actors().values().stream()
                .filter(value -> value.godId().filter(speakerGodId::equals).isPresent())
                .sorted(Comparator.comparing(ActorDefinition::id)).findFirst().orElse(null);
        if (room == null || actor == null || !state.isReady() || !speakerAvailable(player, speakerGodId)) return Optional.empty();
        String normalizedTopic = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
        List<FactDisclosureRequest> requests = state.knowledge().values().stream()
                .filter(value -> value.key().holder().equals(StoryKnowledgeHolder.actor(actor.id())))
                .sorted(Comparator.<KnowledgeRecord>comparingInt(value -> {
                    var fact = definitions.facts().get(value.key().factId());
                    return fact == null ? 0 : (int)fact.keywords().stream()
                            .filter(word -> normalizedTopic.contains(word.getPath().replace('_', ' '))).count();
                }).reversed().thenComparing(value -> value.key().factId().toString())).limit(32)
                .map(value -> new FactDisclosureRequest(value.key().factId(), value.knownLevel(), false)).toList();
        return build(player, room, actor.id(), speakerGodId, requests, Optional.empty(), normalizedTopic,
                PresentationKind.CONVERSATION_CONTEXT, GenerationPolicy.AI_PARAPHRASE, List.of(),
                definitions.hooks().keySet().stream().sorted().toList(), 4, allowHooks, Optional.empty(), "");
    }

    /** Authored opt-in only. A deterministic single room is selected; never broadcast to all memberships. */
    public Optional<Context> forOpportunity(ServerPlayer player, PresentationOpportunity opportunity) {
        requireThread(player);
        var definition = StoryDefinitionManager.INSTANCE.presentation(opportunity.presentationId()).orElse(null);
        if (definition == null || definition.roomDisclosure().isEmpty() || definition.speakerActorId().isEmpty()) return Optional.empty();
        var actor = StoryDefinitionManager.INSTANCE.actor(definition.speakerActorId().orElseThrow()).orElse(null);
        var event = StoryRuntimeState.get(player.server).eventInstance(opportunity.eventInstanceId()).orElse(null);
        if (actor == null || actor.godId().isEmpty() || event == null || event.status() != EventStatus.RESOLVED) return Optional.empty();
        var god = actor.godId().orElseThrow();
        for (var room : ConversationRooms.INSTANCE.memberships(player).stream()
                .filter(value -> value.godIds().contains(god.toString()))
                .sorted(Comparator.comparing((ConversationRoomSnapshot value) -> value.type().isPublic())
                        .thenComparing(value -> value.roomId().toString())).toList()) {
            var result = build(player, room, actor.id(), god, definition.factRequests(), Optional.of(event.scope()), "",
                    definition.kind(), definition.generationPolicy(), definition.performanceTags().stream().sorted()
                            .limit(8).map(id -> id.getPath().replace('_', ' ')).toList(),
                    definition.offeredHookIds(), definition.maximumAiTurns(), true, Optional.of(definition.id()), opportunity.eventInstanceId());
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    private Optional<Context> build(ServerPlayer player, ConversationRoomSnapshot room, ResourceLocation actor,
            ResourceLocation god, List<FactDisclosureRequest> requests, Optional<StoryScopeKey> scope, String topic,
            PresentationKind kind, GenerationPolicy generation, List<String> directives, List<ResourceLocation> hooks,
            int maxLines, boolean allowHooks, Optional<ResourceLocation> presentationId, String presentationSource) {
        var audience = audience(player.server, room);
        var gods = godIds(room);
        if (audience.isEmpty() || audience.stream().anyMatch(id -> player.server.getPlayerList().getPlayer(id) == null)) return Optional.empty();
        List<StatementProof> statements = new ArrayList<>();
        var extra = new ArrayList<RoomEvidenceReference>();
        if (presentationId.isPresent()) {
            var definition = StoryDefinitionManager.INSTANCE.presentation(presentationId.orElseThrow()).orElseThrow();
            if (!definition.roomDisclosure().orElseThrow().permits(room.type().isPublic(), audience.size(), gods, god)) return Optional.empty();
            if (definition.fallbackTranslationKeys().stream().anyMatch(key -> translated(key) == null)) return Optional.empty();
            extra.add(reference(new Proof("PRESENTATION", actor.toString(), god.toString(), presentationId.orElseThrow().toString(),
                    0, "", "", presentationSource, "", "", false, presentationFingerprint(player.server, definition, presentationSource))));
        }
        for (var request : requests) {
            var candidates = factStatements(player.server, actor, god, request.factId(), presentationId.isPresent()
                    ? request.maximumLevel() : Math.min(request.maximumLevel(), 8 - statements.size()), scope,
                    room.type().isPublic(), audience, gods);
            if (candidates.isEmpty() && (request.required() || presentationId.isPresent())) return Optional.empty();
            if (presentationId.isPresent() && !candidates.isEmpty() && candidates.getFirst().proof().type().equals("FACT")
                    && candidates.size() < Math.min(request.maximumLevel(), StoryDefinitionManager.INSTANCE.fact(request.factId()).orElseThrow().maximumLevel()))
                return Optional.empty();
            if (statements.size() + candidates.size() > 8) {
                if (presentationId.isPresent()) return Optional.empty();
                continue;
            }
            statements.addAll(candidates);
            if (statements.size() == 8 && presentationId.isEmpty()) break;
        }
        UUID requestId = UUID.randomUUID();
        var offers = new ArrayList<HookOffer>();
        if (allowHooks && ConversationRooms.INSTANCE.actionScope(player, room.roomId(), room.revision(), god).isPresent()) {
            for (var hookId : hooks) {
                if (offers.size() >= 3) break;
                var hook = StoryDefinitionManager.INSTANCE.hook(hookId).orElse(null);
                if (hook == null || !hook.allowedSpeakerActorIds().contains(actor)
                        || !hook.disclosure().permits(room.type().isPublic(), audience.size(), gods, god)
                        || !StoryHookService.INSTANCE.preview(player, hook.id(), actor).accepted()) continue;
                String title = translated(hook.titleTranslationKey()), summary = translated(hook.summaryTranslationKey());
                if (title == null || summary == null) continue;
                String alias = StoryAiHookTokenService.INSTANCE.issueRoom(player, requestId, hook.id(), actor,
                        god, room.roomId(), room.revision(), offers.size() + 1);
                offers.add(new HookOffer(alias, title, summary));
                extra.add(reference(new Proof("HOOK", actor.toString(), god.toString(), hook.id().toString(),
                        0, "", "", "", "", "", false, definitionFingerprint(hook))));
            }
        }
        List<AllowedStatement> allowed = new ArrayList<>();
        Map<String, StatementProof> byAlias = new LinkedHashMap<>();
        for (var statement : statements) {
            String alias = "statement_" + (allowed.size() + 1);
            allowed.add(new AllowedStatement(statement.text(), true, alias)); byAlias.put(alias, statement);
        }
        var relations = GodRelationRoomContext.capture(player.server, room, god, audience);
        var snapshot = new Snapshot(requestId, player.getUUID(), god, kind, generation, allowed, directives, offers, maxLines,
                relations.promptText(), new Audience(room.type().isPublic(), audience, gods.stream().sorted().toList()));
        var context = new Context(room.roomId(), room.revision(), StoryDefinitionManager.INSTANCE.snapshot().generation(),
                StoryRuntimeState.get(player.server).globalRevision(), audience, topic, snapshot, byAlias,
                List.copyOf(extra), gods, room.type().isPublic(), player.server.overworld().getGameTime() + 1_200L,
                presentationId.isPresent(), relations);
        contexts.put(requestId, context);
        cleanup(player.server.overworld().getGameTime());
        return Optional.of(context);
    }

    private List<StatementProof> factStatements(MinecraftServer server, ResourceLocation actor, ResourceLocation god,
            ResourceLocation factId, int maximum, Optional<StoryScopeKey> specifiedScope,
            boolean publicRoom, Set<UUID> players, Set<ResourceLocation> gods) {
        var definitions = StoryDefinitionManager.INSTANCE.snapshot();
        var state = StoryRuntimeState.get(server);
        var fact = definitions.facts().get(factId);
        var knowledge = state.knowledgeRecord(StoryKnowledgeHolder.actor(actor), factId).orElse(null);
        if (!state.isReady() || fact == null || knowledge == null || players.isEmpty()) return List.of();
        var source = state.eventInstance(knowledge.sourceInstanceId());
        if (source.isPresent() && source.orElseThrow().status() != EventStatus.RESOLVED) return List.of();
        Optional<StoryScopeKey> eventScope = specifiedScope.or(() -> source.map(EventInstance::scope));
        var disclosures = players.stream().sorted().map(id -> StoryDisclosureService.INSTANCE.resolve(server, actor, id, factId,
                eventScope.orElseGet(() -> StoryScopeKey.player(id)))).toList();
        var first = disclosures.getFirst();
        String scopeType = eventScope.map(value -> value.type().name()).orElse("");
        String scopeKey = eventScope.map(StoryScopeKey::key).orElse("");
        var result = new ArrayList<StatementProof>();
        if (first.kind() == StoryDisclosureService.DisclosureKind.AUTHORED_COVER_STORY
                && disclosures.stream().allMatch(value -> value.kind() == first.kind()
                    && value.coverStoryId().equals(first.coverStoryId()) && value.canonicalTranslationKeys().equals(first.canonicalTranslationKeys()))) {
            var cover = first.coverStoryId().map(definitions.coverStories()::get).orElse(null);
            if (cover == null || !cover.disclosure().permits(publicRoom, players.size(), gods, god)) return List.of();
            int index = 0;
            for (String key : cover.canonicalTranslationKeys()) {
                String text = translated(key); if (text == null) return List.of();
                var proof = new Proof("COVER", actor.toString(), god.toString(), factId.toString(), ++index,
                        cover.id().toString(), knowledge.disclosurePolicyId().toString(), knowledge.sourceInstanceId(), scopeType, scopeKey,
                        factValue(state, factId, eventScope), factFingerprint(fact, knowledge.disclosurePolicyId(), cover, text));
                result.add(new StatementProof(text, proof));
            }
            return List.copyOf(result);
        }
        if (disclosures.stream().anyMatch(value -> value.kind() != StoryDisclosureService.DisclosureKind.TRUE_FACT)) return List.of();
        int disclosed = Math.min(maximum, disclosures.stream().mapToInt(value -> value.disclosedLevel()).min().orElse(0));
        List<Integer> otherLevels = gods.stream().filter(id -> !id.equals(god)).map(id -> definitions.actors().values().stream()
                .filter(other -> other.godId().filter(id::equals).isPresent())
                .mapToInt(other -> state.knowledgeLevel(StoryKnowledgeHolder.actor(other.id()), factId)).min().orElse(0)).toList();
        int legacyLevel = StoryRoomDisclosure.allowedLevel(knowledge.knownLevel(), publicRoom,
                state.publicPlayerKnowledge().getOrDefault(factId, 0), List.of(disclosed), otherLevels);
        for (int index = 0; index < Math.min(disclosed, fact.maximumLevel()); index++) {
            var level = fact.levels().get(index);
            boolean permitted = level.disclosure().map(policy -> policy.permits(publicRoom, players.size(), gods, god))
                    .orElse(level.level() <= legacyLevel);
            if (!permitted) break;
            String text = translated(level.canonicalTranslationKey()); if (text == null) return List.of();
            var proof = new Proof("FACT", actor.toString(), god.toString(), factId.toString(), level.level(), "",
                    knowledge.disclosurePolicyId().toString(), knowledge.sourceInstanceId(), scopeType, scopeKey,
                    factValue(state, factId, eventScope), factFingerprint(fact, knowledge.disclosurePolicyId(), null, text));
            result.add(new StatementProof(text, proof));
        }
        return List.copyOf(result);
    }

    /** Live contexts bind exact audience; portable evidence does not bind a transient global revision. */
    public boolean current(ServerPlayer player, Context context) {
        requireThread(player);
        if (!context.snapshot().audiencePlayerId().equals(player.getUUID()) || context.expiresAt() <= player.server.overworld().getGameTime()
                || !context.presentation() && !speakerAvailable(player, context.snapshot().speakerGodId())) return false;
        var room = room(player, context.roomId(), context.roomRevision(), context.snapshot().speakerGodId());
        if (room == null || !context.audience().equals(audience(player.server, room)) || !context.gods().equals(godIds(room))) return false;
        if (!GodRelationRoomContext.isCurrent(player.server, context.relations(), room, context.audience())) return false;
        return context.evidenceReferences().stream().allMatch(ref -> evidenceCurrent(player.server, context.publicRoom(),
                context.audience(), context.gods(), ref));
    }

    /** The memory reader may be another God: validate the original claim, not invented reader knowledge. */
    public boolean evidenceCurrent(MinecraftServer server, RoomConversationEngine.Request request, RoomEvidenceReference reference) {
        if (!server.isSameThread()) return false;
        return evidenceCurrent(server, request.publicRoom(), request.audiencePlayerIds(), Set.copyOf(request.godIds()), reference);
    }

    private boolean evidenceCurrent(MinecraftServer server, boolean publicRoom, Set<UUID> players,
            Set<ResourceLocation> gods, RoomEvidenceReference reference) {
        if (!EVIDENCE_KIND.equals(reference.kind()) || !StoryRuntimeState.get(server).isReady()) return false;
        try {
            Proof proof = JSON.fromJson(reference.payload(), Proof.class);
            ResourceLocation actor = ResourceLocation.parse(proof.actor()), god = ResourceLocation.parse(proof.god());
            if (StoryDefinitionManager.INSTANCE.actor(actor).flatMap(ActorDefinition::godId).filter(god::equals).isEmpty()) return false;
            if (proof.type().equals("HOOK")) {
                var hook = StoryDefinitionManager.INSTANCE.hook(ResourceLocation.parse(proof.id())).orElse(null);
                return hook != null && hook.allowedSpeakerActorIds().contains(actor)
                        && hook.disclosure().permits(publicRoom, players.size(), gods, god)
                        && proof.fingerprint().equals(definitionFingerprint(hook));
            }
            if (proof.type().equals("PRESENTATION")) {
                var presentation = StoryDefinitionManager.INSTANCE.presentation(ResourceLocation.parse(proof.id())).orElse(null);
                return presentation != null && presentation.speakerActorId().filter(actor::equals).isPresent()
                        && presentation.roomDisclosure().filter(p -> p.permits(publicRoom, players.size(), gods, god)).isPresent()
                        && StoryRuntimeState.get(server).eventInstance(proof.source()).filter(event -> event.status() == EventStatus.RESOLVED).isPresent()
                        && proof.fingerprint().equals(presentationFingerprint(server, presentation, proof.source()));
            }
            if (!proof.type().equals("FACT") && !proof.type().equals("COVER")) return false;
            Optional<StoryScopeKey> scope = proof.scopeType().isEmpty() ? Optional.empty()
                    : Optional.of(new StoryScopeKey(ScopeType.valueOf(proof.scopeType()), proof.scopeKey()));
            return factStatements(server, actor, god, ResourceLocation.parse(proof.id()), Math.max(1, proof.level()), scope,
                    publicRoom, players, gods).stream().anyMatch(value -> value.proof().equals(proof));
        } catch (RuntimeException malformed) { return false; }
    }

    public List<DisclosureLine> prepareDisclosures(ServerPlayer player, UUID contextId, UUID roomId, long revision,
            ResourceLocation speaker, Collection<String> aliases) {
        requireThread(player);
        var context = contexts.get(contextId);
        if (context == null || !context.roomId().equals(roomId) || context.roomRevision() != revision
                || !context.snapshot().speakerGodId().equals(speaker) || !current(player, context)
                || aliases.size() > 8 || aliases.stream().anyMatch(alias -> !context.statements().containsKey(alias))) return List.of();
        Set<String> expanded = new LinkedHashSet<>(aliases);
        for (String alias : aliases) {
            Proof selected = context.statements().get(alias).proof();
            context.statements().forEach((candidate, value) -> {
                if (selected.type().equals("FACT") && value.proof().type().equals("FACT")
                        && value.proof().id().equals(selected.id()) && value.proof().level() <= selected.level()) expanded.add(candidate);
            });
        }
        prepared.put(contextId, Set.copyOf(expanded));
        return context.snapshot().allowedStatements().stream().filter(value -> expanded.contains(value.alias()))
                .map(value -> new DisclosureLine(value.alias(), value.text(), reference(context.statements().get(value.alias()).proof()))).toList();
    }

    /** Exact canonical text and an actual game delivery receipt transfer truth; paraphrases/covers do not. */
    public void commitDisclosures(ServerPlayer player, UUID contextId, List<RoomDialogueEvent> receipts) {
        requireThread(player);
        var context = contexts.get(contextId);
        var aliases = prepared.remove(contextId);
        if (context == null || aliases == null || !context.snapshot().audiencePlayerId().equals(player.getUUID())) return;
        var currentRoom = room(player, context.roomId(), context.roomRevision(), context.snapshot().speakerGodId());
        if (currentRoom == null || currentRoom.recordingScope().isTest()
                || MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.RUMOR_TEST) return;
        var state = StoryRuntimeState.get(player.server);
        for (var statement : context.statements().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, StatementProof> entry) -> entry.getValue().proof().id())
                        .thenComparingInt(entry -> entry.getValue().proof().level())).toList()) {
            var value = statement.getValue(); var proof = value.proof();
            if (!aliases.contains(statement.getKey()) || !proof.type().equals("FACT")) continue;
            var ref = reference(proof);
            if (!evidenceCurrent(player.server, context.publicRoom(), context.audience(), context.gods(), ref)) continue;
            for (var receipt : receipts) {
                if (receipt.recordingScope().isTest() || !receipt.roomId().equals(context.roomId()) || receipt.revision() != context.roomRevision()
                        || !receipt.speakerId().equals(context.snapshot().speakerGodId().toString())
                        || !receipt.text().equals(value.text()) || !receipt.evidenceRefs().contains(ref)) continue;
                var fact = ResourceLocation.parse(proof.id()); var policy = ResourceLocation.parse(proof.policy());
                for (UUID recipient : receipt.fullTextReceiverIds()) {
                    if (context.audience().contains(recipient)) grant(state, StoryKnowledgeHolder.player(recipient), fact,
                            proof.level(), policy, proof.source(), player.server.overworld().getGameTime());
                }
                for (String heard : receipt.heardGodIds()) {
                    if (!context.gods().contains(ResourceLocation.parse(heard))) continue;
                    StoryDefinitionManager.INSTANCE.snapshot().actors().values().stream()
                            .filter(actor -> actor.godId().map(Object::toString).filter(heard::equals).isPresent())
                            .forEach(actor -> grant(state, StoryKnowledgeHolder.actor(actor.id()), fact,
                                    proof.level(), policy, proof.source(), player.server.overworld().getGameTime()));
                }
            }
        }
    }

    private static void grant(StoryRuntimeState state, StoryKnowledgeHolder holder, ResourceLocation fact, int level,
            ResourceLocation policy, String source, long now) {
        int known = state.knowledgeLevel(holder, fact);
        if (known < level && known >= level - 1) state.grantKnowledge(holder, fact, level, policy, source, now);
    }

    public boolean hookAudienceCurrent(ServerPlayer player, UUID roomId, long revision, ResourceLocation god,
            ResourceLocation hookId, Set<UUID> expectedAudience) {
        var room = room(player, roomId, revision, god);
        var hook = StoryDefinitionManager.INSTANCE.hook(hookId).orElse(null);
        return room != null && hook != null && expectedAudience.equals(audience(player.server, room))
                && hook.disclosure().permits(room.type().isPublic(), expectedAudience.size(), godIds(room), god);
    }

    public Set<UUID> roomAudience(ServerPlayer player, UUID roomId, long revision, ResourceLocation god) {
        var room = room(player, roomId, revision, god);
        return room == null ? Set.of() : audience(player.server, room);
    }

    /** Only a game-issued, authored presentation context may announce an actor's departure after the state change. */
    public boolean roomDeliveryAuthorized(ServerPlayer player, UUID contextId, UUID roomId, long revision, ResourceLocation god) {
        requireThread(player);
        var context = contexts.get(contextId);
        return activePresentationDeliveries.contains(contextId) && context != null && context.presentation()
                && context.roomId().equals(roomId) && context.roomRevision() == revision
                && context.snapshot().speakerGodId().equals(god) && current(player, context);
    }
    public boolean beginPresentationDelivery(ServerPlayer player, Context context) {
        requireThread(player);
        if (!context.presentation() || contexts.get(context.snapshot().requestId()) != context || !current(player, context)) return false;
        return activePresentationDeliveries.add(context.snapshot().requestId());
    }
    public void endPresentationDelivery(UUID contextId) { activePresentationDeliveries.remove(contextId); }

    public boolean speakerAvailable(ServerPlayer player, ResourceLocation speakerGodId) {
        requireThread(player);
        var actors = StoryDefinitionManager.INSTANCE.snapshot().actors().values().stream()
                .filter(value -> value.godId().filter(speakerGodId::equals).isPresent()).toList();
        if (actors.isEmpty()) return true;
        var state = StoryRuntimeState.get(player.server);
        return state.isReady() && actors.stream().allMatch(actor -> state.actor(actor.id())
                .filter(value -> value.existence() == ExistenceState.ACTIVE && value.availability() == AvailabilityState.AVAILABLE).isPresent());
    }

    public void clear() { contexts.clear(); prepared.clear(); activePresentationDeliveries.clear(); }
    private void cleanup(long now) {
        contexts.values().removeIf(value -> value.expiresAt() <= now);
        while (contexts.size() > 1024) contexts.remove(contexts.keySet().iterator().next());
        prepared.keySet().retainAll(contexts.keySet());
    }
    private static ConversationRoomSnapshot room(ServerPlayer player, UUID id, long revision, ResourceLocation god) {
        return ConversationRooms.INSTANCE.memberships(player).stream().filter(value -> value.roomId().equals(id)
                && value.revision() == revision && value.godIds().contains(god.toString())).findFirst().orElse(null);
    }
    private static Set<ResourceLocation> godIds(ConversationRoomSnapshot room) {
        return room.godIds().stream().map(ResourceLocation::parse).collect(Collectors.toUnmodifiableSet());
    }
    private static Set<UUID> audience(MinecraftServer server, ConversationRoomSnapshot room) {
        return room.type().isPublic() ? server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID)
                .collect(Collectors.toUnmodifiableSet()) : room.playerIds();
    }
    private static boolean factValue(StoryRuntimeState state, ResourceLocation fact, Optional<StoryScopeKey> scope) {
        return state.fact(scope.orElse(StoryScopeKey.server()), fact);
    }
    private static String factFingerprint(FactDefinition fact, ResourceLocation policy, CoverStoryDefinition cover, String text) {
        List<Object> values = new ArrayList<>(); values.add(fact); values.add(text); if (cover != null) values.add(cover);
        Set<ResourceLocation> seen = new HashSet<>();
        ResourceLocation next = policy;
        while (next != null && seen.add(next) && seen.size() <= 16) {
            var definition = StoryDefinitionManager.INSTANCE.disclosure(next).orElse(null);
            if (definition == null) break;
            values.add(definition); next = definition.lockedPolicyId().orElse(null);
        }
        return definitionFingerprint(values);
    }
    private static String presentationFingerprint(MinecraftServer server, PresentationDefinition definition, String source) {
        var event = StoryRuntimeState.get(server).eventInstance(source).orElseThrow();
        return definitionFingerprint(List.of(definition, event.eventId(), event.instanceId(), event.scope(),
                event.status(), event.selectedOutcomeId(), event.revision()));
    }
    static String definitionFingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                canonical(JSON.toJsonTree(value)).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) return value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> JSON.toJson(entry.getKey()) + ":" + canonical(entry.getValue())).collect(Collectors.joining(",", "{", "}"));
        if (value.isJsonArray()) return java.util.stream.StreamSupport.stream(value.getAsJsonArray().spliterator(), false)
                .map(StoryRoomConversationService::canonical).sorted().collect(Collectors.joining(",", "[", "]"));
        return value.toString();
    }
    private static RoomEvidenceReference reference(Proof proof) { return new RoomEvidenceReference(EVIDENCE_KIND, JSON.toJson(proof)); }
    private static String translated(String key) {
        String text = Component.translatable(key).getString().trim(); return text.isEmpty() || text.equals(key) ? null : text;
    }
    private static void requireThread(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Story room context requires game thread");
    }
    public record DisclosureLine(String alias, String text, RoomEvidenceReference evidence) { }
    public record Proof(String type, String actor, String god, String id, int level, String cover, String policy,
            String source, String scopeType, String scopeKey, boolean factValue, String fingerprint) { }
    public record StatementProof(String text, Proof proof) { }
    public record Context(UUID roomId, long roomRevision, long definitionGeneration, long stateRevision,
            Set<UUID> audience, String topic, Snapshot snapshot, Map<String, StatementProof> statements,
            List<RoomEvidenceReference> extraEvidence, Set<ResourceLocation> gods, boolean publicRoom, long expiresAt,
            boolean presentation, GodRelationRoomContext.Snapshot relations) {
        public Context {
            audience = Set.copyOf(audience); Objects.requireNonNull(topic); Objects.requireNonNull(snapshot);
            statements = Map.copyOf(statements); extraEvidence = List.copyOf(extraEvidence); gods = Set.copyOf(gods);
            Objects.requireNonNull(relations);
        }
        public List<RoomEvidenceReference> evidenceReferences() {
            var refs = new ArrayList<>(extraEvidence);
            snapshot.allowedStatements().forEach(value -> refs.add(reference(statements.get(value.alias()).proof())));
            return List.copyOf(refs);
        }
    }
}
