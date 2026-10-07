package com.sande.mythaiaicontent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythaiaicontent.MythAiContentRegistryMod;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Datapack-backed registry for immutable AI content only.  It intentionally has no dependency on MythicTRPG: God IDs
 * are ResourceLocation values in JSON, while the RPG mod remains owner of whether those gods are currently unlocked,
 * present, or allowed to speak.
 */
public final class AiContentRegistry extends SimplePreparableReloadListener<AiContentRegistry.Prepared> {
    public static final AiContentRegistry INSTANCE = new AiContentRegistry();
    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final int LEGACY_SCHEMA_VERSION = 1;
    private static final FileToIdConverter GODS = FileToIdConverter.json("mythai_ai/god_profiles");
    private static final FileToIdConverter LORE = FileToIdConverter.json("mythai_ai/lore");
    private static final FileToIdConverter EXAMPLES = FileToIdConverter.json("mythai_ai/dialogue_examples");
    private static final FileToIdConverter SOCIAL_RELATIONS = FileToIdConverter.json("mythai_ai/social_relations");
    private static final FileToIdConverter QUEST_LISTS = FileToIdConverter.json("mythai_ai/quest_lists");
    private static final FileToIdConverter COMMON_KNOWLEDGE = FileToIdConverter.json("mythai_ai/common_knowledge");

    private volatile Snapshot snapshot = Snapshot.empty();

    private AiContentRegistry() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Unrestricted public reference facts. Relevance selection and prompt budgets belong to the caller. */
    public List<CommonKnowledgeEntry> publicCommonKnowledge() {
        return snapshot.publicCommonKnowledge();
    }

    /** Resolves a static profile using the God ID owned by MythicTRPG, never a display name. */
    public Optional<GodContentProfile> findGod(ResourceLocation mythicGodId) {
        return Optional.ofNullable(snapshot.godsByGodId().get(mythicGodId));
    }

    /** Returns only the selected tier's static writing guidance; dynamic relationship values remain outside this mod. */
    public List<String> relationshipGuidanceFor(ResourceLocation mythicGodId, RelationshipTier tier) {
        GodContentProfile profile = snapshot.godsByGodId().get(mythicGodId);
        return profile == null ? List.of() : profile.relationshipGuidelines().getOrDefault(tier, List.of());
    }

    /**
     * Returns static auxiliary relationship tags from the perspective of sourceGodId toward targetGodId. Dynamic
     * player relationships and the decision of which NPCs are in a session remain outside this registry.
     */
    public List<SocialRelationTag> socialRelationTagsFor(ResourceLocation sourceGodId, ResourceLocation targetGodId) {
        return snapshot.socialTagsBySourceGodId().getOrDefault(sourceGodId, Map.of())
                .getOrDefault(targetGodId, List.of());
    }

    /**
     * Returns the raw content definition. This must not be sent to an LLM because it includes every knowledge tier.
     * Use {@link #loreFor(ResourceLocation, ResourceLocation)} or {@link #loreAvailableTo(ResourceLocation)} instead.
     */
    public Optional<LoreEntry> findLoreDefinition(ResourceLocation loreId) {
        return Optional.ofNullable(snapshot.loreById().get(loreId));
    }

    /** Returns this God's safe, level-filtered view of one lore entry. */
    public Optional<ResolvedLoreKnowledge> loreFor(ResourceLocation mythicGodId, ResourceLocation loreId) {
        Integer level = snapshot.knowledgeLevelsByGodId().getOrDefault(mythicGodId, Map.of()).get(loreId);
        LoreEntry entry = snapshot.loreById().get(loreId);
        return level == null || entry == null ? Optional.empty() : Optional.of(resolveLore(entry, level, snapshot));
    }

