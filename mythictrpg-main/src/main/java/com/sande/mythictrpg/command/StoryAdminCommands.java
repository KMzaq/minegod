package com.sande.mythictrpg.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class StoryAdminCommands {
    private StoryAdminCommands() {}

    public static LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("story")
                .then(Commands.literal("health").executes(StoryAdminCommands::health))
                .then(Commands.literal("event")
                        .then(Commands.literal("list").executes(StoryAdminCommands::listEvents))
                        .then(Commands.literal("get").then(eventArgument()
                                .executes(StoryAdminCommands::getEvent)))
                        .then(Commands.literal("trigger").then(eventArgument()
                                .executes(StoryAdminCommands::triggerEvent)))
                        .then(Commands.literal("trigger-for").then(eventArgument()
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(StoryAdminCommands::triggerEventFor)))))
                .then(Commands.literal("fact").then(Commands.literal("get")
                        .then(factArgument().executes(StoryAdminCommands::getServerFact))))
                .then(Commands.literal("actor").then(Commands.literal("get")
                        .then(actorArgument().executes(StoryAdminCommands::getActor))))
                .then(Commands.literal("knowledge").then(Commands.literal("get-player")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(factArgument().executes(StoryAdminCommands::getPlayerKnowledge)))))
                .then(Commands.literal("schedules").executes(StoryAdminCommands::listSchedules))
                .then(Commands.literal("recovery").executes(StoryAdminCommands::listRecovery))
                .build();
    }

    private static int health(CommandContext<CommandSourceStack> context) {
        StoryRuntimeState state = StoryRuntimeState.get(context.getSource().getServer());
        var definitions = StoryDefinitionManager.INSTANCE.snapshot();
        context.getSource().sendSuccess(() -> Component.literal("Story definitions: actors="
                + definitions.actors().size() + ", facts=" + definitions.facts().size()
                + ", events=" + definitions.events().size() + ", hooks=" + definitions.hooks().size()
                + "; state=" + (state.isReady() ? "READY" : "READ_ONLY")
                + ", revision=" + state.globalRevision()), false);
        state.rejectionReason().ifPresent(reason -> context.getSource().sendFailure(
                Component.literal("Story state rejection: " + reason)));
        return state.isReady() ? 1 : 0;
    }

    private static int listEvents(CommandContext<CommandSourceStack> context) {
        var ids = StoryDefinitionManager.INSTANCE.snapshot().events().keySet().stream().sorted().toList();
        context.getSource().sendSuccess(() -> Component.literal("Story events (" + ids.size() + "): " + ids), false);
        return ids.size();
    }

    private static int getEvent(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "story_event");
        var definition = StoryDefinitionManager.INSTANCE.event(id).orElse(null);
        if (definition == null) { context.getSource().sendFailure(Component.literal("Unknown Story event " + id)); return 0; }
        var instances = StoryRuntimeState.get(context.getSource().getServer()).eventInstances().values().stream()
                .filter(value -> value.eventId().equals(id)).sorted(java.util.Comparator.comparingInt(value -> value.sequence())).toList();
        context.getSource().sendSuccess(() -> Component.literal(id + " role=" + definition.narrativeRole()
                + " scope=" + definition.scope() + " resolution=" + definition.resolutionPolicy()
                + " instances=" + instances.size()), false);
        instances.stream().skip(Math.max(0, instances.size() - 5L)).forEach(instance ->
                context.getSource().sendSuccess(() -> Component.literal(" - " + instance.instanceId()
                        + " status=" + instance.status() + " outcome="
                        + instance.selectedOutcomeId().map(ResourceLocation::toString).orElse("-")), false));
        return 1;
    }

    private static int triggerEvent(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "story_event");
        var result = StoryEventService.INSTANCE.triggerRegistered(context.getSource().getServer(), id, Optional.empty());
        return report(context, result);
    }

    private static int triggerEventFor(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(context, "story_event");
        var result = StoryEventService.INSTANCE.triggerRegistered(context.getSource().getServer(), id,
                Optional.of(EntityArgument.getPlayer(context, "player")));
        return report(context, result);
    }

    private static int report(CommandContext<CommandSourceStack> context,
            StoryEventService.StorySubmissionResult result) {
        if (result.status() == StoryEventService.Status.REJECTED) {
            context.getSource().sendFailure(Component.literal(result.reason())); return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(result.reason() + " " + result.startedInstanceIds()), true);
        return 1;
    }

    private static int getServerFact(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "story_fact");
        StoryRuntimeState state = StoryRuntimeState.get(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal(id + "=" + state.fact(StoryScopeKey.server(), id)), false);
        return 1;
    }

    private static int getActor(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "story_actor");
        var actor = StoryRuntimeState.get(context.getSource().getServer()).actor(id).orElse(null);
        if (actor == null) { context.getSource().sendFailure(Component.literal("Unknown Story actor " + id)); return 0; }
        context.getSource().sendSuccess(() -> Component.literal(id + " existence=" + actor.existence()
                + " availability=" + actor.availability() + " location="
                + actor.locationId().map(ResourceLocation::toString).orElse("-") + " revision=" + actor.revision()), false);
        return 1;
    }

    private static int getPlayerKnowledge(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation fact = ResourceLocationArgument.getId(context, "story_fact");
        int level = StoryRuntimeState.get(context.getSource().getServer())
                .knowledgeLevel(StoryKnowledgeHolder.player(player.getUUID()), fact);
        context.getSource().sendSuccess(() -> Component.literal(player.getGameProfile().getName()
                + " knows " + fact + " at level " + level), false);
        return level;
    }

    private static int listSchedules(CommandContext<CommandSourceStack> context) {
        var values = StoryRuntimeState.get(context.getSource().getServer()).schedules().values().stream()
                .sorted(java.util.Comparator.comparingLong(value -> value.dueGameTime())).toList();
        context.getSource().sendSuccess(() -> Component.literal("Story schedules: " + values.size()), false);
        values.stream().limit(20).forEach(value -> context.getSource().sendSuccess(() ->
                Component.literal(" - due=" + value.dueGameTime() + " event=" + value.targetEventId()), false));
        return values.size();
    }

    private static int listRecovery(CommandContext<CommandSourceStack> context) {
        var values = StoryRuntimeState.get(context.getSource().getServer()).eventInstances().values().stream()
                .filter(value -> value.status() == EventStatus.RECOVERY_REQUIRED
                        || value.status() == EventStatus.EXTERNAL_EFFECT_PENDING).toList();
        context.getSource().sendSuccess(() -> Component.literal("Story recovery entries: " + values.size()), false);
        values.forEach(value -> context.getSource().sendSuccess(() -> Component.literal(" - "
                + value.instanceId() + " status=" + value.status()), false));
        return values.size();
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            eventArgument() {
        return Commands.argument("story_event", ResourceLocationArgument.id()).suggests((context, builder) ->
                SharedSuggestionProvider.suggestResource(StoryDefinitionManager.INSTANCE.snapshot().events().keySet(), builder));
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            factArgument() {
        return Commands.argument("story_fact", ResourceLocationArgument.id()).suggests((context, builder) ->
                SharedSuggestionProvider.suggestResource(StoryDefinitionManager.INSTANCE.snapshot().facts().keySet(), builder));
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            actorArgument() {
        return Commands.argument("story_actor", ResourceLocationArgument.id()).suggests((context, builder) ->
                SharedSuggestionProvider.suggestResource(StoryDefinitionManager.INSTANCE.snapshot().actors().keySet(), builder));
    }
}
