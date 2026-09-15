package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

/** Stable IDs for game-owned actions that an AI response may propose. */
public final class AiActionTypes {
    public static final ResourceLocation QUEST_OFFER = id("quest_offer");
    public static final ResourceLocation ITEM_REQUEST = id("item_request");
    public static final ResourceLocation REWARD_PROPOSAL = id("reward_proposal");
    public static final ResourceLocation RELATIONSHIP_CHANGE = id("relationship_change");
    public static final ResourceLocation BLESSING_OFFER = id("blessing_offer");
    public static final ResourceLocation NPC_VISIT_REQUEST = id("npc_visit_request");
    public static final ResourceLocation WORLD_INTERACTION = id("world_interaction");
    public static final ResourceLocation PLAYER_DAMAGE = id("player_damage");
    public static final ResourceLocation GENERATED_QUEST_OFFER = id("generated_quest_offer");
    public static final ResourceLocation STRUCTURE_EVALUATION_REQUEST = id("structure_evaluation_request");
    public static final ResourceLocation GOD_RELATION_TRANSITION = id("god_relation_transition");
    public static final ResourceLocation STORY_EVENT_HOOK = id("story_event_hook");

    private AiActionTypes() {
    }

    public static ResourceLocation fromProtocolName(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException("AI action type must not be blank");
        }
        String normalized = rawType.trim().toLowerCase(java.util.Locale.ROOT);
        ResourceLocation parsed = ResourceLocation.tryParse(normalized.indexOf(':') >= 0
                ? normalized : MythicTrpg.MOD_ID + ":" + normalized);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid AI action type: " + rawType);
        }
        return parsed;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
