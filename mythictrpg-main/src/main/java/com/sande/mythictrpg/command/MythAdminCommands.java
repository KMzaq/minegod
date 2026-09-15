package com.sande.mythictrpg.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;
import com.sande.mythictrpg.MythicTrpg;
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
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.content.ScriptedInteractionContentPreparer;
import com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator;
import com.sande.mythictrpg.interaction.preview.InteractionPreviewResult;
import com.sande.mythictrpg.interaction.preview.InteractionPreviewService;
import com.sande.mythictrpg.interaction.preview.InteractionPreviewStatus;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.GodAttentionState;
import com.sande.mythictrpg.quest.MythicQuestState;
import com.sande.mythictrpg.quest.QuestOperationResult;
import com.sande.mythictrpg.quest.QuestRuntimeService;
import com.sande.mythictrpg.quest.QuestEvaluationGateway;
import com.sande.mythictrpg.quest.reward.AffinityRewardEntry;
import com.sande.mythictrpg.quest.reward.BlessingRewardEntry;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import com.sande.mythictrpg.quest.reward.ResolvedQuestReward;
import com.sande.mythictrpg.quest.reward.RewardChoiceOption;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import com.sande.mythictrpg.quest.reward.TitleRewardEntry;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MythAdminCommands {
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private MythAdminCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
        FreeStructureCommands.register(event.getDispatcher());
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
                .then(Commands.literal("quest")
                        .then(Commands.literal("list").executes(MythAdminCommands::listQuestBindings))
                        .then(Commands.literal("assign")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(questArgument()
                                                .then(godArgument()
                                                        .executes(MythAdminCommands::assignQuest)))))
                        .then(Commands.literal("check-return")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(godArgument()
                                                .executes(MythAdminCommands::checkReturnedQuests))))
                        .then(Commands.literal("evaluate")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(questArgument()
                                                .then(godArgument()
                                                        .then(Commands.argument("score", IntegerArgumentType.integer(0, 100))
                                                                .then(Commands.argument("summary", StringArgumentType.greedyString())
                                                                        .executes(MythAdminCommands::evaluateQuest)))))))
                        .then(Commands.literal("status")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(MythAdminCommands::showQuestStatus))))
                .then(Commands.literal("reward")
                        .then(Commands.literal("test-choice")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(godArgument().executes(MythAdminCommands::sendTestRewardChoice)))))
                .then(StructureAdminCommands.node())
                .then(GodRelationCommands.node())
                .then(StoryAdminCommands.node())
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

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation>
            questArgument() {
        return Commands.argument("quest", ResourceLocationArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        FtbQuestBindingManager.INSTANCE.ids(), builder));
    }

    private static int listQuestBindings(CommandContext<CommandSourceStack> context) {
        var bindings = FtbQuestBindingManager.INSTANCE.snapshot().byQuestId().values().stream()
                .sorted(java.util.Comparator.comparing(binding -> binding.questId().toString())).toList();
        context.getSource().sendSuccess(() -> Component.literal("Loaded FTB quest bindings: "
                + bindings.size()), false);
        bindings.forEach(binding -> context.getSource().sendSuccess(() -> Component.literal("- "
                + binding.questId() + " -> " + binding.ftbQuestCode() + " ["
                + binding.completionMode() + ", " + binding.narrativeRole()
                + ", minAffinity=" + binding.minimumAffinity() + "]"), false));
        return bindings.size();
    }

    private static int assignQuest(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation questId = ResourceLocationArgument.getId(context, "quest");
        ResourceLocation giver = ResourceLocationArgument.getId(context, "god");
        QuestOperationResult result = QuestRuntimeService.INSTANCE.assign(player, questId, giver);
        context.getSource().sendSuccess(() -> Component.literal("Quest assignment: " + result.status()
                + " " + result.questId() + result.reasonOptional().map(reason -> " (" + reason + ")").orElse("")),
                false);
        return result.succeeded() ? 1 : 0;
    }

    private static int checkReturnedQuests(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation npc = ResourceLocationArgument.getId(context, "god");
        var results = QuestRuntimeService.INSTANCE.onNpcInteraction(context.getSource().getServer(),
                player.getUUID(), npc, InteractionMode.EXPLICIT);
        if (results.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "No assigned return-to-NPC quest matched " + npc), false);
            return 0;
        }
        results.forEach(result -> context.getSource().sendSuccess(() -> Component.literal(
                result.questId() + ": " + result.status()
                        + result.reasonOptional().map(reason -> " (" + reason + ")").orElse("")), false));
        return (int) results.stream().filter(QuestOperationResult::succeeded).count();
    }

    private static int showQuestStatus(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        MythicQuestState state = MythicQuestState.get(context.getSource().getServer());
        var active = state.assignmentsFor(player.getUUID());
        var history = state.historyFor(player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal("Active quests: " + active.size()), false);
        active.forEach(assignment -> context.getSource().sendSuccess(() -> Component.literal("- "
                + assignment.questId() + " (giver=" + assignment.giverGodId() + ")"), false));
        context.getSource().sendSuccess(() -> Component.literal("Completed quests: " + history.size()), false);
        history.forEach(record -> context.getSource().sendSuccess(() -> Component.literal("- "
                + record.questId() + " at " + record.completedAt()), false));
        var focusedGods = GodAttentionState.get(context.getSource().getServer())
                .focusedGodIds(player.getUUID()).stream().sorted().toList();
        context.getSource().sendSuccess(() -> Component.literal("Focused by Gods: "
                + focusedGods.size()), false);
        focusedGods.forEach(godId -> context.getSource().sendSuccess(
                () -> Component.literal("- " + godId), false));
        return active.size() + history.size() + focusedGods.size();
    }

    private static int evaluateQuest(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation questId = ResourceLocationArgument.getId(context, "quest");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        int score = IntegerArgumentType.getInteger(context, "score");
        String summary = StringArgumentType.getString(context, "summary");
        var result = QuestEvaluationGateway.submit(player, questId, godId, score, summary);
        context.getSource().sendSuccess(() -> Component.literal("Quest evaluation: " + result.status()
                + " score=" + result.score()
                + (result.rewardTier().isPresent() ? " rewardTier=" + result.rewardTier().getAsInt() : "")
                + (result.reason().isBlank() ? "" : " (" + result.reason() + ")")), false);
        return result.status() == com.sande.mythictrpg.quest.QuestEvaluationResult.Status.COMPLETED ? 1 : 0;
    }

    private static int sendTestRewardChoice(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        ResourceLocation sourceId = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
                "admin_reward_test/" + UUID.randomUUID());
        ResolvedQuestReward reward = new ResolvedQuestReward("검증할 보상 하나를 선택하세요",
                List.of(new AffinityRewardEntry(5)),
                List.of(
                        new RewardChoiceOption(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
                                "test_choice/item"), "아이템 보상",
                                List.of(new NpcRewardEntry(ResourceLocation.withDefaultNamespace("emerald"), 3))),
                        new RewardChoiceOption(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
                                "test_choice/blessing"), "호감도와 가호",
                                List.of(new AffinityRewardEntry(75), new BlessingRewardEntry(
                                        ResourceLocation.withDefaultNamespace("speed"), 1200, 1))),
                        new RewardChoiceOption(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
                                "test_choice/title"), "영구 칭호",
                                List.of(new TitleRewardEntry(ResourceLocation.fromNamespaceAndPath(
                                        MythicTrpg.MOD_ID, "title/reward_tester"), "보상 시험자")))));
        RewardClaimService.Result result = RewardClaimService.INSTANCE.issue(
                player, godId, sourceId, reward);
        context.getSource().sendSuccess(() -> Component.literal("Reward UI test: " + result.status()
                + (result.reason().isBlank() ? "" : " (" + result.reason() + ")")), false);
        return result.succeeded() ? 1 : 0;
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
