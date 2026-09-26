package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure projection: ownership is profile.loreKnowledge; disclosure is authored and evaluated for every listener. */
public final class ContentAudienceResolver {
    private ContentAudienceResolver() { }

    public static Optional<AudienceGodContent> resolve(AiContentRegistry.Snapshot snapshot,
            RelationshipTier tier, ContentAudience audience) {
        return resolve(snapshot, tier, audience, audience.godIds());
    }

    /** Historical references retain their original relation targets, while EVERY disclosure rule uses today's audience. */
    public static Optional<AudienceGodContent> resolve(AiContentRegistry.Snapshot snapshot,
            RelationshipTier tier, ContentAudience audience, List<ResourceLocation> relationTargets) {
        relationTargets = List.copyOf(relationTargets);
        if (relationTargets.isEmpty() || relationTargets.size() > 16
                || relationTargets.stream().distinct().count() != relationTargets.size())
            throw new IllegalArgumentException("Invalid static relation reference scope");
        var profile = snapshot.godsByGodId().get(audience.speakerGodId());
        if (profile == null) return Optional.empty();
        var safeProfile = new AudienceGodContent.Profile(
                text(profile, audience, "displayName", profile.displayName()),
                text(profile, audience, "identity", profile.identity()),
                text(profile, audience, "description", profile.description()),
                texts(profile, audience, "personality", profile.personality()),
                texts(profile, audience, "values", profile.values()),
                texts(profile, audience, "speechStyles", profile.speechStyles()),
                texts(profile, audience, "dialogueGuidelines", profile.dialogueGuidelines()),
                permits(profile, audience, "situationGuidelines") ? profile.situationGuidelines() : Map.of(),
                permits(profile, audience, "repetitionGuidelines") ? profile.repetitionGuidelines() : Map.of(),
                texts(profile, audience, "restrictions", profile.restrictions()),
                texts(profile, audience, "characterTags", profile.characterTags()));
        var lore = new ArrayList<ResolvedLoreKnowledge>();
        for (var knowledge : profile.loreKnowledge()) {
            var definition = snapshot.loreById().get(knowledge.loreId());
            if (definition != null) loreFor(definition, knowledge.level(), audience).ifPresent(lore::add);
        }
        lore.sort(Comparator.comparing(value -> value.id().toString()));
        var examples = new LinkedHashMap<ResourceLocation, DialogueExample>();
        if (permits(profile, audience, "examples")) {
            snapshot.examplesById().values().stream().sorted(Comparator.comparing(value -> value.id().toString()))
                    .filter(value -> value.availableTo(profile.godId()) && value.disclosure().permits(audience))
                    .forEach(value -> examples.put(value.id(), value));
        }
        var tags = new ArrayList<String>();
        if (permits(profile, audience, "socialRelationTags")) {
            for (var god : relationTargets) {
                if (god.equals(profile.godId())) continue;
                snapshot.socialTagsBySourceGodId().getOrDefault(profile.godId(), Map.of())
                        .getOrDefault(god, List.of()).forEach(tag -> tags.add(tag.tag()));
            }
        }
        return Optional.of(new AudienceGodContent(safeProfile, lore, List.copyOf(examples.values()),
                texts(profile, audience, "relationshipGuidelines", profile.relationshipGuidelines()
                        .getOrDefault(tier, List.of())), tags.stream().distinct().sorted().toList(), snapshot.generation()));
    }

    /** Cumulative tiers stop at the first denied tier; a later tier must not silently expose its hidden prerequisites. */
    public static Optional<ResolvedLoreKnowledge> loreFor(LoreEntry definition, int knownLevel, ContentAudience audience) {
        if (knownLevel < 1) return Optional.empty();
        var visible = new ArrayList<LoreKnowledgeLevel>();
        for (var level : definition.levelsThrough(knownLevel)) {
            var disclosure = level.disclosure() != null ? level.disclosure()
                    : definition.secrecy() == LoreSecrecy.PUBLIC ? ContentDisclosure.PUBLIC : ContentDisclosure.NEVER;
            if (!disclosure.permits(audience)) break;
            // Holder identities/levels are independently sensitive; revealKnowledgeHolders is ownership, not disclosure.
            visible.add(new LoreKnowledgeLevel(level.level(), level.content(), false));
        }
        if (visible.isEmpty()) return Optional.empty();
        return Optional.of(new ResolvedLoreKnowledge(definition.id(), definition.title(), definition.secrecy(),
                definition.keywords(), visible.getLast().level(), visible, List.of()));
    }

    private static boolean permits(GodContentProfile profile, ContentAudience audience, String field) {
        return profile.fieldDisclosure().getOrDefault(field, ContentDisclosure.PUBLIC).permits(audience);
    }
    private static String text(GodContentProfile profile, ContentAudience audience, String field, String value) {
        return permits(profile, audience, field) ? value : "";
    }
    private static List<String> texts(GodContentProfile profile, ContentAudience audience, String field, List<String> values) {
        return permits(profile, audience, field) ? values : List.of();
    }
}
