package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Static quest authoring data. MythicTRPG remains responsible for validation, progress, completion, and rewards. */
public record QuestDefinition(ResourceLocation questId, String title, String content,
        List<QuestContentNode> objectives, List<QuestContentNode> rewards,
        List<QuestContentNode> acceptanceConditions, int progressOnClear, ContentDisclosure disclosure) {
    public QuestDefinition(ResourceLocation questId, String title, String content,
            List<QuestContentNode> objectives, List<QuestContentNode> rewards,
            List<QuestContentNode> acceptanceConditions, int progressOnClear) {
        this(questId, title, content, objectives, rewards, acceptanceConditions, progressOnClear, ContentDisclosure.PUBLIC);
    }

    public QuestDefinition {
        Objects.requireNonNull(questId, "questId");
        title = required(title, "title");
        content = required(content, "content");
        objectives = immutableNodes(objectives, "objectives");
        rewards = immutableNodes(rewards, "rewards");
        acceptanceConditions = acceptanceConditions == null ? List.of() : List.copyOf(acceptanceConditions);
        acceptanceConditions.forEach(node -> Objects.requireNonNull(node, "acceptanceConditions contains null"));
        disclosure = disclosure == null ? ContentDisclosure.PUBLIC : disclosure;
        if (progressOnClear < 0 || progressOnClear > 100) {
            throw new IllegalArgumentException("progressOnClear must be between 0 and 100");
        }
    }

    /** Bounded author-written information suitable for an AI proposal prompt; never implies availability or execution. */
    public String promptSummary() {
        return "questId=" + questId + ", title=" + title + ", content=" + content
                + ", objectives=" + objectives.stream().map(QuestContentNode::description).toList()
                + ", rewards=" + rewards.stream().map(QuestContentNode::description).toList()
                + ", progressOnClear=" + progressOnClear;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Quest " + field + " must not be blank");
        }
        return value.trim();
    }

    private static List<QuestContentNode> immutableNodes(List<QuestContentNode> nodes, String field) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("Quest " + field + " must contain at least one entry");
        }
        nodes.forEach(node -> Objects.requireNonNull(node, "Quest " + field + " contains null"));
        return List.copyOf(nodes);
    }
}
