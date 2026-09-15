package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.MythicTrpg;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.net.CreateObjectResponseMessage;
import dev.ftb.mods.ftbquests.net.SyncTranslationMessageToClient;
import dev.ftb.mods.ftbquests.quest.translation.TranslationKey;
import dev.ftb.mods.ftblibrary.util.NetworkHelper;
import com.sande.mythictrpg.quest.dynamic.GeneratedQuestInstance;
import dev.ftb.mods.ftbquests.util.ProgressChange;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Thin, version-pinned adapter. No FTB types are exposed to the rest of the quest domain. */
final class FtbQuestAdapter {
    static final FtbQuestAdapter INSTANCE = new FtbQuestAdapter();

    private boolean eventsRegistered;
    private static final long GENERATED_CHAPTER_ID = Long.parseUnsignedLong("1D1A4D1C00000001", 16);
    private static final long INTERNAL_CHAPTER_ID = Long.parseUnsignedLong("3C0FDADF7B7693BA", 16);

    private FtbQuestAdapter() {
    }

    synchronized void registerEvents() {
        if (eventsRegistered) {
            return;
        }
        ObjectCompletedEvent.QUEST.register(event -> {
            try {
                QuestRuntimeService.INSTANCE.onFtbQuestCompleted(event.getQuest().getId(),
                        event.getOnlineMembers(), event.getTime().toInstant());
            } catch (RuntimeException exception) {
                MythicTrpg.LOGGER.error("FTB quest completion bridge failed for {}",
                        event.getQuest().getCodeString(), exception);
            }
            return EventResult.pass();
        });
        eventsRegistered = true;
    }

    boolean definitionsPresent(FtbQuestBinding binding) {
        return quest(binding.ftbQuestId()).isPresent() && quest(binding.assignmentQuestId()).isPresent();
    }

    boolean activate(ServerPlayer player, FtbQuestBinding binding) {
        Optional<Quest> marker = quest(binding.assignmentQuestId());
        Optional<Quest> target = quest(binding.ftbQuestId());
        if (marker.isEmpty() || target.isEmpty()) {
            return false;
        }
        TeamData data = teamData(player);
        force(marker.orElseThrow(), data, player.getUUID(), false, false);
        data.setQuestPinned(player, target.orElseThrow().getId(), true);
        data.saveIfChanged();
        return true;
    }

    boolean objectivesReady(ServerPlayer player, FtbQuestBinding binding) {
        Quest quest = quest(binding.ftbQuestId()).orElse(null);
        if (quest == null) {
            return false;
        }
        TeamData data = teamData(player);
        var objectives = quest.getTasks().stream().filter(task -> !(task instanceof CustomTask)).toList();
        return !objectives.isEmpty() && objectives.stream().allMatch(data::isCompleted);
    }

    boolean complete(ServerPlayer player, FtbQuestBinding binding) {
        Quest quest = quest(binding.ftbQuestId()).orElse(null);
        if (quest == null) {
            return false;
        }
        TeamData data = teamData(player);
        force(quest, data, player.getUUID(), false, true);
        data.setQuestPinned(player, quest.getId(), false);
        data.saveIfChanged();
        return true;
    }

    void hideInvalidated(ServerPlayer player, FtbQuestBinding binding) {
        Optional<Quest> target = quest(binding.ftbQuestId());
        Optional<Quest> marker = quest(binding.assignmentQuestId());
        if (target.isEmpty() || marker.isEmpty()) {
            return;
        }
        TeamData data = teamData(player);
        force(target.orElseThrow(), data, player.getUUID(), true, false);
        force(marker.orElseThrow(), data, player.getUUID(), true, false);
        data.setQuestPinned(player, target.orElseThrow().getId(), false);
        data.saveIfChanged();
    }

