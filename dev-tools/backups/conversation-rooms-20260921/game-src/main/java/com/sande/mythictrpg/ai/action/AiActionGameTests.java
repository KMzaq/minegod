package com.sande.mythictrpg.ai.action;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AiActionGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation DEMETER = ResourceLocation.fromNamespaceAndPath(
            MythicTrpg.MOD_ID, "demeter");
    private static final ResourceLocation FORTUNA = ResourceLocation.fromNamespaceAndPath(
            MythicTrpg.MOD_ID, "fortuna");

    private AiActionGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void protocolNamesResolveOnlyToBoundedResourceIds(GameTestHelper helper) {
        helper.assertValueEqual(AiActionTypes.fromProtocolName("quest_offer"),
                AiActionTypes.QUEST_OFFER, "short action type");
        helper.assertValueEqual(AiActionTypes.fromProtocolName("mythictrpg:quest_offer"),
                AiActionTypes.QUEST_OFFER, "qualified action type");
        expectRejected(helper, () -> AiActionTypes.fromProtocolName("invalid action"),
                "invalid action type was accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void proposalBoundsRejectOversizedUntrustedParameters(GameTestHelper helper) {
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 33; index++) {
            tooMany.put("key_" + index, "value");
        }
        expectRejected(helper, () -> proposal(tooMany), "too many parameters were accepted");
        expectRejected(helper, () -> proposal(Map.of("key", "x".repeat(513))),
                "oversized parameter was accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void registryAllowsOnlyDefinitionsOwnedByTheGame(GameTestHelper helper) {
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.QUEST_OFFER).isPresent(),
                "quest offer action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.ITEM_REQUEST).isPresent(),
                "item request action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.REWARD_PROPOSAL).isPresent(),
                "reward proposal action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.RELATIONSHIP_CHANGE).isPresent(),
                "relationship change action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.BLESSING_OFFER).isPresent(),
                "blessing offer action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.WORLD_INTERACTION).isPresent(),
                "world interaction action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.PLAYER_DAMAGE).isPresent(),
                "player damage action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.GENERATED_QUEST_OFFER).isPresent(),
                "generated quest offer action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.STRUCTURE_EVALUATION_REQUEST).isPresent(),
                "structure evaluation request action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.GOD_RELATION_TRANSITION).isPresent(),
                "God relation transition action was not registered");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.STORY_EVENT_HOOK).isPresent(),
                "Story event Hook action was not registered");
        helper.assertValueEqual(AiActionRegistry.INSTANCE.find(AiActionTypes.STORY_EVENT_HOOK).orElseThrow()
                        .confirmationPolicy(), AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                "Story event Hooks must require player confirmation");
        helper.assertValueEqual(AiActionRegistry.INSTANCE.find(AiActionTypes.GOD_RELATION_TRANSITION).orElseThrow()
                        .confirmationPolicy(), AiActionDefinition.ConfirmationPolicy.PLAYER_CONFIRMATION_REQUIRED,
                "God relation transitions must require player confirmation");
        helper.assertValueEqual(AiActionRegistry.INSTANCE.find(AiActionTypes.PLAYER_DAMAGE).orElseThrow()
                        .confirmationPolicy(), AiActionDefinition.ConfirmationPolicy.IMMEDIATE,
                "player damage should be an immediate scene action");
        helper.assertTrue(AiActionRegistry.INSTANCE.find(AiActionTypes.NPC_VISIT_REQUEST).isEmpty(),
                "NPC visit action became executable before the external NPC mod integration");
        helper.assertValueEqual(AiActionGateway.registeredActionTypes().size(), 11,
                "unexpected production AI action count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void damageTemplatesEnforceAuthoredSafetyBounds(GameTestHelper helper) {
        ResourceLocation templateId = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "test_damage");
        ResourceLocation generic = ResourceLocation.withDefaultNamespace("generic");
        new PlayerDamageTemplate(templateId, DEMETER, PlayerDamageTemplate.DamageMode.FLAT,
                generic, 2.0D, false, 3, 20);
        new PlayerDamageTemplate(templateId, DEMETER,
                PlayerDamageTemplate.DamageMode.MAX_HEALTH_FRACTION,
                generic, 1.0D / 3.0D, false, 2, 0);
        new PlayerDamageTemplate(templateId, DEMETER, PlayerDamageTemplate.DamageMode.LETHAL,
                generic, 0.0D, true, 1, 0);
        expectRejected(helper, () -> new PlayerDamageTemplate(templateId, DEMETER,
                PlayerDamageTemplate.DamageMode.MAX_HEALTH_FRACTION,
                generic, 1.01D, false, 1, 0), "oversized health fraction was accepted");
        expectRejected(helper, () -> new PlayerDamageTemplate(templateId, DEMETER,
                PlayerDamageTemplate.DamageMode.LETHAL,
                generic, 0.0D, false, 1, 0), "non-lethal instant-kill template was accepted");
        expectRejected(helper, () -> new PlayerDamageTemplate(templateId, DEMETER,
                PlayerDamageTemplate.DamageMode.FLAT,
                generic, 2.0D, false, 17, 0), "oversized per-session damage count was accepted");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 100)
    public static void authoredNonLethalDamageActuallyHurtsAndPreservesLife(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setInvulnerable(false);
        player.getAbilities().invulnerable = false;
        player.invulnerableTime = 0;
        player.setHealth(player.getMaxHealth());
        helper.runAfterDelay(61, () -> {
            try {
                AiActionProposal proposal = new AiActionProposal(UUID.randomUUID(), UUID.randomUUID(),
                        AiActionTypes.PLAYER_DAMAGE, FORTUNA, player.getUUID(), "playful hit", "test",
                        Map.of("template_id", "mythictrpg:fortuna_playful_smack"));
                AiActionDefinition definition = AiActionRegistry.INSTANCE.find(AiActionTypes.PLAYER_DAMAGE)
                        .orElseThrow();
                AiActionContext context = new AiActionContext(player.server, player);
                helper.assertTrue(definition.validator().validate(context, proposal).accepted(),
                        "authored damage proposal failed validation");
                float before = player.getHealth();
                AiActionExecution execution = definition.executor().execute(context, proposal);
                helper.assertTrue(execution.executed(),
                        "authored damage did not execute: " + execution.reason());
                helper.assertTrue(player.getHealth() < before, "authored damage did not reduce health");
                helper.assertTrue(player.isAlive() && player.getHealth() >= 1.0F,
                        "non-lethal damage killed the player");
            } finally {
                if (player.server.getPlayerList().getPlayer(player.getUUID()) != null) {
                    player.server.getPlayerList().remove(player);
                }
            }
            helper.succeed();
        });
    }

    private static AiActionProposal proposal(Map<String, String> parameters) {
        return new AiActionProposal(UUID.randomUUID(), UUID.randomUUID(), AiActionTypes.QUEST_OFFER,
                DEMETER, UUID.randomUUID(), "title", "summary", parameters);
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String message) {
        try {
            action.run();
            helper.fail(message);
        } catch (IllegalArgumentException expected) {
            // Expected bounded-input rejection.
        }
    }
}
