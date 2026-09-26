package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.PresentationStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.Set;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoryEngineGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation RELEASE_EVENT = id("demo_release_wanderer");
    private static final ResourceLocation OCCUPATION_EVENT = id("demo_wanderer_occupies_base");
    private static final ResourceLocation INVESTIGATION_EVENT = id("demo_investigate_lubras_base");
    private static final ResourceLocation RETURN_EVENT = id("demo_lubras_returns");
    private static final ResourceLocation FULL_STORY_EVENT = id("demo_ask_lubras_full_story");
    private static final ResourceLocation INVESTIGATION_HOOK = id("investigate_lubras_base");
    private static final ResourceLocation FULL_STORY_HOOK = id("ask_lubras_full_story");
    private static final ResourceLocation WANDERER = id("sealed_wanderer");
    private static final ResourceLocation LUBRAS = id("lubras");
    private static final ResourceLocation APHRODITE = id("aphrodite");
    private static final ResourceLocation BASE = id("lubras_hell_base");
    private static final ResourceLocation RELEASED_FACT = id("wanderer_released");
    private static final ResourceLocation OCCUPIED_FACT = id("wanderer_occupies_lubras_base");

    private StoryEngineGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void strictDefinitionsAndSignalIndexAreReady(GameTestHelper helper) {
        var snapshot = StoryDefinitionManager.INSTANCE.snapshot();
        helper.assertTrue(snapshot.generation() > 0, "Story definitions did not complete a reload");
        helper.assertTrue(snapshot.actors().containsKey(WANDERER), "Story actor was not loaded");
        helper.assertTrue(snapshot.locations().containsKey(BASE), "Story location was not loaded");
        helper.assertTrue(snapshot.facts().containsKey(OCCUPIED_FACT), "Story fact was not loaded");
        helper.assertTrue(snapshot.events().containsKey(RELEASE_EVENT), "Story event was not loaded");
        helper.assertTrue(snapshot.hooks().containsKey(INVESTIGATION_HOOK), "Story Hook was not loaded");
        helper.assertTrue(snapshot.candidates(StorySignalTypes.SCHEDULED_DUE,
                Optional.of(OCCUPATION_EVENT)).stream().anyMatch(value -> value.id().equals(OCCUPATION_EVENT)),
                "Exact signal index did not find the scheduled event");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void malformedSavedDataIsRejectedWithoutReplacement(GameTestHelper helper) {
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("dataVersion", 999);
        malformed.putLong("sentinel", 73L);
        StoryRuntimeState loaded = StoryRuntimeState.load(malformed, helper.getLevel().registryAccess());
        helper.assertTrue(!loaded.isReady(), "Unsupported Story SavedData was accepted");
        helper.assertTrue(loaded.rejectionReason().isPresent(), "Rejected Story SavedData lost its reason");
        CompoundTag preserved = loaded.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertValueEqual(preserved.getLong("sentinel"), 73L,
                "Rejected raw Story SavedData was overwritten");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 160)
    public static void scheduledWorldEventHookDisclosureAndPersistenceWorkTogether(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StoryRuntimeState state = StoryRuntimeState.get(player.server);
        try {
            StoryEventService.INSTANCE.resetScenarioForTesting(player.server,
                    Set.of(RELEASE_EVENT, OCCUPATION_EVENT, INVESTIGATION_EVENT, RETURN_EVENT, FULL_STORY_EVENT),
                    Set.of(WANDERER, LUBRAS), Set.of(RELEASED_FACT, OCCUPIED_FACT),
                    Set.of(INVESTIGATION_HOOK, FULL_STORY_HOOK));
            var result = StoryEventService.INSTANCE.triggerRegistered(player.server, RELEASE_EVENT,
                    Optional.of(player));
            helper.assertTrue(result.status() == StoryEventService.Status.STARTED
                            || state.fact(StoryScopeKey.server(), OCCUPIED_FACT),
                    "Release event neither started nor already reached its authored result: " + result.reason());
        } catch (RuntimeException exception) {
            removePlayer(player);
            throw exception;
        }

        helper.runAfterDelay(30, () -> {
            try {
                helper.assertTrue(state.fact(StoryScopeKey.server(), RELEASED_FACT),
                        "Release event did not commit its fact");
                helper.assertTrue(state.fact(StoryScopeKey.server(), OCCUPIED_FACT),
                        "Scheduled occupation event did not commit its fact");
                helper.assertValueEqual(state.actor(WANDERER).orElseThrow().locationId().orElseThrow(), BASE,
                        "Scheduled occupation event did not move the actor");
                helper.assertValueEqual(state.latestEvent(OCCUPATION_EVENT, StoryScopeKey.server())
                        .orElseThrow().status(), EventStatus.RESOLVED.name(), "Occupation event status");
                helper.assertValueEqual(state.knowledgeLevel(StoryKnowledgeHolder.actor(LUBRAS), OCCUPIED_FACT),
                        2, "Actor knowledge granted by the event");

                var concealed = StoryDisclosureService.INSTANCE.resolve(player.server, LUBRAS,
                        player.getUUID(), OCCUPIED_FACT, StoryScopeKey.server());
                helper.assertValueEqual(concealed.kind(),
                        StoryDisclosureService.DisclosureKind.AUTHORED_COVER_STORY,
                        "Disclosure policy must return an authored cover story, not leak truth");

                var hook = StoryHookService.INSTANCE.accept(player, INVESTIGATION_HOOK, APHRODITE, true);
                helper.assertTrue(hook.accepted(), "Investigation Hook was rejected: " + hook.reason());
                helper.assertValueEqual(state.knowledgeLevel(
                        StoryKnowledgeHolder.player(player.getUUID()), OCCUPIED_FACT), 1,
                        "Hook event did not grant player knowledge");
                helper.assertValueEqual(state.latestEvent(INVESTIGATION_EVENT,
                        StoryScopeKey.player(player.getUUID())).orElseThrow().status(),
                        EventStatus.RESOLVED.name(), "Investigation event status");
                helper.assertTrue(!StoryHookService.INSTANCE.accept(player, INVESTIGATION_HOOK, APHRODITE, true)
                        .accepted(), "One-shot Story Hook was accepted twice");
            } catch (RuntimeException exception) {
                removePlayer(player);
                throw exception;
            }

            helper.succeedWhen(() -> {
                var returned = state.latestEvent(RETURN_EVENT, StoryScopeKey.server());
                helper.assertTrue(returned.isPresent(), "Lubras return event is not due yet");
                helper.assertValueEqual(returned.get().status(), EventStatus.RESOLVED.name(),
                        "Lubras return event status");
                helper.assertValueEqual(state.actor(LUBRAS).orElseThrow().availability(),
                        com.sande.mythictrpg.story.definition.StoryDefinitions.AvailabilityState.AVAILABLE,
                        "Lubras did not become available after the scheduled return");
                if (state.knowledgeLevel(StoryKnowledgeHolder.player(player.getUUID()), OCCUPIED_FACT) < 2) {
                    var fullStory = StoryHookService.INSTANCE.accept(player, FULL_STORY_HOOK, LUBRAS, true);
                    helper.assertTrue(fullStory.accepted(), "Full Story Hook was rejected: " + fullStory.reason());
                }
                helper.assertValueEqual(state.knowledgeLevel(
                        StoryKnowledgeHolder.player(player.getUUID()), OCCUPIED_FACT), 2,
                        "Returning actor did not disclose the full authored fact");
                helper.assertValueEqual(state.latestEvent(FULL_STORY_EVENT,
                        StoryScopeKey.player(player.getUUID())).orElseThrow().status(),
                        EventStatus.RESOLVED.name(), "Full Story event status");
                helper.assertTrue(state.presentations().values().stream()
                        .filter(value -> value.audiencePlayerId().equals(player.getUUID()))
                        .allMatch(value -> value.status() == PresentationStatus.DELIVERED),
                        "Online fallback presentation remained pending");

                CompoundTag saved = state.save(new CompoundTag(), player.server.registryAccess());
                StoryRuntimeState loaded = StoryRuntimeState.load(saved, player.server.registryAccess());
                helper.assertTrue(loaded.isReady(), "Valid Story SavedData failed to reload");
                helper.assertTrue(loaded.fact(StoryScopeKey.server(), OCCUPIED_FACT),
                        "Persisted Story fact was lost");
                helper.assertValueEqual(loaded.knowledgeLevel(
                        StoryKnowledgeHolder.player(player.getUUID()), OCCUPIED_FACT), 2,
                        "Persisted player knowledge was lost");
                helper.assertTrue(!loaded.hookAcceptances().isEmpty(),
                        "Persisted Hook acceptance was lost");
                removePlayer(player);
            });
        });
    }

    private static void removePlayer(ServerPlayer player) {
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != null) {
            player.server.getPlayerList().remove(player);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
