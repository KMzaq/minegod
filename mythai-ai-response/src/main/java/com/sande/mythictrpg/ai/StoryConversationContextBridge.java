package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.story.presentation.StoryAiHookTokenService;
import com.sande.mythictrpg.story.presentation.StoryRoomConversationService;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** One request's private capability map; only the prompt projection is ever sent to the model. */
final class StoryConversationContextBridge {
    private static final Gson JSON = new Gson();
    private final Optional<StoryRoomConversationService.Context> context;
    private StoryConversationContextBridge(Optional<StoryRoomConversationService.Context> context) { this.context = context; }

    static StoryConversationContextBridge capture(ServerPlayer player, Request request) {
        return new StoryConversationContextBridge(StoryRoomConversationService.INSTANCE.snapshot(player,
                request.roomId(), request.revision(), request.speakerGodId(), request.currentText(), !request.readOnly())
                .filter(value -> !value.snapshot().allowedStatements().isEmpty() || !value.snapshot().hookOffers().isEmpty()));
    }

    String prompt() {
        return context.map(value -> prompt(value.snapshot())).orElse("");
    }

    boolean hasContext() { return context.isPresent(); }

    Optional<UUID> contextId() { return context.map(value -> value.snapshot().requestId()); }

    List<RoomEvidenceReference> evidenceReferences() {
        return context.map(StoryRoomConversationService.Context::evidenceReferences).orElse(List.of());
    }

    List<String> selectedAliases(List<AiDialogueModels.Proposal> proposals) {
        return context.map(value -> selectedAliases(value.snapshot(), proposals)).orElse(List.of());
    }

    static List<String> selectedAliases(com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.Snapshot snapshot,
            List<AiDialogueModels.Proposal> proposals) {
        Set<String> allowed = snapshot.allowedStatements().stream()
                .map(value -> value.alias()).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toSet());
        Set<String> selected = new LinkedHashSet<>();
        for (var proposal : proposals) {
            if (!"story_disclose".equals(proposal.type()) || !proposal.targetParticipantIds().isEmpty()
                    || !proposal.parameters().keySet().equals(Set.of("statement_aliases"))) continue;
            String raw = proposal.parameters().get("statement_aliases");
            if (raw == null || raw.length() > 256) continue;
            var aliases = Arrays.stream(raw.split(",")).map(String::trim).toList();
            if (!aliases.isEmpty() && aliases.size() <= 8 && aliases.stream().allMatch(allowed::contains)) selected.addAll(aliases);
        }
        return List.copyOf(selected);
    }

    static String prompt(com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.Snapshot snapshot) {
        if (snapshot.allowedStatements().isEmpty() && snapshot.hookOffers().isEmpty()) return "";
        return "\n[SERVER_APPROVED_STORY_CONTEXT]\n"
                + "Only these statements are permitted for this speaker and the entire current audience. "
                + "Unknown or withheld details are absent; do not infer them. A hook is an offer, not an event result. "
                + (snapshot.allowedStatements().isEmpty() ? "" : "When you actually explain an allowed statement, also propose "
                    + "story_disclose with parameters {statement_aliases: comma-separated statement aliases} and no target IDs. "
                    + "The server delivers exact canonical text separately; this is not an action or event change. "
                    + "Do not select unrelated facts merely to grant knowledge.\n")
                + (snapshot.hookOffers().isEmpty() ? "No Story Hook is authorized in this context.\n"
                    : "If appropriate, propose story_event_hook with parameters {hook_alias: offered alias}, no target IDs. "
                    + "Never invent a hook ID or claim it was accepted/completed.\n")
                + JSON.toJson(Map.of("allowedStatements", snapshot.allowedStatements(), "hookOffers", snapshot.hookOffers()));
    }

    boolean current(ServerPlayer player) {
        return context.map(value -> StoryRoomConversationService.INSTANCE.current(player, value)).orElse(true);
    }

    AiDialogueModels.Proposal normalize(ServerPlayer player, Request request, AiDialogueModels.Proposal proposal) {
        if (context.isEmpty() || request.readOnly() || !current(player)) return null;
        var value = context.orElseThrow();
        if (!request.roomId().equals(value.roomId()) || request.revision() != value.roomRevision()
                || !request.playerId().equals(value.snapshot().audiencePlayerId())
                || !request.speakerGodId().equals(value.snapshot().speakerGodId())) return null;
        String alias = allowedAlias(value.snapshot(), proposal).orElse(null);
        if (alias == null) return null;
        var token = StoryAiHookTokenService.INSTANCE.resolveRoomAlias(player, value.snapshot().requestId(), alias,
                request.speakerGodId(), request.roomId(), request.revision());
        if (token.isEmpty()) return null;
        var hook = value.snapshot().hookOffers().stream().filter(offer -> offer.alias().equals(alias)).findFirst().orElseThrow();
        return new AiDialogueModels.Proposal("story_event_hook", hook.title(), hook.summary(), List.of(),
                Map.of("token", token.orElseThrow().toString()));
    }

    static Optional<String> allowedAlias(com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.Snapshot snapshot,
            AiDialogueModels.Proposal proposal) {
        if (!"story_event_hook".equals(proposal.type()) || !proposal.targetParticipantIds().isEmpty()
                || !proposal.parameters().keySet().equals(Set.of("hook_alias"))) return Optional.empty();
        String alias = proposal.parameters().get("hook_alias");
        return snapshot.hookOffers().stream().filter(offer -> offer.alias().equals(alias)).map(offer -> offer.alias()).findFirst();
    }
}
