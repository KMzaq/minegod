package com.sande.mythictrpg.condition.registry;

import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.story.condition.StoryConditionTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ConditionTypeRegistry {
    public static final ConditionTypeRegistry INSTANCE = new ConditionTypeRegistry();

    private final Map<ResourceLocation, ConditionType<? extends ConditionNode>> types = new ConcurrentHashMap<>();

    private ConditionTypeRegistry() {
        BuiltinConditionTypes.register(this);
        StoryConditionTypes.register(this);
    }

    public void register(ConditionType<? extends ConditionNode> type) {
        ConditionType<?> previous = types.putIfAbsent(type.id(), type);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate condition type: " + type.id());
        }
    }

    public Optional<ConditionType<? extends ConditionNode>> find(ResourceLocation id) {
        return Optional.ofNullable(types.get(id));
    }
}
