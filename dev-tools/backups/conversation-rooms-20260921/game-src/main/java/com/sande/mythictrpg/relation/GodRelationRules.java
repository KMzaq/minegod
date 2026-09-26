package com.sande.mythictrpg.relation;

import java.util.Set;

final class GodRelationRules {
    private GodRelationRules() {
    }

    static void requireCompatible(Set<GodRelationTag> tags) {
        incompatible(tags, GodRelationTag.ALLIED, GodRelationTag.HOSTILE);
        incompatible(tags, GodRelationTag.ALLIED, GodRelationTag.AT_WAR);
        incompatible(tags, GodRelationTag.TRUCE, GodRelationTag.AT_WAR);
        if (tags.contains(GodRelationTag.INDIFFERENT) && tags.stream().anyMatch(tag -> tag != GodRelationTag.INDIFFERENT
                && tag != GodRelationTag.WATCHFUL)) {
            throw new IllegalArgumentException("INDIFFERENT cannot be combined with an active emotional relation tag");
        }
    }

    private static void incompatible(Set<GodRelationTag> tags, GodRelationTag first, GodRelationTag second) {
        if (tags.contains(first) && tags.contains(second)) {
            throw new IllegalArgumentException(first + " cannot be combined with " + second);
        }
    }
}

