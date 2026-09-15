package com.sande.mythictrpg.story.signal;

import net.minecraft.resources.ResourceLocation;

public final class StorySignalTypes {
    public static final ResourceLocation GAMEPLAY_OBSERVED = id("gameplay_observed");
    public static final ResourceLocation QUEST_COMPLETED = id("quest_completed");
    public static final ResourceLocation GOD_UNLOCKED = id("god_unlocked");
    public static final ResourceLocation GOD_IDENTIFIED = id("god_identified");
    public static final ResourceLocation RELATION_TRANSITIONED = id("relation_transitioned");
    public static final ResourceLocation FACT_CHANGED = id("fact_changed");
    public static final ResourceLocation ACTOR_STATE_CHANGED = id("actor_state_changed");
    public static final ResourceLocation EVENT_RESOLVED = id("event_resolved");
    public static final ResourceLocation SCHEDULED_DUE = id("scheduled_due");
    public static final ResourceLocation STORY_HOOK_ACCEPTED = id("story_hook_accepted");
    public static final ResourceLocation ADMIN_TRIGGERED = id("admin_triggered");

    private StorySignalTypes() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg", path);
    }
}