    /** Returns all lore this God knows, with every entry capped to that God's declared knowledge level. */
    public List<ResolvedLoreKnowledge> loreAvailableTo(ResourceLocation mythicGodId) {
        Map<ResourceLocation, Integer> levels = snapshot.knowledgeLevelsByGodId().get(mythicGodId);
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.entrySet().stream().map(entry -> {
            LoreEntry lore = snapshot.loreById().get(entry.getKey());
            return lore == null ? null : resolveLore(lore, entry.getValue(), snapshot);
        }).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Prompt-facing contract. The caller MUST supply the complete actual audience from the game, not an AI guess
     * or a recording-enabled subset. The raw ownership queries above are not audience disclosure authorization.
     */
    public Optional<AudienceGodContent> audienceContentFor(ResourceLocation mythicGodId, String relationshipTier,
            boolean publicRoom, List<ResourceLocation> participantGodIds, Set<UUID> audiencePlayerIds) {
        return audienceContentFor(mythicGodId, relationshipTier, publicRoom, participantGodIds, audiencePlayerIds,
                participantGodIds);
    }

    /** Evidence revalidation only: relation reference targets are historical, but disclosure audience is current. */
    public Optional<AudienceGodContent> audienceContentFor(ResourceLocation mythicGodId, String relationshipTier,
            boolean publicRoom, List<ResourceLocation> participantGodIds, Set<UUID> audiencePlayerIds,
            List<ResourceLocation> relationReferenceGodIds) {
        Snapshot current = snapshot;
        return ContentAudienceResolver.resolve(current, RelationshipTier.fromTag(relationshipTier),
                new ContentAudience(mythicGodId, publicRoom, participantGodIds, audiencePlayerIds), relationReferenceGodIds);
    }

    /** Returns candidate examples; scoring and prompt limits remain the AI Response Module's responsibility. */
    public List<DialogueExample> dialogueExamplesAvailableTo(ResourceLocation mythicGodId) {
        return snapshot.examplesById().values().stream().filter(example -> example.availableTo(mythicGodId)).toList();
    }

    public Optional<QuestListDefinition> findQuestList(ResourceLocation questListId) {
        return Optional.ofNullable(snapshot.questListsById().get(questListId));
    }

    /** Returns reusable static lists referenced by this God. Availability still requires MythicTRPG validation. */
    public List<QuestListDefinition> questListsFor(ResourceLocation mythicGodId) {
        GodContentProfile profile = snapshot.godsByGodId().get(mythicGodId);
        return profile == null ? List.of() : profile.questListIds().stream().map(snapshot.questListsById()::get)
                .filter(java.util.Objects::nonNull).toList();
    }

    /** Flattens referenced lists in author order. These are candidates, not accepted or active quests. */
    public List<QuestDefinition> questDefinitionsFor(ResourceLocation mythicGodId) {
        return questCandidatesFor(mythicGodId).stream().map(QuestCandidateDefinition::quest).toList();
    }

    public List<QuestCandidateDefinition> questCandidatesFor(ResourceLocation mythicGodId) {
        LinkedHashMap<ResourceLocation, QuestCandidateDefinition> unique = new LinkedHashMap<>();
        for (QuestListDefinition list : questListsFor(mythicGodId)) {
            for (QuestDefinition quest : list.quests()) {
                unique.putIfAbsent(quest.questId(), new QuestCandidateDefinition(list.questListId(),
                        list.progressTrackId(), quest));
            }
        }
        return List.copyOf(unique.values());
    }

    /** Prompt-safe quest text for the game's complete actual audience; availability remains a game concern. */
    public List<QuestCandidateDefinition> audienceQuestCandidatesFor(ResourceLocation godId, boolean publicRoom,
            List<ResourceLocation> godIds, Set<java.util.UUID> playerIds) {
        var audience = new ContentAudience(godId, publicRoom, godIds, playerIds);
        return questCandidatesFor(godId).stream().filter(candidate -> candidate.quest().disclosure().permits(audience)).toList();
    }

    /** Legacy consumers without an explicit complete audience can only receive unrestricted public text. */
    public List<QuestCandidateDefinition> publicQuestCandidatesFor(ResourceLocation godId) {
        return questCandidatesFor(godId).stream().filter(candidate -> candidate.quest().disclosure().mode() == ContentDisclosure.Mode.PUBLIC
                && candidate.quest().disclosure().allowedGodIds().isEmpty()).toList();
    }

    /** Resolves only content explicitly referenced by the profile, useful for bounded initial AI context construction. */
    public Optional<StaticGodContent> staticContentFor(ResourceLocation mythicGodId) {
        GodContentProfile profile = snapshot.godsByGodId().get(mythicGodId);
        if (profile == null) {
            return Optional.empty();
        }
        List<ResolvedLoreKnowledge> lore = profile.loreKnowledge().stream()
                .map(knowledge -> resolveLore(snapshot.loreById().get(knowledge.loreId()), knowledge.level(), snapshot))
                .toList();
        List<DialogueExample> examples = profile.signatureExampleIds().stream().map(snapshot.examplesById()::get)
                .filter(java.util.Objects::nonNull).filter(example -> example.availableTo(mythicGodId)).toList();
        return Optional.of(new StaticGodContent(profile, lore, examples));
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, GodContentProfile> godsByContentId = parseAll(resourceManager, GODS,
                AiContentRegistry::parseGod, errors);
        Map<ResourceLocation, LoreEntry> loreById = parseAll(resourceManager, LORE, AiContentRegistry::parseLore, errors);
        Map<ResourceLocation, DialogueExample> examplesById = parseAll(resourceManager, EXAMPLES,
                AiContentRegistry::parseExample, errors);
        Map<ResourceLocation, SocialRelation> socialRelationsById = parseAll(resourceManager, SOCIAL_RELATIONS,
                AiContentRegistry::parseSocialRelation, errors);
        Map<ResourceLocation, QuestListDefinition> questListsById = parseAll(resourceManager, QUEST_LISTS,
                AiContentRegistry::parseQuestList, errors);
        Map<ResourceLocation, CommonKnowledgeEntry> commonKnowledgeById = parseAll(resourceManager, COMMON_KNOWLEDGE,
                AiContentRegistry::parseCommonKnowledge, errors, CommonKnowledgeEntry.MAX_ENTRIES);

        Map<ResourceLocation, GodContentProfile> godsByGodId = new LinkedHashMap<>();
        for (GodContentProfile profile : godsByContentId.values()) {
            GodContentProfile existing = godsByGodId.putIfAbsent(profile.godId(), profile);
            if (existing != null) {
                errors.add("God profiles " + existing.contentId() + " and " + profile.contentId()
                        + " both reference MythicTRPG God ID " + profile.godId());
            }
        }
        validateProfileReferences(godsByContentId.values(), loreById, examplesById, questListsById, errors);
        validateGlobalQuestIds(questListsById.values(), errors);
        validateSocialRelations(socialRelationsById.values(), godsByGodId, errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected AI content reload with " + errors.size()
                    + " error(s); the previous static-content snapshot remains active. First error: " + errors.getFirst());
        }
        KnowledgeIndexes indexes = buildKnowledgeIndexes(godsByGodId.values());
        return new Prepared(Map.copyOf(godsByContentId), Map.copyOf(godsByGodId), Map.copyOf(loreById),
                Map.copyOf(examplesById), Map.copyOf(socialRelationsById), Map.copyOf(questListsById),
                indexes.knowledgeLevelsByGodId(),
                indexes.holdersByLoreId(), buildSocialRelationIndex(socialRelationsById.values()),
                List.copyOf(commonKnowledgeById.values()));
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        snapshot = new Snapshot(prepared.godsByContentId(), prepared.godsByGodId(), prepared.loreById(),
                prepared.examplesById(), prepared.socialRelationsById(), prepared.questListsById(),
                prepared.knowledgeLevelsByGodId(),
                prepared.holdersByLoreId(), prepared.socialTagsBySourceGodId(), prepared.publicCommonKnowledge(),
                snapshot.generation() + 1);
        MythAiContentRegistryMod.LOGGER.info("Loaded {} static God profiles, {} lore entries, {} dialogue examples, "
                        + "{} social relations, {} quest lists, and {} public common knowledge entries (generation {}).",
                prepared.godsByContentId().size(),
                prepared.loreById().size(), prepared.examplesById().size(), prepared.socialRelationsById().size(),
                prepared.questListsById().size(), prepared.publicCommonKnowledge().size(), snapshot.generation());
    }

