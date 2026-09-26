package com.sande.mythictrpg.quest.structure;

import com.sande.mythictrpg.quest.FtbQuestBinding;
import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.MythicQuestState;
import com.sande.mythictrpg.quest.QuestAssignment;
import com.sande.mythictrpg.quest.QuestEvaluationGateway;
import com.sande.mythictrpg.quest.QuestEvaluationResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/** Independent server-authoritative entry point used by AI, fallback interaction, and admin commands. */
public final class StructureEvaluationService {
    public static final StructureEvaluationService INSTANCE = new StructureEvaluationService();
    private static final long EVALUATION_COOLDOWN_TICKS = 100L;
    private StructureEvaluationService() {}

    public Validation validateRequest(ServerPlayer player, ResourceLocation questId, ResourceLocation evaluatorGodId) {
        if (!player.server.isSameThread()) return Validation.reject("건축 평가는 서버 스레드에서만 실행할 수 있습니다");
        FtbQuestBinding binding=FtbQuestBindingManager.INSTANCE.find(questId).orElse(null);
        if(binding==null||binding.structureEvaluationPolicyId().isEmpty())return Validation.reject("건축 평가 정책이 연결된 퀘스트가 아닙니다");
        StructureEvaluationPolicy policy=StructureEvaluationPolicyManager.INSTANCE.find(binding.structureEvaluationPolicyId().orElseThrow()).orElse(null);
        if(policy==null)return Validation.reject("건축 평가 정책이 로드되지 않았습니다");
        if(!policy.godId().equals(evaluatorGodId))return Validation.reject("이 정책의 평가 신과 요청 신이 다릅니다");
        QuestAssignment assignment=MythicQuestState.get(player.server).assignmentsFor(player.getUUID()).stream()
                .filter(a->a.questId().equals(questId)).findFirst().orElse(null);
        if(assignment==null)return Validation.reject("플레이어에게 해당 퀘스트가 배정되어 있지 않습니다");
        if(binding.participation().isPresent() && MythicQuestState.get(player.server).participationRun(questId)
                .filter(run -> run.accepting(player.getUUID(), player.server.overworld().getGameTime())).isEmpty())
            return Validation.reject("이미 최종 평가를 제출했거나 퀘스트가 마감되었습니다");
        if(!assignment.giverGodId().equals(evaluatorGodId)||!binding.acceptsCompletionNpc(assignment.giverGodId(),evaluatorGodId))
            return Validation.reject("평가 신이 퀘스트 담당자가 아닙니다");
        StructureBuildRecord build=StructureEvaluationState.get(player.server).build(player.getUUID(),questId).orElse(null);
        if(build==null)return Validation.reject("확정된 건축 영역이 없습니다");
        long elapsed=player.serverLevel().getGameTime()-build.lastEvaluationTick();
        if(build.lastEvaluationTick()!=Long.MIN_VALUE&&elapsed<EVALUATION_COOLDOWN_TICKS)
            return Validation.reject("건축 평가 재요청 대기 시간이 남아 있습니다");
        return Validation.accept(binding,policy,build);
    }

    public Result requestEvaluation(ServerPlayer player,ResourceLocation questId,ResourceLocation evaluatorGodId){
        Validation validation=validateRequest(player,questId,evaluatorGodId);if(!validation.allowed())return Result.reject(validation.reason());
        StructureEvaluationPolicy policy=validation.policy().orElseThrow();StructureBuildRecord build=validation.build().orElseThrow();
        ServerLevel level=player.server.getLevel(build.region().dimension());if(level==null)return Result.reject("건축 차원을 찾을 수 없습니다");
        StructureSnapshotService.Capture capture=StructureSnapshotService.capture(level,build,policy);if(!capture.succeeded())return Result.reject(capture.rejectionReason());
        StructureSnapshot snapshot=capture.snapshot();StructureEvaluationState state=StructureEvaluationState.get(player.server);
        if(!policy.allowReuse()&&state.fingerprintUsedByAnotherQuest(snapshot.fingerprint(),questId))
            return Result.reject("이미 성공한 다른 퀘스트에 사용된 구조물입니다");
        StructureEvaluationReport report=StructureCriterionRegistry.evaluate(snapshot,policy);build.markEvaluated(level.getGameTime());state.setDirty();
        PlayerConstructionState.get(player.server).recordEvaluation("quest:"+player.getUUID()+":"+questId,
                build.ownerId(),build.eligibleContributors(),report,level.getGameTime());
        QuestEvaluationResult questResult=QuestEvaluationGateway.submit(player,questId,evaluatorGodId,report.score(),report.evidenceSummary());
        if(questResult.status()==QuestEvaluationResult.Status.COMPLETED || questResult.status()==QuestEvaluationResult.Status.SUBMITTED)
            state.markSuccessful(snapshot.fingerprint(),questId);
        StructureVisualEvaluationService.INSTANCE.submitQuest(player.server, player.getUUID(), questId,
                policy, report, level, build);
        return new Result(Status.EVALUATED,report,questResult,"");
    }

    public Inspection inspect(ServerPlayer player,ResourceLocation questId){
        FtbQuestBinding binding=FtbQuestBindingManager.INSTANCE.find(questId).orElse(null);if(binding==null||binding.structureEvaluationPolicyId().isEmpty())return Inspection.reject("정책 연결이 없습니다");
        StructureEvaluationPolicy policy=StructureEvaluationPolicyManager.INSTANCE.find(binding.structureEvaluationPolicyId().orElseThrow()).orElse(null);if(policy==null)return Inspection.reject("정책이 로드되지 않았습니다");
        StructureBuildRecord build=StructureEvaluationState.get(player.server).build(player.getUUID(),questId).orElse(null);if(build==null)return Inspection.reject("확정된 영역이 없습니다");
        ServerLevel level=player.server.getLevel(build.region().dimension());if(level==null)return Inspection.reject("차원을 찾을 수 없습니다");
        StructureSnapshotService.Capture capture=StructureSnapshotService.capture(level,build,policy);if(!capture.succeeded())return Inspection.reject(capture.rejectionReason());
        return new Inspection(StructureCriterionRegistry.evaluate(capture.snapshot(),policy),"");
    }

    public record Validation(boolean allowed,String reason,Optional<FtbQuestBinding> binding,Optional<StructureEvaluationPolicy> policy,Optional<StructureBuildRecord> build){
        static Validation accept(FtbQuestBinding b,StructureEvaluationPolicy p,StructureBuildRecord r){return new Validation(true,"",Optional.of(b),Optional.of(p),Optional.of(r));}
        static Validation reject(String reason){return new Validation(false,reason,Optional.empty(),Optional.empty(),Optional.empty());}}
    public record Result(Status status,StructureEvaluationReport report,QuestEvaluationResult questResult,String reason){static Result reject(String reason){return new Result(Status.REJECTED,null,null,reason);}public boolean succeeded(){return status==Status.EVALUATED;}}
    public record Inspection(StructureEvaluationReport report,String reason){static Inspection reject(String reason){return new Inspection(null,reason);}public boolean succeeded(){return report!=null;}}
    public enum Status{EVALUATED,REJECTED}
}
