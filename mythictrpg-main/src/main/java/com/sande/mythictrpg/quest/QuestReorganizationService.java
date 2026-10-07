package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.ai.action.AiActionScope;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** NPC-scoped, explicit two-step roster changes. AI opens the menu, never supplies consent. */
public final class QuestReorganizationService {
    public static final QuestReorganizationService INSTANCE = new QuestReorganizationService();
    enum Operation { WITHDRAW, REMOVE_ABSENT, RECRUIT }
    record Choice(UUID token, UUID actor, UUID target, ResourceLocation quest, AiActionScope scope,
            UUID runId, long revision, long definitions, long expires, Operation operation, boolean confirmation, List<Integer> progress) {}
    private final Map<UUID, Choice> choices = new LinkedHashMap<>();
    private record EvidenceKey(UUID player, AiActionScope scope) {}
    private record Evidence(long expires, Map<String, Object> facts) {}
    private final Map<EvidenceKey, Evidence> evidence = new LinkedHashMap<>();
    private QuestReorganizationService() {}

    public String validate(ServerPlayer player, ResourceLocation quest, AiActionScope scope) {
        requireThread(player.server);
        if (scope == null || !ConversationRooms.INSTANCE.actionCurrent(player, scope.sessionId(), scope.actingGodId()))
            return "해당 NPC와 실제 게임 대화 중에 요청해주세요.";
        var state = MythicQuestState.get(player.server);
        var run = state.participationRun(quest).orElse(null);
        var binding = FtbQuestBindingManager.INSTANCE.find(quest).orElse(null);
        if (!state.isWritable() || run == null || run.snapshot().closed() || run.snapshot().roster() == null
                || binding == null || !QuestParticipationService.matches(binding, run)) return "이 퀘스트는 현재 재편성할 수 없습니다.";
        if (!run.participants().contains(player.getUUID())) return "현재 퀘스트 참가자만 요청할 수 있습니다.";
        if (!binding.acceptsCompletionNpc(ResourceLocation.parse(run.snapshot().giverId()), scope.actingGodId()))
            return "퀘스트를 준 NPC 또는 지정된 확인 상대에게 요청해주세요.";
        if (QuestParticipationService.INSTANCE.pendingOffer(player.getUUID()).isPresent()) return "진행 중인 수주 질문에 먼저 답해주세요.";
        return "";
    }

