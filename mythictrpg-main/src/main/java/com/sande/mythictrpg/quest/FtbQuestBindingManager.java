package com.sande.mythictrpg.quest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.sande.mythictrpg.quest.reward.QuestRewardPolicy;

/** Loads logical quest ID to FTB quest ID mappings from datapacks. */
public final class FtbQuestBindingManager
        extends SimplePreparableReloadListener<FtbQuestBindingManager.Prepared> {
    public static final FtbQuestBindingManager INSTANCE = new FtbQuestBindingManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/ftb_quests");

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private FtbQuestBindingManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public Optional<FtbQuestBinding> find(ResourceLocation questId) {
        return Optional.ofNullable(snapshot.byQuestId().get(questId));
    }

    public Optional<FtbQuestBinding> findByFtbQuestId(long ftbQuestId) {
        return Optional.ofNullable(snapshot.byFtbQuestId().get(ftbQuestId));
    }

    public Set<ResourceLocation> ids() {
        return snapshot.byQuestId().keySet();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, FtbQuestBinding> byQuest = new LinkedHashMap<>();
        Map<Long, FtbQuestBinding> byFtb = new LinkedHashMap<>();
        Set<Long> allFtbIds = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();

        CONVERTER.listMatchingResources(resourceManager).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), byQuest, byFtb, allFtbIds, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected FTB quest binding reload with " + errors.size()
                    + " error(s); the previous snapshot remains active");
        }
        return new Prepared(Map.copyOf(byQuest), Map.copyOf(byFtb));
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, FtbQuestBinding> byQuest, Map<Long, FtbQuestBinding> byFtb,
            Set<Long> allFtbIds, List<String> errors) {
        ResourceLocation contentId = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root value must be a JSON object");
            }
            FtbQuestBinding binding = parseBinding(contentId, root.getAsJsonObject());
            if (byQuest.putIfAbsent(binding.questId(), binding) != null) {
                throw new IllegalArgumentException("Duplicate logical quest ID " + binding.questId());
            }
            if (byFtb.putIfAbsent(binding.ftbQuestId(), binding) != null) {
                throw new IllegalArgumentException("Duplicate FTB quest ID " + binding.ftbQuestCode());
            }
            if (!allFtbIds.add(binding.ftbQuestId()) || !allFtbIds.add(binding.assignmentQuestId())) {
                throw new IllegalArgumentException("FTB quest/marker ID is reused by another binding");
            }
        } catch (Exception exception) {
            String message = "FTB quest binding " + file + " (ID " + contentId + ") from pack '"
                    + resource.sourcePackId() + "' failed: " + exception.getMessage();
            errors.add(message);
            MythicTrpg.LOGGER.error(message, exception);
        }
    }

    private static FtbQuestBinding parseBinding(ResourceLocation contentId, JsonObject json) {
        Set<String> allowedRoot = Set.of("schemaVersion", "questId", "ftbQuestId",
                "assignmentQuestId", "completionMode", "completionNpcIds", "progressTrackId",
                "progressOnClear", "narrativeRole", "minimumAffinity", "reminder",
                "evaluation", "structureEvaluation", "rewards");
        json.keySet().forEach(field -> {
            if (!allowedRoot.contains(field)) {
                throw new IllegalArgumentException("Unknown quest binding field '" + field + "'");
            }
        });
        int version = integer(json, "schemaVersion");
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported schemaVersion " + version + " (expected 1)");
        }
        ResourceLocation declared = id(string(json, "questId"), "questId");
        if (!declared.equals(contentId)) {
            throw new IllegalArgumentException("questId " + declared + " must match file-derived ID " + contentId);
        }
        Set<ResourceLocation> npcIds = new LinkedHashSet<>();
        JsonArray array = json.has("completionNpcIds") && json.get("completionNpcIds").isJsonArray()
                ? json.getAsJsonArray("completionNpcIds") : new JsonArray();
        for (JsonElement element : array) {
            npcIds.add(id(element.getAsString(), "completionNpcIds"));
        }
        return new FtbQuestBinding(declared,
                hex(string(json, "ftbQuestId"), "ftbQuestId"),
                hex(string(json, "assignmentQuestId"), "assignmentQuestId"),
                QuestCompletionMode.parse(string(json, "completionMode")), npcIds,
                id(string(json, "progressTrackId"), "progressTrackId"),
                integer(json, "progressOnClear"), narrativeRole(json),
                optionalInteger(json, "minimumAffinity", -1000), reminderPolicy(json),
                evaluationPolicy(json), structureEvaluationPolicy(json), rewardPolicy(json));
    }

    private static Optional<ResourceLocation> structureEvaluationPolicy(JsonObject root) {
        if (!root.has("structureEvaluation") || root.get("structureEvaluation").isJsonNull()) return Optional.empty();
        if (!root.get("structureEvaluation").isJsonObject()) {
            throw new IllegalArgumentException("Optional field 'structureEvaluation' must be an object");
        }
        JsonObject json = root.getAsJsonObject("structureEvaluation");
        json.keySet().forEach(field -> { if (!field.equals("policyId")) throw new IllegalArgumentException(
                "Unknown structureEvaluation field '" + field + "'"); });
        return Optional.of(id(string(json, "policyId"), "structureEvaluation.policyId"));
    }

    private static Optional<QuestRewardPolicy> rewardPolicy(JsonObject root) {
        if (!root.has("rewards") || root.get("rewards").isJsonNull()) {
            return Optional.empty();
        }
        if (!root.get("rewards").isJsonObject()) {
            throw new IllegalArgumentException("Optional field 'rewards' must be an object");
        }
        return Optional.of(QuestRewardPolicy.parse(root.getAsJsonObject("rewards"), "rewards"));
    }

    private static QuestNarrativeRole narrativeRole(JsonObject json) {
        return json.has("narrativeRole")
                ? QuestNarrativeRole.parse(string(json, "narrativeRole"))
                : QuestNarrativeRole.SIDE;
    }

    private static Optional<QuestEvaluationPolicy> evaluationPolicy(JsonObject root) {
        if (!root.has("evaluation") || root.get("evaluation").isJsonNull()) {
            return Optional.empty();
        }
        if (!root.get("evaluation").isJsonObject()) {
            throw new IllegalArgumentException("Optional field 'evaluation' must be an object");
        }
        JsonObject json = root.getAsJsonObject("evaluation");
        Set<String> allowed = Set.of("rewardTableId", "minimumRewardTier", "maximumRewardTier",
                "passingScore");
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown evaluation field '" + field + "'");
            }
        });
        return Optional.of(new QuestEvaluationPolicy(
                json.has("rewardTableId") ? Optional.of(id(string(json, "rewardTableId"), "evaluation.rewardTableId")) : Optional.empty(),
                optionalInteger(json, "minimumRewardTier", 1), optionalInteger(json, "maximumRewardTier", 1),
                integer(json, "passingScore")));
    }

    private static Optional<QuestReminderPolicy> reminderPolicy(JsonObject root) {
        if (!root.has("reminder") || root.get("reminder").isJsonNull()) {
            return Optional.empty();
        }
        if (!root.get("reminder").isJsonObject()) {
            throw new IllegalArgumentException("Optional field 'reminder' must be an object");
        }
        JsonObject json = root.getAsJsonObject("reminder");
        Set<String> allowed = Set.of("unrelatedActivityTicks", "cooldownTicks", "signalId",
                "relevantActions");
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown reminder field '" + field + "'");
            }
        });
        if (!json.has("relevantActions") || !json.get("relevantActions").isJsonArray()) {
            throw new IllegalArgumentException("Reminder requires array 'relevantActions'");
        }
        List<QuestRelevantAction> actions = new ArrayList<>();
        int index = 0;
        for (JsonElement element : json.getAsJsonArray("relevantActions")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("relevantActions[" + index + "] must be an object");
            }
            JsonObject action = element.getAsJsonObject();
            Set<String> actionAllowed = Set.of("observation", "subject");
            action.keySet().forEach(field -> {
                if (!actionAllowed.contains(field)) {
                    throw new IllegalArgumentException("Unknown relevant action field '" + field + "'");
                }
            });
            Optional<ResourceLocation> subject = action.has("subject") && !action.get("subject").isJsonNull()
                    ? Optional.of(id(string(action, "subject"), "reminder.relevantActions.subject"))
                    : Optional.empty();
            actions.add(new QuestRelevantAction(
                    id(string(action, "observation"), "reminder.relevantActions.observation"), subject));
            index++;
        }
        return Optional.of(new QuestReminderPolicy(
                longInteger(json, "unrelatedActivityTicks"), longInteger(json, "cooldownTicks"),
                id(string(json, "signalId"), "reminder.signalId"), actions));
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("Missing string '" + key + "'");
        }
        String value = json.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank string '" + key + "'");
        }
        return value;
    }

    private static int integer(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + key + "'");
        }
        return json.get(key).getAsInt();
    }

    private static int optionalInteger(JsonObject json, String key, int fallback) {
        return json.has(key) ? integer(json, key) : fallback;
    }

    private static long longInteger(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + key + "'");
        }
        double raw = json.get(key).getAsDouble();
        long value = json.get(key).getAsLong();
        if (raw != value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer");
        }
        return value;
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in '" + field + "': " + value);
        }
        return id;
    }

    private static long hex(String value, String field) {
        if (!value.matches("[0-9A-Fa-f]{16}")) {
            throw new IllegalArgumentException(field + " must be exactly 16 hexadecimal characters: " + value);
        }
        try {
            // FTB Quests treats object IDs as positive signed longs. Values whose
            // first digit is 8-F are rewritten on load and would break bindings.
            long parsed = Long.parseLong(value, 16);
            if (parsed <= 0L) {
                throw new NumberFormatException("ID must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field
                    + " must be a positive FTB ID (16 hex digits starting with 0-7): " + value,
                    exception);
        }
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        long generation = snapshot.generation() + 1;
        snapshot = new Snapshot(prepared.byQuestId(), prepared.byFtbQuestId(), generation);
        MythicTrpg.LOGGER.info("Loaded {} FTB quest bindings (generation {}).",
                snapshot.byQuestId().size(), generation);
    }

    protected record Prepared(Map<ResourceLocation, FtbQuestBinding> byQuestId,
            Map<Long, FtbQuestBinding> byFtbQuestId) {
    }

    public record Snapshot(Map<ResourceLocation, FtbQuestBinding> byQuestId,
            Map<Long, FtbQuestBinding> byFtbQuestId, long generation) {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), 0);

        public Snapshot {
            byQuestId = Map.copyOf(byQuestId);
            byFtbQuestId = Map.copyOf(byFtbQuestId);
        }
    }
}
