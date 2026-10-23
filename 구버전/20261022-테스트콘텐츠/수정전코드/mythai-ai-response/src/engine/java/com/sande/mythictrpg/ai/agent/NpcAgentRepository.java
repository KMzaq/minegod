package com.sande.mythictrpg.ai.agent;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.data.BundledJsonData;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.tag.ExampleStyleTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Server-editable NPC agent definitions. Adding an NPC is data-only as long as its persona/content data exists. */
public final class NpcAgentRepository {
    private static final Gson GSON = new Gson();
    private static final String BUNDLED_RESOURCE = "data/mythictrpg/ai/npc-agents.json";
    public static final NpcAgentRepository INSTANCE = new NpcAgentRepository();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/npc-agents.json");
    private volatile Map<ResourceLocation, NpcAgent> agents = Map.of();

    private NpcAgentRepository() {
    }

    public synchronized void load() {
        try {
            BundledJsonData.ensureServerCopy(file, BUNDLED_RESOURCE);
            try (Reader reader = Files.newBufferedReader(file)) {
                agents = parse(GSON.fromJson(reader, RawRepository.class));
            }
        } catch (Exception exception) {
            MythicTrpg.LOGGER.error("Could not load NPC agents {}", file, exception);
            agents = Map.of();
        }
        MythicTrpg.LOGGER.info("Loaded {} NPC agent(s) from {}.", agents.size(), file);
    }

    public Optional<NpcAgent> find(ResourceLocation npcId) {
        return Optional.ofNullable(agents.get(npcId));
    }

    public Map<ResourceLocation, NpcAgent> all() {
        return agents;
    }

    public Path file() {
        return file;
    }

    private static Map<ResourceLocation, NpcAgent> parse(RawRepository raw) {
        if (raw == null || raw.agents == null) {
            throw new IllegalArgumentException("NPC agent data requires agents");
        }
        Map<ResourceLocation, NpcAgent> parsed = new LinkedHashMap<>();
        raw.agents.forEach((rawId, entry) -> {
            try {
                if (entry == null) {
                    throw new IllegalArgumentException("Agent entry is null");
                }
                ResourceLocation id = ResourceLocation.parse(rawId);
                ResourceLocation persona = ResourceLocation.parse(required(entry.persona, "persona"));
                NpcAgent agent = new NpcAgent(new NpcIdentity(id, required(entry.name, "name")), persona,
                        entry.personality, entry.values, entry.likes, entry.dislikes, styles(entry.speechStyles),
                        voiceStyleIds(entry.voiceStyleIds),
                        new KnowledgePermissions(asSet(entry.knowledgePermissions == null ? null
                                : entry.knowledgePermissions.allowedScopes), asSet(entry.knowledgePermissions == null ? null
                                : entry.knowledgePermissions.deniedScopes)),
                        new CurrentEmotion(entry.globalEmotion == null ? Map.of() : entry.globalEmotion),
                        entry.capabilities, entry.restrictions, conversationPolicy(entry.conversation));
                parsed.put(id, agent);
            } catch (Exception exception) {
                MythicTrpg.LOGGER.warn("Ignored invalid NPC agent '{}': {}", rawId, exception.getMessage());
            }
        });
        return Map.copyOf(parsed);
    }

    private static Set<ExampleStyleTag> styles(List<String> rawStyles) {
        if (rawStyles == null || rawStyles.isEmpty()) {
            return Set.of();
        }
        EnumSet<ExampleStyleTag> styles = EnumSet.noneOf(ExampleStyleTag.class);
        for (String rawStyle : rawStyles) {
            styles.add(ExampleStyleTag.valueOf(required(rawStyle, "speech style")));
        }
        return styles;
    }

    private static List<ResourceLocation> voiceStyleIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        return rawIds.stream().map(rawId -> ResourceLocation.parse(required(rawId, "voice style ID"))).toList();
    }

    private static Set<String> asSet(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static NpcConversationPolicy conversationPolicy(RawConversationPolicy raw) {
        if (raw == null) {
            return NpcConversationPolicy.singlePhysicalPresence();
        }
        return new NpcConversationPolicy(raw.allowSimultaneousSessions, raw.maxSessions == null ? 1 : raw.maxSessions);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("NPC agent " + name + " must not be blank");
        }
        return value.trim();
    }

    private static final class RawRepository {
        private int schemaVersion;
        private Map<String, RawAgent> agents;
    }

    private static final class RawAgent {
        private String name;
        private String persona;
        private Map<String, Double> personality;
        private List<String> values;
        private List<String> likes;
        private List<String> dislikes;
        private List<String> speechStyles;
        private List<String> voiceStyleIds;
        private RawKnowledgePermissions knowledgePermissions;
        private Map<String, Integer> globalEmotion;
        private List<String> capabilities;
        private List<String> restrictions;
        private RawConversationPolicy conversation;
    }

    private static final class RawConversationPolicy {
        private boolean allowSimultaneousSessions;
        private Integer maxSessions;
    }

    private static final class RawKnowledgePermissions {
        private List<String> allowedScopes;
        private List<String> deniedScopes;
    }
}
