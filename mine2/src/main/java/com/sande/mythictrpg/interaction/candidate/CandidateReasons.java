package com.sande.mythictrpg.interaction.candidate;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class CandidateReasons {
    public static final ResourceLocation CONTEXT_UNAVAILABLE = id("context_unavailable");
    public static final ResourceLocation NO_SIGNAL_BINDING = id("no_signal_binding");
    public static final ResourceLocation NO_ELIGIBLE_CANDIDATE = id("no_eligible_candidate");
    public static final ResourceLocation DEFINITION_UNAVAILABLE = id("definition_unavailable");
    public static final ResourceLocation PLAYER_NOT_ACTIVE = id("player_not_active");
    public static final ResourceLocation NOT_EFFECTIVELY_UNLOCKED = id("not_effectively_unlocked");
    public static final ResourceLocation EXPLICIT_ONLY = id("explicit_only");
    public static final ResourceLocation APPEARANCE_NO_MATCH = id("appearance_no_match");
    public static final ResourceLocation APPEARANCE_UNKNOWN = id("appearance_unknown");
    public static final ResourceLocation EXPLICIT_TARGET = id("explicit_target");
    public static final ResourceLocation UNSUPPORTED_EXPLICIT_POLICY = id("unsupported_explicit_policy");
    public static final ResourceLocation RUNTIME_RESTRICTED = id("runtime_restricted");
    public static final ResourceLocation DIRECTOR_NO_CANDIDATE = id("director_no_candidate");
    public static final ResourceLocation STALE_SELECTION = id("stale_selection");

    private CandidateReasons() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
