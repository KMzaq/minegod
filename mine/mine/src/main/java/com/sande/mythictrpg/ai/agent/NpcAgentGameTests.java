package com.sande.mythictrpg.ai.agent;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.RelationshipDataService;
import com.sande.mythictrpg.ai.tag.ExampleStyleTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcAgentGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation LUBRAS = id("lubras");
    private static final ResourceLocation HEPHAESTUS = id("fixture_hephaestus");

    private NpcAgentGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void configuredAgentPreservesCompleteDeclarativeProfile(GameTestHelper helper) {
        NpcAgent agent = NpcAgentRepository.INSTANCE.find(HEPHAESTUS).orElseThrow();
        helper.assertValueEqual(agent.identity().id(), HEPHAESTUS, "Agent identity ID was not preserved");
        helper.assertValueEqual(agent.personaId(), LUBRAS, "Persona reference was not preserved");
        helper.assertValueEqual(agent.personality().get("blunt"), 0.9D, "Blunt personality score was not preserved");
        helper.assertValueEqual(agent.personality().get("hidden_warmth"), 0.8D,
                "Hidden warmth score was not preserved");
        helper.assertValueEqual(agent.values(), List.of("craftsmanship", "effort", "honesty"),
                "Agent values were not preserved");
        helper.assertTrue(agent.dislikes().containsAll(List.of("laziness", "disrespect_for_craft")),
                "Agent dislikes were not preserved");
        helper.assertTrue(agent.speechStyles().containsAll(Set.of(ExampleStyleTag.P_GRUFF, ExampleStyleTag.P_SHORT,
                ExampleStyleTag.P_DRY_HUMOR, ExampleStyleTag.P_INDIRECT_CARE)),
                "Speech styles were not parsed as the separate P_* layer");
        helper.assertTrue(agent.knowledgePermissions().permits("craftsmanship"),
                "Allowed knowledge scope was not preserved");
        helper.assertTrue(!agent.knowledgePermissions().permits("hidden_quest_data"),
                "Denied knowledge scope was unexpectedly permitted");
        helper.assertValueEqual(agent.globalEmotion().intensities().get("focus"), 65,
                "Global NPC emotion was not preserved");
        helper.assertTrue(agent.capabilities().contains("assess_craft"), "Agent capability was not preserved");
        helper.assertTrue(agent.restrictions().contains("no_direct_game_mutation"),
                "Agent restriction was not preserved");
        helper.assertTrue(!agent.conversationPolicy().allowSimultaneousSessions()
                        && agent.conversationPolicy().maxSessions() == 1,
                "The configured physical NPC conversation policy was not preserved");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void playerCurrentEmotionRemainsSeparateFromGlobalAgentEmotion(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        CurrentEmotion current = new CurrentEmotion(Map.of("anger", 80));
        RelationshipDataService.get(helper.getLevel().getServer()).setEmotion(player, LUBRAS, current);

        NpcAgentState state = new NpcAgentResolver().resolve(helper.getLevel().getServer(), player, LUBRAS).orElseThrow();
        helper.assertValueEqual(state.agent().globalEmotion(), new CurrentEmotion(Map.of("vigilance", 45)),
                "Player emotion overwrote global NPC emotion");
        helper.assertValueEqual(state.playerCurrentEmotion(), current,
                "Player-specific current emotion was not resolved from relationship data");
        helper.assertTrue(state.persona().isPresent() && state.tags().isPresent(),
                "Agent resolver did not connect the configured persona and tag profile");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void invalidAgentPersonalityIsRejectedRatherThanClamped(GameTestHelper helper) {
        try {
            new NpcAgent(new NpcIdentity(id("strict_agent"), "Strict Agent"), LUBRAS, Map.of("pride", 1.01D),
                    List.of(), List.of(), List.of(), Set.of(), KnowledgePermissions.none(), CurrentEmotion.calm(),
                    List.of(), List.of());
            helper.fail("Out-of-range agent personality was silently accepted");
            return;
        } catch (IllegalArgumentException expected) {
            // Configuration must be corrected by its author; it is never silently changed.
        }
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
