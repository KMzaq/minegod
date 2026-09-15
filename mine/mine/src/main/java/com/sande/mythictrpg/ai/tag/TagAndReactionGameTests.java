package com.sande.mythictrpg.ai.tag;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.reaction.MinecraftWeather;
import com.sande.mythictrpg.ai.reaction.ReactionExamplePreparation;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineId;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRepository;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRetriever;
import com.sande.mythictrpg.ai.reaction.ReactionPreparationService;
import com.sande.mythictrpg.ai.reaction.SituationContext;
import com.sande.mythictrpg.ai.relationship.DefaultRelationshipStateResolver;
import com.sande.mythictrpg.ai.relationship.RelationshipExampleRetriever;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TagAndReactionGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation HEPHAESTUS = id("fixture_hephaestus");
    private static final ResourceLocation PLAYFUL = id("fixture_playful");

    private TagAndReactionGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void rawTagsAreStoredAndAutoClassified(GameTestHelper helper) {
        NpcTagProfile profile = NpcCharacterTagRepository.INSTANCE.find(PLAYFUL).orElseThrow();
        helper.assertTrue(profile.rawTags().containsAll(List.of("여행", "상업", "장난기", "교활", "인간간섭")),
                "Raw NPC tags were not preserved");
        helper.assertValueEqual(profile.classification().tags(CharacterTagCategory.HUMAN_ATTITUDE),
                List.of("인간간섭"), "Human attitude was not auto-classified");
        helper.assertTrue(profile.classification().tags(CharacterTagCategory.PERSONALITY)
                .containsAll(List.of("장난기", "교활")), "Personality tags were not auto-classified");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void explicitClassificationOverridesAutoAndStyleLayerIsSeparate(GameTestHelper helper) {
        NpcTagProfile profile = NpcCharacterTagRepository.INSTANCE.find(HEPHAESTUS).orElseThrow();
        helper.assertValueEqual(profile.classification().tags(CharacterTagCategory.SYMBOLS), List.of("망치"),
                "Explicit symbol classification did not override registry weapon category");
        helper.assertTrue(profile.classification().tags(CharacterTagCategory.WEAPONS).isEmpty(),
                "Automatic classification leaked into an explicitly classified category");

        NpcTagProfile playful = NpcCharacterTagRepository.INSTANCE.find(PLAYFUL).orElseThrow();
        ExampleStyleContext style = CharacterStyleTagMapper.INSTANCE.map(playful);
        helper.assertTrue(style.styleTags().contains(ExampleStyleTag.P_PLAYFUL),
                "Playful character tag did not map to P_PLAYFUL style tag");
        helper.assertTrue(!playful.rawTags().contains(ExampleStyleTag.P_PLAYFUL.name()),
                "Example style tag was stored as a raw character tag");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void situationSignalsSelectGuidanceWithoutResponseText(GameTestHelper helper) {
        ReactionGuidelineRetriever retriever = new ReactionGuidelineRetriever(ReactionGuidelineRepository.INSTANCE);
        NpcTagProfile npc = NpcCharacterTagRepository.INSTANCE.find(PLAYFUL).orElseThrow();

        SituationContext firstMeeting = new SituationContext(SituationContext.PlayerState.unknown(),
                SituationContext.WorldState.unknown(), new SituationContext.ConversationState(true, false, false,
                        false, false, false, false, false, false, false, false),
                SituationContext.RelationshipState.unknown(), SituationContext.MemoryState.unknown(),
                SituationContext.QuestState.unknown(), SituationContext.SocialContext.solo());
        helper.assertTrue(retriever.retrieve(firstMeeting, npc).ids().contains(ReactionGuidelineId.FIRST_ENCOUNTER),
                "FIRST_ENCOUNTER was not selected");

        SituationContext lowHealth = new SituationContext(
                new SituationContext.PlayerState(Optional.of(0.25D), false, Optional.empty(), Optional.empty()),
                SituationContext.WorldState.unknown(), SituationContext.ConversationState.empty(),
                SituationContext.RelationshipState.unknown(), SituationContext.MemoryState.unknown(),
                SituationContext.QuestState.unknown(), SituationContext.SocialContext.solo());
        var lowHealthMatch = retriever.retrieve(lowHealth, npc).matches().stream()
                .filter(match -> match.guideline().id() == ReactionGuidelineId.LOW_HEALTH).findFirst().orElseThrow();
        helper.assertValueEqual(lowHealthMatch.guideline().ruleType(), com.sande.mythictrpg.ai.reaction.GuidelineRuleType.GUIDANCE,
                "LOW_HEALTH should remain guidance, not a hard coded response");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void repeatedAndUnknownSignalsAreIndependentlySelectable(GameTestHelper helper) {
        ReactionGuidelineRetriever retriever = new ReactionGuidelineRetriever(ReactionGuidelineRepository.INSTANCE);
        NpcTagProfile npc = NpcCharacterTagRepository.INSTANCE.find(PLAYFUL).orElseThrow();
        SituationContext situation = new SituationContext(SituationContext.PlayerState.unknown(),
                SituationContext.WorldState.unknown(), new SituationContext.ConversationState(false, true, true,
                        false, false, false, false, false, false, false, true),
                SituationContext.RelationshipState.unknown(), SituationContext.MemoryState.unknown(),
                SituationContext.QuestState.unknown(), SituationContext.SocialContext.solo());
        List<ReactionGuidelineId> ids = retriever.retrieve(situation, npc).ids();
        helper.assertTrue(ids.contains(ReactionGuidelineId.REPEATED_CONVERSATION),
                "Repeated conversation guideline was not selected");
        helper.assertTrue(ids.contains(ReactionGuidelineId.UNKNOWN_INFORMATION),
                "Unknown information guideline was not selected");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void weatherTagBoostAndExampleOutputStaySeparate(GameTestHelper helper) {
        ReactionGuidelineRetriever retriever = new ReactionGuidelineRetriever(ReactionGuidelineRepository.INSTANCE);
        NpcTagClassifier classifier = new NpcTagClassifier(CharacterTagRegistry.INSTANCE);
        List<String> weatherTags = List.of("번개", "폭풍");
        NpcTagProfile weatherNpc = new NpcTagProfile(weatherTags, NpcTagClassification.empty(),
                classifier.classify(weatherTags, NpcTagClassification.empty()));
        NpcTagProfile ordinaryNpc = new NpcTagProfile(List.of("인간중립"), NpcTagClassification.empty(),
                classifier.classify(List.of("인간중립"), NpcTagClassification.empty()));
        SituationContext weather = new SituationContext(SituationContext.PlayerState.unknown(),
                new SituationContext.WorldState(Optional.of(18_000L), MinecraftWeather.THUNDER, true),
                SituationContext.ConversationState.empty(), SituationContext.RelationshipState.unknown(),
                SituationContext.MemoryState.unknown(), SituationContext.QuestState.unknown(),
                SituationContext.SocialContext.solo());
        int boosted = weatherScore(retriever, weather, weatherNpc);
        int ordinary = weatherScore(retriever, weather, ordinaryNpc);
        helper.assertTrue(boosted > ordinary, "Thunder tags did not increase WEATHER_CHANGE relevance");

        AiDialogueModels.GodPersona persona = new AiDialogueModels.GodPersona("Fixture", "Speak briefly.", "",
                List.of(), List.of("[R_STRANGER] A guarded greeting", "An ordinary answer"));
        NpcExampleRetriever examples = new NpcExampleRetriever(
                new RelationshipExampleRetriever(new DefaultRelationshipStateResolver()), CharacterStyleTagMapper.INSTANCE);
        ReactionPreparationService preparationService = new ReactionPreparationService(retriever, examples);
        ReactionExamplePreparation preparation = preparationService.prepare(weather, persona, weatherNpc,
                RelationshipMetrics.neutral(), 2);
        helper.assertTrue(preparation.guidelines() != null && preparation.examples() != null,
                "Guideline and example results were not returned as separate objects");
        helper.succeed();
    }

    private static int weatherScore(ReactionGuidelineRetriever retriever, SituationContext situation,
            NpcTagProfile npc) {
        return retriever.retrieve(situation, npc).matches().stream()
                .filter(match -> match.guideline().id() == ReactionGuidelineId.WEATHER_CHANGE)
                .findFirst().orElseThrow().score();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
