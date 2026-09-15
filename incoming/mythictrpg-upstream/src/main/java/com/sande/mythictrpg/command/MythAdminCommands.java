package com.sande.mythictrpg.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;
import com.sande.mythictrpg.data.god.AppearanceEvaluation;
import com.sande.mythictrpg.data.god.GodAppearanceService;
import com.sande.mythictrpg.data.god.GodDefinition;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.god.GodAccessService;
import com.sande.mythictrpg.data.god.GodIdentityService;
import com.sande.mythictrpg.data.god.GodUnlockService;
import com.sande.mythictrpg.data.god.UnlockEvaluationReport;
import com.sande.mythictrpg.data.player.GodKnowledgeSnapshot;
import com.sande.mythictrpg.data.player.KnowledgeMutationResult;
import com.sande.mythictrpg.data.player.PlayerGodKnowledgeService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.dialogue.server.DialogueSendResult;
import com.sande.mythictrpg.dialogue.server.DialogueSendStatus;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import com.sande.mythictrpg.interaction.content.ScriptedInteractionContentPreparer;
import com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator;
import com.sande.mythictrpg.interaction.preview.InteractionPreviewResult;
import com.sande.mythictrpg.interaction.preview.InteractionPreviewService;
import com.sande.mythictrpg.interaction.preview.InteractionPreviewStatus;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

