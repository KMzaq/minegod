package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.content.ContentSystemsGameTests;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import com.sande.mythictrpg.story.definition.StoryDefinitions.Effect;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.check;

@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class ReputationRecoveryGameTests {
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static final ResourceLocation OTHER_GOD = ResourceLocation.parse("mythictrpg:demeter");
    private static final ResourceLocation REASON = ResourceLocation.parse("test:restitution");
    private static final ReputationSettings.Rule POLICY = rule("test:repair", GOD);
    private static final ReputationSettings.Rule OTHER_POLICY = rule("test:other_policy", GOD);
    private static final ReputationSettings.Rule OTHER_GOD_POLICY = rule("test:other_god", OTHER_GOD);
    private static final ReputationSettings.Rule UNKNOWN_GOD_POLICY =
            rule("test:unknown_god", ResourceLocation.parse("test:unregistered_god"));
    private static final ReputationSettings SETTINGS = new ReputationSettings(1, true, 50, 50,
            List.of(POLICY, OTHER_POLICY, OTHER_GOD_POLICY, UNKNOWN_GOD_POLICY));

    @GameTest(templateNamespace="mythictrpg_content_systems", template="empty", timeoutTicks=100)
    public static void ordinaryStoryRecoverySurvivesRestartWithoutRecoveringNewAllegations(GameTestHelper h) throws Exception {
        var channels = new ArrayList<EmbeddedChannel>();
        var player = ContentSystemsGameTests.connect(h, "RecoveryOwner", channels);
        var server = player.server;
        var storyBefore = StoryDefinitionManager.INSTANCE.snapshot();
        var stateBefore = StoryRuntimeState.get(server);
        try (var fixture = new Fixture(h)) {
            fixture.start(SETTINGS, MemoryFoundationSettings.Mode.PERSONAL);
            check(MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.PERSONAL, "ordinary gameplay mode not loaded");
            var a = fixture.seed(player.getUUID(), POLICY);
            var b = fixture.seed(UUID.randomUUID(), POLICY);
            var c = fixture.seed(player.getUUID(), OTHER_GOD_POLICY);
            var d = fixture.seed(player.getUUID(), OTHER_POLICY);
            var eventId = ContentSystemsGameTests.id("reputation_repair");
            var effectId = ContentSystemsGameTests.id("reputation_effect");
            var event = ContentSystemsGameTests.event(eventId, false, List.of(
                    new Effect.RecoverReputation(effectId, GOD, ResourceLocation.parse(POLICY.id()), REASON)));
            var failedEvent = ContentSystemsGameTests.event(ContentSystemsGameTests.id("uncommitted_repair"), false,
                    List.of(new Effect.RecoverReputation(ContentSystemsGameTests.id("uncommitted_effect"),
                            GOD, ResourceLocation.parse(POLICY.id()), REASON)));
            ContentSystemsGameTests.swapStory(new StoryDefinitionSnapshot(storyBefore.actors(), storyBefore.locations(),
                    storyBefore.facts(), storyBefore.coverStories(), storyBefore.disclosurePolicies(),
                    Map.of(event.id(), event, failedEvent.id(), failedEvent), Map.of(), Map.of(), Map.of(),
                    storyBefore.generation() + 1));
            server.overworld().getDataStorage().set("mythictrpg_story", new StoryRuntimeState());
            StoryEventService.INSTANCE.onServerStarted(new ServerStartedEvent(server));

            check(StoryEventService.INSTANCE.triggerRegistered(server, eventId, Optional.of(player)).status()
                    == StoryEventService.Status.STARTED, "ordinary Story event did not start");
            var state = StoryRuntimeState.get(server);
            var instance = state.eventInstances().values().stream()
                    .filter(value -> value.eventId().equals(eventId)).findFirst().orElseThrow();
            check(instance.status() == EventStatus.RESOLVED && instance.appliedEffectIds().contains(effectId),
                    "ordinary Story recovery was not committed");
            var snapshot = fixture.data().snapshot();
            check(snapshot.entries().stream().filter(ReputationLedger.Entry::terminal).count() == 1,
                    "recovery crossed subject, god or policy");
            var recovered = fixture.entry(a);
            check(recovered.decision().outcome() == ReputationLedger.Outcome.RECOVERED
                    && recovered.decision().sourceId().equals(a.sourceId())
                    && recovered.initialApproval().equals(a.approval())
                    && recovered.decision().approval().kind() == ReputationLedger.ApprovalKind.GAME_RESULT,
                    "recovery rewrote original source or approval");
            check(!fixture.entry(b).terminal() && !fixture.entry(c).terminal() && !fixture.entry(d).terminal(),
                    "unrelated assessments changed");
            UUID result = UUID.nameUUIDFromBytes((instance.instanceId() + "/" + effectId).getBytes(StandardCharsets.UTF_8));
            check(ReputationService.recoveryCompleted(server, player.getUUID(), GOD,
                    ResourceLocation.parse(POLICY.id()), result, REASON), "committed recovery receipt missing");

            // Reconstruct both stores with an assessment receipt but no Story effect receipt, then use real startup hooks.
            var later = fixture.seed(player.getUUID(), POLICY);
            state.putEvent(instance.withAppliedEffects(Set.of(), EventStatus.EXTERNAL_EFFECT_PENDING));
            var storyNbt = state.save(new CompoundTag(), h.getLevel().registryAccess());
            fixture.reloadData();
            server.overworld().getDataStorage().set("mythictrpg_story",
                    StoryRuntimeState.load(storyNbt, h.getLevel().registryAccess()));
            StoryEventService.INSTANCE.onServerStarted(new ServerStartedEvent(server));
            check(StoryRuntimeState.get(server).eventInstance(instance.instanceId()).orElseThrow().status()
                    == EventStatus.RESOLVED, "saved recovery receipt did not finish Story after restart");
            check(!fixture.entry(later).terminal(), "restart recovered a later allegation");
            check(ReputationService.recoverFromGameResult(server, player.getUUID(), GOD,
                    ResourceLocation.parse(POLICY.id()), result, REASON), "recorded recovery replay was not idempotent");
            check(!fixture.entry(later).terminal(), "replayed game result expanded the original recovery");

            var beforeRejected = fixture.data().snapshot();
            check(!ReputationService.recoverFromGameResult(server, player.getUUID(), GOD,
                    ResourceLocation.parse("test:unknown_policy"), UUID.randomUUID(), REASON), "unknown policy accepted");
            check(!ReputationService.recoverFromGameResult(server, player.getUUID(),
                    ResourceLocation.parse(UNKNOWN_GOD_POLICY.godId()), ResourceLocation.parse(UNKNOWN_GOD_POLICY.id()),
                    UUID.randomUUID(), REASON), "unregistered god accepted");
            check(!ReputationService.recoverFromGameResult(server, UUID.randomUUID(), GOD,
                    ResourceLocation.parse(POLICY.id()), result, REASON), "receipt reused for another player");
            check(fixture.data().snapshot().equals(beforeRejected), "rejected recovery changed the ledger");

            // No receipt is not permission to choose a newer set of allegations on startup.
            fixture.start(ReputationSettings.OFF, MemoryFoundationSettings.Mode.PERSONAL);
            check(!ReputationService.recoverFromGameResult(server, player.getUUID(), GOD,
                    ResourceLocation.parse(POLICY.id()), UUID.randomUUID(), REASON), "disabled reputation accepted recovery");
            StoryEventService.INSTANCE.triggerRegistered(server, failedEvent.id(), Optional.of(player));
            state = StoryRuntimeState.get(server);
            var missing = state.eventInstances().values().stream()
                    .filter(value -> value.eventId().equals(failedEvent.id())).findFirst().orElseThrow();
            check(missing.status() == EventStatus.RECOVERY_REQUIRED, "disabled Story recovery reported success");
            state.putEvent(missing.withDecision(failedEvent.outcomes().getFirst().id(), OptionalLong.empty(),
                    EventStatus.COMMITTED, server.overworld().getGameTime()));
            storyNbt = state.save(new CompoundTag(), h.getLevel().registryAccess());
            fixture.start(SETTINGS, MemoryFoundationSettings.Mode.PERSONAL);
            server.overworld().getDataStorage().set("mythictrpg_story",
                    StoryRuntimeState.load(storyNbt, h.getLevel().registryAccess()));
            StoryEventService.INSTANCE.onServerStarted(new ServerStartedEvent(server));
            check(StoryRuntimeState.get(server).eventInstance(missing.instanceId()).orElseThrow().status()
                    == EventStatus.RECOVERY_REQUIRED && !fixture.entry(later).terminal(),
                    "restart applied a recovery without a saved game result receipt");

            // AI memory settings do not own an authored game's restitution result.
            fixture.start(SETTINGS, MemoryFoundationSettings.Mode.OFF);
            check(ReputationService.recoverFromGameResult(server, player.getUUID(), GOD,
                    ResourceLocation.parse(POLICY.id()), UUID.randomUUID(), REASON),
                    "authored gameplay recovery was tied to AI memory mode");
            check(fixture.entry(later).terminal(), "OFF-memory game result did not recover its assessment");
            h.succeed();
        } finally {
            ContentSystemsGameTests.swapStory(storyBefore);
            server.overworld().getDataStorage().set("mythictrpg_story", stateBefore);
            StoryEventService.INSTANCE.onServerStarted(new ServerStartedEvent(server));
            ContentSystemsGameTests.disconnect(player, channels);
        }
    }

    @GameTest(templateNamespace="mythictrpg_content_systems", template="empty", timeoutTicks=100)
    public static void emptyRecoveryPersistsReceiptAndCannotClearFutureAllegations(GameTestHelper h) throws Exception {
        try (var fixture = new Fixture(h)) {
            var server = h.getLevel().getServer();
            fixture.start(SETTINGS, MemoryFoundationSettings.Mode.PERSONAL);
            var subject = UUID.randomUUID();
            var result = UUID.randomUUID();
            var policy = ResourceLocation.parse(POLICY.id());
            check(ReputationService.recoverFromGameResult(server, subject, GOD, policy, result, REASON),
                    "empty authored recovery failed");
            fixture.reloadData();
            check(ReputationService.recoveryCompleted(server, subject, GOD, policy, result, REASON),
                    "empty batch lost its receipt on save/load");
            var future = fixture.seed(subject, POLICY);
            check(ReputationService.recoverFromGameResult(server, subject, GOD, policy, result, REASON)
                    && !fixture.entry(future).terminal(), "empty recovery replay cleared a future allegation");
            var before = fixture.data().snapshot();
            check(!ReputationService.recoverFromGameResult(server, subject, GOD, policy, result,
                    ResourceLocation.parse("test:different_reason")), "result id was reused for a different approval");
            check(fixture.data().snapshot().equals(before), "conflicting result changed state");
            check(ReputationService.recoverFromGameResult(server, subject, GOD, policy, UUID.randomUUID(), REASON)
                    && fixture.entry(future).terminal(), "new restitution result could not recover new allegation");
            h.succeed();
        }
    }

    private static ReputationSettings.Rule rule(String id, ResourceLocation god) {
        return new ReputationSettings.Rule(id, "test:courier", god.toString(), -20, -1000, 1000);
    }

    /** Config files and SavedData are confined to the throwaway build fixture and restored in the same tick. */
    private static final class Fixture implements AutoCloseable {
        private static final String DATA_NAME = "mythictrpg_reputation_judgement_v1";
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final UUID world;
        private final ReputationSavedData oldData;
        private final Path config;
        private final Path memoryConfig;
        private final byte[] oldConfig;
        private final byte[] oldMemoryConfig;

        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            server = helper.getLevel().getServer();
            config = server.getServerDirectory().resolve("config/mythictrpg/reputation-judgement.json");
            memoryConfig = FMLPaths.CONFIGDIR.get().resolve("mythictrpg/ai-memory-foundation.json");
            for (Path path : List.of(server.getWorldPath(LevelResource.ROOT), config, memoryConfig))
                check(path.toAbsolutePath().normalize().toString().replace('\\', '/').contains("/build/"),
                        "reputation lifecycle tests require an isolated build fixture: " + path);
            oldConfig = Files.exists(config) ? Files.readAllBytes(config) : null;
            oldMemoryConfig = Files.exists(memoryConfig) ? Files.readAllBytes(memoryConfig) : null;
            world = RumorSavedData.get(server).worldId();
            oldData = ReputationSavedData.get(server, world);
            server.overworld().getDataStorage().set(DATA_NAME, new ReputationSavedData(world));
        }

        void start(ReputationSettings settings, MemoryFoundationSettings.Mode mode) throws Exception {
            ReputationService.stopped(new ServerStoppedEvent(server));
            Files.createDirectories(config.getParent());
            Files.createDirectories(memoryConfig.getParent());
            Files.writeString(config, new Gson().toJson(settings));
            Files.writeString(memoryConfig, "{\"schemaVersion\":1,\"mode\":\"" + mode.name() + "\"}");
            MemoryFoundationSettings.reset();
            ReputationService.started(new ServerStartedEvent(server));
        }

        ReputationSavedData data() { return ReputationSavedData.get(server, world); }
        ReputationLedger.Decision seed(UUID subject, ReputationSettings.Rule rule) {
            var source = UUID.randomUUID();
            var decision = new ReputationLedger.Decision(UUID.randomUUID(), world, subject, rule.godId(), source,
                    UUID.randomUUID(), 1, rule.id(), rule.fingerprint(), 0, ReputationLedger.Outcome.ACCEPTED,
                    ReputationLedger.DirectImpact.NOT_APPLIED,
                    new ReputationLedger.Approval(UUID.randomUUID(), ReputationLedger.ApprovalKind.ADMIN_REVIEW, "test:fixture"));
            check(data().access(server, world, ledger -> ledger.apply(decision)) == ReputationLedger.Result.APPLIED,
                    "fixture assessment was not seeded");
            return decision;
        }
        ReputationLedger.Entry entry(ReputationLedger.Decision decision) {
            return data().access(server, world, ledger -> ledger.find(decision.subject(), decision.godId(), decision.sourceId()));
        }
        void reloadData() {
            var before = data().snapshot();
            var loaded = ReputationSavedData.load(data().save(new CompoundTag(), helper.getLevel().registryAccess()),
                    helper.getLevel().registryAccess());
            check(loaded.ready(world) && loaded.snapshot().equals(before), "assessment or game result receipt did not persist");
            ReputationService.stopped(new ServerStoppedEvent(server));
            server.overworld().getDataStorage().set(DATA_NAME, loaded);
            ReputationService.started(new ServerStartedEvent(server));
        }
        @Override public void close() throws Exception {
            ReputationService.stopped(new ServerStoppedEvent(server));
            server.overworld().getDataStorage().set(DATA_NAME, oldData);
            restore(config, oldConfig);
            restore(memoryConfig, oldMemoryConfig);
            MemoryFoundationSettings.reset();
            ReputationService.started(new ServerStartedEvent(server));
        }
        private static void restore(Path path, byte[] contents) throws Exception {
            if (contents == null) Files.deleteIfExists(path);
            else Files.write(path, contents);
        }
    }
}
