package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.intent.ConversationAct;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.tone.PlayerSpeechToneHeuristics;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.dialogue.server.DialogueSendStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary, read-only Minecraft adapter for exercising the local LLM with MythAI Content Registry data. It owns
 * only in-memory test sessions and never calls proposal gateways or gameplay-state services.
 */
public final class AiTestDialogueAdapter {
    public static final AiTestDialogueAdapter INSTANCE = new AiTestDialogueAdapter();

    private static final Set<String> RELATIONSHIP_TIERS = Set.of("R_EXTREME_HOSTILE", "R_HOSTILE", "R_DISLIKE",
            "R_WARY", "R_NEUTRAL", "R_FAVORABLE", "R_FRIENDLY", "R_TRUSTED", "R_DEEP_BOND");
    private static final List<String> ACTION_CLAIM_MARKERS = List.of("아이템을 지급", "아이템을 드렸", "보상을 지급",
            "보상을 드렸", "퀘스트를 등록", "의뢰를 등록", "관계를 변경", "호감도를 올렸", "호감도를 내렸",
            "몹을 소환", "몬스터를 소환", "소환했", "가호를 부여", "i gave you", "quest registered",
            "relationship changed", "summoned a", "reward granted");

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AiTestContentRegistryBridge contentRegistry = new AiTestContentRegistryBridge();
    private final LocalLlmClient llm = new LocalOllamaClient();
    private final AiTestDialogueLog logs = new AiTestDialogueLog();

    private AiTestDialogueAdapter() {
    }

