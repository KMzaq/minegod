package com.sande.mythictrpg.condition.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.api.ConditionScope;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.function.Function;

public final class ConditionType<T extends ConditionNode> {
    private final ResourceLocation id;
    private final Class<T> nodeClass;
    private final Set<ConditionScope> allowedScopes;
    private final JsonDecoder<T> codec;
    private final Evaluator<T> evaluator;
    private final Function<T, Set<ConditionDependency>> dependencies;

    public ConditionType(ResourceLocation id, Class<T> nodeClass, Set<ConditionScope> allowedScopes,
            JsonDecoder<T> codec, Evaluator<T> evaluator,
            Function<T, Set<ConditionDependency>> dependencies) {
        this.id = id;
        this.nodeClass = nodeClass;
        this.allowedScopes = Set.copyOf(allowedScopes);
        this.codec = codec;
        this.evaluator = evaluator;
        this.dependencies = dependencies;
    }

    public ResourceLocation id() {
        return id;
    }

    public Set<ConditionScope> allowedScopes() {
        return allowedScopes;
    }

    public T decode(JsonObject json, Function<JsonElement, ConditionNode> childParser) {
        return codec.decode(json, childParser);
    }

    public ConditionResult evaluate(ConditionNode node, ConditionContext context, ChildEvaluator children) {
        return evaluator.evaluate(cast(node), context, children);
    }

    public Set<ConditionDependency> dependencies(ConditionNode node) {
        return dependencies.apply(cast(node));
    }

    private T cast(ConditionNode node) {
        if (!nodeClass.isInstance(node)) {
            throw new IllegalStateException("Condition node for " + id + " has type " + node.getClass().getName());
        }
        return nodeClass.cast(node);
    }

    @FunctionalInterface
    public interface JsonDecoder<T extends ConditionNode> {
        T decode(JsonObject json, Function<JsonElement, ConditionNode> childParser);
    }

    @FunctionalInterface
    public interface Evaluator<T extends ConditionNode> {
        ConditionResult evaluate(T node, ConditionContext context, ChildEvaluator children);
    }

    @FunctionalInterface
    public interface ChildEvaluator {
        ConditionResult evaluate(ConditionNode child, ConditionContext context);
    }
}
