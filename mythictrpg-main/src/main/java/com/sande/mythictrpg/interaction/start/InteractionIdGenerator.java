package com.sande.mythictrpg.interaction.start;

import java.util.UUID;

@FunctionalInterface
public interface InteractionIdGenerator {
    InteractionIdGenerator RANDOM = UUID::randomUUID;

    UUID nextId();
}
