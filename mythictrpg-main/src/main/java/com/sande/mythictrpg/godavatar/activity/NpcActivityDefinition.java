package com.sande.mythictrpg.godavatar.activity;

import net.minecraft.resources.ResourceLocation;
import java.util.*;

public record NpcActivityDefinition(ResourceLocation id, ActivityKind kind, Mode mode,
        Set<String> siteTags, int durationTicks, Map<String, String> parameters) {
    public enum Mode { DECORATIVE, REAL }
    public NpcActivityDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(mode);
        siteTags = Set.copyOf(siteTags); parameters = Map.copyOf(parameters);
        if (siteTags.isEmpty() || siteTags.size() > 16 || siteTags.stream().anyMatch(t -> !t.matches("[a-z_]{1,40}"))
                || durationTicks < 20 || durationTicks > 24000 || parameters.size() > 16
                || parameters.entrySet().stream().anyMatch(e -> !e.getKey().matches("[a-z_]{1,40}") || e.getValue().length() > 2048))
            throw new IllegalArgumentException("Invalid activity bounds");
    }
}
