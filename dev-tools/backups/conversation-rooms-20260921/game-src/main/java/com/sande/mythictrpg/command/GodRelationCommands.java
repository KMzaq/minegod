package com.sande.mythictrpg.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.relation.DynamicGodRelationState;
import com.sande.mythictrpg.relation.GodRelationKey;
import com.sande.mythictrpg.relation.GodRelationService;
import com.sande.mythictrpg.relation.GodRelationSnapshot;
import com.sande.mythictrpg.relation.GodRelationTransition;
import com.sande.mythictrpg.relation.GodRelationTransitionManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class GodRelationCommands {
    private GodRelationCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("relation")
                .then(Commands.literal("gods")
                        .then(Commands.literal("get")
                                .then(god("source")
                                        .then(god("target").executes(GodRelationCommands::get))))
                        .then(Commands.literal("history")
                                .then(god("source")
                                        .then(god("target").executes(GodRelationCommands::history))))
                        .then(Commands.literal("preview")
                                .then(transition("transition").executes(context -> apply(context, false))))
                        .then(Commands.literal("apply")
                                .then(transition("transition").executes(context -> apply(context, true)))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            god(String name) {
        return Commands.argument(name, ResourceLocationArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        GodDefinitionManager.INSTANCE.ids(), builder));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            transition(String name) {
        return Commands.argument(name, ResourceLocationArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        GodRelationTransitionManager.INSTANCE.ids(), builder));
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        ResourceLocation source = ResourceLocationArgument.getId(context, "source");
        ResourceLocation target = ResourceLocationArgument.getId(context, "target");
        if (source.equals(target)) {
            context.getSource().sendFailure(Component.literal("Source and target Gods must differ"));
            return 0;
        }
        DynamicGodRelationState state = DynamicGodRelationState.get(context.getSource().getServer());
        GodRelationSnapshot relation = state.find(source, target)
                .orElseGet(() -> GodRelationSnapshot.neutral(new GodRelationKey(source, target)));
        context.getSource().sendSuccess(() -> Component.literal(source + " -> " + target
                + " score=" + relation.score() + " tags=" + relation.tags()
                + " revision=" + relation.revision() + " cause=" + relation.lastCauseId()), false);
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> context) {
        ResourceLocation source = ResourceLocationArgument.getId(context, "source");
        ResourceLocation target = ResourceLocationArgument.getId(context, "target");
        if (source.equals(target)) {
            context.getSource().sendFailure(Component.literal("Source and target Gods must differ"));
            return 0;
        }
        var history = DynamicGodRelationState.get(context.getSource().getServer()).find(source, target)
                .map(GodRelationSnapshot::recentHistory).orElseGet(java.util.List::of);
        context.getSource().sendSuccess(() -> Component.literal("Recent God relation changes: " + history.size()), false);
        history.forEach(value -> context.getSource().sendSuccess(() -> Component.literal("- revision="
                + value.revision() + " tick=" + value.gameTime() + " cause=" + value.causeId()
                + " score=" + value.previousScore() + "->" + value.currentScore()
                + " added=" + value.addedTags() + " removed=" + value.removedTags()), false));
        return history.size();
    }

    private static int apply(CommandContext<CommandSourceStack> context, boolean commit) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "transition");
        GodRelationTransition transition = GodRelationTransitionManager.INSTANCE.find(id).orElse(null);
        if (transition == null) {
            context.getSource().sendFailure(Component.literal("Unknown God relation transition: " + id));
            return 0;
        }
        var result = commit ? GodRelationService.INSTANCE.apply(context.getSource().getServer(), transition)
                : GodRelationService.INSTANCE.preview(context.getSource().getServer(), transition);
        if (!result.succeeded()) {
            context.getSource().sendFailure(Component.literal("God relation transition rejected: " + result.reason()));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal((commit ? "Applied " : "Previewed ") + id
                + " changedDirections=" + result.changes().size()), true);
        result.changes().forEach(change -> context.getSource().sendSuccess(() -> Component.literal("- "
                + change.key().sourceGodId() + " -> " + change.key().targetGodId()
                + " score=" + change.previousScore() + "->" + change.currentScore()
                + " tags=" + change.previousTags() + "->" + change.currentTags()), false));
        return result.changes().size();
    }
}

