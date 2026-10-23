package com.sande.mythictrpg.content;

import com.sande.mythictrpg.story.state.StoryRuntimeModels.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Reservations are stored on the existing event, so reloads/restarts cannot silently release them. */
public final class StoryAdmission {
    private StoryAdmission() { }
    public static Set<ResourceLocation> resources(ResourceLocation event, Optional<UUID> player) {
        var policy = ContentDefinitions.INSTANCE.snapshot().admissions().get(event);
        if (policy == null) return Set.of();
        var keys = new TreeSet<ResourceLocation>(policy.resources());
        if (policy.playerExclusive()) keys.add(ResourceLocation.fromNamespaceAndPath("mythictrpg", "content_player/"
                + player.orElseThrow(() -> new IllegalArgumentException("Admission requires a player"))));
        return Set.copyOf(keys);
    }
    public static boolean available(Set<ResourceLocation> requested, Collection<EventInstance> existing) {
        if (requested.isEmpty()) return true;
        return existing.stream().filter(event -> event.status() != EventStatus.RESOLVED && event.status() != EventStatus.CANCELLED)
                .noneMatch(event -> !Collections.disjoint(requested, event.reservedResources()));
    }
}