    private static <T> Map<ResourceLocation, T> parseAll(ResourceManager resources, FileToIdConverter converter,
            EntryParser<T> parser, List<String> errors) {
        return parseAll(resources, converter, parser, errors, Integer.MAX_VALUE);
    }

    private static <T> Map<ResourceLocation, T> parseAll(ResourceManager resources, FileToIdConverter converter,
            EntryParser<T> parser, List<String> errors, int maximumEntries) {
        Map<ResourceLocation, T> parsed = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> matching = converter.listMatchingResources(resources);
        if (matching.size() > maximumEntries) {
            errors.add("AI content category exceeds maximum of " + maximumEntries + " entries");
            return parsed;
        }
        matching.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ResourceLocation id = converter.fileToId(entry.getKey());
                    try (Reader reader = entry.getValue().openAsReader()) {
                        JsonElement root = JsonParser.parseReader(reader);
                        if (!root.isJsonObject()) {
                            throw new IllegalArgumentException("Root value must be a JSON object");
                        }
                        if (parsed.putIfAbsent(id, parser.parse(id, root.getAsJsonObject())) != null) {
                            throw new IllegalArgumentException("Duplicate resource ID " + id);
                        }
                    } catch (Exception exception) {
                        String message = "AI content " + entry.getKey() + " (ID " + id + ") from pack '"
                                + entry.getValue().sourcePackId() + "' failed: " + exception.getMessage();
                        errors.add(message);
                        MythAiContentRegistryMod.LOGGER.error(message, exception);
                    }
                });
        return parsed;
    }

    /** Deliberately has no lore ownership/disclosure fields: every accepted fact is public to every audience. */
    static CommonKnowledgeEntry parseCommonKnowledge(ResourceLocation contentId, JsonObject json) {
        if (!Set.of("schemaVersion", "title", "keywords", "content").containsAll(json.keySet())) {
            throw new IllegalArgumentException("common_knowledge accepts only schemaVersion, title, keywords, content; "
                    + "secret or restricted knowledge belongs in lore");
        }
        JsonElement version = json.get("schemaVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || !version.getAsString().equals("2")) {
            throw new IllegalArgumentException("common_knowledge schemaVersion must be the integer 2");
        }
        JsonArray rawKeywords = array(json, "keywords");
        if (rawKeywords.isEmpty() || rawKeywords.size() > CommonKnowledgeEntry.MAX_KEYWORDS) {
            throw new IllegalArgumentException("keywords must contain 1.." + CommonKnowledgeEntry.MAX_KEYWORDS + " strings");
        }
        List<String> keywords = new ArrayList<>();
        for (JsonElement keyword : rawKeywords) {
            keywords.add(strictString(keyword, "keywords[]"));
        }
        return new CommonKnowledgeEntry(contentId, strictString(json.get("title"), "title"), keywords,
                strictString(json.get("content"), "content"));
    }

    private static String strictString(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static GodContentProfile parseGod(ResourceLocation contentId, JsonObject json) {
        int schemaVersion = schema(json);
        List<LoreKnowledge> loreKnowledge = schemaVersion == LEGACY_SCHEMA_VERSION
                ? ids(json, "loreIds").stream().map(id -> new LoreKnowledge(id, 1)).toList()
                : loreKnowledge(json, "loreKnowledge");
        return new GodContentProfile(contentId, id(required(json, "godId")), required(json, "displayName"),
                required(json, "identity"), optional(json, "description"), strings(json, "personality"),
                strings(json, "values"), strings(json, "speechStyles"), optionalStrings(json, "dialogueGuidelines"),
                situationGuidelines(json), repetitionGuidelines(json), strings(json, "restrictions"),
                optionalStrings(json, "characterTags"), loreKnowledge, optionalIds(json, "questListIds"),
                ids(json, "signatureExampleIds"),
                relationshipGuidelines(json), fieldDisclosure(json));
    }

    private static QuestListDefinition parseQuestList(ResourceLocation contentId, JsonObject json) {
        schema(json);
        ResourceLocation declaredId = id(required(json, "questListId"));
        if (!contentId.equals(declaredId)) {
            throw new IllegalArgumentException("questListId " + declaredId + " must match file-derived ID " + contentId);
        }
        List<QuestDefinition> quests = new ArrayList<>();
        for (JsonElement entry : array(json, "quests")) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException("quests must contain only objects");
            }
            JsonObject quest = entry.getAsJsonObject();
            quests.add(new QuestDefinition(id(required(quest, "questId")), required(quest, "title"),
                    required(quest, "content"), questNodes(quest, "objectives", false),
                    questNodes(quest, "rewards", false), questNodes(quest, "acceptanceConditions", true),
                    boundedInt(quest, "progressOnClear", 0, 100), disclosure(quest, "disclosure", ContentDisclosure.PUBLIC)));
        }
        return new QuestListDefinition(contentId, id(required(json, "progressTrackId")),
                required(json, "displayName"), quests);
    }

    private static LoreEntry parseLore(ResourceLocation id, JsonObject json) {
        int schemaVersion = schema(json);
        LoreSecrecy secrecy = LoreSecrecy.valueOf(optional(json, "secrecy", "PUBLIC").toUpperCase(Locale.ROOT));
        List<LoreKnowledgeLevel> levels = schemaVersion == LEGACY_SCHEMA_VERSION
                ? List.of(new LoreKnowledgeLevel(1, required(json, "content"), false, disclosure(json, "disclosure", null)))
                : knowledgeLevels(json, "knowledgeLevels");
        return new LoreEntry(id, required(json, "title"), levels, secrecy, strings(json, "keywords"));
    }

    private static DialogueExample parseExample(ResourceLocation id, JsonObject json) {
        schema(json);
        List<DialogueExampleTurn> turns = new ArrayList<>();
        JsonArray dialogue = array(json, "dialogue");
        for (JsonElement entry : dialogue) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException("dialogue contains a non-object turn");
            }
            JsonObject turn = entry.getAsJsonObject();
            turns.add(new DialogueExampleTurn(DialogueExampleTurn.Role.parse(required(turn, "role")),
                    required(turn, "text")));
        }
        return new DialogueExample(id, Set.copyOf(strings(json, "tags")), Set.copyOf(ids(json, "known_by")), turns,
                disclosure(json, "disclosure", ContentDisclosure.PUBLIC));
    }

    private static SocialRelation parseSocialRelation(ResourceLocation contentId, JsonObject json) {
        schema(json);
        return new SocialRelation(contentId, id(required(json, "participantA")), id(required(json, "participantB")),
                socialRelationTags(json, "aToBTags"), socialRelationTags(json, "bToATags"));
    }

    private static void validateProfileReferences(Iterable<GodContentProfile> profiles,
            Map<ResourceLocation, LoreEntry> lore, Map<ResourceLocation, DialogueExample> examples,
            Map<ResourceLocation, QuestListDefinition> questLists, List<String> errors) {
        for (GodContentProfile profile : profiles) {
            for (LoreKnowledge knowledge : profile.loreKnowledge()) {
                LoreEntry entry = lore.get(knowledge.loreId());
                if (entry == null) {
                    errors.add("God profile " + profile.contentId() + " references missing lore " + knowledge.loreId());
                } else if (knowledge.level() > entry.maxKnowledgeLevel()) {
                    errors.add("God profile " + profile.contentId() + " gives lore " + knowledge.loreId() + " level "
                            + knowledge.level() + ", but its maximum level is " + entry.maxKnowledgeLevel());
                }
            }
            for (ResourceLocation id : profile.signatureExampleIds()) {
                DialogueExample example = examples.get(id);
                if (example == null) {
                    errors.add("God profile " + profile.contentId() + " references missing dialogue example " + id);
                } else if (!example.availableTo(profile.godId())) {
                    errors.add("God profile " + profile.contentId() + " references example " + id
                            + " whose known_by does not include " + profile.godId());
                }
            }
            for (ResourceLocation questListId : profile.questListIds()) {
                if (!questLists.containsKey(questListId)) {
                    errors.add("God profile " + profile.contentId() + " references missing quest list " + questListId);
                }
            }
        }
    }

    private static void validateGlobalQuestIds(Iterable<QuestListDefinition> lists, List<String> errors) {
        Map<ResourceLocation, ResourceLocation> ownerByQuestId = new LinkedHashMap<>();
        for (QuestListDefinition list : lists) {
            for (QuestDefinition quest : list.quests()) {
                ResourceLocation existing = ownerByQuestId.putIfAbsent(quest.questId(), list.questListId());
                if (existing != null) {
                    errors.add("Quest ID " + quest.questId() + " is declared by both " + existing + " and "
                            + list.questListId());
                }
            }
        }
    }

    private static KnowledgeIndexes buildKnowledgeIndexes(Iterable<GodContentProfile> profiles) {
        Map<ResourceLocation, Map<ResourceLocation, Integer>> mutableByGod = new LinkedHashMap<>();
        Map<ResourceLocation, List<LoreKnowledgeHolder>> mutableHolders = new LinkedHashMap<>();
        for (GodContentProfile profile : profiles) {
            Map<ResourceLocation, Integer> levels = new LinkedHashMap<>();
            for (LoreKnowledge knowledge : profile.loreKnowledge()) {
                levels.put(knowledge.loreId(), knowledge.level());
                mutableHolders.computeIfAbsent(knowledge.loreId(), ignored -> new ArrayList<>())
                        .add(new LoreKnowledgeHolder(profile.godId(), knowledge.level()));
            }
            mutableByGod.put(profile.godId(), Map.copyOf(levels));
        }
        Map<ResourceLocation, List<LoreKnowledgeHolder>> holdersByLore = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, List<LoreKnowledgeHolder>> entry : mutableHolders.entrySet()) {
            List<LoreKnowledgeHolder> holders = entry.getValue().stream()
                    .sorted(Comparator.comparing(holder -> holder.godId().toString())).toList();
            holdersByLore.put(entry.getKey(), holders);
        }
        return new KnowledgeIndexes(Map.copyOf(mutableByGod), Map.copyOf(holdersByLore));
    }

    private static void validateSocialRelations(Iterable<SocialRelation> relations,
            Map<ResourceLocation, GodContentProfile> godsByGodId, List<String> errors) {
        Map<String, SocialRelation> byPair = new LinkedHashMap<>();
        for (SocialRelation relation : relations) {
            if (!godsByGodId.containsKey(relation.participantA())) {
                errors.add("Social relation " + relation.contentId() + " references unknown participantA "
                        + relation.participantA());
            }
            if (!godsByGodId.containsKey(relation.participantB())) {
                errors.add("Social relation " + relation.contentId() + " references unknown participantB "
                        + relation.participantB());
            }
            String pairKey = canonicalPairKey(relation.participantA(), relation.participantB());
            SocialRelation existing = byPair.putIfAbsent(pairKey, relation);
            if (existing != null) {
                errors.add("Social relations " + existing.contentId() + " and " + relation.contentId()
                        + " both define the pair " + pairKey);
            }
        }
    }

    private static Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> buildSocialRelationIndex(
            Iterable<SocialRelation> relations) {
        Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> index = new LinkedHashMap<>();
        for (SocialRelation relation : relations) {
            addSocialRelationDirection(index, relation.participantA(), relation.participantB(), relation.aToBTags());
            addSocialRelationDirection(index, relation.participantB(), relation.participantA(), relation.bToATags());
        }
        return immutableNestedListMap(index);
    }

    private static void addSocialRelationDirection(
            Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> index, ResourceLocation source,
            ResourceLocation target, List<SocialRelationTag> tags) {
        if (!tags.isEmpty()) {
            index.computeIfAbsent(source, ignored -> new LinkedHashMap<>()).put(target, List.copyOf(tags));
        }
    }

    private static String canonicalPairKey(ResourceLocation first, ResourceLocation second) {
        return first.toString().compareTo(second.toString()) <= 0 ? first + "|" + second : second + "|" + first;
    }

    private static ResolvedLoreKnowledge resolveLore(LoreEntry entry, int knowledgeLevel, Snapshot snapshot) {
        if (entry == null) {
            throw new IllegalArgumentException("Cannot resolve a missing lore entry");
        }
        List<LoreKnowledgeLevel> levels = entry.levelsThrough(knowledgeLevel);
        List<LoreKnowledgeHolder> holders = entry.revealsKnowledgeHoldersThrough(knowledgeLevel)
                ? snapshot.holdersByLoreId().getOrDefault(entry.id(), List.of()) : List.of();
        return new ResolvedLoreKnowledge(entry.id(), entry.title(), entry.secrecy(), entry.keywords(), knowledgeLevel,
                levels, holders);
    }

    private static int schema(JsonObject json) {
        if (!json.has("schemaVersion") || !json.get("schemaVersion").isJsonPrimitive()) {
            throw new IllegalArgumentException("schemaVersion must be " + CURRENT_SCHEMA_VERSION);
        }
        int version = json.get("schemaVersion").getAsInt();
        if (version != LEGACY_SCHEMA_VERSION && version != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + LEGACY_SCHEMA_VERSION + " or "
                    + CURRENT_SCHEMA_VERSION);
        }
        return version;
    }

    private static String required(JsonObject json, String field) {
        String value = optional(json, field, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optional(JsonObject json, String field) {
        return optional(json, field, "");
    }

    private static String optional(JsonObject json, String field, String fallback) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : fallback;
    }

    private static JsonArray array(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static List<String> strings(JsonObject json, String field) {
        return strings(array(json, field), field);
    }

    private static List<String> strings(JsonArray entries, String field) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement entry : entries) {
            if (!entry.isJsonPrimitive()) {
                throw new IllegalArgumentException(field + " must contain only strings");
            }
            String value = entry.getAsString().trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static List<String> optionalStrings(JsonObject json, String field) {
        return json.has(field) ? strings(json, field) : List.of();
    }

    private static Map<RelationshipTier, List<String>> relationshipGuidelines(JsonObject json) {
        JsonElement value = json.get("relationshipGuidelines");
        if (value == null) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("relationshipGuidelines must be an object");
        }
        Map<RelationshipTier, List<String>> guidelines = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            RelationshipTier tier = RelationshipTier.fromTag(entry.getKey());
            if (!entry.getValue().isJsonArray()) {
                throw new IllegalArgumentException("relationshipGuidelines." + entry.getKey() + " must be an array");
            }
            if (guidelines.putIfAbsent(tier, strings(entry.getValue().getAsJsonArray(),
                    "relationshipGuidelines." + entry.getKey())) != null) {
                throw new IllegalArgumentException("Duplicate relationship guidance for " + entry.getKey());
            }
        }
        return Map.copyOf(guidelines);
    }

    private static Map<String, List<String>> situationGuidelines(JsonObject json) {
        JsonElement value = json.get("situationGuidelines");
        if (value == null) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("situationGuidelines must be an object");
        }
        Map<String, List<String>> guidelines = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String tag = entry.getKey().trim();
            if (!tag.startsWith("S_")) {
                throw new IllegalArgumentException("situationGuidelines key must use an S_ tag: " + tag);
            }
            if (!entry.getValue().isJsonArray()) {
                throw new IllegalArgumentException("situationGuidelines." + tag + " must be an array");
            }
            if (guidelines.putIfAbsent(tag, strings(entry.getValue().getAsJsonArray(),
                    "situationGuidelines." + tag)) != null) {
                throw new IllegalArgumentException("Duplicate situation guidance for " + tag);
            }
        }
        return Map.copyOf(guidelines);
    }

    private static Map<String, List<String>> repetitionGuidelines(JsonObject json) {
        JsonElement value = json.get("repetitionGuidelines");
        if (value == null) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("repetitionGuidelines must be an object");
        }
        Map<String, List<String>> guidelines = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String key = entry.getKey().trim();
            if (!key.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("Invalid repetitionGuidelines key: " + key);
            }
            if (!entry.getValue().isJsonArray()) {
                throw new IllegalArgumentException("repetitionGuidelines." + key + " must be an array");
            }
            if (guidelines.putIfAbsent(key, strings(entry.getValue().getAsJsonArray(),
                    "repetitionGuidelines." + key)) != null) {
                throw new IllegalArgumentException("Duplicate repetition guidance for " + key);
            }
        }
        return Map.copyOf(guidelines);
    }

    private static List<ResourceLocation> ids(JsonObject json, String field) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (String raw : strings(json, field)) {
            ids.add(id(raw));
        }
        return List.copyOf(ids);
    }

    private static List<ResourceLocation> optionalIds(JsonObject json, String field) {
        return json.has(field) ? ids(json, field) : List.of();
    }

    private static List<QuestContentNode> questNodes(JsonObject json, String field, boolean optional) {
        if (optional && !json.has(field)) {
            return List.of();
        }
        List<QuestContentNode> nodes = new ArrayList<>();
        for (JsonElement entry : array(json, field)) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException(field + " must contain only objects");
            }
            JsonObject node = entry.getAsJsonObject();
            Map<String, String> parameters = new LinkedHashMap<>();
            JsonElement rawParameters = node.get("parameters");
            if (rawParameters != null) {
                if (!rawParameters.isJsonObject()) {
                    throw new IllegalArgumentException(field + ".parameters must be an object");
                }
                for (Map.Entry<String, JsonElement> parameter : rawParameters.getAsJsonObject().entrySet()) {
                    if (!parameter.getValue().isJsonPrimitive()) {
                        throw new IllegalArgumentException(field + ".parameters values must be strings or numbers");
                    }
                    parameters.put(parameter.getKey(), parameter.getValue().getAsString());
                }
            }
            nodes.add(new QuestContentNode(required(node, "type"), optional(node, "description"), parameters));
        }
        return List.copyOf(nodes);
    }

    private static int boundedInt(JsonObject json, String field, int minimum, int maximum) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be an integer from " + minimum + " to " + maximum);
        }
        int parsed = value.getAsInt();
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(field + " must be from " + minimum + " to " + maximum);
        }
        return parsed;
    }

    private static List<LoreKnowledge> loreKnowledge(JsonObject json, String field) {
        List<LoreKnowledge> knowledge = new ArrayList<>();
        for (JsonElement entry : array(json, field)) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException(field + " must contain only objects");
            }
            JsonObject object = entry.getAsJsonObject();
            knowledge.add(new LoreKnowledge(id(required(object, "loreId")), positiveInt(object, "level")));
        }
        return List.copyOf(knowledge);
    }

    private static List<SocialRelationTag> socialRelationTags(JsonObject json, String field) {
        return strings(json, field).stream().map(SocialRelationTag::fromTag).toList();
    }

    private static List<LoreKnowledgeLevel> knowledgeLevels(JsonObject json, String field) {
        List<LoreKnowledgeLevel> levels = new ArrayList<>();
        for (JsonElement entry : array(json, field)) {
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException(field + " must contain only objects");
            }
            JsonObject object = entry.getAsJsonObject();
            levels.add(new LoreKnowledgeLevel(positiveInt(object, "level"), required(object, "content"),
                    bool(object, "revealKnowledgeHolders", false), disclosure(object, "disclosure", null)));
        }
        return List.copyOf(levels);
    }

    private static Map<String, ContentDisclosure> fieldDisclosure(JsonObject json) {
        if (!json.has("fieldDisclosure")) return Map.of();
        if (!json.get("fieldDisclosure").isJsonObject())
            throw new IllegalArgumentException("fieldDisclosure must be an object");
        var object = json.getAsJsonObject("fieldDisclosure");
        var result = new LinkedHashMap<String, ContentDisclosure>();
        for (String field : object.keySet()) {
            if (!GodContentProfile.DISCLOSURE_FIELDS.contains(field))
                throw new IllegalArgumentException("Unsupported fieldDisclosure field: " + field);
            result.put(field, disclosure(object, field, ContentDisclosure.NEVER));
        }
        return Map.copyOf(result);
    }

    /** Reject malformed/unknown permission fields instead of ignoring typos that could broaden disclosure. */
    private static ContentDisclosure disclosure(JsonObject parent, String field, ContentDisclosure fallback) {
        if (!parent.has(field)) return fallback;
        if (!parent.get(field).isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
        var object = parent.getAsJsonObject(field);
        if (!Set.of("mode", "allowedGodIds").containsAll(object.keySet()))
            throw new IllegalArgumentException("Unknown " + field + " permission field");
        var mode = ContentDisclosure.Mode.valueOf(required(object, "mode"));
        return new ContentDisclosure(mode, Set.copyOf(optionalIds(object, "allowedGodIds")));
    }

    private static int positiveInt(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        int parsed = value.getAsInt();
        if (parsed < 1) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return parsed;
    }

    private static boolean bool(JsonObject json, String field, boolean fallback) {
        JsonElement value = json.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static ResourceLocation id(String raw) {
        return ResourceLocation.parse(raw);
    }

    private interface EntryParser<T> {
        T parse(ResourceLocation id, JsonObject json);
    }

    protected record Prepared(Map<ResourceLocation, GodContentProfile> godsByContentId,
            Map<ResourceLocation, GodContentProfile> godsByGodId, Map<ResourceLocation, LoreEntry> loreById,
            Map<ResourceLocation, DialogueExample> examplesById, Map<ResourceLocation, SocialRelation> socialRelationsById,
            Map<ResourceLocation, QuestListDefinition> questListsById,
            Map<ResourceLocation, Map<ResourceLocation, Integer>> knowledgeLevelsByGodId,
            Map<ResourceLocation, List<LoreKnowledgeHolder>> holdersByLoreId,
            Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> socialTagsBySourceGodId,
            List<CommonKnowledgeEntry> publicCommonKnowledge) {
    }

    public record Snapshot(Map<ResourceLocation, GodContentProfile> godsByContentId,
            Map<ResourceLocation, GodContentProfile> godsByGodId, Map<ResourceLocation, LoreEntry> loreById,
            Map<ResourceLocation, DialogueExample> examplesById, Map<ResourceLocation, SocialRelation> socialRelationsById,
            Map<ResourceLocation, QuestListDefinition> questListsById,
            Map<ResourceLocation, Map<ResourceLocation, Integer>> knowledgeLevelsByGodId,
            Map<ResourceLocation, List<LoreKnowledgeHolder>> holdersByLoreId,
            Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> socialTagsBySourceGodId,
            List<CommonKnowledgeEntry> publicCommonKnowledge,
            long generation) {
        private static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0);
        }

        public Snapshot {
            godsByContentId = Map.copyOf(godsByContentId);
            godsByGodId = Map.copyOf(godsByGodId);
            loreById = Map.copyOf(loreById);
            examplesById = Map.copyOf(examplesById);
            socialRelationsById = Map.copyOf(socialRelationsById);
            questListsById = Map.copyOf(questListsById);
            knowledgeLevelsByGodId = immutableNestedMap(knowledgeLevelsByGodId);
            holdersByLoreId = immutableListMap(holdersByLoreId);
            socialTagsBySourceGodId = immutableNestedListMap(socialTagsBySourceGodId);
            publicCommonKnowledge = List.copyOf(publicCommonKnowledge);
            if (publicCommonKnowledge.size() > CommonKnowledgeEntry.MAX_ENTRIES) {
                throw new IllegalArgumentException("Too many public common knowledge entries");
            }
        }

        /** Source/binary compatibility for consumers that construct the previous snapshot shape. */
        public Snapshot(Map<ResourceLocation, GodContentProfile> godsByContentId,
                Map<ResourceLocation, GodContentProfile> godsByGodId, Map<ResourceLocation, LoreEntry> loreById,
                Map<ResourceLocation, DialogueExample> examplesById, Map<ResourceLocation, SocialRelation> socialRelationsById,
                Map<ResourceLocation, QuestListDefinition> questListsById,
                Map<ResourceLocation, Map<ResourceLocation, Integer>> knowledgeLevelsByGodId,
                Map<ResourceLocation, List<LoreKnowledgeHolder>> holdersByLoreId,
                Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> socialTagsBySourceGodId,
                long generation) {
            this(godsByContentId, godsByGodId, loreById, examplesById, socialRelationsById, questListsById,
                    knowledgeLevelsByGodId, holdersByLoreId, socialTagsBySourceGodId, List.of(), generation);
        }
    }

    private static Map<ResourceLocation, Map<ResourceLocation, Integer>> immutableNestedMap(
            Map<ResourceLocation, Map<ResourceLocation, Integer>> source) {
        Map<ResourceLocation, Map<ResourceLocation, Integer>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        return Map.copyOf(copy);
    }

    private static Map<ResourceLocation, List<LoreKnowledgeHolder>> immutableListMap(
            Map<ResourceLocation, List<LoreKnowledgeHolder>> source) {
        Map<ResourceLocation, List<LoreKnowledgeHolder>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private static Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> immutableNestedListMap(
            Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> source) {
        Map<ResourceLocation, Map<ResourceLocation, List<SocialRelationTag>>> copy = new LinkedHashMap<>();
        source.forEach((sourceId, targets) -> {
            Map<ResourceLocation, List<SocialRelationTag>> targetCopy = new LinkedHashMap<>();
            targets.forEach((targetId, tags) -> targetCopy.put(targetId, List.copyOf(tags)));
            copy.put(sourceId, Map.copyOf(targetCopy));
        });
        return Map.copyOf(copy);
    }

    private record KnowledgeIndexes(Map<ResourceLocation, Map<ResourceLocation, Integer>> knowledgeLevelsByGodId,
            Map<ResourceLocation, List<LoreKnowledgeHolder>> holdersByLoreId) {
    }
}
