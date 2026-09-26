package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityService;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityState;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationSink;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionSubmissionService;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousSubmissionResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Set;

/**
 * Tracks whether active quest players spend a configured interval doing only
 * unrelated observed actions, then submits a normal spontaneous AI interaction.
 */
public final class QuestReminderService implements GameplayObservationSink {
    public static final QuestReminderService INSTANCE = new QuestReminderService();
    private static final long CHECK_INTERVAL_TICKS = 100L;

    private QuestReminderService() {
    }

    @Override
    public void accept(MinecraftServer server, GameplayObservation<?> observation) {
        requireServerThread(server);
        MythicQuestState quests = MythicQuestState.get(server);
        QuestReminderState reminders = QuestReminderState.get(server);
        for (QuestAssignment assignment : quests.assignmentsFor(observation.initiatingPlayerId())) {
            FtbQuestBindingManager.INSTANCE.find(assignment.questId())
                    .flatMap(FtbQuestBinding::reminderPolicy)
                    .ifPresent(policy -> reminders.observe(assignment.questId(), assignment.playerId(),
                            observation.gameTime(), policy.isRelevant(observation)));
        }
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        requireServerThread(server);
        long gameTime = server.overworld().getGameTime();
        if (gameTime % CHECK_INTERVAL_TICKS != 0L) {
            return;
        }
        MythicQuestState quests = MythicQuestState.get(server);
        QuestReminderState reminders = QuestReminderState.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PlayerActivityService.INSTANCE.find(server, player.getUUID())
                    .filter(activity -> activity.state() == PlayerActivityState.ACTIVE).isEmpty()) {
                continue;
            }
            for (QuestAssignment assignment : quests.assignmentsFor(player.getUUID())) {
                FtbQuestBinding binding = FtbQuestBindingManager.INSTANCE.find(assignment.questId()).orElse(null);
                QuestReminderPolicy policy = binding == null ? null : binding.reminderPolicy().orElse(null);
                if (policy == null || FtbQuestAdapter.INSTANCE.objectivesReady(player, binding)) {
                    continue;
                }
                QuestReminderState.Tracker tracker = reminders.ensure(
                        assignment.questId(), player.getUUID(), gameTime);
                if (!tracker.eligible(gameTime, policy)) {
                    continue;
                }
                QuestReminderPayload payload = new QuestReminderPayload(assignment.questId(),
                        assignment.giverGodId(), gameTime - tracker.lastRelevantGameTick(),
                        gameTime - tracker.firstUnrelatedGameTick(), tracker.unrelatedActionCount(),
                        tracker.reminderCount());
                InteractionSignalType<QuestReminderPayload> type = new InteractionSignalType<>(
                        policy.signalId(), InteractionMode.SPONTANEOUS, QuestReminderPayload.class);
                SpontaneousSubmissionResult result = SpontaneousInteractionSubmissionService.INSTANCE.submit(
                        server, new InteractionSignal<>(type, player.getUUID(), Set.of(), payload));
                if (result == SpontaneousSubmissionResult.ACCEPTED) {
                    reminders.markReminder(assignment.questId(), player.getUUID(), gameTime);
                    MythicTrpg.LOGGER.info("Queued quest reminder {} for player {} after {} unrelated ticks",
                            assignment.questId(), player.getUUID(), payload.unrelatedActivityTicks());
                }
            }
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Quest reminders may only run on the server thread");
        }
    }
}
