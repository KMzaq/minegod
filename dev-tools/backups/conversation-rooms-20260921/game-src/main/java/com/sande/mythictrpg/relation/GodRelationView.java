package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public interface GodRelationView {
    boolean isReady();

    Optional<GodRelationSnapshot> find(ResourceLocation sourceGodId, ResourceLocation targetGodId);

    static GodRelationView unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements GodRelationView {
        INSTANCE;

        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public Optional<GodRelationSnapshot> find(ResourceLocation sourceGodId, ResourceLocation targetGodId) {
            return Optional.empty();
        }
    }
}

