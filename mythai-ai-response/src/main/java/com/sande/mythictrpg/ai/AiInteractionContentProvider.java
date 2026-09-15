package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions;
import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.content.ContentPreparationRequest;
import com.sande.mythictrpg.interaction.content.PreparationResult;
import com.sande.mythictrpg.interaction.content.PreparedDialogueTurn;
import com.sande.mythictrpg.interaction.content.PreparedInteractionContent;
import com.sande.mythictrpg.interaction.spontaneous.ContentPreparerResolution;
import com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolver;
import com.sande.mythictrpg.quest.QuestReminderPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Production content provider for server-approved spontaneous interaction plans. */
public final class AiInteractionContentProvider implements InteractionContentPreparerResolver {
    public static final AiInteractionContentProvider INSTANCE = new AiInteractionContentProvider();

    private static final ResourceLocation PROVIDER_UNAVAILABLE = id("provider_unavailable");
    private static final ResourceLocation CONTENT_MISSING = id("content_missing");
    private static final ResourceLocation GENERATION_FAILED = id("generation_failed");
    private static final ResourceLocation EMPTY_RESPONSE = id("empty_response");

    private final AiTestContentRegistryBridge contentRegistry = new AiTestContentRegistryBridge();
    private final LocalLlmClient llm = new LocalOllamaClient();

    private AiInteractionContentProvider() {
    }

    @Override
    public ContentPreparerResolution resolve(InteractionSignal<?> signal) {
        if (!AiTestDialogueAdapter.enabled() || ServerLifecycleHooks.getCurrentServer() == null) {
            return ContentPreparerResolution.unavailable();
        }
        return ContentPreparerResolution.available(this::prepare);
    }

