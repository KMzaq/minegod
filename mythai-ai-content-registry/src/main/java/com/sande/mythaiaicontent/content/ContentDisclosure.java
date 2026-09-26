package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.Set;

/** Author permission to include content in a prompt for the WHOLE current audience, not knowledge ownership. */
public record ContentDisclosure(Mode mode, Set<ResourceLocation> allowedGodIds) {
    public enum Mode { PUBLIC, PRIVATE_ROOM, NEVER }
    public static final ContentDisclosure PUBLIC = new ContentDisclosure(Mode.PUBLIC, Set.of());
    public static final ContentDisclosure NEVER = new ContentDisclosure(Mode.NEVER, Set.of());

    public ContentDisclosure {
        Objects.requireNonNull(mode, "disclosure mode");
        allowedGodIds = Set.copyOf(Objects.requireNonNull(allowedGodIds, "allowedGodIds"));
    }

    public boolean permits(ContentAudience audience) {
        Objects.requireNonNull(audience, "audience");
        return mode != Mode.NEVER && (mode != Mode.PRIVATE_ROOM || !audience.publicRoom())
                && (allowedGodIds.isEmpty() || audience.godIds().stream()
                .filter(god -> !god.equals(audience.speakerGodId())).allMatch(allowedGodIds::contains));
    }
}
