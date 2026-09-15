package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.quest.GodAttentionState;
import com.sande.mythictrpg.quest.QuestRuntimeService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Optional reflection boundary to the independently built static content registry. */
final class AiQuestContentBridge {
    private static final String REGISTRY_CLASS = "com.sande.mythaiaicontent.content.AiContentRegistry";

    private AiQuestContentBridge() {
    }

    static List<QuestCandidate> candidatesFor(ResourceLocation godId, ServerPlayer player) {
        if (!ModList.get().isLoaded("mythaiaicontent")) {
            return List.of();
        }
        try {
            Class<?> registryClass = Class.forName(REGISTRY_CLASS);
            Field instanceField = registryClass.getField("INSTANCE");
            Object registry = instanceField.get(null);
            Method definitionsFor = registryClass.getMethod("questCandidatesFor", ResourceLocation.class);
            Object raw = definitionsFor.invoke(registry, godId);
            if (!(raw instanceof List<?> definitions)) {
                return List.of();
            }
            List<QuestCandidate> result = new ArrayList<>();
            for (Object candidate : definitions) {
                Object definition = read(candidate, "quest");
                QuestCandidate resolved = new QuestCandidate((ResourceLocation) read(definition, "questId"),
                        (ResourceLocation) read(candidate, "questListId"),
                        (ResourceLocation) read(candidate, "progressTrackId"), String.valueOf(read(definition, "title")),
                        String.valueOf(read(definition, "content")), String.valueOf(read(candidate, "promptSummary")));
                if (isAvailable(definition, resolved, player.server)
                        && QuestRuntimeService.INSTANCE.validateAssignment(
                                player, resolved.questId(), godId).allowed()) {
                    result.add(resolved);
                }
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Could not resolve static quest candidates for {}", godId, exception);
            return List.of();
        }
    }

    static String attentionPrompt(ResourceLocation godId, ServerPlayer player) {
        GodAttentionState attention = GodAttentionState.get(player.server);
        if (!attention.isWritable()) {
            return "\n[GOD_ATTENTION]\nstatus: UNAVAILABLE\n"
                    + "Do not infer or claim any main-quest selection state.\n";
        }
        if (attention.record(godId).isEmpty()) {
            return "\n[GOD_ATTENTION]\nstatus: UNCLAIMED\n"
                    + "No player has been selected through this God's first fixed main quest yet. "
                    + "Only the authoritative quest candidates below may be offered.\n";
        }
        if (attention.isFocused(godId, player.getUUID())) {
            return "\n[GOD_ATTENTION]\nstatus: FOCUSED\n"
                    + "This player is one of the recipients selected through the first fixed main quest. "
                    + "Main quests may be discussed only when an authoritative candidate is supplied.\n";
        }
        return "\n[GOD_ATTENTION]\nstatus: NOT_FOCUSED\n"
                + "Another player or group received this God's first fixed main quest. Do not offer or imply a main quest "
                + "for this player. Side quests, blessings, rewards, ordinary interaction, and other listed capabilities "
                + "remain possible when the live context justifies them.\n";
    }

    /**
     * Completion narration needs the authored text even after the completion
     * changed a progress track and made the quest unavailable for new offers.
     */
    static Optional<QuestCandidate> completionCandidate(ResourceLocation godId, ResourceLocation questId) {
        if (!ModList.get().isLoaded("mythaiaicontent")) {
            return Optional.empty();
        }
        try {
            Class<?> registryClass = Class.forName(REGISTRY_CLASS);
            Object registry = registryClass.getField("INSTANCE").get(null);
            Object raw = registryClass.getMethod("questCandidatesFor", ResourceLocation.class).invoke(registry, godId);
            if (!(raw instanceof List<?> definitions)) {
                return Optional.empty();
            }
            for (Object candidate : definitions) {
                Object definition = read(candidate, "quest");
                ResourceLocation candidateId = (ResourceLocation) read(definition, "questId");
                if (questId.equals(candidateId)) {
                    return Optional.of(new QuestCandidate(candidateId,
                            (ResourceLocation) read(candidate, "questListId"),
                            (ResourceLocation) read(candidate, "progressTrackId"),
                            String.valueOf(read(definition, "title")), String.valueOf(read(definition, "content")),
                            String.valueOf(read(candidate, "promptSummary"))));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Could not resolve completion quest content for {}", questId, exception);
        }
        return Optional.empty();
    }

    private static Object read(Object target, String accessor) throws ReflectiveOperationException {
        return target.getClass().getMethod(accessor).invoke(target);
    }

    private static boolean isAvailable(Object definition, QuestCandidate candidate, MinecraftServer server)
            throws ReflectiveOperationException {
        Object raw = read(definition, "acceptanceConditions");
        if (!(raw instanceof List<?> conditions)) {
            return false;
        }
        for (Object condition : conditions) {
            String type = String.valueOf(read(condition, "type"));
            if (!(read(condition, "parameters") instanceof java.util.Map<?, ?> parameters)) {
                return false;
            }
            if (!"world_quest_progress_range".equals(type)) {
                return false;
            }
            Object rawTrack = parameters.get("progressTrackId");
            String track = rawTrack == null ? candidate.progressTrackId().toString() : String.valueOf(rawTrack);
            int minimum = integer(parameters.get("minimum"), 0);
            int maximum = integer(parameters.get("maximum"), 100);
            int current = worldProgress(server, ResourceLocation.parse(track));
            if (current < minimum || current > maximum) {
                return false;
            }
        }
        return true;
    }

    private static int worldProgress(MinecraftServer server, ResourceLocation progressTrackId)
            throws ReflectiveOperationException {
        Class<?> worldStateClass = Class.forName("com.sande.mythictrpg.data.world.MythicWorldState");
        Object state = worldStateClass.getMethod("get", MinecraftServer.class).invoke(null, server);
        return ((Number) worldStateClass.getMethod("questProgress", ResourceLocation.class)
                .invoke(state, progressTrackId)).intValue();
    }

    private static int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    record QuestCandidate(ResourceLocation questId, ResourceLocation questListId, ResourceLocation progressTrackId,
            String title, String content, String promptSummary) {
    }
}