    public String open(ServerPlayer player, ResourceLocation quest, AiActionScope scope) {
        String problem = validate(player, quest, scope);
        if (!problem.isEmpty()) return problem;
        choices.values().removeIf(c -> c.actor().equals(player.getUUID()));
        if (choices.size() >= 2048) return "다른 참가 확인이 많습니다. 잠시 후 다시 요청해주세요.";
        var run = MythicQuestState.get(player.server).participationRun(quest).orElseThrow();
        var policy = run.snapshot().roster().policy();
        say(player, scope.actingGodId(), "[퀘스트 참가자 관리] " + quest + "\n"
                + "최소 인원 " + policy.minimumParticipants() + "명, 빈자리 충원 " + (policy.allowReplacement() ? "허용" : "불가")
                + ", 미제출자의 제출품 " + (policy.refundItems() ? "반환" : "반환 없음")
                + ". 최종 제출자의 완료·보상 자격은 유지됩니다.");
        int offered = 0;
        if (policy.allowWithdrawal() && !run.snapshot().submissions().containsKey(player.getUUID())) {
            offer(player, run, scope, player.getUUID(), Operation.WITHDRAW, "내 참여 포기"); offered++;
        }
        long now = player.server.overworld().getGameTime();
        for (UUID id : run.participants()) if (!id.equals(player.getUUID())
                && player.server.getPlayerList().getPlayer(id) == null && run.absent(id, now)) {
            offer(player, run, scope, id, Operation.REMOVE_ABSENT, "장기 부재자 제외: " + name(player.server, id)); offered++;
        }
        for (UUID id : ConversationRooms.INSTANCE.actionPlayers(player, scope.sessionId(), scope.actingGodId())) {
            ServerPlayer candidate = player.server.getPlayerList().getPlayer(id);
            if (candidate != null && run.canRecruit(id) && QuestRuntimeService.INSTANCE.validateReplacement(candidate, quest,
                    ResourceLocation.parse(run.snapshot().giverId())).allowed()) {
                offer(player, run, scope, id, Operation.RECRUIT, "빈자리 참여 요청: " + candidate.getGameProfile().getName()); offered++;
            }
        }
        if (offered == 0) say(player, scope.actingGodId(), "[현재 가능한 변경이 없습니다] 미제출자의 포기·장기 부재 조건 또는 빈자리와 동석 후보를 확인해주세요.");
        return "";
    }
    private void offer(ServerPlayer player, QuestParticipationRun run, AiActionScope scope, UUID target, Operation operation, String label) {
        var choice = new Choice(UUID.randomUUID(), player.getUUID(), target, ResourceLocation.parse(run.snapshot().questId()), scope,
                run.snapshot().runId(), run.rosterRevision(), FtbQuestBindingManager.INSTANCE.snapshot().generation(),
                player.server.overworld().getGameTime() + 1200, operation, false, run.snapshot().progress().getOrDefault(target, List.of()));
        choices.put(choice.token(), choice);
        player.sendSystemMessage(button(label, "/mythquest roster-select " + choice.token()));
    }
    public boolean select(ServerPlayer player, UUID token) {
        requireThread(player.server);
        Choice choice = choices.get(token);
        if (choice == null || choice.confirmation() || !choice.actor().equals(player.getUUID())) return false;
        choices.remove(token);
        String problem = current(player.server, choice);
        if (!problem.isEmpty()) { say(player, choice.scope().actingGodId(), problem); return false; }
        var run = MythicQuestState.get(player.server).participationRun(choice.quest()).orElseThrow();
        ServerPlayer recipient = choice.operation() == Operation.RECRUIT ? player.server.getPlayerList().getPlayer(choice.target()) : player;
        // One confirmation per respondent/room; no yes from an unrelated question can mutate this roster.
        choices.values().removeIf(c -> c.confirmation() && respondent(c).equals(recipient.getUUID())
                && c.scope().sessionId().equals(choice.scope().sessionId()));
        Choice confirmation = new Choice(UUID.randomUUID(), choice.actor(), choice.target(), choice.quest(), choice.scope(),
                choice.runId(), choice.revision(), choice.definitions(), choice.expires(), choice.operation(), true,
                run.snapshot().progress().getOrDefault(choice.target(), List.of()));
        choices.put(confirmation.token(), confirmation);
        int paid = 0;
        for (int i = 0; i < run.snapshot().objectives().size(); i++) {
            var kind = run.snapshot().objectives().get(i).kind();
            if (kind == QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION || kind == QuestParticipationPolicy.ObjectiveKind.ITEM_DONATION)
                paid += run.snapshot().progress().getOrDefault(choice.target(), Collections.nCopies(run.snapshot().objectives().size(), 0)).get(i);
        }
        String terms = choice.operation() == Operation.RECRUIT
                ? "빈자리에 참여하겠습니까? 본인 목표를 처음부터 수행하며, 퀘스트의 기존 보상 규칙을 적용합니다."
                : name(player.server, choice.target()) + "의 참여를 끝내겠습니까? 이 퀘스트의 완료·보상 대상에서 제외됩니다. 제출한 아이템 "
                    + paid + "개는 " + (run.snapshot().roster().policy().refundItems() ? "원래 제출자에게 반환됩니다." : "반환되지 않습니다.")
                    + " 신의 기존 메인 진행 자격은 유지됩니다.";
        say(recipient, choice.scope().actingGodId(), "[" + choice.quest() + "] " + terms
                + " 최소 인원 " + run.snapshot().roster().policy().minimumParticipants() + "명 미만이면 충원까지 기다립니다. 확인 / 취소를 선택해주세요.");
        recipient.sendSystemMessage(button("확인", "/mythquest roster-answer " + confirmation.token() + " yes")
                .append(" ").append(button("취소", "/mythquest roster-answer " + confirmation.token() + " no")));
        remember(player.server, confirmation, "AWAITING_EXPLICIT_CONFIRMATION", run);
        return true;
    }
    public boolean answer(ServerPlayer player, UUID token, boolean accepted) {
        requireThread(player.server);
        Choice choice = choices.get(token);
        if (choice == null || !choice.confirmation() || !respondent(choice).equals(player.getUUID())) return false;
        choices.remove(token);
        if (!accepted) {
            forget(choice);
            say(player, choice.scope().actingGodId(), "[참가자 변경을 취소했습니다]"); return true;
        }
        String problem = current(player.server, choice);
        if (!problem.isEmpty()) { forget(choice); say(player, choice.scope().actingGodId(), problem); return false; }
        var state = MythicQuestState.get(player.server);
        var run = state.participationRun(choice.quest()).orElseThrow();
        var binding = FtbQuestBindingManager.INSTANCE.find(choice.quest()).orElseThrow();
        long now = player.server.overworld().getGameTime();
        boolean changed = choice.operation() == Operation.RECRUIT ? run.recruit(choice.target(), now)
                : run.remove(choice.target(), choice.operation() == Operation.WITHDRAW, now);
        if (!changed) return false;
        state.reconcileRoster(run);
        if (choice.operation() == Operation.RECRUIT) {
            var recruit = player.server.getPlayerList().getPlayer(choice.target());
            if (recruit != null) state.recordAssignmentOrigin(choice.quest(), choice.target(), QuestContactLocation.capture(recruit));
        }
        choices.values().removeIf(c -> c.runId().equals(run.snapshot().runId()));
        if (choice.operation() == Operation.RECRUIT) QuestReminderState.get(player.server).ensure(choice.quest(), choice.target(), now);
        else QuestReminderState.get(player.server).remove(choice.quest(), choice.target());
        for (UUID id : run.snapshot().progress().keySet()) {
            ServerPlayer member = player.server.getPlayerList().getPlayer(id);
            if (member != null) {
                sync(member, binding, run);
                say(member, choice.scope().actingGodId(), "[퀘스트 참가자가 변경되었습니다] " + name(player.server, choice.target())
                        + (choice.operation() == Operation.RECRUIT ? " 참여" : " 참여 종료") + "; 현재 " + run.participants().size() + "명.");
            }
        }
        com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.transition(player.server, choice.target(), choice.quest(),
                choice.operation().name(), run.snapshot().runId().toString(), java.time.Instant.now(), run.snapshot().runId());
        QuestRosterRefunds.tick(player.server, run);
        QuestParticipationService.INSTANCE.settle(player.server, binding, run);
        remember(player.server, choice, "APPLIED", run);
        return true;
    }
    public boolean handleAnswer(ServerPlayer player, UUID session, ResourceLocation god, String text) {
        var matching = choices.values().stream().filter(c -> c.confirmation() && respondent(c).equals(player.getUUID())
                && c.scope().sessionId().equals(session) && c.scope().actingGodId().equals(god)).toList();
        if (matching.size() != 1) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("네", "예", "yes", "확인", "아니요", "아니오", "no", "취소").contains(normalized)) return false;
        answer(player, matching.getFirst().token(), Set.of("네", "예", "yes", "확인").contains(normalized));
        return true;
    }
    private String current(MinecraftServer server, Choice choice) {
        ServerPlayer actor = server.getPlayerList().getPlayer(choice.actor());
        long now = server.overworld().getGameTime();
        if (actor == null || now >= choice.expires() || FtbQuestBindingManager.INSTANCE.snapshot().generation() != choice.definitions())
            return "확인이 만료됐거나 퀘스트 설정이 바뀌었습니다. 다시 요청해주세요.";
        String problem = validate(actor, choice.quest(), choice.scope());
        if (!problem.isEmpty()) return problem;
        var run = MythicQuestState.get(server).participationRun(choice.quest()).orElseThrow();
        if (!run.snapshot().runId().equals(choice.runId()) || run.rosterRevision() != choice.revision()) return "참가자가 변경되었습니다. 다시 확인해주세요.";
        if (choice.confirmation() && !run.snapshot().progress().getOrDefault(choice.target(), List.of()).equals(choice.progress()))
            return "확인 이후 제출품 또는 실적이 바뀌었습니다. 반환 조건을 다시 확인해주세요.";
        if (choice.operation() == Operation.RECRUIT) {
            ServerPlayer candidate = server.getPlayerList().getPlayer(choice.target());
            if (candidate == null || !run.canRecruit(choice.target())
                    || !ConversationRooms.INSTANCE.actionCurrent(candidate, choice.scope().sessionId(), choice.scope().actingGodId())
                    || QuestParticipationService.INSTANCE.pendingOffer(candidate.getUUID()).isPresent()
                    || !QuestRuntimeService.INSTANCE.validateReplacement(candidate, choice.quest(), ResourceLocation.parse(run.snapshot().giverId())).allowed())
                return "충원 대상의 동석·수주 조건 또는 빈자리가 바뀌었습니다.";
        } else {
            if (!run.participants().contains(choice.target()) || run.snapshot().submissions().containsKey(choice.target()))
                return "이미 제출한 사람 또는 현재 참가자가 아닌 사람은 제외할 수 없습니다.";
            if (choice.operation() == Operation.WITHDRAW && (!choice.actor().equals(choice.target()) || !run.snapshot().roster().policy().allowWithdrawal()))
                return "본인의 포기만 확인할 수 있습니다.";
            if (choice.operation() == Operation.REMOVE_ABSENT && (server.getPlayerList().getPlayer(choice.target()) != null || !run.absent(choice.target(), now)))
                return "대상이 접속했거나 장기 부재 기준을 충족하지 않습니다.";
        }
        return "";
    }
    public void tick(MinecraftServer server) {
        choices.values().removeIf(c -> !current(server, c).isEmpty());
        long now = server.overworld().getGameTime();
        evidence.entrySet().removeIf(e -> {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey().player());
            return now >= e.getValue().expires() || player == null || !ConversationRooms.INSTANCE.actionCurrent(player,
                    e.getKey().scope().sessionId(), e.getKey().scope().actingGodId());
        });
        for (var run : MythicQuestState.get(server).participationRuns()) if (!run.snapshot().closed() && run.snapshot().roster() != null) {
            boolean changed = false;
            for (UUID id : run.participants()) if (server.getPlayerList().getPlayer(id) != null) { run.seen(id, now); changed = true; }
            if (changed) MythicQuestState.get(server).setDirty();
        }
    }
    public void login(ServerPlayer player) {
        var state = MythicQuestState.get(player.server);
        if (!state.isWritable()) return;
        for (var run : state.participationRuns()) if (run.snapshot().roster() != null && run.snapshot().progress().containsKey(player.getUUID())) {
            run.seen(player.getUUID(), player.server.overworld().getGameTime()); state.setDirty();
            if (run.snapshot().roster().departed().containsKey(player.getUUID())) {
                FtbQuestBindingManager.INSTANCE.find(ResourceLocation.parse(run.snapshot().questId())).ifPresent(b -> sync(player, b, run));
                say(player, ResourceLocation.parse(run.snapshot().giverId()), "[참여 종료된 퀘스트] " + run.snapshot().questId()
                        + "; " + run.snapshot().roster().departed().get(player.getUUID())
                        + (run.snapshot().roster().policy().refundItems() ? "; 제출품은 지급권으로 반환됩니다." : "; 제출품 반환 없음."));
            }
        }
    }
    public void logout(MinecraftServer server, UUID player) {
        choices.values().removeIf(c -> c.actor().equals(player) || c.target().equals(player));
        evidence.keySet().removeIf(k -> k.player().equals(player));
        var state = MythicQuestState.get(server);
        if (!state.isWritable()) return;
        for (var run : state.participationRuns()) if (!run.snapshot().closed() && run.snapshot().roster() != null && run.participants().contains(player)) {
            run.seen(player, server.overworld().getGameTime()); state.setDirty();
        }
    }
    public String contextFor(ServerPlayer player, AiActionScope scope) {
        if (!ConversationRooms.INSTANCE.actionCurrent(player, scope.sessionId(), scope.actingGodId())) return "";
        var value = evidence.get(new EvidenceKey(player.getUUID(), scope));
        if (value == null || player.server.overworld().getGameTime() >= value.expires()) return "";
        if ("AWAITING_EXPLICIT_CONFIRMATION".equals(value.facts().get("status")) && choices.values().stream().noneMatch(c ->
                c.confirmation() && c.scope().equals(scope) && c.quest().toString().equals(value.facts().get("quest_id"))
                && (c.actor().equals(player.getUUID()) || respondent(c).equals(player.getUUID())) && current(player.server, c).isEmpty())) return "";
        return "\n[QUEST_ROSTER_LAST_RESULT]\n" + new com.google.gson.Gson().toJson(value.facts())
                + "\nMenu/pending is not an applied change; this is the last result in this room, not a new order or reward grant.\n[/QUEST_ROSTER_LAST_RESULT]\n";
    }
    private void remember(MinecraftServer server, Choice choice, String status, QuestParticipationRun run) {
        var facts = Map.<String, Object>of("status", status, "operation", choice.operation().name(), "quest_id", choice.quest().toString(),
                "target_id", choice.target().toString(), "target_name", name(server, choice.target()),
                "roster_revision", run.rosterRevision(), "active_participants", run.participants().size(), "quest_closed", run.snapshot().closed());
        for (UUID id : new HashSet<>(List.of(choice.actor(), respondent(choice)))) {
            ServerPlayer recipient = server.getPlayerList().getPlayer(id);
            if (recipient == null || !ConversationRooms.INSTANCE.actionCurrent(recipient, choice.scope().sessionId(), choice.scope().actingGodId())) continue;
            if (evidence.size() >= 1024) evidence.remove(evidence.keySet().iterator().next());
            evidence.put(new EvidenceKey(id, choice.scope()), new Evidence(server.overworld().getGameTime() + 1200, facts));
        }
    }
    private void forget(Choice choice) {
        evidence.remove(new EvidenceKey(choice.actor(), choice.scope()));
        evidence.remove(new EvidenceKey(respondent(choice), choice.scope()));
    }
    public void clear() { choices.clear(); evidence.clear(); }
    private static UUID respondent(Choice c) { return c.operation() == Operation.RECRUIT ? c.target() : c.actor(); }
    private static net.minecraft.network.chat.MutableComponent button(String label, String command) {
        return Component.literal("[" + label + "]").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }
    private static void say(ServerPlayer player, ResourceLocation god, String text) {
        DialoguePresentationService.INSTANCE.sendTo(player, GodDialogueRequest.literal(god, text));
    }
    private static String name(MinecraftServer server, UUID id) {
        var player = server.getPlayerList().getPlayer(id);
        return player == null ? (server.getProfileCache() == null ? id.toString()
                : server.getProfileCache().get(id).map(com.mojang.authlib.GameProfile::getName).orElse(id.toString()))
                : player.getGameProfile().getName();
    }
    private static void sync(ServerPlayer player, FtbQuestBinding binding, QuestParticipationRun run) {
        try { FtbQuestAdapter.INSTANCE.syncParticipation(player, binding, run); }
        catch (RuntimeException failure) { com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Roster mirror sync deferred until login", failure); }
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Quest roster changes require server thread");
    }
}