    Optional<GeneratedQuestFtbDisplay.Mirror> createGenerated(ServerPlayer player,
            GeneratedQuestInstance instance) {
        ServerQuestFile file = ServerQuestFile.getInstance().orElse(null);
        if (file == null) {
            return Optional.empty();
        }
        Chapter generatedChapter = file.getChapter(GENERATED_CHAPTER_ID);
        Chapter internalChapter = file.getChapter(INTERNAL_CHAPTER_ID);
        if (generatedChapter == null || internalChapter == null) {
            MythicTrpg.LOGGER.error("Generated quest FTB chapters are missing; install the supplied ftbquests-pack");
            return Optional.empty();
        }

        Quest marker = new Quest(file.newID(), internalChapter);
        marker.setRawTitle("Generated quest assignment: " + instance.instanceId());
        marker.onCreated();

        Quest quest = new Quest(file.newID(), generatedChapter);
        quest.setX(0.0D);
        quest.setY(generatedChapter.getQuests().size() * 1.5D);
        quest.setRawTitle(instance.title().isBlank() ? "즉석 의뢰" : instance.title());
        quest.setRawDescription(List.of(
                instance.summary().isBlank() ? "신이 현재 상황에 맞춰 제시한 보조 의뢰입니다." : instance.summary(),
                "",
                "목표: " + instance.subjectId() + " / " + instance.requiredCount() + "회",
                "보상: " + instance.rewardTableId() + " 단계 " + instance.rewardTier(),
                "진행 판정과 보상 지급은 MythicTRPG 서버가 전담합니다."));
        quest.addDependency(marker);
        CustomTask task = new CustomTask(file.newID(), quest);
        task.setMaxProgress(instance.requiredCount());
        task.setRawTitle(instance.subjectId() + " " + instance.requiredCount() + "회");
        quest.addTask(task);
        quest.onCreated();

        NetworkHelper.sendToAll(player.server,
                CreateObjectResponseMessage.create(List.of(marker, quest, task), null));
        syncTranslation(player, marker, TranslationKey.TITLE, marker.getRawTitle());
        syncTranslation(player, quest, TranslationKey.TITLE, quest.getRawTitle());
        NetworkHelper.sendToAll(player.server, SyncTranslationMessageToClient.create(quest,
                file.getLocale(), TranslationKey.QUEST_DESC, quest.getRawDescription()));
        syncTranslation(player, task, TranslationKey.TITLE, task.getRawTitle());

        TeamData data = teamData(player);
        force(marker, data, player.getUUID(), false, false);
        data.setProgress(task, instance.progress());
        data.setQuestPinned(player, quest.getId(), true);
        data.saveIfChanged();
        file.markDirty();
        file.saveNow();
        return Optional.of(new GeneratedQuestFtbDisplay.Mirror(
                quest.getId(), marker.getId(), task.getId()));
    }

    boolean syncGenerated(ServerPlayer player, GeneratedQuestInstance instance) {
        if (!instance.hasFtbMirror()) {
            return false;
        }
        ServerQuestFile file = ServerQuestFile.getInstance().orElse(null);
        if (file == null || !(file.getTask(instance.ftbTaskId()) instanceof CustomTask task)) {
            return false;
        }
        TeamData data = teamData(player);
        data.setProgress(task, instance.progress());
        data.saveIfChanged();
        return true;
    }

    boolean restoreGenerated(ServerPlayer player, GeneratedQuestInstance instance) {
        if (!instance.hasFtbMirror()) {
            return false;
        }
        ServerQuestFile file = ServerQuestFile.getInstance().orElse(null);
        if (file == null || !(file.getQuest(instance.ftbQuestId()) instanceof Quest quest)
                || !(file.getQuest(instance.ftbMarkerQuestId()) instanceof Quest marker)
                || !(file.getTask(instance.ftbTaskId()) instanceof CustomTask task)) {
            return false;
        }
        TeamData data = teamData(player);
        force(marker, data, player.getUUID(), false, false);
        data.setProgress(task, instance.progress());
        data.setQuestPinned(player, quest.getId(), true);
        data.saveIfChanged();
        return true;
    }

    void hideGenerated(ServerPlayer player, GeneratedQuestInstance instance) {
        if (!instance.hasFtbMirror()) {
            return;
        }
        ServerQuestFile file = ServerQuestFile.getInstance().orElse(null);
        if (file == null) {
            return;
        }
        Quest quest = file.getQuest(instance.ftbQuestId());
        Quest marker = file.getQuest(instance.ftbMarkerQuestId());
        if (quest == null || marker == null) {
            return;
        }
        TeamData data = teamData(player);
        force(quest, data, player.getUUID(), true, false);
        force(marker, data, player.getUUID(), true, false);
        data.setQuestPinned(player, quest.getId(), false);
        data.saveIfChanged();
    }

    private static void syncTranslation(ServerPlayer player,
            dev.ftb.mods.ftbquests.quest.QuestObjectBase object,
            TranslationKey key, String value) {
        ServerQuestFile file = ServerQuestFile.getInstance().orElseThrow();
        NetworkHelper.sendToAll(player.server,
                SyncTranslationMessageToClient.create(object, file.getLocale(), key, value));
    }

    private static Optional<Quest> quest(long id) {
        return ServerQuestFile.getInstance().map(file -> file.getQuest(id));
    }

    private static TeamData teamData(ServerPlayer player) {
        return ServerQuestFile.getInstance().orElseThrow(
                () -> new IllegalStateException("FTB Quests server file is not loaded"))
                .getOrCreateTeamData(player);
    }

    private static void force(Quest quest, TeamData data, UUID playerId,
            boolean reset, boolean notifications) {
        ProgressChange change = new ProgressChange(quest, playerId).setReset(reset);
        if (notifications) {
            change.withNotifications();
        }
        quest.forceProgressRaw(data, change);
    }
}
