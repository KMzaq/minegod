package com.sande.mythictrpg.story.definition;

import com.sande.mythictrpg.story.definition.StoryDefinitions.ActorDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.CoverStoryDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.DisclosurePolicy;
import com.sande.mythictrpg.story.definition.StoryDefinitions.EventDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.FactDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.HookDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.LocationDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.PresentationDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record StoryDefinitionSnapshot(
        Map<ResourceLocation, ActorDefinition> actors,
        Map<ResourceLocation, LocationDefinition> locations,
        Map<ResourceLocation, FactDefinition> facts,
        Map<ResourceLocation, CoverStoryDefinition> coverStories,
        Map<ResourceLocation, DisclosurePolicy> disclosurePolicies,
        Map<ResourceLocation, EventDefinition> events,
        Map<ResourceLocation, HookDefinition> hooks,
        Map<ResourceLocation, PresentationDefinition> presentations,
        Map<SignalKey, List<EventDefinition>> eventsBySignal,
        long generation) {

    public StoryDefinitionSnapshot {
        actors = Map.copyOf(actors);
        locations = Map.copyOf(locations);
        facts = Map.copyOf(facts);
        coverStories = Map.copyOf(coverStories);
        disclosurePolicies = Map.copyOf(disclosurePolicies);
        events = Map.copyOf(events);
        hooks = Map.copyOf(hooks);
        presentations = Map.copyOf(presentations);
        Map<SignalKey, List<EventDefinition>> immutableIndex = new LinkedHashMap<>();
        eventsBySignal.forEach((key, value) -> immutableIndex.put(key, List.copyOf(value)));
        eventsBySignal = Map.copyOf(immutableIndex);
    }

    public static StoryDefinitionSnapshot empty() {
        return new StoryDefinitionSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), 0);
    }

    public List<EventDefinition> candidates(ResourceLocation signalType, Optional<ResourceLocation> subjectId) {
        List<EventDefinition> result = new ArrayList<>();
        result.addAll(eventsBySignal.getOrDefault(new SignalKey(signalType, subjectId), List.of()));
        if (subjectId.isPresent()) {
            result.addAll(eventsBySignal.getOrDefault(new SignalKey(signalType, Optional.empty()), List.of()));
        }
        return result.stream().distinct().sorted(java.util.Comparator.comparing(EventDefinition::id)).toList();
    }

    public record SignalKey(ResourceLocation signalType, Optional<ResourceLocation> subjectId) {
        public SignalKey {
            java.util.Objects.requireNonNull(signalType, "signalType");
            subjectId = java.util.Objects.requireNonNull(subjectId, "subjectId");
        }
    }
}
