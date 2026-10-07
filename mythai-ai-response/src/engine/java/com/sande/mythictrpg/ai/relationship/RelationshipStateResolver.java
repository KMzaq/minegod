package com.sande.mythictrpg.ai.relationship;

import java.util.Set;

/** Policy seam for deriving dialogue tags from the four persistent relationship metrics. */
@FunctionalInterface
public interface RelationshipStateResolver {
    Set<RelationshipTag> resolve(RelationshipMetrics relationship);
}