    private CompletionStage<PreparationResult> prepare(ContentPreparationRequest request) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return CompletableFuture.completedFuture(PreparationResult.failed(PROVIDER_UNAVAILABLE));
        }
        ServerPlayer player = server.getPlayerList().getPlayer(request.signal().initiatingPlayerId());
        if (player == null) {
            return CompletableFuture.completedFuture(PreparationResult.failed(PROVIDER_UNAVAILABLE));
        }

        ResourceLocation primaryGod = request.plan().participants().primaryGodId();
        List<ResourceLocation> participants = new ArrayList<>();
        participants.add(primaryGod);
        participants.addAll(request.plan().participants().secondaryGodIds());
        AiTestContentRegistryBridge.ContentSnapshot content;
        try {
            content = contentRegistry.load(primaryGod, "R_NEUTRAL", participants);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(PreparationResult.failed(CONTENT_MISSING));
        }

        UUID requestId = UUID.randomUUID();
        var memory = com.sande.mythictrpg.ai.memorycontract.PreparedMemoryContext.forPlan(server, request.plan(), requestId)
                .map(context -> com.sande.mythai.response.memory.DialogueMemoryBridge.prepare(server, context,
                        gameplayEventContext(request, primaryGod))).orElse(com.sande.mythai.response.memory.DialogueMemoryBridge.EMPTY);
        List<AiDialogueModels.OllamaMessage> messages = messages(
                request, player.getGameProfile().getName(), primaryGod, content.profile());
        if (!memory.prompt().isEmpty()) {
            var last = messages.getLast();
            messages = List.of(messages.getFirst(), new AiDialogueModels.OllamaMessage("user", last.content() + memory.prompt()));
        }
        try {
            CompletableFuture<PreparationResult> prepared = new CompletableFuture<>();
            llm.submit(requestId, messages, AiDialogueConfig.INSTANCE.settings()).completion()
                    .whenComplete((scheduled, failure) -> server.execute(() -> {
                        if (failure != null || scheduled == null || scheduled.value() == null
                                || server.getPlayerList().getPlayer(player.getUUID()) != player
                                || !com.sande.mythai.response.memory.DialogueMemoryBridge.preparedCurrent(server, memory)) {
                            prepared.complete(PreparationResult.failed(GENERATION_FAILED));
                            return;
                        }
                        AiDialogueModels.StructuredAiResult result = scheduled.value();
                        PreparationResult validated = toPreparationResult(result, primaryGod,
                                com.sande.mythai.response.memory.DialogueMemoryBridge.preparedSpeakers(
                                        primaryGod, Set.copyOf(participants), memory));
                        if (validated.status() == PreparationResult.Status.PREPARED) {
                            result.speech().stream()
                                    .filter(speech -> primaryGod.toString().equals(speech.speakerId()))
                                    .forEach(speech -> SpokenIdentityReveal.commitIfNameWasSpoken(
                                            player, primaryGod, content.profile().displayName(), speech.text()));
                        }
                        prepared.complete(validated);
                    }));
            return prepared;
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(PreparationResult.failed(GENERATION_FAILED));
        }
    }

    private static List<AiDialogueModels.OllamaMessage> messages(ContentPreparationRequest request,
            String playerName, ResourceLocation godId, AiTestContentRegistryBridge.Profile profile) {
        String system = """
                You generate natural Korean dialogue for a Minecraft RPG God NPC.
                This is a server-approved gameplay reaction, not a request to change game state.
                Return one JSON object only with this exact shape:
                {"speech":[{"speakerId":"namespace:id","text":"Korean dialogue","audienceParticipantIds":["player"]}],"currentTopic":"short topic","proposals":[]}
                Use only the supplied speaker ID. Produce one or two concise sentences. React to the event itself.
                Never claim that an item, quest, reward, blessing, relationship change, summon, or world mutation was executed.
                Do not invent coordinates, NPC inventories, hidden events, or facts that were not supplied.
                """;
        String user = "[SPEAKER_ID]\n" + godId
                + "\n[PLAYER]\n" + bounded(playerName, 64)
                + "\n[IDENTITY]\n" + bounded(profile.identity(), 1600)
                + "\n[DESCRIPTION]\n" + bounded(profile.description(), 1800)
                + "\n[PERSONALITY]\n" + String.join("; ", profile.personality())
                + "\n[VALUES]\n" + String.join("; ", profile.values())
                + "\n[SPEECH_STYLE]\n" + String.join("; ", profile.speechStyles())
                + "\n[DIALOGUE_GUIDELINES]\n" + String.join("; ", profile.dialogueGuidelines())
                + "\n[RESTRICTIONS]\n" + String.join("; ", profile.restrictions())
                + gameplayEventContext(request, godId)
                + "\nRespond as the God now.";
        return List.of(new AiDialogueModels.OllamaMessage("system", system),
                new AiDialogueModels.OllamaMessage("user", user));
    }

    private static String gameplayEventContext(ContentPreparationRequest request, ResourceLocation godId) {
        if (request.signal().payload() instanceof QuestReminderPayload reminder) {
            String quest = AiQuestContentBridge.completionCandidate(godId, reminder.questId())
                    .map(candidate -> candidate.title() + ": " + candidate.content())
                    .orElse(reminder.questId().toString());
            long unrelatedSeconds = reminder.unrelatedActivityTicks() / 20L;
            long sinceRelevantSeconds = reminder.ticksSinceRelevantAction() / 20L;
            return "\n[QUEST_REMINDER_EVENT]"
                    + "\nquest=" + bounded(quest, 1800)
                    + "\nseconds_since_relevant_action=" + sinceRelevantSeconds
                    + "\nseconds_spent_on_unrelated_observed_actions=" + unrelatedSeconds
                    + "\nunrelated_action_count=" + reminder.unrelatedActionCount()
                    + "\nprevious_reminder_count=" + reminder.previousReminderCount()
                    + "\nThe quest is still active and its objectives are not complete. Remind or prod the player "
                    + "in the NPC's own voice. You may become more direct after repeated reminders, but do not invent "
                    + "what unrelated activities the player performed, do not claim failure, and do not change the quest.";
        }
        return "\n[GAMEPLAY_EVENT]\nsignal=" + request.signal().type().id()
                + "\npayload=" + bounded(String.valueOf(request.signal().payload()), 1200);
    }

    private static PreparationResult toPreparationResult(AiDialogueModels.StructuredAiResult result,
            ResourceLocation primaryGod, Set<ResourceLocation> allowedGods) {
        List<PreparedDialogueTurn> turns = new ArrayList<>();
        for (AiDialogueModels.Speech speech : result.speech()) {
            ResourceLocation speaker;
            try {
                speaker = ResourceLocation.parse(speech.speakerId());
            } catch (RuntimeException exception) {
                continue;
            }
            String text = bounded(speech.text(), 1024);
            if (!allowedGods.contains(speaker) || text.isBlank()) {
                continue;
            }
            turns.add(new PreparedDialogueTurn(speaker, Component.literal(text),
                    DialoguePriority.NORMAL, DialogueDisplayOptions.defaults()));
            if (turns.size() >= 4) {
                break;
            }
        }
        if (turns.stream().noneMatch(turn -> turn.speakerGodId().equals(primaryGod))) {
            return PreparationResult.failed(EMPTY_RESPONSE);
        }
        return PreparationResult.prepared(new PreparedInteractionContent(turns));
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mythai_ai_response", path.toLowerCase(Locale.ROOT));
    }
}
