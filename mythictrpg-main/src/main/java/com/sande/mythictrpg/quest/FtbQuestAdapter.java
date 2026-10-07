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
        return quest(binding.ftbQuestId()).isPresent() && quest(binding.assignmentQuestId()).isPresent()
                && (binding.participation().isEmpty() || ServerQuestFile.getInstance()
                    .filter(file -> file.getChapter(GENERATED_CHAPTER_ID) != null && file.getChapter(INTERNAL_CHAPTER_ID) != null).isPresent());
    }

    boolean activate(ServerPlayer player, FtbQuestBinding binding) {
        if (binding.participation().isPresent()) {
            var run = MythicQuestState.get(player.server).participationRun(binding.questId()).orElse(null);
            return run != null && syncParticipation(player, binding, run);
        }
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
        if (binding.participation().isPresent()) return MythicQuestState.get(player.server)
                .participationRun(binding.questId()).map(run -> run.ready(player.getUUID())).orElse(false);
        Quest quest = quest(binding.ftbQuestId()).orElse(null);
        if (quest == null) {
            return false;
        }
        TeamData data = teamData(player);
        var objectives = quest.getTasks().stream().filter(task -> !(task instanceof CustomTask)).toList();
        return !objectives.isEmpty() && objectives.stream().allMatch(data::isCompleted);
    }

    boolean complete(ServerPlayer player, FtbQuestBinding binding) {
        if (binding.participation().isPresent()) return activate(player, binding);
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

    /** Restore completion display only. forceProgressRaw also visits reward children and must not be used here. */
    boolean restoreCompletedMirror(ServerPlayer player, FtbQuestBinding binding, QuestCompletionRecord completion) {
        if (binding.participation().isPresent() || !completion.questId().equals(binding.questId())
                || !completion.completedBy().equals(player.getUUID())) return false;
        Quest marker = quest(binding.assignmentQuestId()).orElse(null);
        Quest target = quest(binding.ftbQuestId()).orElse(null);
        if (marker == null || target == null) return false;
        TeamData data = teamData(player);
        if (data.isLocked()) return false;
        var time = java.util.Date.from(completion.completedAt());
        for (Quest value : List.of(marker, target)) {
            if (!data.isStarted(value)) data.setStarted(value.getId(), time);
            if (!data.isCompleted(value)) data.setCompleted(value.getId(), time);
        }
        data.setQuestPinned(player, target.getId(), false);
        data.saveIfChanged();
        // Numeric task progress and claimed reward state are intentionally left untouched.
        return data.isCompleted(marker) && data.isCompleted(target);
    }

    void hideInvalidated(ServerPlayer player, FtbQuestBinding binding) {
        if (binding.participation().isPresent()) {
            activate(player, binding);
            return;
        }
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
                instance.completionMode() == QuestCompletionMode.AUTO
                        ? "이 의뢰는 작성자가 지정한 자동 완료 예외입니다."
                        : "목표 달성 후 의뢰한 신의 확인이 필요합니다. 마지막 진행 칸은 확인 단계입니다.",
                "진행 판정과 보상 지급은 MythicTRPG 서버가 전담합니다."));
        quest.addDependency(marker);
        CustomTask task = new CustomTask(file.newID(), quest);
        task.setMaxProgress(instance.displayMaximum());
        task.setRawTitle(generatedTaskTitle(instance));
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
        data.setProgress(task, instance.displayProgress());
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
        prepareGeneratedTask(player, file, data, task, instance);
        data.setProgress(task, instance.displayProgress());
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
        prepareGeneratedTask(player, file, data, task, instance);
        data.setProgress(task, instance.displayProgress());
        data.setQuestPinned(player, quest.getId(), true);
        data.saveIfChanged();
        return true;
    }

    private static String generatedTaskTitle(GeneratedQuestInstance instance) {
        return instance.subjectId() + " " + instance.progress() + "/" + instance.requiredCount()
                + "회 · " + instance.completionStatus();
    }

    private static void prepareGeneratedTask(ServerPlayer player, ServerQuestFile file, TeamData data,
            CustomTask task, GeneratedQuestInstance instance) {
        // Old mirrors used the objective count as the final threshold. Reconcile only this per-instance
        // generated mirror; it has no FTB rewards and cannot authoritatively complete the quest.
        if (task.getMaxProgress() != instance.displayMaximum()) {
            if (!instance.completionApproved()) force(task.getQuest(), data, player.getUUID(), true, false);
            task.setMaxProgress(instance.displayMaximum());
            file.markDirty();
        }
        String title = generatedTaskTitle(instance);
        if (!title.equals(task.getRawTitle())) {
            task.setRawTitle(title);
            syncTranslation(player, task, TranslationKey.TITLE, title);
            file.markDirty();
        }
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

    boolean syncParticipation(ServerPlayer player, FtbQuestBinding binding, QuestParticipationRun run) {
        ServerQuestFile file = ServerQuestFile.getInstance().orElse(null);
        if (file == null) return false;
        if (!run.participants().contains(player.getUUID())) {
            var old = run.snapshot().mirrors().get(player.getUUID());
            if (old == null) return true;
            var marker = file.getQuest(old.marker());
            if (marker == null) return false;
            TeamData departedData = teamData(player);
            force(marker, departedData, player.getUUID(), true, false);
            departedData.setQuestPinned(player, old.quest(), false);
            departedData.saveIfChanged();
            return true;
        }
        var mirror = run.snapshot().mirrors().get(player.getUUID());
        if (mirror == null || file.getQuest(mirror.quest()) == null || file.getTask(mirror.task()) == null) {
            Quest template = file.getQuest(binding.ftbQuestId());
            Chapter chapter = file.getChapter(GENERATED_CHAPTER_ID);
            Chapter internal = file.getChapter(INTERNAL_CHAPTER_ID);
            if (template == null || chapter == null || internal == null) return false;
            Quest marker = new Quest(file.newID(), internal);
            marker.setRawTitle("참여 수주: " + run.snapshot().runId() + " / " + player.getUUID());
            marker.onCreated();
            Quest display = new Quest(file.newID(), chapter);
            display.setRawTitle("[" + run.snapshot().type() + "] " + template.getTitle().getString()
                    + " · " + player.getGameProfile().getName());
            display.setRawDescription(List.of("이 퀘스트의 진행·제출·보상은 수주자별로 판정됩니다.",
                    "아이템 제출: /mythquest submit " + binding.questId(),
                    "완료 방식: " + binding.completionMode(), "대상: " + player.getGameProfile().getName()));
            display.setY(chapter.getQuests().size() * 1.5D);
            display.addDependency(marker);
            CustomTask task = new CustomTask(file.newID(), display);
            task.setMaxProgress(run.maximum() + 1L);
            task.setRawTitle("목표 진행 + 최종 완료 확인");
            display.addTask(task);
            display.onCreated();
            mirror = new QuestParticipationRun.Mirror(display.getId(), marker.getId(), task.getId());
            run.mirror(player.getUUID(), mirror);
            MythicQuestState.get(player.server).setDirty();
            NetworkHelper.sendToAll(player.server, CreateObjectResponseMessage.create(List.of(marker, display, task), null));
            syncTranslation(player, display, TranslationKey.TITLE, display.getRawTitle());
            syncTranslation(player, task, TranslationKey.TITLE, task.getRawTitle());
            NetworkHelper.sendToAll(player.server, SyncTranslationMessageToClient.create(display,
                    file.getLocale(), TranslationKey.QUEST_DESC, display.getRawDescription()));
            file.markDirty(); file.saveNow();
        }
        TeamData data = teamData(player);
        force(file.getQuest(mirror.marker()), data, player.getUUID(), false, false);
        boolean completed = run.snapshot().closed() && run.winners().contains(player.getUUID());
        data.setProgress(file.getTask(mirror.task()), completed ? run.maximum() + 1L : run.total(player.getUUID()));
        data.setQuestPinned(player, mirror.quest(), !run.snapshot().closed());
        data.saveIfChanged();
        return true;
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