    /** Set -Dmythictrpg.ai_test.enabled=false to leave the test adapter completely unregistered. */
    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("mythictrpg.ai_test.enabled", "true"));
    }

    public StartResult start(ServerPlayer player, ResourceLocation godId) {
        if (GodAiDialogueService.INSTANCE.isActive(player)) {
            return StartResult.NORMAL_AI_SESSION_ACTIVE;
        }
        try {
            AiTestContentRegistryBridge.ContentSnapshot content = contentRegistry.load(godId, "R_NEUTRAL", List.of(godId));
            Session session = new Session(player.getUUID(), List.of(godId), "R_NEUTRAL", "E_NEUTRAL", content.profile());
            sessions.put(player.getUUID(), session);
            logs.append(player.server, player.getUUID(), "SYSTEM", "Started AI test session for " + godId);
            player.sendSystemMessage(Component.literal("[AI Test] " + content.profile().displayName()
                    + " 테스트 대화를 시작했습니다. 일반 채팅 또는 /ai_test say로 입력하세요.")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            return StartResult.STARTED;
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Could not start AI test session for {}", godId, exception);
            return StartResult.contentFailure(message(exception));
        }
    }

    public boolean stop(ServerPlayer player) {
        Session removed = sessions.remove(player.getUUID());
        if (removed == null) {
            return false;
        }
        logs.append(player.server, player.getUUID(), "SYSTEM", "Stopped AI test session");
        player.sendSystemMessage(Component.literal("[AI Test] 테스트 대화를 종료했습니다.").withStyle(ChatFormatting.GRAY));
        return true;
    }

    public boolean isActive(ServerPlayer player) {
        return sessions.containsKey(player.getUUID());
    }

    public boolean setRelationship(ServerPlayer player, String tier) {
        Session session = sessions.get(player.getUUID());
        String normalized = normalizeTier(tier);
        if (session == null || normalized == null) {
            return false;
        }
        session.relationshipTier = normalized;
        player.sendSystemMessage(Component.literal("[AI Test] 관계 테스트 태그: " + normalized));
        return true;
    }

    public boolean setEmotion(ServerPlayer player, String emotionTag) {
        Session session = sessions.get(player.getUUID());
        String normalized = normalizeEmotion(emotionTag);
        if (session == null || normalized == null) {
            return false;
        }
        session.emotionTag = normalized;
        player.sendSystemMessage(Component.literal("[AI Test] 감정 테스트 태그: " + normalized));
        return true;
    }

    public boolean setParticipants(ServerPlayer player, ResourceLocation first, ResourceLocation second) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return false;
        }
        List<ResourceLocation> participants = first.equals(second) ? List.of(first) : List.of(first, second);
        try {
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents = loadContents(participants,
                    session.relationshipTier);
            session.participants = participants;
            session.profile = contents.get(first).profile();
            player.sendSystemMessage(Component.literal("[AI Test] NPC 참가자: " + joinIds(participants)));
            return true;
        } catch (RuntimeException exception) {
            player.sendSystemMessage(Component.literal("[AI Test] 참가자 설정 실패: " + message(exception))
                    .withStyle(ChatFormatting.RED));
            return false;
        }
    }

    public boolean toggleDebug(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return false;
        }
        session.debug = !session.debug;
        player.sendSystemMessage(Component.literal("[AI Test] debug=" + session.debug));
        return true;
    }

    public Component status(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return Component.literal("활성 AI 테스트 대화가 없습니다.");
        }
        return Component.literal("[AI Test] NPC=" + joinIds(session.participants) + " / " + session.relationshipTier
                + " / " + session.emotionTag + " / debug=" + session.debug + (session.pending ? " / 생성 중" : ""));
    }

    public void handlePlayerText(ServerPlayer player, String rawText) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            return;
        }
        String text = bounded(rawText, AiDialogueConfig.INSTANCE.settings().maxPromptCharacters());
        if (text.isEmpty()) {
            player.sendSystemMessage(Component.literal("[AI Test] 빈 메시지는 보낼 수 없습니다.").withStyle(ChatFormatting.RED));
            return;
        }
        if (session.pending) {
            player.sendSystemMessage(Component.literal("[AI Test] 이전 응답을 생성 중입니다.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        session.pending = true;
        long turn = ++session.turn;
        session.history.add(new Transcript("PLAYER", "player", player.getGameProfile().getName(), text));
        trimHistory(session);
        logs.append(player.server, player.getUUID(), "PLAYER", text);

        Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents;
        try {
            contents = loadContents(session.participants, session.relationshipTier);
            session.profile = contents.get(session.speaker()).profile();
        } catch (RuntimeException exception) {
            finishFailure(player, session, turn, "콘텐츠 레지스트리 오류: " + message(exception));
            return;
        }
        GenerationPlan socialPlan = generationPlan(session, text, ConversationIntent.heuristicFallback(), contents);
        if (isDirectSocialTurn(socialPlan.directive())) {
            completeDirectSocialResponse(player, session, turn, contents, socialPlan);
            return;
        }
        ConversationIntent fastSocialIntent = simpleCasualFeelingIntent(text);
        if (fastSocialIntent != null) {
            logs.appendBlock(player.server, player.getUUID(), "INTENT_ROUTING_POLICY",
                    "llm_called=false\nreason=clear_simple_casual_feeling\nintent=S_CHAT + CASUAL_FEELING");
            submitGeneration(player, session, turn, text, contents, fastSocialIntent, null);
            return;
        }
        UUID routingRequest = UUID.randomUUID();
        LocalOllamaClient.installWireObserver(routingRequest, new LocalOllamaClient.WireObserver() {
            @Override
            public void onRequest(String json) {
                logs.appendBlock(player.server, player.getUUID(), "INTENT_CLASSIFIER_REQUEST_JSON", json);
            }

            @Override
            public void onResponse(String json) {
                logs.appendBlock(player.server, player.getUUID(), "INTENT_CLASSIFIER_RAW_RESPONSE", json);
            }

            @Override
            public void onFailure(Throwable failure) {
                logs.append(player.server, player.getUUID(), "INTENT_CLASSIFIER_FAILURE", message(failure));
            }
        });
        try {
            List<AiTestContentRegistryBridge.Example> intentExamples = intentClassificationExamples(contents);
            logs.appendBlock(player.server, player.getUUID(), "INTENT_CLASSIFIER_EXAMPLES",
                    intentExamples.stream().map(AiTestDialogueAdapter::formatIntentExample).reduce("", (left, right) ->
                            left.isEmpty() ? right : left + "\n" + right));
            llm.submitIntent(routingRequest, classifierMessages(session, text, intentExamples), AiDialogueConfig.INSTANCE.settings())
                    .completion().whenComplete((scheduled, failure) -> player.server.execute(() -> {
                        ConversationIntent classified = failure == null && scheduled != null && scheduled.value() != null
                                ? scheduled.value() : ConversationIntent.heuristicFallback();
                        ConversationIntent intent = resolveConversationIntent(classified, text, session);
                        logs.appendBlock(player.server, player.getUUID(), "INTENT_CLASSIFIER_PARSED",
                                "tags=" + intent.tags() + "\nconversationAct=" + intent.conversationAct()
                                        + "\nconfidence=" + intent.confidence() + "\nsource=" + intent.source());
                        submitGeneration(player, session, turn, text, contents, intent, failure);
                    }));
        } catch (RuntimeException exception) {
            LocalOllamaClient.removeWireObserver(routingRequest);
            submitGeneration(player, session, turn, text, contents,
                    resolveConversationIntent(ConversationIntent.heuristicFallback(), text, session), exception);
        }
    }

    private void submitGeneration(ServerPlayer player, Session session, long turn, String text,
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents, ConversationIntent intent,
            Throwable routingFailure) {
        if (!isCurrent(player, session, turn)) {
            return;
        }
        GenerationPlan plan = generationPlan(session, text, intent, contents);
        AiTestContentRegistryBridge.ContentSnapshot primary = contents.get(plan.speakers().getFirst());
        List<String> tags = promptTags(session, primary, intent);
        List<AiTestContentRegistryBridge.Lore> lore = plan.directive().isSocialOnly() ? List.of()
                : selectedLore(primary, intent);
        // Dialogue examples deliberately remain registered for future intent/retrieval work, but are never passed to
        // the phase-2 generator.  Qwen tends to reuse their objects and sentence shapes as if they were live context.
        List<AiTestContentRegistryBridge.Example> examples = List.of();
        ConversationDynamics dynamics = ConversationDynamics.from(session, text, intent);
        UUID requestId = UUID.randomUUID();
        List<AiDialogueModels.OllamaMessage> messages = generationMessages(player, session, text, contents, plan, tags,
                lore, dynamics);
        logGenerationInput(player, primary, session, plan, tags, lore, examples, dynamics, messages);
        LocalOllamaClient.installWireObserver(requestId, new LocalOllamaClient.WireObserver() {
            @Override
            public void onRequest(String json) {
                logs.appendBlock(player.server, player.getUUID(), "GENERATION_REQUEST_JSON", json);
            }

            @Override
            public void onResponse(String json) {
                logs.appendBlock(player.server, player.getUUID(), "GENERATION_RAW_LLM_RESPONSE", json);
            }

            @Override
            public void onFailure(Throwable failure) {
                logs.append(player.server, player.getUUID(), "GENERATION_TRANSPORT_FAILURE", message(failure));
            }
        });
        try {
            llm.submit(requestId, messages, AiDialogueConfig.INSTANCE.settings()).completion().whenComplete((scheduled, failure) ->
                            player.server.execute(() -> completeGeneration(player, session, turn, contents, plan, tags,
                                    lore, examples, text, false, scheduled == null ? null : scheduled.value(), routingFailure, failure)));
        } catch (RuntimeException exception) {
            LocalOllamaClient.removeWireObserver(requestId);
            completeGeneration(player, session, turn, contents, plan, tags, lore, examples, text, false, null,
                    routingFailure, exception);
        }
    }

    /** One bounded LLM revision is preferable to replacing a contextual reply with a canned local sentence. */
    private void submitCasualRepair(ServerPlayer player, Session session, long turn,
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents, GenerationPlan plan, List<String> tags,
            List<AiTestContentRegistryBridge.Lore> lore, List<AiTestContentRegistryBridge.Example> examples,
            String text, Throwable routingFailure, String rejectedSpeech) {
        if (!isCurrent(player, session, turn)) {
            return;
        }
        logs.appendBlock(player.server, player.getUUID(), "CASUAL_RESPONSE_RETRY",
                "reason=abstract_mood_lecture_or_unsupported_joint_activity\nrejected=" + rejectedSpeech
                        + "\nllm_called=true");
        ConversationDynamics dynamics = ConversationDynamics.from(session, text, plan.intent());
        List<AiDialogueModels.OllamaMessage> messages = new ArrayList<>(generationMessages(player, session, text, contents,
                plan, tags, lore, dynamics));
        messages.add(new AiDialogueModels.OllamaMessage("user", """
                [REVISION_REQUEST]
                The previous draft broke the social-dialogue boundary. Write a fresh reply to CURRENT_PLAYER_MESSAGE using
                RECENT_CONVERSATION. Do not explain boredom as an abstract sign, meaning, lesson, opportunity, fate, or
                choice. Do not invite walking, travel, roaming, following, or any joint physical activity. Preserve the
                NPC's personality through the reaction itself, not by forcing its mythology into the reply.
                Return the required JSON object only.
                """));
        UUID requestId = UUID.randomUUID();
        LocalOllamaClient.installWireObserver(requestId, new LocalOllamaClient.WireObserver() {
            @Override
            public void onRequest(String json) {
                logs.appendBlock(player.server, player.getUUID(), "GENERATION_REPAIR_REQUEST_JSON", json);
            }

            @Override
            public void onResponse(String json) {
                logs.appendBlock(player.server, player.getUUID(), "GENERATION_REPAIR_RAW_LLM_RESPONSE", json);
            }

            @Override
            public void onFailure(Throwable failure) {
                logs.append(player.server, player.getUUID(), "GENERATION_REPAIR_TRANSPORT_FAILURE", message(failure));
            }
        });
        try {
            llm.submit(requestId, messages, AiDialogueConfig.INSTANCE.settings()).completion().whenComplete((scheduled, failure) ->
                    player.server.execute(() -> completeGeneration(player, session, turn, contents, plan, tags, lore, examples,
                            text, true, scheduled == null ? null : scheduled.value(), routingFailure, failure)));
        } catch (RuntimeException exception) {
            LocalOllamaClient.removeWireObserver(requestId);
            completeGeneration(player, session, turn, contents, plan, tags, lore, examples, text, true, null,
                    routingFailure, exception);
        }
    }

    private void completeDirectSocialResponse(ServerPlayer player, Session session, long turn,
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents, GenerationPlan plan) {
        if (!isCurrent(player, session, turn)) {
            return;
        }
        session.pending = false;
        ResourceLocation speaker = plan.speakers().getFirst();
        AiTestContentRegistryBridge.ContentSnapshot content = contents.get(speaker);
        String response = directSocialResponse(content.profile(), plan.directive(), session.turn);
        session.history.add(new Transcript("NPC", speaker.toString(), content.profile().displayName(), response));
        trimHistory(session);
        logs.appendBlock(player.server, player.getUUID(), "SOCIAL_RESPONSE_POLICY",
                "mode=" + plan.directive().mode() + "\nllm_called=false\nresponse=" + response);
        logs.append(player.server, player.getUUID(), "NPC", content.profile().displayName() + ": " + response);
        logs.appendBlock(player.server, player.getUUID(), "FINAL_SPEECH", content.profile().displayName() + ": " + response);
        logs.append(player.server, player.getUUID(), "VALIDATION_RESULT", "direct social response; no LLM call or proposal");
        showDialogue(player, speaker, content.profile().displayName(), response);
    }


    private void completeGeneration(ServerPlayer player, Session session, long turn,
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents, GenerationPlan plan, List<String> tags,
            List<AiTestContentRegistryBridge.Lore> lore, List<AiTestContentRegistryBridge.Example> examples,
            String text, boolean repairAttempted, AiDialogueModels.StructuredAiResult result, Throwable routingFailure,
            Throwable failure) {
        if (!isCurrent(player, session, turn)) {
            return;
        }
        if (failure != null || result == null) {
            finishFailure(player, session, turn, "응답 생성 실패 또는 시간 초과");
            return;
        }
        logs.appendBlock(player.server, player.getUUID(), "GENERATION_PARSED_RESPONSE", parsedResponse(result));
        List<AiDialogueModels.Speech> speeches = usableSpeeches(result, plan, session);
        if (speeches.isEmpty()) {
            speeches = List.of(new AiDialogueModels.Speech(plan.speakers().getFirst().toString(),
                    "지금은 대답을 정리하지 못했어. 한 번만 다시 말해 봐.", List.of("player")));
        }
        String invalidCasualSpeech = speeches.stream().map(AiDialogueModels.Speech::text)
                .filter(response -> requiresCasualRepair(plan.directive(), response)).findFirst().orElse("");
        if (!invalidCasualSpeech.isEmpty()) {
            if (!repairAttempted) {
                submitCasualRepair(player, session, turn, contents, plan, tags, lore, examples, text, routingFailure,
                        invalidCasualSpeech);
                return;
            }
            finishFailure(player, session, turn, "감정 대화 응답이 안전·대화 규칙을 반복해서 위반했습니다. 다시 말해 주세요.");
            return;
        }
        session.pending = false;
        boolean blockedClaim = false;
        for (AiDialogueModels.Speech speech : speeches) {
            ResourceLocation speaker = ResourceLocation.parse(speech.speakerId());
            AiTestContentRegistryBridge.ContentSnapshot content = contents.get(speaker);
            String response = bounded(speech.text(), AiDialogueConfig.INSTANCE.settings().maxResponseCharacters());
            if (claimsGameAction(response)) {
                blockedClaim = true;
                response = "나는 여기서 실제 보상이나 퀘스트, 관계, 소환을 실행할 수 없어. 말로만 답할 수 있지.";
            }
            session.history.add(new Transcript("NPC", speaker.toString(), content.profile().displayName(), response));
            logs.append(player.server, player.getUUID(), "NPC", content.profile().displayName() + ": " + response);
            logs.appendBlock(player.server, player.getUUID(), "FINAL_SPEECH", content.profile().displayName() + ": " + response);
            showDialogue(player, speaker, content.profile().displayName(), response);
        }
        trimHistory(session);
        List<AiDialogueModels.Proposal> proposals = plan.directive().proposalsAllowed() ? result.proposals() : List.of();
        logs.append(player.server, player.getUUID(), "VALIDATION_RESULT", blockedClaim
                ? "unsafe completed-game-action claim blocked"
                : proposals.size() == result.proposals().size() ? "speech accepted; proposals are display-only"
                : "speech accepted; proposals dropped by turn directive");
        for (AiDialogueModels.Proposal proposal : proposals) {
            showProposal(player, proposal);
        }
        if (session.debug) {
            MythicTrpg.LOGGER.info("[AI Test] player={} speaker={} tier={} emotion={} tags={} lore={} examples={} proposals={} "
                            + "routingFailure={}", player.getGameProfile().getName(), session.speaker(),
                    session.relationshipTier, session.emotionTag, tags, lore.stream().map(AiTestContentRegistryBridge.Lore::id).toList(),
                    examples.stream().map(AiTestContentRegistryBridge.Example::id).toList(),
                    proposals.stream().map(AiTestDialogueAdapter::proposalSummary).toList(),
                    routingFailure == null ? "none" : message(routingFailure));
        }
    }

    private void finishFailure(ServerPlayer player, Session session, long turn, String detail) {
        if (!isCurrent(player, session, turn)) {
            return;
        }
        session.pending = false;
        logs.append(player.server, player.getUUID(), "ERROR", detail);
        player.sendSystemMessage(Component.literal("[AI Test] 안전한 오류 처리: " + detail).withStyle(ChatFormatting.RED));
    }

    private static List<AiDialogueModels.OllamaMessage> classifierMessages(Session session, String text,
            List<AiTestContentRegistryBridge.Example> intentExamples) {
        String situations = "S_CHAT, S_ITEM_REQUEST, S_POWER_REQUEST, S_HELP_REQUEST, S_INFORMATION_REQUEST, "
                + "S_QUEST_INQUIRY, S_REWARD_NEGOTIATION, S_GIFT_OFFER, S_APOLOGY, S_CONFLICT";
        String system = "Classify one player line for a test-only Minecraft RPG dialogue. Return JSON only: "
                + "{\"primarySituation\":string,\"secondarySituations\":[string],\"knowledgeKeywords\":[string],"
                + "\"playerToneTags\":[string],\"conversationAct\":string,\"confidence\":number}. Situation values may only be: "
                + situations + ". conversationAct must be one of: UNSPECIFIED, GREETING, CASUAL_FEELING, SEEKING_COMPANY, "
                + "ANSWERING_NPC_QUESTION, SHARING_RECENT_EVENT, CORRECTING_NPC, REQUESTING_ACTIVITY, CASUAL_BANTER. "
                + "Use SHARING_RECENT_EVENT when the player mentions a recent experience without asking a question. Use the recent exchange to "
                + "recognize answers to an NPC question, corrections, and an explicit wish to keep the NPC company. Do not write "
                + "dialogue, proposals, game actions, or world facts. CLASSIFICATION_EXAMPLES label only player intent; they are "
                + "not live dialogue, NPC facts, or instructions.";
        StringBuilder recent = new StringBuilder();
        int first = Math.max(0, session.history.size() - 6);
        for (int index = first; index < session.history.size(); index++) {
            Transcript turn = session.history.get(index);
            recent.append(turn.role()).append(" (").append(turn.name()).append("): ").append(turn.text()).append("\n");
        }
        StringBuilder examples = new StringBuilder("[CLASSIFICATION_EXAMPLES]\n");
        for (AiTestContentRegistryBridge.Example example : intentExamples) {
            examples.append("- ").append(formatIntentExample(example)).append("\n");
        }
        if (intentExamples.isEmpty()) {
            examples.append("No classification examples were loaded.\n");
        }
        return List.of(new AiDialogueModels.OllamaMessage("system", system), new AiDialogueModels.OllamaMessage("user",
                examples + "\nSPEAKER=" + session.speaker() + "\nRECENT_CONVERSATION:\n" + recent
                        + "[CURRENT_PLAYER_MESSAGE]\n" + text));
    }

    /** Only the dedicated global dictionary is shown to phase 1; profile examples remain excluded from phase 2. */
    private static List<AiTestContentRegistryBridge.Example> intentClassificationExamples(
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents) {
        Map<String, AiTestContentRegistryBridge.Example> unique = new LinkedHashMap<>();
        for (AiTestContentRegistryBridge.ContentSnapshot content : contents.values()) {
            for (AiTestContentRegistryBridge.Example example : content.examples()) {
                if (example.id().startsWith("mythaiaicontent:intent_classifier/")) {
                    unique.putIfAbsent(example.id(), example);
                }
            }
        }
        return unique.values().stream().sorted(Comparator.comparing(AiTestContentRegistryBridge.Example::id))
                .limit(40).toList();
    }

    private static String formatIntentExample(AiTestContentRegistryBridge.Example example) {
        String playerText = example.dialogue().stream().filter(turn -> "player".equalsIgnoreCase(turn.role()))
                .map(AiTestContentRegistryBridge.ExampleTurn::text).findFirst().orElse("");
        String primary = example.tags().stream().filter(tag -> tag.startsWith("S_")).sorted().findFirst().orElse("S_CHAT");
        return "PLAYER=\"" + playerText.replace("\"", "'") + "\" => primarySituation=" + primary;
    }

    private static List<AiDialogueModels.OllamaMessage> generationMessages(ServerPlayer player, Session session, String text,
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents, GenerationPlan plan,
            List<String> tags, List<AiTestContentRegistryBridge.Lore> lore, ConversationDynamics dynamics) {
        String allowedSpeakers = plan.speakers().stream().map(ResourceLocation::toString)
                .reduce((first, second) -> first + ", " + second).orElse("");
        String system = """
                [ROLE]
                You generate natural Korean dialogue for a test-only Minecraft RPG conversation.

                [GLOBAL_HARD_RULES]
                Return only the required JSON object. Do not invent a place, item, person, event, action, or changing game state as
                a current fact. Never claim an item was given, a quest registered, a reward granted, a relationship changed, a mob
                summoned, a buff applied, or any game action completed. A proposal is advisory text only and must use future or
                conditional wording.
                Unless CURRENT_GAME_CONTEXT explicitly provides a joint-movement or companion interaction, never invite the
                player to walk, travel, roam, follow, or physically act together with the NPC.

                [RESPONSE_PRIORITY]
                1. CURRENT_PLAYER_MESSAGE
                2. RECENT_CONVERSATION
                3. NPC_IDENTITY_AND_PERSONALITY
                4. RELATIONSHIP
                5. CURRENT_GAME_CONTEXT
                6. RESPONSE_INTENT
                7. SELECTED_DIALOGUE_GUIDELINES
                8. KNOWN_LORE

                [NATURAL_DIALOGUE_PREFERENCES]
                Respond as a present conversation partner to the current player message. Answer directly before adding flavor.
                Do not add explanations, new topics, questions, analogies, lessons, jokes, or metaphors unless they genuinely help
                this reply. Prefer a short, complete conversational response when that is enough. Do not repeat the previous NPC
                meaning or force a profile motif or guideline into every answer. Natural conversation is more important than
                demonstrating every guideline. Treat guidelines as preferences, not mandatory content.

                [OUTPUT_RULES]
                Return JSON only with this schema: {"speech":[{"speakerId":string,"text":string,
                "audienceParticipantIds":["player"]}],"currentTopic":string,"proposals":[{"type":string,"title":string,
                "summary":string,"targetParticipantIds":[string],"parameters":{}}]}. Use only the speaker IDs listed in
                TURN_SPEAKERS. Obey TURN_DIRECTIVE exactly. In ordinary conversation, proposals must be []. Speech must sound
                like the NPC, never expose implementation terms, IDs, or proposal field names.
                """;
        StringBuilder context = new StringBuilder();
        context.append("[CURRENT_PLAYER_MESSAGE]\n").append(text).append("\n\n[RECENT_CONVERSATION]\n");
        for (int index = 0; index < Math.max(0, session.history.size() - 1); index++) {
            Transcript turn = session.history.get(index);
            context.append(turn.role()).append(" (").append(turn.name()).append("): ").append(turn.text()).append("\n");
        }
        if (session.history.size() <= 1) {
            context.append("No earlier live conversation.\n");
        }
        context.append("\n");
        for (ResourceLocation godId : session.participants) {
            AiTestContentRegistryBridge.ContentSnapshot content = contents.get(godId);
            if (plan.directive().isSocialOnly()) {
                context.append("[NPC_IDENTITY_AND_PERSONALITY: ").append(godId).append("]\nname: ")
                        .append(content.profile().displayName()).append("\nrelationship tier: ")
                        .append(session.relationshipTier).append("\nidentity: ").append(content.profile().identity())
                        .append("\ndescription: ").append(content.profile().description())
                        .append("\npersonality: ").append(content.profile().personality())
                        .append("\ncurrent emotion: ").append(session.emotionTag).append("\n");
                context.append("[RELATIONSHIP_DIALOGUE_GUIDELINES: ").append(godId).append("]\n");
                for (String guideline : content.relationshipGuidance()) {
                    context.append("- ").append(guideline).append("\n");
                }
                context.append("[SELECTED_DIALOGUE_GUIDELINES: ").append(godId).append("]\n");
                for (String guideline : dialogueGuidelines(content.profile(), plan.intent(), dynamics)) {
                    context.append("- ").append(guideline).append("\n");
                }
                context.append("Do not explain the player's mood or introduce the NPC's mythology as a topic.\n\n");
                continue;
            }
            context.append("[NPC_IDENTITY_AND_PERSONALITY: ").append(godId).append("]\nname: ")
                    .append(content.profile().displayName()).append("\nidentity: ")
                    .append(content.profile().identity()).append("\ndescription: ")
                    .append(content.profile().description()).append("\npersonality: ").append(content.profile().personality())
                    .append("\nvalues: ").append(content.profile().values()).append("\ncharacterTags: ")
                    .append(content.profile().characterTags()).append("\nrestrictions: ")
                    .append(content.profile().restrictions()).append("\n\n[RELATIONSHIP: ").append(godId)
                    .append("]\ntier: ").append(session.relationshipTier).append("\nemotion: ")
                    .append(session.emotionTag).append("\nrelationship dialogue guidelines:\n");
            for (String guideline : content.relationshipGuidance()) {
                context.append("- ").append(guideline).append("\n");
            }
            context.append("[SELECTED_DIALOGUE_GUIDELINES: ").append(godId)
                    .append("]\n");
            for (String guideline : dialogueGuidelines(content.profile(), plan.intent(), dynamics)) {
                context.append("- ").append(guideline).append("\n");
            }
            context.append("[NPC_SOCIAL_RELATIONS: ").append(godId).append("]\n")
                    .append(content.socialRelationTags())
                    .append("\nUse these only to color a real exchange between present NPCs. Do not force rivalry, family, "
                            + "or any relationship topic into an unrelated player message.\n\n");
        }
        context.append("\n[CURRENT_GAME_CONTEXT]\nTEST_ONLY=true\nplayer: ").append(player.getGameProfile().getName())
                .append("\nNPC participants: ").append(joinIds(session.participants))
                .append("\nNo other game fact, location, item, quest, reward, or world event was supplied.\n")
                .append("\n[RESPONSE_INTENT]\ntags: ").append(tags).append("\nconversation_act: ")
                .append(plan.intent().conversationAct()).append("\nreply_guidance: ")
                .append(plan.intent().conversationAct().replyGuidance()).append("\n")
                .append("\n");
        if (dynamics.isPresent()) {
            context.append(dynamics.promptBlock()).append("\n");
        }
        context.append("[KNOWN_LORE: ").append(plan.speakers().getFirst()).append("]\n");
        for (AiTestContentRegistryBridge.Lore entry : lore) {
            context.append("- ").append(entry.id()).append(" (level ").append(entry.knowledgeLevel()).append(") ")
                    .append(entry.title()).append(": ").append(entry.accessibleLevels()).append("\n");
        }
        if (lore.isEmpty()) {
            context.append("- No relevant lore supplied.\n");
        }
        context.append("\n[TURN_SPEAKERS]\nOnly these NPCs may speak this turn: ").append(allowedSpeakers)
                .append(". Prefer one reply unless the player explicitly addressed multiple NPCs.\n\n")
                .append(plan.directive().promptBlock()).append("\n");
        context.append("\n[OUTPUT_RULES]\nReturn the JSON object only.");
        return List.of(new AiDialogueModels.OllamaMessage("system", system),
                new AiDialogueModels.OllamaMessage("user", bounded(context.toString(), 12_000)));
    }

    private static List<String> promptTags(Session session, AiTestContentRegistryBridge.ContentSnapshot content,
            ConversationIntent intent) {
        LinkedHashSet<String> tags = new LinkedHashSet<>(content.profile().speechStyles());
        tags.add(session.relationshipTier);
        tags.add(session.emotionTag);
        tags.addAll(content.socialRelationTags());
        intent.tags().forEach(tag -> tags.add(tag.name()));
        intent.playerToneTags().forEach(tag -> tags.add(tag.name()));
        tags.add("A_" + intent.conversationAct());
        if (session.participants.size() > 1) {
            tags.add("C_MULTIPLE_GODS");
        } else {
            tags.add("C_ONE_TO_ONE");
        }
        return List.copyOf(tags);
    }

    private static List<AiTestContentRegistryBridge.Lore> selectedLore(
            AiTestContentRegistryBridge.ContentSnapshot content, ConversationIntent intent) {
        if (intent.knowledgeKeywords().isEmpty()) {
            return List.of();
        }
        List<AiTestContentRegistryBridge.Lore> candidates = new ArrayList<>(content.lore());
        candidates.sort(Comparator.comparingInt((AiTestContentRegistryBridge.Lore lore) -> relevance(lore, intent)).reversed()
                .thenComparing(AiTestContentRegistryBridge.Lore::id));
        int limit = Math.max(1, AiDialogueConfig.INSTANCE.settings().maxRetrievedKnowledge());
        return candidates.stream().filter(lore -> relevance(lore, intent) > 0).limit(limit).toList();
    }

    private static int relevance(AiTestContentRegistryBridge.Lore lore, ConversationIntent intent) {
        String haystack = (lore.title() + " " + lore.accessibleLevels()).toLowerCase(Locale.ROOT);
        return (int) intent.knowledgeKeywords().stream().filter(keyword -> haystack.contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
    }

    private static List<AiTestContentRegistryBridge.Example> selectedExamples(
            AiTestContentRegistryBridge.ContentSnapshot content, List<String> tags, DialogueTurnDirective directive) {
        if (directive.mode() == DialogueTurnDirective.Mode.GREETING
                || directive.mode() == DialogueTurnDirective.Mode.REPEATED_GREETING
                || directive.isSocialOnly()) {
            return List.of();
        }
        Set<String> wanted = Set.copyOf(tags);
        List<AiTestContentRegistryBridge.Example> candidates = new ArrayList<>(content.examples());
        candidates.sort(Comparator.comparingInt((AiTestContentRegistryBridge.Example example) -> exampleScore(example, wanted))
                .reversed().thenComparing(AiTestContentRegistryBridge.Example::id));
        int limit = Math.min(2, AiDialogueConfig.INSTANCE.settings().exampleRetrievalLimit());
        return candidates.stream().filter(example -> compatibleExample(example, wanted, directive))
                .limit(limit).toList();
    }

    private static int exampleScore(AiTestContentRegistryBridge.Example example, Set<String> wanted) {
        return (int) example.tags().stream().filter(wanted::contains).count();
    }

    private static String latestNpcSpeech(Session session) {
        for (int index = session.history.size() - 1; index >= 0; index--) {
            Transcript turn = session.history.get(index);
            if ("NPC".equals(turn.role())) {
                return turn.text();
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean compatibleExample(AiTestContentRegistryBridge.Example example, Set<String> wanted,
            DialogueTurnDirective directive) {
        boolean hasSituation = false;
        boolean matchedSituation = false;
        for (String tag : example.tags()) {
            if (tag.startsWith("R_") || tag.startsWith("E_") || tag.startsWith("RT_")) {
                if (!wanted.contains(tag)) {
                    return false;
                }
            }
            if (tag.startsWith("S_")) {
                hasSituation = true;
                matchedSituation |= wanted.contains(tag);
            }
        }
        if (directive.mode() == DialogueTurnDirective.Mode.SMALL_TALK && !matchedSituation) {
            return false;
        }
        return (!hasSituation || matchedSituation) && exampleScore(example, wanted) > 0;
    }

    private static List<String> dialogueGuidelines(AiTestContentRegistryBridge.Profile profile,
            ConversationIntent intent, ConversationDynamics dynamics) {
        List<String> guidelines = new ArrayList<>();
        guidelines.add("Prefer the NPC profile's vocabulary, length, humor, intimacy, and information disclosure when it fits the current exchange.");
        guidelines.add("Prefer a direct response to the current player line; do not force a question, metaphor, or theme into every reply.");
        guidelines.addAll(profile.dialogueGuidelines());
        if (intent != null) {
            intent.tags().stream().filter(tag -> tag.name().startsWith("S_"))
                    .map(DialogueExampleTag::name).sorted().limit(2)
                    .forEach(tag -> guidelines.addAll(profile.situationGuidelines().getOrDefault(tag, List.of())));
        }
        if (dynamics.isRepeatedCasualComplaint()) {
            guidelines.addAll(profile.repetitionGuidelines().getOrDefault("CASUAL_COMPLAINT", List.of()));
        }
        for (String style : profile.speechStyles()) {
            switch (style) {
                case "P_SHORT" -> guidelines.add("Prefer a concise answer unless the current question needs more detail.");
                case "P_CALM" -> guidelines.add("Keep the delivery composed; reduce drama when the player is casual.");
                case "P_MYSTERIOUS" -> guidelines.add("Use selective, indirect phrasing only when relevant; do not invent omens, places, or hidden facts.");
                case "P_PLAYFUL" -> guidelines.add("Light teasing is optional and must still answer the player directly.");
                case "P_FORMAL" -> guidelines.add("Maintain the profile's formal register without becoming mechanically stiff.");
                default -> guidelines.add("Use speech style tag " + style + " as an optional manner cue, never as a required topic.");
            }
        }
        return List.copyOf(guidelines);
    }

    private void logGenerationInput(ServerPlayer player, AiTestContentRegistryBridge.ContentSnapshot content, Session session,
            GenerationPlan plan, List<String> tags, List<AiTestContentRegistryBridge.Lore> lore,
            List<AiTestContentRegistryBridge.Example> examples, ConversationDynamics dynamics,
            List<AiDialogueModels.OllamaMessage> messages) {
        AiDialogueConfig.Settings settings = AiDialogueConfig.INSTANCE.settings();
        logs.appendBlock(player.server, player.getUUID(), "GENERATION_SYSTEM_PROMPT", messages.getFirst().content());
        logs.appendBlock(player.server, player.getUUID(), "GENERATION_USER_PROMPT", messages.get(1).content());
        logs.appendBlock(player.server, player.getUUID(), "GENERATION_SETTINGS", "model=" + settings.ollamaModel()
                + "\ntemperature=0.60\ntop_p=Ollama default (not sent)\ntop_k=Ollama default (not sent)"
                + "\nmax_tokens=" + settings.maxOutputTokens() + "\ntimeout_seconds=" + settings.requestTimeoutSeconds());
        logs.appendBlock(player.server, player.getUUID(), "PROMPT_SELECTION", "dialogueGuidelines="
                + dialogueGuidelines(content.profile(), plan.intent(), dynamics) + "\nconversationDynamics=" + dynamics
                + "\nturnDirective=" + plan.directive().promptBlock()
                + "\nconversationAct=" + plan.intent().conversationAct()
                + "\nturnSpeakers=" + plan.speakers() + "\nrelationship=" + session.relationshipTier + "\nemotion="
                + session.emotionTag + "\ntags=" + tags + "\nlore="
                + lore.stream().map(AiTestContentRegistryBridge.Lore::id).toList()
                + "\ngenerationReferenceExamples=DISABLED (phase-2 prompt never receives example dialogue)"
                + "\nlegacyExampleSelection=" + examples.stream().map(AiTestContentRegistryBridge.Example::id).toList());
    }

    private static String parsedResponse(AiDialogueModels.StructuredAiResult result) {
        return "speech=" + result.speech() + "\ncurrentTopic=" + result.currentTopic() + "\nproposals=" + result.proposals();
    }

    private static ConversationIntent resolveConversationIntent(ConversationIntent classified, String text, Session session) {
        ConversationIntent safe = classified == null ? ConversationIntent.heuristicFallback() : classified;
        if (hasEarlierMatchingCasualFeeling(session, text)) {
            // The same mood statement after an NPC question is usually persistence, not an answer to that question.
            // Keep the LLM generation path, but give it a correct, stable reading of the ongoing conversation.
            return new ConversationIntent(Set.of(DialogueExampleTag.S_CHAT), Set.of(), safe.playerToneTags(),
                    ConversationAct.CASUAL_FEELING, safe.confidence(), safe.source());
        }
        ConversationAct act = ConversationAct.resolve(safe.conversationAct(), text, latestNpcSpeech(session));
        if (act == ConversationAct.SHARING_RECENT_EVENT) {
            // A declarative event report is conversational context, not an information request or a lore lookup.
            return new ConversationIntent(Set.of(DialogueExampleTag.S_CHAT), Set.of(), safe.playerToneTags(), act,
                    safe.confidence(), safe.source());
        }
        return safe.withConversationAct(act);
    }

    /** Clear, low-risk mood statements do not need a separate LLM classification call. */
    private static ConversationIntent simpleCasualFeelingIntent(String text) {
        String normalized = normalize(text);
        if (!normalized.matches("^(나 )?(심심해|심심해요|심심하다|지루해|지루해요|피곤해|피곤해요|졸려|졸려요|외로워|외로워요)[!?.~]*$")) {
            return null;
        }
        return new ConversationIntent(Set.of(DialogueExampleTag.S_CHAT), Set.of(),
                PlayerSpeechToneHeuristics.classify(text), ConversationAct.CASUAL_FEELING, 100,
                ConversationIntent.Source.HEURISTIC);
    }

    private Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> loadContents(List<ResourceLocation> participants,
            String relationshipTier) {
        Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents = new LinkedHashMap<>();
        for (ResourceLocation godId : participants) {
            contents.put(godId, contentRegistry.load(godId, relationshipTier, participants));
        }
        return Map.copyOf(contents);
    }

    private static GenerationPlan generationPlan(Session session, String text, ConversationIntent intent,
            Map<ResourceLocation, AiTestContentRegistryBridge.ContentSnapshot> contents) {
        DialogueTurnDirective directive = DialogueTurnDirective.plan(text,
                session.history.stream().map(Transcript::text).toList(), session.participants.size(), intent);
        List<ResourceLocation> mentioned = new ArrayList<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        for (ResourceLocation godId : session.participants) {
            String name = contents.get(godId).profile().displayName().toLowerCase(Locale.ROOT);
            if (!name.isBlank() && normalized.contains(name)) {
                mentioned.add(godId);
            }
        }
        boolean groupAddress = session.participants.size() > 1
                && (normalized.contains("둘") || normalized.contains("너희") || normalized.contains("모두"));
        if (mentioned.isEmpty() && groupAddress) {
            mentioned.addAll(session.participants);
        }
        if (mentioned.isEmpty()) {
            ResourceLocation previous = session.history.stream().filter(turn -> "NPC".equals(turn.role()))
                    .map(Transcript::speakerId).filter(id -> !id.isBlank()).map(ResourceLocation::parse).reduce((first, second) -> second)
                    .orElse(null);
            for (ResourceLocation candidate : session.participants) {
                if (!candidate.equals(previous)) {
                    mentioned.add(candidate);
                    break;
                }
            }
        }
        if (mentioned.isEmpty()) {
            mentioned.add(session.speaker());
        }
        return new GenerationPlan(directive, intent, List.copyOf(mentioned.subList(0,
                Math.min(directive.maximumNpcReplies(), mentioned.size()))));
    }

    private static List<AiDialogueModels.Speech> usableSpeeches(AiDialogueModels.StructuredAiResult result,
            GenerationPlan plan, Session session) {
        List<AiDialogueModels.Speech> accepted = new ArrayList<>();
        Set<String> allowed = plan.speakers().stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toSet());
        for (AiDialogueModels.Speech speech : result.speech()) {
            if (speech == null || speech.text().isBlank()) {
                continue;
            }
            String speakerId = speech.speakerId().isBlank() ? plan.speakers().getFirst().toString() : speech.speakerId();
            if (!allowed.contains(speakerId) || accepted.stream().anyMatch(existing -> speakerId.equals(existing.speakerId()))) {
                continue;
              }
            String response = plan.directive().compactSpeech(
                    bounded(speech.text(), AiDialogueConfig.INSTANCE.settings().maxResponseCharacters()));
            if (plan.directive().requiresFreshReply() && response.equals(normalize(latestNpcSpeech(session)))) {
                response = plan.directive().repeatedGreetingFallback();
            }
            accepted.add(new AiDialogueModels.Speech(speakerId, response, List.of("player")));
            if (accepted.size() == plan.directive().maximumNpcReplies()) {
                break;
            }
        }
        return List.copyOf(accepted);
    }

    private static boolean isDirectSocialTurn(DialogueTurnDirective directive) {
        return directive.mode() == DialogueTurnDirective.Mode.GREETING
                || directive.mode() == DialogueTurnDirective.Mode.REPEATED_GREETING;
    }

    private static boolean hasEarlierMatchingCasualFeeling(Session session, String text) {
        String need = casualFeelingNeed(text);
        if (need.isBlank()) {
            return false;
        }
        // The current player turn is already the last entry when this method is called.
        for (int index = 0; index < Math.max(0, session.history.size() - 1); index++) {
            Transcript turn = session.history.get(index);
            if ("PLAYER".equals(turn.role()) && need.equals(casualFeelingNeed(turn.text))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCasualFeelingText(String text) {
        return !casualFeelingNeed(text).isBlank();
    }

    /**
     * Conversation dynamics follows the social meaning, not a byte-for-byte player sentence.  For example,
     * "심심해" and "심심하다고" are both one unresolved BOREDOM beat.
     */
    private static String casualFeelingNeed(String text) {
        String normalized = normalize(text);
        if (normalized.contains("심심") || normalized.contains("지루")) {
            return "BOREDOM";
        }
        if (normalized.contains("피곤") || normalized.contains("졸려")) {
            return "FATIGUE";
        }
        if (normalized.contains("배고파") || normalized.contains("배고프")) {
            return "HUNGER";
        }
        if (normalized.contains("외로")) {
            return "LONELINESS";
        }
        if (normalized.contains("기분이 별로") || normalized.contains("기분 별로")) {
            return "LOW_MOOD";
        }
        return "";
    }

    private static String socialNormalize(String value) {
        return normalize(value).replaceAll("[!?.~]+$", "");
    }

    private static boolean requiresCasualRepair(DialogueTurnDirective directive, String response) {
        if (directive.mode() != DialogueTurnDirective.Mode.CASUAL_FEELING) {
            return false;
        }
        String normalized = normalize(response);
        boolean abstractMoodLecture = normalized.contains("심심함은") || normalized.contains("심심하다는 건")
                || normalized.contains("심심한 건") || normalized.contains("지루함은")
                || normalized.contains("지루하다는 건") || normalized.contains("지루한 건")
                || (normalized.contains("지루한 일")
                        && (normalized.contains("얼마나") || normalized.contains("것은") || normalized.contains("라는")))
                || (normalized.contains("심심")
                        && (normalized.contains("신호") || normalized.contains("의미") || normalized.contains("기회")));
        boolean unsupportedJointActivity = normalized.contains("같이 길") || normalized.contains("함께 길")
                || normalized.contains("같이 떠돌") || normalized.contains("함께 떠돌")
                || normalized.contains("나를 따라") || normalized.contains("내가 따라");
        return abstractMoodLecture || unsupportedJointActivity;
    }

    private static String directSocialResponse(AiTestContentRegistryBridge.Profile profile,
            DialogueTurnDirective directive, long turn) {
        boolean repeated = directive.mode() == DialogueTurnDirective.Mode.REPEATED_GREETING;
        Set<String> styles = Set.copyOf(profile.speechStyles());
        if (styles.contains("P_FORMAL")) {
            return repeated ? "다시 인사하는군요." : "안녕하십니까.";
        }
        if (styles.contains("P_GRUFF")) {
            return repeated ? "또 인사냐." : "그래.";
        }
        if (styles.contains("P_PLAYFUL")) {
            return repeated ? "인사가 두 번째네." : "안녕.";
        }
        List<String> responses = repeated
                ? List.of("또 인사하네.", "응, 들었어.")
                : List.of("안녕.", "왔네.");
        return responses.get((int) Math.floorMod(turn - 1, responses.size()));
    }

    private static boolean claimsGameAction(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return ACTION_CLAIM_MARKERS.stream().anyMatch(marker -> normalized.contains(marker.toLowerCase(Locale.ROOT)));
    }

    private static void showDialogue(ServerPlayer player, ResourceLocation godId, String displayName, String text) {
        if (DialoguePresentationService.INSTANCE.sendTo(player, GodDialogueRequest.literal(godId, text)).status()
                != DialogueSendStatus.SENT) {
            player.sendSystemMessage(Component.literal("[AI Test][" + displayName + "] " + text)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    private static void showProposal(ServerPlayer player, AiDialogueModels.Proposal proposal) {
        if (proposal == null || proposal.title().isBlank() || proposal.summary().isBlank()) {
            return;
        }
        player.sendSystemMessage(Component.literal("[AI Test][Proposal only] " + bounded(proposal.title(), 120) + ": "
                + bounded(proposal.summary(), 300)).withStyle(ChatFormatting.YELLOW));
    }

    private static String proposalSummary(AiDialogueModels.Proposal proposal) {
        return proposal == null ? "invalid" : bounded(proposal.type() + ":" + proposal.title(), 160);
    }

    private boolean isCurrent(ServerPlayer player, Session session, long turn) {
        return sessions.get(player.getUUID()) == session && session.turn == turn;
    }

    private static void trimHistory(Session session) {
        int maximum = AiDialogueConfig.INSTANCE.settings().transcriptMessages();
        while (session.history.size() > maximum) {
            session.history.removeFirst();
        }
    }

    private static String normalizeTier(String tier) {
        String normalized = tier == null ? "" : tier.trim().toUpperCase(Locale.ROOT);
        return RELATIONSHIP_TIERS.contains(normalized) ? normalized : null;
    }

    private static String normalizeEmotion(String tag) {
        String normalized = tag == null ? "" : tag.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("E_[A-Z0-9_]{1,48}") ? normalized : null;
    }

    private static String bounded(String text, int maximum) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String message(Throwable failure) {
        String value = failure == null ? "unknown failure" : failure.getMessage();
        return bounded(value == null || value.isBlank() ? failure.getClass().getSimpleName() : value, 180);
    }

    private static String joinIds(List<ResourceLocation> values) {
        return values.stream().map(ResourceLocation::toString).reduce((first, second) -> first + ", " + second).orElse("");
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        Session removed = sessions.remove(player.getUUID());
        if (removed != null) {
            logs.append(player.server, player.getUUID(), "SYSTEM", "Player logged out; test session discarded");
        }
    }

    public void stop() {
        sessions.clear();
        llm.close();
        logs.close();
    }

    public enum StartResult {
        STARTED,
        NORMAL_AI_SESSION_ACTIVE,
        CONTENT_FAILURE;

        static StartResult contentFailure(String ignoredDetail) {
            return CONTENT_FAILURE;
        }
    }

    private static final class Session {
        private final UUID playerId;
        private List<ResourceLocation> participants;
        private String relationshipTier;
        private String emotionTag;
        private AiTestContentRegistryBridge.Profile profile;
        private final List<Transcript> history = new ArrayList<>();
        private long turn;
        private boolean pending;
        private boolean debug;

        private Session(UUID playerId, List<ResourceLocation> participants, String relationshipTier, String emotionTag,
                AiTestContentRegistryBridge.Profile profile) {
            this.playerId = playerId;
            this.participants = List.copyOf(participants);
            this.relationshipTier = relationshipTier;
            this.emotionTag = emotionTag;
            this.profile = profile;
        }

        private ResourceLocation speaker() {
            return participants.getFirst();
        }
    }

    /**
     * Turn-local reading of the visible transcript. It is neither persistent emotion nor authoritative relationship
     * state; it only makes an unresolved conversational pattern explicit to the generator.
     */
    private record ConversationDynamics(int samePlayerExpressionCount, int npcRepliesSinceFirstExpression,
            String unresolvedSocialNeed) {
        private static ConversationDynamics from(Session session, String playerText, ConversationIntent intent) {
            boolean casualFeeling = isCasualFeelingText(playerText)
                    || intent != null && intent.conversationAct() == ConversationAct.CASUAL_FEELING;
            if (!casualFeeling) {
                return new ConversationDynamics(0, 0, "");
            }
            String need = casualFeelingNeed(playerText);
            if (need.isBlank()) {
                need = "CASUAL_FEELING";
            }
            int matchingPlayerTurns = 0;
            int npcReplies = 0;
            boolean expressionHasAppeared = false;
            for (Transcript turn : session.history) {
                if ("PLAYER".equals(turn.role()) && need.equals(casualFeelingNeed(turn.text))) {
                    matchingPlayerTurns++;
                    expressionHasAppeared = true;
                } else if (expressionHasAppeared && "NPC".equals(turn.role())) {
                    npcReplies++;
                }
            }
            return new ConversationDynamics(matchingPlayerTurns, npcReplies, need);
        }

        private boolean isPresent() {
            return !unresolvedSocialNeed.isBlank();
        }

        private boolean isRepeatedCasualComplaint() {
            return "BOREDOM".equals(unresolvedSocialNeed) && samePlayerExpressionCount > 1
                    && npcRepliesSinceFirstExpression > 0;
        }

        private String promptBlock() {
            if (!isPresent()) {
                return "";
            }
            StringBuilder block = new StringBuilder("[CONVERSATION_DYNAMICS]\nunresolved_social_need: ")
                    .append(unresolvedSocialNeed).append("\nsame_player_expression_count: ")
                    .append(samePlayerExpressionCount).append("\nnpc_replies_since_first_expression: ")
                    .append(npcRepliesSinceFirstExpression).append("\n");
            if (isRepeatedCasualComplaint()) {
                block.append("previous_approach_accepted: false\n")
                        .append("interpretation: The player repeated the same underlying social need after prior NPC "
                                + "replies, even if the wording changed. Read the visible transcript, react to that unresolved "
                                + "pattern, and do not repeat those earlier approaches.\n");
            }
            return block.toString();
        }
    }

    private record GenerationPlan(DialogueTurnDirective directive, ConversationIntent intent,
            List<ResourceLocation> speakers) {
        private GenerationPlan {
            intent = intent == null ? ConversationIntent.heuristicFallback() : intent;
            speakers = List.copyOf(speakers);
            if (speakers.isEmpty()) {
                throw new IllegalArgumentException("A generation plan requires at least one NPC speaker");
            }
        }
    }

    private record Transcript(String role, String speakerId, String name, String text) {
    }
}
