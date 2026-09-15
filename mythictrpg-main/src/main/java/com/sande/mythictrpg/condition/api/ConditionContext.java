package com.sande.mythictrpg.condition.api;

import com.sande.mythictrpg.data.player.PlayerMythQueryService;
import com.sande.mythictrpg.quest.structure.StructureEvaluationView;
import com.sande.mythictrpg.relation.GodRelationView;
import com.sande.mythictrpg.story.api.StoryStateView;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ConditionContext(
        WorldStateView world,
        PlayerMythQueryService players,
        ServerStateView server,
        GodDefinitionView gods,
        Optional<UUID> targetPlayerId,
        Optional<ConditionEnvironment> environment,
        Optional<ConditionEventContext> event,
        StructureEvaluationView structures,
        GodRelationView relations,
        StoryStateView story,
        Optional<StoryScopeKey> storyScope
) {
    public ConditionContext {
        Objects.requireNonNull(world);
        Objects.requireNonNull(players);
        Objects.requireNonNull(server);
        Objects.requireNonNull(gods);
        Objects.requireNonNull(targetPlayerId);
        Objects.requireNonNull(environment);
        Objects.requireNonNull(event);
        Objects.requireNonNull(structures);
        Objects.requireNonNull(relations);
        Objects.requireNonNull(story);
        Objects.requireNonNull(storyScope);
    }

    public ConditionContext(WorldStateView world, PlayerMythQueryService players, ServerStateView server,
            GodDefinitionView gods, Optional<UUID> targetPlayerId,
            Optional<ConditionEnvironment> environment, Optional<ConditionEventContext> event,
            StructureEvaluationView structures, GodRelationView relations) {
        this(world, players, server, gods, targetPlayerId, environment, event, structures, relations,
                StoryStateView.unavailable(), Optional.empty());
    }

    public ConditionContext(WorldStateView world, PlayerMythQueryService players, ServerStateView server,
            GodDefinitionView gods, Optional<UUID> targetPlayerId,
            Optional<ConditionEnvironment> environment, Optional<ConditionEventContext> event,
            StructureEvaluationView structures) {
        this(world, players, server, gods, targetPlayerId, environment, event, structures,
                GodRelationView.unavailable(), StoryStateView.unavailable(), Optional.empty());
    }

    public ConditionContext(WorldStateView world, PlayerMythQueryService players, ServerStateView server,
            GodDefinitionView gods, Optional<UUID> targetPlayerId,
            Optional<ConditionEnvironment> environment, Optional<ConditionEventContext> event) {
        this(world, players, server, gods, targetPlayerId, environment, event,
                StructureEvaluationView.unavailable(), GodRelationView.unavailable(),
                StoryStateView.unavailable(), Optional.empty());
    }
}
