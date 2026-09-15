package com.sande.mythictrpg.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.ai.agent.NpcAgentRepository;
import com.sande.mythictrpg.ai.reaction.MinecraftWeather;
import com.sande.mythictrpg.ai.reaction.ReactionDiagnostics;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRepository;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRetriever;
import com.sande.mythictrpg.ai.reaction.ReactionPreparationService;
import com.sande.mythictrpg.ai.reaction.SituationContext;
import com.sande.mythictrpg.ai.relationship.DefaultRelationshipStateResolver;
import com.sande.mythictrpg.ai.relationship.RelationshipDataService;
import com.sande.mythictrpg.ai.relationship.RelationshipExampleRetriever;
import com.sande.mythictrpg.ai.tag.CharacterStyleTagMapper;
import com.sande.mythictrpg.ai.tag.NpcCharacterTagRepository;
import com.sande.mythictrpg.ai.tag.NpcExampleRetriever;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;

/** Operator-only entry points while AI dialogue remains an isolated integration phase. */
public final class AiDialogueCommands {
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private AiDialogueCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mythai")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("start")
                        .then(Commands.argument("god", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        GodDefinitionManager.INSTANCE.ids(), builder))
                                .executes(AiDialogueCommands::start)))
                .then(Commands.literal("leave").executes(AiDialogueCommands::leave))
                .then(Commands.literal("status").executes(AiDialogueCommands::status))
                .then(Commands.literal("debug")
                        .then(Commands.argument("god", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        GodDefinitionManager.INSTANCE.ids(), builder))
                                .executes(AiDialogueCommands::debug)))
                .then(Commands.literal("reload").executes(AiDialogueCommands::reload)));
    }

    private static int start(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        var player = context.getSource().getPlayerOrException();
        GodAiDialogueService.StartResult result = GodAiDialogueService.INSTANCE.start(player, godId);
        if (result == GodAiDialogueService.StartResult.STARTED) {
            return 1;
        }
        if (result == GodAiDialogueService.StartResult.PERSONA_MISSING) {
            context.getSource().sendFailure(Component.literal("이 신의 AI 페르소나가 없습니다. "
                    + GodPersonaRepository.INSTANCE.file() + "에 페르소나를 추가하세요.")
                    .withStyle(ChatFormatting.RED));
        } else if (result == GodAiDialogueService.StartResult.NPC_BUSY) {
            context.getSource().sendFailure(Component.literal("이 신은 이미 다른 대화에 참여 중입니다.")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            context.getSource().sendFailure(Component.literal("Unknown god: " + godId).withStyle(ChatFormatting.RED));
        }
        return 0;
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return GodAiDialogueService.INSTANCE.leave(context.getSource().getPlayerOrException()) ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        context.getSource().sendSuccess(() -> GodAiDialogueService.INSTANCE.status(player), false);
        return 1;
    }

    private static int debug(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation godId = ResourceLocationArgument.getId(context, "god");
        var player = context.getSource().getPlayerOrException();
        var npc = NpcCharacterTagRepository.INSTANCE.find(godId).orElse(null);
        var persona = GodPersonaRepository.INSTANCE.find(godId).orElse(null);
        var agent = NpcAgentRepository.INSTANCE.find(godId).orElse(null);
        if (npc == null || persona == null || agent == null) {
            context.getSource().sendFailure(Component.literal("NPC agent, tag profile, or persona is missing for " + godId)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        var relationships = RelationshipDataService.get(player.server);
        var relationship = relationships.relationship(player.getUUID(), godId);
        var relationshipTags = new DefaultRelationshipStateResolver().resolve(relationship);
        MinecraftWeather weather = player.level().isThundering() ? MinecraftWeather.THUNDER
                : player.level().isRaining() ? MinecraftWeather.RAIN : MinecraftWeather.CLEAR;
        SituationContext situation = new SituationContext(
                new SituationContext.PlayerState(Optional.of((double) player.getHealth() / player.getMaxHealth()), false,
                        Optional.empty(), Optional.empty()),
                new SituationContext.WorldState(Optional.of(player.level().getDayTime()), weather, false),
                SituationContext.ConversationState.empty(), new SituationContext.RelationshipState(relationshipTags),
                SituationContext.MemoryState.unknown(), SituationContext.QuestState.unknown(),
                SituationContext.SocialContext.solo());
        ReactionPreparationService preparationService = new ReactionPreparationService(
                new ReactionGuidelineRetriever(ReactionGuidelineRepository.INSTANCE),
                new NpcExampleRetriever(new RelationshipExampleRetriever(new DefaultRelationshipStateResolver()),
                        CharacterStyleTagMapper.INSTANCE));
        var preparation = preparationService.prepare(situation, persona, npc, relationship, 3);
        var snapshot = ReactionDiagnostics.snapshot(npc, situation, preparation);
        context.getSource().sendSuccess(() -> Component.literal("[AI Debug] " + godId
                + "\nagent: " + agent.identity().name() + " / persona=" + agent.personaId()
                + " / global emotion=" + agent.globalEmotion().intensities()
                + " / capabilities=" + agent.capabilities() + " / restrictions=" + agent.restrictions()
                + "\nraw tags: " + snapshot.rawNpcTags()
                + "\nclassified tags: " + snapshot.classifiedNpcTags().asMap()
                + "\nsituation: " + snapshot.situation().signals()
                + "\nguidelines: " + snapshot.guidelines().matches().stream().map(match -> match.guideline().id()
                        + "=" + match.score() + " " + match.reasons()).toList()
                + "\nexample style: " + snapshot.exampleStyleContext().styleTags()
                + "\nselected example IDs: " + snapshot.selectedExampleIds()), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        GodAiDialogueService.INSTANCE.reload();
        context.getSource().sendSuccess(() -> Component.literal("AI dialogue settings, personas, NPC agents, tags, reaction guidelines, knowledge, shared dialogue examples, and tag guidance reloaded."), false);
        return 1;
    }
}
