package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.godavatar.activity.NpcActivityDefinitions;
import com.sande.mythictrpg.power.BlessingPowerPolicyManager;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.structure.StructureEvaluationPolicyManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.DisclosureMode;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.InactiveProfiler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks the actual startup reload, without installing synthetic content into the live registries. */
@GameTestHolder("mythictrpg_content_isolation")
@PrefixGameTestTemplate(false)
public final class ContentIsolationGameTests {
    private ContentIsolationGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_content_isolation", template = "empty", batch = "content_isolation")
    public static void archivedContentIsAbsentAndCommonSystemsRemainLoaded(GameTestHelper helper) {
        boolean fixtures = Boolean.parseBoolean(System.getProperty("mythictrpg.legacyContentFixtures", "true"));
        var gods = GodDefinitionManager.INSTANCE;
        var story = StoryDefinitionManager.INSTANCE.snapshot();
        var quests = FtbQuestBindingManager.INSTANCE;
        helper.assertTrue(gods.generation() > 0 && story.generation() > 0 && quests.snapshot().generation() > 0,
                "Empty catalogs must come from a completed server resource reload, not uninitialized managers");
        count(helper, gods.definitions().size(), fixtures, 4, "God definitions");
        count(helper, quests.ids().size(), fixtures, 1, "FTB quest bindings");
        count(helper, story.actors().size(), fixtures, 3, "Story actors");
        count(helper, story.locations().size(), fixtures, 3, "Story locations");
        count(helper, story.facts().size(), fixtures, 2, "Story facts");
        count(helper, story.coverStories().size(), fixtures, 1, "Story cover stories");
        count(helper, story.events().size(), fixtures, 5, "Story events");
        count(helper, story.hooks().size(), fixtures, 2, "Story hooks");
        count(helper, story.presentations().size(), fixtures, 4, "Story presentations");
        count(helper, StructureEvaluationPolicyManager.INSTANCE.ids().size(), fixtures, 1,
                "Character-specific structure evaluation");

        var actions = AiActionTemplateManager.INSTANCE;
        var parsedActions = actions.prepare(helper.getLevel().getServer().getResourceManager(), InactiveProfiler.INSTANCE);
        count(helper, parsedActions.size(), fixtures, 6, "AI action templates from active server resources");
        helper.assertValueEqual(actions.templatesFor(id("fortuna")).size(), parsedActions.size(),
                "Published templates must match the active server resources");

        helper.assertValueEqual(story.disclosurePolicies().size(), fixtures ? 3 : 2,
                "Common disclosure policies remain available");
        helper.assertValueEqual(story.disclosurePolicies().get(id("full")).mode(), DisclosureMode.FULL,
                "Full disclosure policy");
        helper.assertValueEqual(story.disclosurePolicies().get(id("withhold")).mode(), DisclosureMode.WITHHOLD,
                "Withhold disclosure policy");
        var activities = NpcActivityDefinitions.INSTANCE;
        helper.assertTrue(activities.generation() > 0, "Common NPC activity library completed reload");
        helper.assertValueEqual(activities.data().activities().size(), 29, "Common NPC activity library remains loaded");
        var power = CombatPowerPolicyManager.INSTANCE.current();
        helper.assertTrue(power.policy() != null && power.policy().model() != null && power.reason().equals("READY"),
                "Combat power policy remains usable without character content");
        helper.assertValueEqual(power.policy().id(), "mythictrpg:default", "Default combat power policy");
        var blessings = BlessingPowerPolicyManager.INSTANCE.current();
        helper.assertTrue(blessings.policy() != null && blessings.reason().equals("READY"),
                "Common blessing power policy remains usable");
        helper.succeed();
    }

    private static void count(GameTestHelper helper, int actual, boolean fixtures, int fixtureCount, String label) {
        helper.assertValueEqual(actual, fixtures ? fixtureCount : 0,
                label + (fixtures ? " only in the explicit legacy regression fixture" : " absent from production resources"));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg", path);
    }
}
