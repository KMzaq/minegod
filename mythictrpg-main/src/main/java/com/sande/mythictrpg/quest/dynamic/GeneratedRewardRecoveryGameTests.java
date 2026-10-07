package com.sande.mythictrpg.quest.dynamic;

import com.sande.mythictrpg.economy.CurrencyService;
import com.sande.mythictrpg.economy.CurrencyState;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import com.sande.mythictrpg.quest.QuestCompletionMode;
import com.sande.mythictrpg.quest.QuestContactLocation;
import com.sande.mythictrpg.quest.QuestContactService;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.room.RecordingScope;

@GameTestHolder("mythictrpg_generated_rewards")
@PrefixGameTestTemplate(false)
public final class GeneratedRewardRecoveryGameTests {
    @GameTest(templateNamespace = "mythictrpg_generated_rewards", template = "empty")
    public static void frozenRewardsRoundTripWithoutInventingLegacySnapshot(GameTestHelper helper) {
        var data = new GeneratedQuestState();
        var components = new CompoundTag(); components.putInt("minecraft:custom_model_data", 918274);
        var reward = new NpcRewardEntry(ResourceLocation.withDefaultNamespace("iron_sword"), 1, components);
        var original = instance(UUID.randomUUID(), 0).withFrozenRewards(List.of(reward));
        data.create(original);
        var saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertValueEqual(saved.getInt("dataVersion"), 3, "version guard absent");
        var restored = GeneratedQuestState.load(saved, helper.getLevel().registryAccess());
        helper.assertValueEqual(restored.active(original.playerId()).orElseThrow(), original, "frozen reward lost");
        helper.assertValueEqual(original.withProgress(1).withFtbMirror(1, 2, 3).frozenRewards(),
                original.frozenRewards(), "progress/mirror rewrote frozen reward");
        var legacy = new GeneratedQuestState(); legacy.create(instance(UUID.randomUUID(), 0));
        var old = legacy.save(new CompoundTag(), helper.getLevel().registryAccess()); old.putInt("dataVersion", 1);
        var loaded = GeneratedQuestState.load(old, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.isWritable(), "v1 rejected");
        helper.assertTrue(loaded.activeQuests().getFirst().frozenRewards().isEmpty(), "historical reward invented");
        var corrupt = saved.copy();
        corrupt.getList("active", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("frozenRewards");
        var rejected = GeneratedQuestState.load(corrupt, helper.getLevel().registryAccess());
        helper.assertFalse(rejected.isWritable(), "missing v2 snapshot became an invented legacy reward");
        helper.assertValueEqual(rejected.save(new CompoundTag(), helper.getLevel().registryAccess()), corrupt,
                "corrupt snapshot was overwritten");
        helper.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_generated_rewards", template = "empty")
    public static void earnedRewardSurvivesExpiryFailureAndLoginRetry(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "GeneratedRetry"), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        try {
            var quest = instance(player.getUUID(), 1).withFrozenRewards(List.of(new CurrencyRewardEntry(10))).approveCompletion();
            var state = GeneratedQuestState.get(player.server); state.create(quest);
            CurrencyService.INSTANCE.set(player.server, player.getUUID(), CurrencyState.MAX_BALANCE);
            // NPC approval already happened; the expired deadline may not revoke an earned reward.
            GeneratedQuestService.INSTANCE.onPlayerLoggedIn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertTrue(state.active(player.getUUID()).isPresent(), "failed payout discarded completed quest");
            var persisted = GeneratedQuestState.load(state.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
            helper.assertTrue(persisted.active(player.getUUID()).orElseThrow().objectivesCompleted(), "pending completion not persisted");
            helper.assertTrue(persisted.active(player.getUUID()).orElseThrow().completionApproved(), "approved payout lost on reload");
            var source = id("generated/" + quest.instanceId());
            var claim = RewardClaimState.get(player.server).findBySource(player.getUUID(), source).orElseThrow();
            helper.assertFalse(claim.automaticGranted(), "failed preflight consumed reward receipt");
            CurrencyService.INSTANCE.debit(player.server, player.getUUID(), 20);
            GeneratedQuestService.INSTANCE.onPlayerLoggedIn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertTrue(state.active(player.getUUID()).isEmpty(), "login did not settle pending completion");
            helper.assertValueEqual(CurrencyService.INSTANCE.balance(player.server, player.getUUID()),
                    CurrencyState.MAX_BALANCE - 10, "reward not issued exactly once");
            // A separately saved old quest cannot pay again while its monotonic claim receipt exists.
            state.create(persisted.active(player.getUUID()).orElseThrow());
            GeneratedQuestService.INSTANCE.onPlayerLoggedIn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertValueEqual(CurrencyService.INSTANCE.balance(player.server, player.getUUID()),
                    CurrencyState.MAX_BALANCE - 10, "retry duplicated reward");
            helper.assertTrue(state.active(player.getUUID()).isEmpty(), "duplicate receipt did not finish quest cleanup");
            helper.succeed();
        } finally {
            GeneratedQuestState.get(player.server).active(player.getUUID())
                    .ifPresent(q -> GeneratedQuestState.get(player.server).remove(player.getUUID(), q.instanceId()));
            player.server.getPlayerList().remove(player); channel.finishAndReleaseAll();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_generated_rewards", template = "empty")
    public static void confirmationStateAndLegacyMigrationAreFailClosed(GameTestHelper h) {
        var origin = new QuestContactLocation(id("fixture_dimension"), 10, 60, 20, 8);
        var destination = new QuestContactLocation(id("return_dimension"), 30, 70, 40, 4);
        var quest = instance(UUID.randomUUID(), 1)
                .withContactRules(QuestCompletionMode.PLAYER_RETURN_TO_NPC, Optional.of(origin), Optional.of(destination))
                .withFrozenRewards(List.of(new CurrencyRewardEntry(10)));
        var state = new GeneratedQuestState(); state.create(quest);
        var saved = state.save(new CompoundTag(), h.getLevel().registryAccess());
        var loaded = GeneratedQuestState.load(saved, h.getLevel().registryAccess());
        h.assertValueEqual(loaded.active(quest.playerId()).orElseThrow(), quest, "ready contact data round trip");
        h.assertTrue(quest.awaitingConfirmation() && quest.displayProgress() < quest.displayMaximum(), "ready displayed complete");
        h.assertTrue(quest.approveCompletion().displayProgress() == quest.displayMaximum(), "approved display incomplete");
        var missing = saved.copy(); missing.getList("active", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).remove("completionApproved");
        var rejected = GeneratedQuestState.load(missing, h.getLevel().registryAccess());
        h.assertTrue(!rejected.isWritable() && rejected.save(new CompoundTag(), h.getLevel().registryAccess()).equals(missing),
                "missing approval field invented a completion");
        var invalid = saved.copy(); invalid.getList("active", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).putInt("progress", 0);
        invalid.getList("active", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putBoolean("completionApproved", true);
        h.assertFalse(GeneratedQuestState.load(invalid, h.getLevel().registryAccess()).isWritable(), "approved incomplete objectives accepted");
        // v2's objective-complete instances were already in its automatic payout phase.
        var legacy = saved.copy(); legacy.putInt("dataVersion", 2);
        var legacyQuest = GeneratedQuestState.load(legacy, h.getLevel().registryAccess()).active(quest.playerId()).orElseThrow();
        h.assertTrue(legacyQuest.completionApproved() && legacyQuest.completionMode() == QuestCompletionMode.AUTO,
                "legacy earned retry right lost");
        h.assertTrue(legacyQuest.origin().isEmpty() && legacyQuest.destination().isEmpty(), "legacy location invented");
        legacy.getList("active", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putInt("progress", 0);
        legacyQuest = GeneratedQuestState.load(legacy, h.getLevel().registryAccess()).active(quest.playerId()).orElseThrow();
        h.assertTrue(!legacyQuest.completionApproved() && legacyQuest.completionMode() == QuestCompletionMode.PLAYER_RETURN_TO_NPC,
                "unfinished legacy quest acquired automatic approval");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_generated_rewards", template = "empty")
    public static void readySurvivesLoginButOnlyActualContactApproves(GameTestHelper h) throws Exception {
        var installed = RoomConversationEngineRouter.class.getDeclaredField("installed"); installed.setAccessible(true);
        boolean previous = installed.getBoolean(RoomConversationEngineRouter.INSTANCE);
        installed.setBoolean(RoomConversationEngineRouter.INSTANCE, true);
        try (var fixture = new PlayerFixture(h, "GeneratedContact"); var actor = new ContactActor(h, fixture.player)) {
            var player = fixture.player;
            var state = GeneratedQuestState.get(player.server);
            var template = GeneratedQuestTemplateManager.INSTANCE.find(id("fortuna_zombie_cull"), id("fortuna")).orElseThrow();
            var quest = new GeneratedQuestInstance(UUID.randomUUID(), template.id(), player.getUUID(), template.godId(),
                    "접촉 검증 fixture", "Not operating content", template.observationTypeId(), template.subjectId(),
                    template.requiredCount(), template.requiredCount(), template.rewardTableId(), 1, false,
                    0, 1, 0, 0, 0).withContactRules(template.completionMode(), Optional.of(QuestContactLocation.capture(player)),
                    template.returnLocation()).withFrozenRewards(List.of(new CurrencyRewardEntry(10)));
            state.create(quest);
            GeneratedQuestService.INSTANCE.onPlayerLoggedIn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            h.assertTrue(state.active(player.getUUID()).orElseThrow().awaitingConfirmation(), "login approved ready quest");
            var source = id("generated/" + quest.instanceId());
            h.assertTrue(RewardClaimState.get(player.server).findBySource(player.getUUID(), source).isEmpty(), "ready state issued reward claim");
            var room = ConversationRooms.INSTANCE.create(player, RoomType.PRIVATE, List.of(template.godId()), RecordingScope.STANDARD);
            GeneratedQuestService.INSTANCE.onNpcContact(player, template.godId());
            h.assertTrue(state.active(player.getUUID()).orElseThrow().awaitingConfirmation(), "arbitrary room became actual contact");
            GeneratedQuestService.INSTANCE.onNpcContact(player, id("wrong_god"));
            h.assertTrue(state.active(player.getUUID()).orElseThrow().awaitingConfirmation(), "wrong NPC approved quest");
            CurrencyService.INSTANCE.set(player.server, player.getUUID(), CurrencyState.MAX_BALANCE);
            var scope = ConversationRooms.INSTANCE.actionScope(player, room.roomId(), room.revision(), template.godId()).orElseThrow();
            h.assertTrue(QuestContactService.met(player, actor.avatar, scope), "actual nearby avatar contact rejected");
            h.assertTrue(state.active(player.getUUID()).orElseThrow().completionApproved(), "real contact did not approve ready quest");
            String context = com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.contextForRoom(
                    player, template.godId(), scope.sessionId());
            h.assertTrue(context.contains("GENERATED_QUEST_LAST_RESULT") && context.contains("CONFIRMED_REWARD_PENDING"),
                    "existing quest context omitted confirmed generated outcome");
            h.assertFalse(GeneratedQuestService.INSTANCE.contextFor(player, template.godId(), UUID.randomUUID())
                    .contains("GENERATED_QUEST_LAST_RESULT"), "generated outcome leaked into another room");
            h.assertTrue(GeneratedQuestService.INSTANCE.contextFor(player, id("wrong_god"), scope.sessionId()).isEmpty(),
                    "generated state leaked to another God");
            var saved = GeneratedQuestState.load(state.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
            var pending = saved.active(player.getUUID()).orElseThrow();
            ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END");
            QuestContactService.clear();
            state.replace(pending);
            CurrencyService.INSTANCE.debit(player.server, player.getUUID(), 20);
            GeneratedQuestService.INSTANCE.onPlayerLoggedIn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            h.assertTrue(state.active(player.getUUID()).isEmpty(), "approved payout required a new contact after reload");
            h.assertValueEqual(CurrencyService.INSTANCE.balance(player.server, player.getUUID()), CurrencyState.MAX_BALANCE - 10,
                    "approved payout not issued once");
            var laterRoom = ConversationRooms.INSTANCE.create(player, RoomType.PRIVATE, List.of(template.godId()), RecordingScope.STANDARD);
            var laterScope = ConversationRooms.INSTANCE.actionScope(player, laterRoom.roomId(), laterRoom.revision(), template.godId()).orElseThrow();
            h.assertTrue(GeneratedQuestService.INSTANCE.contextFor(player, template.godId(), laterScope.sessionId()).isEmpty(),
                    "closed-room generated outcome migrated to a later conversation");
            h.succeed();
        } finally {
            QuestContactService.clear();
            installed.setBoolean(RoomConversationEngineRouter.INSTANCE, previous);
        }
    }

    private static final class ContactActor implements AutoCloseable {
        final java.lang.reflect.Field definitions;
        final Object previous;
        final com.sande.mythictrpg.godavatar.GodAvatarEntity avatar;
        ContactActor(GameTestHelper h, ServerPlayer player) throws Exception {
            var manager = com.sande.mythictrpg.godavatar.GodAvatarDefinitionManager.INSTANCE;
            definitions = manager.getClass().getDeclaredField("definitions"); definitions.setAccessible(true);
            previous = definitions.get(manager);
            var god = id("fortuna");
            var definition = new com.sande.mythictrpg.godavatar.GodAvatarDefinition(god,
                    new com.sande.mythictrpg.godavatar.GodAvatarDefinition.Appearance(0, 1),
                    new com.sande.mythictrpg.godavatar.GodAvatarDefinition.Stats(20, .25, 2, 0, 16),
                    new com.sande.mythictrpg.godavatar.GodAvatarDefinition.Movement(true, false, true, 1, 32, 32),
                    new com.sande.mythictrpg.godavatar.GodAvatarDefinition.Combat(false, false, false, false),
                    new com.sande.mythictrpg.godavatar.GodAvatarDefinition.Placement(false, 0), 4);
            definitions.set(manager, java.util.Map.of(god, definition));
            var feet = h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
            for (int dx = 0; dx < 3; dx++) {
                h.getLevel().setBlockAndUpdate(feet.offset(dx, -1, 0), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                h.getLevel().setBlockAndUpdate(feet.offset(dx, 0, 0), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                h.getLevel().setBlockAndUpdate(feet.offset(dx, 1, 0), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            player.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet.offset(2, 0, 0)));
            try {
                avatar = com.sande.mythictrpg.godavatar.GodAvatarService.INSTANCE.spawn(h.getLevel(), god,
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(feet)).orElseThrow();
            } catch (Exception failure) { definitions.set(manager, previous); throw failure; }
        }
        @Override public void close() throws Exception {
            com.sande.mythictrpg.godavatar.GodAvatarService.INSTANCE.despawn(avatar);
            definitions.set(com.sande.mythictrpg.godavatar.GodAvatarDefinitionManager.INSTANCE, previous);
        }
    }

    @GameTest(templateNamespace = "mythictrpg_generated_rewards", template = "empty")
    public static void explicitAutoRemainsAnExceptionAndUnfinishedQuestsExpire(GameTestHelper h) {
        try (var fixture = new PlayerFixture(h, "GeneratedAuto")) {
            var player = fixture.player; var state = GeneratedQuestState.get(player.server);
            var auto = instance(player.getUUID(), 1).withFrozenRewards(List.of(new CurrencyRewardEntry(10)))
                    .withContactRules(QuestCompletionMode.AUTO, Optional.empty(), Optional.empty());
            state.create(auto);
            CurrencyService.INSTANCE.set(player.server, player.getUUID(), 0);
            GeneratedQuestService.INSTANCE.onPlayerLoggedIn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            h.assertTrue(state.active(player.getUUID()).isEmpty(), "authored AUTO did not complete");
            h.assertValueEqual(CurrencyService.INSTANCE.balance(player.server, player.getUUID()), 10L, "AUTO reward");
            var unfinished = instance(player.getUUID(), 0).withFrozenRewards(List.of(new CurrencyRewardEntry(10)));
            state.create(unfinished);
            GeneratedQuestService.INSTANCE.onPlayerLoggedIn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
            h.assertTrue(state.active(player.getUUID()).isEmpty(), "unfinished expired quest survived");
            h.assertValueEqual(CurrencyService.INSTANCE.balance(player.server, player.getUUID()), 10L, "expired quest awarded reward");
            h.succeed();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_generated_rewards", template = "empty")
    public static void objectiveEventWaitsForApprovalInActualFtbMirror(GameTestHelper h) {
        var file = dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var added = new java.util.ArrayList<dev.ftb.mods.ftbquests.quest.Chapter>();
        for (long chapterId : new long[]{0x1D1A4D1C00000001L, 0x3C0FDADF7B7693BAL}) {
            if (file.getChapter(chapterId) == null) {
                var chapter = new dev.ftb.mods.ftbquests.quest.Chapter(chapterId, file, file.getDefaultChapterGroup());
                chapter.onCreated(); added.add(chapter);
            }
        }
        com.sande.mythictrpg.quest.GeneratedQuestFtbDisplay.Mirror mirror = null;
        try (var fixture = new PlayerFixture(h, "GeneratedReady")) {
            var player = fixture.player;
            long now = player.server.overworld().getGameTime();
            var quest = new GeneratedQuestInstance(UUID.randomUUID(), id("fixture_objective"), player.getUUID(), id("fortuna"),
                    "완료 대기 fixture", "Not operating content", GameplayObservationTypes.ENTITY_KILLED.id(),
                    ResourceLocation.withDefaultNamespace("zombie"), 1, 0, id("fixture_table"), 1, false,
                    now, now + 1200, 0, 0, 0).withFrozenRewards(List.of(new CurrencyRewardEntry(10)));
            mirror = com.sande.mythictrpg.quest.GeneratedQuestFtbDisplay.create(player, quest).orElseThrow();
            quest = quest.withFtbMirror(mirror.questId(), mirror.markerQuestId(), mirror.taskId());
            var state = GeneratedQuestState.get(player.server); state.create(quest);
            var event = new com.sande.mythictrpg.gameplay.observation.GameplayObservation<>(
                    GameplayObservationTypes.ENTITY_KILLED, player.getUUID(), now,
                    new com.sande.mythictrpg.gameplay.observation.EntityKilledPayload(quest.subjectId(), player.level().dimension().location()));
            GeneratedQuestService.INSTANCE.accept(player.server, event);
            var ready = state.active(player.getUUID()).orElseThrow();
            h.assertTrue(ready.awaitingConfirmation(), "objective event auto-approved default quest");
            GeneratedQuestService.INSTANCE.accept(player.server, event);
            h.assertValueEqual(state.active(player.getUUID()).orElseThrow(), ready, "ready quest accepted duplicate objective");
            var task = file.getTask(mirror.taskId()); var display = file.getQuest(mirror.questId());
            var data = file.getOrCreateTeamData(player);
            h.assertTrue(data.getProgress(task) == 1 && task.getMaxProgress() == 2 && !data.isCompleted(display),
                    "FTB falsely displayed ready objectives as final completion");
            h.assertTrue(task.getRawTitle().contains("확인 대기"), "FTB omitted confirmation status");
            h.assertTrue(RewardClaimState.get(player.server).findBySource(player.getUUID(), id("generated/" + quest.instanceId())).isEmpty(),
                    "objective event created reward before approval");
            // Reconcile a historical mirror whose objective threshold was itself the completion mark.
            ((dev.ftb.mods.ftbquests.quest.task.CustomTask) task).setMaxProgress(1);
            data.setProgress(task, 1);
            com.sande.mythictrpg.quest.GeneratedQuestFtbDisplay.restore(player, ready);
            h.assertTrue(!data.isCompleted(display) && data.getProgress(task) == 1 && task.getMaxProgress() == 2,
                    "legacy FTB completion survived pending-confirmation migration");
            h.succeed();
        } finally {
            if (mirror != null) {
                var display = file.getQuest(mirror.questId()); if (display != null) display.deleteSelf();
                var marker = file.getQuest(mirror.markerQuestId()); if (marker != null) marker.deleteSelf();
            }
            for (var chapter : added) chapter.deleteSelf();
        }
    }

    private static final class PlayerFixture implements AutoCloseable {
        final ServerPlayer player;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        PlayerFixture(GameTestHelper h, String name) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        }
        @Override public void close() {
            for (var room : ConversationRooms.INSTANCE.memberships(player)) ConversationRooms.INSTANCE.leave(player, room, "FIXTURE_END");
            GeneratedQuestState.get(player.server).active(player.getUUID())
                    .ifPresent(q -> GeneratedQuestState.get(player.server).remove(player.getUUID(), q.instanceId()));
            player.server.getPlayerList().remove(player); channel.finishAndReleaseAll();
        }
    }

    private static GeneratedQuestInstance instance(UUID player, int progress) {
        return new GeneratedQuestInstance(UUID.randomUUID(), id("fixture_generated"), player, id("fortuna"),
                "Fixture", "Not operating content", GameplayObservationTypes.ENTITY_KILLED.id(),
                ResourceLocation.withDefaultNamespace("zombie"), 1, progress, id("removed_fixture_table"),
                1, false, 0, 1, 0, 0, 0);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }
}
