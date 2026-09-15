package com.sande.mythictrpg.ai.agent;

import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.tag.ExampleStyleTag;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration-driven NPC agent. It declares narrative identity and boundaries only; it has no game mutation API.
 */
public record NpcAgent(NpcIdentity identity, ResourceLocation personaId, Map<String, Double> personality,
        List<String> values, List<String> likes, List<String> dislikes, Set<ExampleStyleTag> speechStyles,
        List<ResourceLocation> voiceStyleIds,
        KnowledgePermissions knowledgePermissions, CurrentEmotion globalEmotion, List<String> capabilities,
        List<String> restrictions, NpcConversationPolicy conversationPolicy) {
    public NpcAgent {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(personaId, "personaId");
        personality = immutablePersonality(personality);
        values = immutableLabels(values, "values");
        likes = immutableLabels(likes, "likes");
        dislikes = immutableLabels(dislikes, "dislikes");
        speechStyles = immutableStyles(speechStyles);
        voiceStyleIds = immutableVoiceStyleIds(voiceStyleIds, speechStyles);
        knowledgePermissions = knowledgePermissions == null ? KnowledgePermissions.none() : knowledgePermissions;
        globalEmotion = globalEmotion == null ? CurrentEmotion.calm() : globalEmotion;
        capabilities = immutableLabels(capabilities, "capabilities");
        restrictions = immutableLabels(restrictions, "restrictions");
        conversationPolicy = conversationPolicy == null ? NpcConversationPolicy.singlePhysicalPresence()
                : conversationPolicy;
    }

    /** Compatibility constructor for existing agent data that did not declare a conversation policy. */
    public NpcAgent(NpcIdentity identity, ResourceLocation personaId, Map<String, Double> personality,
            List<String> values, List<String> likes, List<String> dislikes, Set<ExampleStyleTag> speechStyles,
            KnowledgePermissions knowledgePermissions, CurrentEmotion globalEmotion, List<String> capabilities,
            List<String> restrictions) {
        this(identity, personaId, personality, values, likes, dislikes, speechStyles, List.of(), knowledgePermissions,
                globalEmotion, capabilities, restrictions, NpcConversationPolicy.singlePhysicalPresence());
    }

    /** Compatibility constructor for callers that already define conversation policy but not explicit voice IDs. */
    public NpcAgent(NpcIdentity identity, ResourceLocation personaId, Map<String, Double> personality,
            List<String> values, List<String> likes, List<String> dislikes, Set<ExampleStyleTag> speechStyles,
            KnowledgePermissions knowledgePermissions, CurrentEmotion globalEmotion, List<String> capabilities,
            List<String> restrictions, NpcConversationPolicy conversationPolicy) {
        this(identity, personaId, personality, values, likes, dislikes, speechStyles, List.of(), knowledgePermissions,
                globalEmotion, capabilities, restrictions, conversationPolicy);
    }

    private static Map<String, Double> immutablePersonality(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            String key = normalizedLabel(entry.getKey(), "personality key");
            Double score = entry.getValue();
            if (score == null || !Double.isFinite(score) || score < 0.0D || score > 1.0D) {
                throw new IllegalArgumentException("Personality '" + key + "' must be between 0.0 and 1.0: " + score);
            }
            checked.put(key, score);
        }
        return Map.copyOf(checked);
    }

    private static List<String> immutableLabels(List<String> labels, String name) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        for (String label : labels) {
            checked.add(normalizedLabel(label, name));
        }
        return List.copyOf(checked);
    }

    private static Set<ExampleStyleTag> immutableStyles(Set<ExampleStyleTag> styles) {
        if (styles == null || styles.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(styles);
    }

    private static List<ResourceLocation> immutableVoiceStyleIds(List<ResourceLocation> ids,
            Set<ExampleStyleTag> legacyStyles) {
        LinkedHashSet<ResourceLocation> checked = new LinkedHashSet<>();
        if (ids != null) {
            for (ResourceLocation id : ids) {
                checked.add(Objects.requireNonNull(id, "voice style ID"));
            }
        }
        if (checked.isEmpty()) {
            legacyStyles.stream().sorted().map(NpcAgent::legacyVoiceStyleId).forEach(checked::add);
        }
        return List.copyOf(checked);
    }

    private static ResourceLocation legacyVoiceStyleId(ExampleStyleTag style) {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg",
                "voice/" + style.name().substring(2).toLowerCase(Locale.ROOT));
    }

    private static String normalizedLabel(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not contain a blank value");
        }
        return value.trim();
    }
}
