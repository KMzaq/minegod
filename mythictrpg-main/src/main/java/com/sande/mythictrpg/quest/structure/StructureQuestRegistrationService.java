package com.sande.mythictrpg.quest.structure;

import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.MythicQuestState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/** Shared player/admin confirmation boundary. This registers evidence, never completes a quest. */
public final class StructureQuestRegistrationService {
    public static final StructureQuestRegistrationService INSTANCE = new StructureQuestRegistrationService();

    private StructureQuestRegistrationService() {}

    public Result confirm(ServerPlayer owner, ResourceLocation questId) {
        if (!owner.server.isSameThread()) return Result.reject("영역 확정은 서버 스레드에서만 실행할 수 있습니다");
        var binding = FtbQuestBindingManager.INSTANCE.find(questId).orElse(null);
        if (binding == null || binding.structureEvaluationPolicyId().isEmpty())
            return Result.reject("건축 평가 정책이 연결된 퀘스트가 아닙니다");
        var quests = MythicQuestState.get(owner.server);
        if (!quests.isWritable()) return Result.reject("퀘스트 저장소를 사용할 수 없습니다");
        var assignment = quests.assignmentsFor(owner.getUUID()).stream()
                .filter(value -> value.questId().equals(questId)).findFirst().orElse(null);
        if (assignment == null) return Result.reject("플레이어에게 퀘스트가 배정되어 있지 않습니다");
        var policy = StructureEvaluationPolicyManager.INSTANCE.find(
                binding.structureEvaluationPolicyId().orElseThrow()).orElse(null);
        if (policy == null) return Result.reject("건축 평가 정책이 로드되지 않았습니다");
        if (!policy.godId().equals(assignment.giverGodId()))
            return Result.reject("건축 평가 정책의 신과 퀘스트 담당자가 다릅니다");
        var run = quests.participationRun(questId);
        if ((binding.participation().isPresent() && run.isEmpty())
                || run.filter(value -> !value.accepting(owner.getUUID(),
                    owner.server.overworld().getGameTime())).isPresent())
            return Result.reject("이미 최종 평가를 제출했거나 퀘스트가 마감되었습니다");
        var structures = StructureEvaluationState.get(owner.server);
        var draft = structures.draft(owner.getUUID()).orElse(null);
        if (draft == null || draft.first() == null || draft.second() == null)
            return Result.reject("지점 1과 지점 2를 모두 지정해야 합니다");
        if (!draft.dimension().equals(owner.serverLevel().dimension()))
            return Result.reject("선택한 영역의 차원에서 확정해야 합니다");
        // The current FTB team is copied once; future team changes cannot change this build's evidence.
        try {
            var record = structures.confirm(owner.getUUID(), questId,
                    StructureTeamAdapter.frozenContributors(owner), owner.serverLevel().getGameTime());
            return new Result(Optional.of(record), "");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.reject("영역을 확정할 수 없습니다: " + exception.getMessage());
        }
    }

    public record Result(Optional<StructureBuildRecord> build, String reason) {
        private static Result reject(String reason) { return new Result(Optional.empty(), reason); }
        public boolean accepted() { return build.isPresent(); }
    }
}
