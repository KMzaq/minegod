package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class InteractionStartReasons {
    public static final ResourceLocation NO_PLAN = id("interaction_no_plan");
    public static final ResourceLocation PREPARER_FAILED = id("content_preparer_failed");
    public static final ResourceLocation SIGNAL_PLAN_MISMATCH = id("signal_plan_mismatch");
    public static final ResourceLocation STALE_GOD_DEFINITIONS = id("stale_god_definitions");
    public static final ResourceLocation STALE_INTERACTION_RULES = id("stale_interaction_rules");
    public static final ResourceLocation AUDIENCE_OFFLINE = id("interaction_audience_offline");
    public static final ResourceLocation AUDIENCE_INACTIVE = id("interaction_audience_inactive");
    public static final ResourceLocation UNKNOWN_PARTICIPANT = id("unknown_interaction_participant");
    public static final ResourceLocation GOD_LOCKED = id("interaction_god_locked");
    public static final ResourceLocation APPEARANCE_REJECTED = id("interaction_appearance_rejected");
    public static final ResourceLocation EXPLICIT_POLICY_REJECTED = id("explicit_policy_rejected");
    public static final ResourceLocation COOLDOWN_BLOCKED = id("interaction_cooldown_blocked");
    public static final ResourceLocation RUNTIME_BUSY = id("interaction_runtime_busy");
    public static final ResourceLocation CONTEXT_UNAVAILABLE = id("interaction_context_unavailable");
    public static final ResourceLocation COMMIT_REJECTED = id("interaction_commit_rejected");

    private InteractionStartReasons() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