public final class MythAdminCommands {
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private MythAdminCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mythadmin")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("gods").executes(MythAdminCommands::listGods))
                .then(Commands.literal("evaluate")
                        .then(Commands.literal("unlocks").executes(MythAdminCommands::evaluateUnlocks)))
                .then(Commands.literal("appearance")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(godArgument().executes(MythAdminCommands::evaluateAppearance))))
                .then(Commands.literal("encounter")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(godArgument().executes(context -> mutateKnowledge(context, true)))))
                .then(Commands.literal("identify")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(godArgument().executes(context -> mutateKnowledge(context, false)))))
                .then(Commands.literal("knowledge")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(godArgument().executes(MythAdminCommands::showKnowledge))))
                .then(Commands.literal("dialogue")
                        .then(Commands.literal("god")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(godArgument()
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(MythAdminCommands::sendGodDialogue))))))
                .then(Commands.literal("interaction")
                        .then(Commands.literal("god")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(godArgument()
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(MythAdminCommands::startGodInteraction)))))
                        .then(Commands.literal("dry-run")
                                .then(Commands.literal("god")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(godArgument()
                                                        .executes(MythAdminCommands::previewGodInteraction))))))
                .then(Commands.literal("god")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        GodDefinitionManager.INSTANCE.ids(), builder))
                                .executes(MythAdminCommands::showGod))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            godArgument() {
        return Commands.argument("god", ResourceLocationArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        GodDefinitionManager.INSTANCE.ids(), builder));
    }

    private static int listGods(CommandContext<CommandSourceStack> context) {
        var ids = GodDefinitionManager.INSTANCE.ids().stream().sorted().toList();
        context.getSource().sendSuccess(() -> Component.literal("Loaded gods: " + ids.size()), false);
        ids.forEach(id -> context.getSource().sendSuccess(() -> Component.literal("- " + id), false));
        return ids.size();
    }

    private static int showGod(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "id");
        GodDefinition definition = GodDefinitionManager.INSTANCE.find(id).orElse(null);
        if (definition == null) {
            context.getSource().sendFailure(Component.literal("Unknown god: " + id).withStyle(ChatFormatting.RED));
            return 0;
        }

        String categories = definition.categories().stream()
                .sorted(Comparator.naturalOrder())
                .map(ResourceLocation::toString)
                .collect(Collectors.joining(", "));
        context.getSource().sendSuccess(
                () -> Component.literal(id + " - ").append(definition.displayName()), false);
        context.getSource().sendSuccess(
                () -> Component.literal("schema=" + definition.schemaVersion()
                        + ", origin=" + definition.origin()
                        + ", faction=" + definition.faction()
                        + ", categories=[" + categories + "]"), false);
        MythicWorldState world = MythicWorldState.get(context.getSource().getServer());
        boolean explicit = world.isGodUnlocked(id);
        boolean effective = new GodAccessService(GodDefinitionManager.INSTANCE)
                .isEffectivelyUnlocked(context.getSource().getServer(), id);
        context.getSource().sendSuccess(() -> Component.literal("unlock_policy=" + definition.unlockPolicy()
                + ", unlock_condition=" + definition.unlockConditions().isPresent()
                + ", explicit_unlocked=" + explicit
                + ", effectively_unlocked=" + effective), false);
        return 1;
    }

    private static int evaluateUnlocks(CommandContext<CommandSourceStack> context) {
        UnlockEvaluationReport report = GodUnlockService.INSTANCE.evaluateCatchUp(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("Unlock evaluation complete. Checked: "
                + report.checked() + ", Newly unlocked: " + report.newlyUnlockedCount()), false);
        report.newlyUnlocked().forEach(id ->
                context.getSource().sendSuccess(() -> Component.literal("- " + id), false));
        return report.newlyUnlockedCount();
    }

    private static int evaluateAppearance(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        AppearanceEvaluation evaluation = GodAppearanceService.INSTANCE
                .evaluateAutomaticAppearance(player, godId);
        String policy = evaluation.policy().map(Enum::name).orElse("UNKNOWN");
        String condition = evaluation.conditionResult().map(Enum::name).orElse("NOT_EVALUATED");
        context.getSource().sendSuccess(() -> Component.literal("Appearance " + godId
                + " for " + player.getGameProfile().getName()
                + ": policy=" + policy
                + ", effectively_unlocked=" + evaluation.effectivelyUnlocked()
                + ", condition=" + condition
                + ", eligible=" + evaluation.eligible()
                + ", reason=" + evaluation.reason()), false);
        return evaluation.eligible() ? 1 : 0;
    }

    private static int mutateKnowledge(CommandContext<CommandSourceStack> context, boolean encounter)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        PlayerGodKnowledgeService knowledge = PlayerGodKnowledgeService.get(context.getSource().getServer());
        KnowledgeMutationResult result = encounter
                ? knowledge.recordEncounter(player, godId) : knowledge.identifyGod(player, godId);
        if (result == KnowledgeMutationResult.UNKNOWN_GOD) {
            context.getSource().sendFailure(Component.literal("Unknown god: " + godId)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        String operation = encounter ? "Encounter" : "Identification";
        context.getSource().sendSuccess(() -> Component.literal(operation + " " + result
                + " for " + player.getGameProfile().getName() + " and " + godId), false);
        return result == KnowledgeMutationResult.NEW_RECORD ? 1 : 0;
    }

    private static int showKnowledge(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        GodKnowledgeSnapshot knowledge = PlayerGodKnowledgeService.get(context.getSource().getServer())
                .snapshot(player.getUUID(), godId);
        Component displayName = GodIdentityService.INSTANCE.getDisplayName(player, godId);
        context.getSource().sendSuccess(() -> Component.literal("Knowledge " + godId
                + " for " + player.getGameProfile().getName()
                + ": encountered=" + knowledge.encountered()
                + ", identified=" + knowledge.identified()
                + ", display_name=").append(displayName), false);
        return 1;
    }

    private static int sendGodDialogue(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        String text = StringArgumentType.getString(context, "text");
        DialogueSendResult result = DialoguePresentationService.INSTANCE.sendTo(
                player, GodDialogueRequest.literal(godId, text));
        if (result.status() == DialogueSendStatus.UNKNOWN_GOD) {
            context.getSource().sendFailure(Component.literal("Unknown god: " + godId)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (result.status() == DialogueSendStatus.REJECTED) {
            context.getSource().sendFailure(Component.literal(
                    "Dialogue presentation was rejected; see the server log for the validation reason.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Sent God dialogue "
                + result.messageId().orElseThrow() + " to " + player.getGameProfile().getName()), false);
        return 1;
    }

    private static int startGodInteraction(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var source = context.getSource();
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        String dialogue = StringArgumentType.getString(context, "text");
        var signal = new InteractionSignal<>(InteractionSignalTypes.EXPLICIT_GOD_CALL,
                player.getUUID(), Set.of(), new ExplicitGodCallPayload(
                        godId, InteractionSignalTypes.PLAYER_EXPLICIT_POLICY));
        InteractionOrchestrator.INSTANCE.execute(source.getServer(), signal,
                new ScriptedInteractionContentPreparer(Component.literal(dialogue)))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        source.sendFailure(Component.literal("Interaction failed unexpectedly; see server log.")
                                .withStyle(ChatFormatting.RED));
                    } else if (result.status() == InteractionStartStatus.STARTED) {
                        source.sendSuccess(() -> Component.literal("Started interaction "
                                + result.interactionId().orElseThrow() + " for "
                                + player.getGameProfile().getName() + " (delivery="
                                + result.delivery().orElseThrow().status() + ")"), false);
                    } else {
                        source.sendFailure(Component.literal("Interaction not started: "
                                + result.status() + " (" + result.reason().map(Object::toString)
                                .orElse("no_reason") + ")").withStyle(ChatFormatting.RED));
                    }
                });
        return 1;
    }

    private static int previewGodInteraction(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var source = context.getSource();
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        var signal = new InteractionSignal<>(InteractionSignalTypes.EXPLICIT_GOD_CALL,
                player.getUUID(), Set.of(), new ExplicitGodCallPayload(
                        godId, InteractionSignalTypes.PLAYER_EXPLICIT_POLICY));
        InteractionPreviewResult result = InteractionPreviewService.INSTANCE.preview(
                source.getServer(), signal);
        String provider = switch (result.status()) {
            case PROVIDER_AVAILABLE -> "AVAILABLE";
            case PROVIDER_UNAVAILABLE -> "UNAVAILABLE";
            case PROVIDER_FAILED -> "FAILED";
            default -> "NOT_EVALUATED";
        };
        source.sendSuccess(() -> Component.literal("Interaction preview: status=" + result.status()
                + ", signal=" + result.signalTypeId()
                + ", primary_god=" + result.primaryGodId().map(ResourceLocation::toString).orElse("none")
                + ", reason=" + result.reasonId().map(ResourceLocation::toString).orElse("none")
                + ", provider=" + provider), false);
        return result.status() == InteractionPreviewStatus.PROVIDER_AVAILABLE
                || result.status() == InteractionPreviewStatus.PROVIDER_UNAVAILABLE ? 1 : 0;
    }
}
