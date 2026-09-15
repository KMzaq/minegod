package com.sande.mythictrpg.ai.relationship;

import java.util.EnumSet;
import java.util.Set;

/** Conservative default policy. Games may replace this policy without changing stored relationship data. */
public final class DefaultRelationshipStateResolver implements RelationshipStateResolver {
    @Override
    public Set<RelationshipTag> resolve(RelationshipMetrics relationship) {
        EnumSet<RelationshipTag> tags = EnumSet.noneOf(RelationshipTag.class);
        if (relationship.affinity() >= 70 && relationship.trust() >= 60) {
            tags.add(RelationshipTag.R_CLOSE);
        } else if (relationship.affinity() >= 30) {
            tags.add(RelationshipTag.R_FRIENDLY);
        }
        if (relationship.caution() >= 60) {
            tags.add(RelationshipTag.R_WARY);
        }
        if (relationship.trust() <= -40) {
            tags.add(RelationshipTag.R_DISTRUST);
        }
        if (relationship.affinity() <= -25 && relationship.respect() >= 50) {
            tags.add(RelationshipTag.R_RIVAL);
        }
        if (relationship.affinity() <= -60 && relationship.trust() <= -40) {
            tags.add(RelationshipTag.R_HOSTILE);
        }
        if (relationship.affinity() <= -80 && relationship.trust() <= -60 && relationship.respect() <= 0
                && relationship.caution() >= 70) {
            tags.add(RelationshipTag.R_NEMESIS);
        }
        if (tags.isEmpty()) {
            tags.add(relationship.affinity() == 0 && relationship.trust() == 0 && relationship.respect() == 0
                    && relationship.caution() == 0 ? RelationshipTag.R_STRANGER : RelationshipTag.R_ACQUAINTANCE);
        }
        return Set.copyOf(tags);
    }
}
