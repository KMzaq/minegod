package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** One static, directional pair relationship between two God IDs. */
public record SocialRelation(ResourceLocation contentId, ResourceLocation participantA, ResourceLocation participantB,
        List<SocialRelationTag> aToBTags, List<SocialRelationTag> bToATags) {
    public SocialRelation {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(participantA, "participantA");
        Objects.requireNonNull(participantB, "participantB");
        if (participantA.equals(participantB)) {
            throw new IllegalArgumentException("A static social relation requires two different participants");
        }
        aToBTags = immutableTags(aToBTags);
        bToATags = immutableTags(bToATags);
        if (aToBTags.isEmpty() && bToATags.isEmpty()) {
            throw new IllegalArgumentException("A static social relation needs at least one directional tag");
        }
    }

    private static List<SocialRelationTag> immutableTags(List<SocialRelationTag> tags) {
        return tags == null ? List.of() : tags.stream().filter(Objects::nonNull).distinct().toList();
    }
}
