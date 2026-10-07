package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.ai.social.RoomSocialContext;
import com.sande.mythictrpg.ai.social.RoomSocialContextProvider;
import net.neoforged.neoforge.event.server.*;
import java.util.*;

/** Explicit game-only judgment API. No automatic assessment, AI Proposal, affinity mutation or quest hook. */
public final class ReputationService {
    public record Judgement(UUID subject,ResourceLocation god,Set<UUID> audience,UUID worldId,ReputationEngine.View view) {
        public Judgement {Objects.requireNonNull(subject);Objects.requireNonNull(god);audience=Set.copyOf(audience);Objects.requireNonNull(view);}
    }
    private record Runtime(ReputationSettings settings,RumorSavedData rumors,ReputationSavedData data) {}
    private static final Map<MinecraftServer,Runtime> INSTANCES=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Long> WARNINGS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,DialogueRecovery.Reviewer> REVIEWERS=new IdentityHashMap<>();
    private ReputationService() {}
    public static void started(ServerStartedEvent event) {
        var server=event.getServer();ReputationSavedData.requireThread(server);INSTANCES.remove(server);WARNINGS.remove(server);REVIEWERS.remove(server);
        var settings=ReputationSettings.load(server.getServerDirectory().resolve("config/mythictrpg/reputation-judgement.json"));
        if(!settings.enabled()||MemoryFoundationSettings.mode()!=MemoryFoundationSettings.Mode.RUMOR_TEST)return;
        var rumors=RumorSavedData.get(server);if(!rumors.ready())return;
        var data=ReputationSavedData.get(server,rumors.worldId());
        INSTANCES.put(server,new Runtime(settings,rumors,data));warn(server,data);
    }
    public static void stopped(ServerStoppedEvent event){ReputationSavedData.requireThread(event.getServer());INSTANCES.remove(event.getServer());WARNINGS.remove(event.getServer());REVIEWERS.remove(event.getServer());}
    private static Runtime current(MinecraftServer server) {
        ReputationSavedData.requireThread(server);
        return MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.RUMOR_TEST?INSTANCES.get(server):null;
    }
    private static boolean ready(Runtime runtime){return runtime.rumors().ready()&&runtime.data().ready(runtime.rumors().worldId());}
    private static ReputationEngine engine(MinecraftServer server,Runtime runtime,ReputationLedger ledger,Set<UUID> audience) {
        return new ReputationEngine(ledger,runtime.settings(),(subject,god,root)->{
            var received=CourierRumorService.heardOne(server,subject,god,root,audience);if(received.isEmpty())return Optional.empty();
            return runtime.rumors().access(server,rumors->{var evidence=rumors.evidence(root);
                if(evidence==null||evidence.proof()==null)return Optional.empty();var proof=evidence.proof();
                return Optional.of(new ReputationEngine.Evidence(rumors.worldId(),root,received.orElseThrow().revision(),subject,god,proof.sourceId(),proof.ruleId()));
            });
        });
    }
    /** The trusted game caller has already approved this decision and verified its Approval reference.
     * No dialogue agreement or LLM text is itself such an approval. Automatic recovery criteria remain unauthored. */
    public static ReputationLedger.Result applyApproved(MinecraftServer server,ReputationLedger.Decision decision) {
        return applyDecision(server,decision,false);
    }
    private static ReputationLedger.Result applyDecision(MinecraftServer server,ReputationLedger.Decision decision,boolean reviewedDialogue) {
        var runtime=current(server);if(runtime==null||!ready(runtime)
                ||decision.approval().kind()==ReputationLedger.ApprovalKind.DIALOGUE_REVIEW&&!reviewedDialogue
                ||!GodDefinitionManager.INSTANCE.definitions().containsKey(ResourceLocation.parse(decision.godId())))return ReputationLedger.Result.REJECTED;
        var result=runtime.data().access(server,runtime.rumors().worldId(),ledger->engine(server,runtime,ledger,Set.of(decision.subject())).applyApproved(decision));
        // A notification failure must not misreport an already committed assessment as rejected.
        try {warn(server,runtime.data());}catch(RuntimeException unavailable){MythicTrpg.LOGGER.warn("Reputation capacity notification unavailable",unavailable);}
        return result;
    }
    /** Future game integration only; no reviewer is registered by default or by the AI response module. */
    public static boolean installDialogueReviewer(MinecraftServer server,DialogueRecovery.Reviewer reviewer) {
        if(current(server)==null)return false;REVIEWERS.put(server,Objects.requireNonNull(reviewer));return true;
    }
    static int affinity(MinecraftServer server,UUID subject,String god) {
        var profiles=PlayerMythDataService.get(server);
        if(!profiles.isReady())throw new IllegalStateException("profile unavailable");
        return profiles.find(subject).map(p->p.affinities().getOrDefault(ResourceLocation.parse(god),0)).orElse(0);
    }
    static ReputationLedger.Entry assessment(MinecraftServer server,UUID subject,String god,UUID root,Set<UUID> audience) {
        var runtime=current(server);
        if(runtime==null||!ready(runtime)||CourierRumorService.heardOne(server,subject,god,root,audience).isEmpty())return null;
        return runtime.data().access(server,runtime.rumors().worldId(),ledger->ledger.entries().stream()
                .filter(e->e.decision().subject().equals(subject)&&e.decision().godId().equals(god)&&e.decision().rootId().equals(root)).findFirst().orElse(null));
    }
    /** Native archive point-read only: no preview, affinity lookup, review or mutation. */
    static NativeRumorReadAccess.Assessment nativeAssessment(MinecraftServer server, RumorLedger.Evidence evidence,
            RumorLedger.HeardRumor heard, String god, Set<UUID> audience) {
        var runtime=current(server);
        if(runtime==null||!ready(runtime)||evidence==null||evidence.proof()==null||heard==null
                ||!CourierRumorService.heardOne(server,evidence.subject(),god,evidence.id(),audience).filter(heard::equals).isPresent())
            return NativeRumorReadAccess.Assessment.unknown();
        var policy=receptionPolicy(server,evidence.subject(),god,evidence.id());
        if(policy==null)return NativeRumorReadAccess.Assessment.unknown();
        return runtime.data().access(server,runtime.rumors().worldId(),ledger->NativeRumorReadAccess.assessment(
                runtime.rumors().worldId(),evidence,heard,god,policy,ledger.find(evidence.subject(),god,evidence.proof().sourceId())));
    }
    static ReputationSettings.Rule receptionPolicy(MinecraftServer server,UUID subject,String god,UUID root) {
        var runtime=current(server);if(runtime==null||!ready(runtime))return null;
        return runtime.rumors().access(server,ledger->{
            var e=ledger.evidence(root);if(e==null||e.proof()==null||!e.subject().equals(subject))return null;
            var rules=runtime.settings().rules().stream().filter(r->r.godId().equals(god)&&r.courierRuleId().equals(e.proof().ruleId())).toList();
            return rules.size()==1?rules.getFirst():null; // ambiguous authored policies fail closed
        });
    }
    static ReputationLedger.Result receiveReviewed(MinecraftServer server,UUID subject,String god,RumorLedger.HeardRumor heard,
            ReputationSettings.Rule policy,int previousAffinity,SocialReview.Request request,SocialReview.Answer answer) {
        return receiveReviewed(server,subject,god,Set.of(subject),heard,policy,previousAffinity,request,answer);
    }
    static ReputationLedger.Result receiveReviewed(MinecraftServer server,UUID subject,String god,Set<UUID> audience,RumorLedger.HeardRumor heard,
            ReputationSettings.Rule policy,int previousAffinity,SocialReview.Request request,SocialReview.Answer answer) {
        var runtime=current(server);
        if(runtime==null||!ready(runtime)||request.kind()!=SocialReview.Kind.RECEPTION||!SocialReview.valid(request,answer)
                ||answer.verdict()==SocialReview.Verdict.SKIP||affinity(server,subject,god)!=previousAffinity
                ||!Objects.equals(policy,receptionPolicy(server,subject,god,heard.rootId()))
                ||!CourierRumorService.heardOne(server,subject,god,heard.rootId(),audience).filter(heard::equals).isPresent())return ReputationLedger.Result.STALE;
        return runtime.rumors().access(server,ledger->{
            var proof=ledger.evidence(heard.rootId()).proof();
            return applyApproved(server,new ReputationLedger.Decision(request.id(),ledger.worldId(),subject,god,proof.sourceId(),heard.rootId(),heard.revision(),
                    policy.id(),policy.fingerprint(),0,switch(answer.verdict()) {
                        case ACCEPT -> ReputationLedger.Outcome.ACCEPTED;case DOUBT -> ReputationLedger.Outcome.DOUBTFUL;default -> ReputationLedger.Outcome.IGNORED;
                    },ReputationLedger.DirectImpact.UNKNOWN,new ReputationLedger.Approval(request.id(),ReputationLedger.ApprovalKind.RECEPTION_REVIEW,"mythictrpg:received_semantic_review")));
        });
    }
    static RumorLedger.HeardRumor decorate(MinecraftServer server,UUID subject,String god,Set<UUID> audience,RumorLedger.HeardRumor heard) {
        var e=assessment(server,subject,god,heard.rootId(),audience);
        var policy=receptionPolicy(server,subject,god,heard.rootId());
        if(e==null||policy==null||!policy.id().equals(e.decision().policyId())||!policy.fingerprint().equals(e.decision().policyFingerprint()))return heard;
        return new RumorLedger.HeardRumor(heard.rootId(),heard.revision(),heard.text(),heard.epithet(),heard.reception(),e.decision().outcome().name(),e.version());
    }
    public static ReputationLedger.Result reviewDialogueRecovery(ServerPlayer player,DialogueRecovery.Proposal proposal) {
        var server=player.server;var runtime=current(server);var reviewer=REVIEWERS.get(server);
        if(runtime==null||!ready(runtime)||reviewer==null||server.getPlayerList().getPlayer(player.getUUID())!=player
                ||!player.getUUID().equals(proposal.context().playerId()))return ReputationLedger.Result.REJECTED;
        var entry=runtime.data().access(server,runtime.rumors().worldId(),ledger->ledger.entries().stream()
                .filter(e->e.decision().subject().equals(player.getUUID())&&e.decision().godId().equals(proposal.context().godId())&&e.decision().rootId().equals(proposal.rootId())).findFirst().orElse(null));
        try {
            var context=roomRecoveryContext(player,proposal.context())
                    ?proposal.context():AiConversationRuntimeService.INSTANCE.memoryContext(player).orElse(null);
            if(!DialogueRecovery.eligible(proposal,context,context==null?-1:reviewer.currentTurn(context),entry)
                    ||!recoverySourceCurrent(server,player,context,proposal.rootId()))return ReputationLedger.Result.STALE;
            var verdict=reviewer.review(proposal,entry);if(verdict==null||!verdict.accepted())return ReputationLedger.Result.REJECTED;
            // A completed async review cannot apply after a new turn, audience/session or source revision.
            if(!AiConversationRuntimeService.INSTANCE.memoryContextCurrent(player,context)||reviewer.currentTurn(context)!=proposal.turn()
                    ||!recoverySourceCurrent(server,player,context,proposal.rootId()))return ReputationLedger.Result.STALE;
            var d=entry.decision();
            return applyDecision(server,new ReputationLedger.Decision(proposal.id(),d.worldId(),d.subject(),d.godId(),d.sourceId(),d.rootId(),d.rumorRevision(),
                    d.policyId(),d.policyFingerprint(),entry.version(),ReputationLedger.Outcome.RECOVERED,d.directImpact(),
                    new ReputationLedger.Approval(proposal.id(),ReputationLedger.ApprovalKind.DIALOGUE_REVIEW,verdict.reasonId())),true);
        }catch(RuntimeException unavailable){return ReputationLedger.Result.REJECTED;}
    }
    private static boolean roomRecoveryContext(ServerPlayer player,com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext context) {
        return context!=null&&ConversationRooms.INSTANCE.memoryCurrent(player,context);
    }
    private static boolean recoverySourceCurrent(MinecraftServer server,ServerPlayer player,
            com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext context,UUID root) {
        if(context==null||CourierRumorService.heardOne(server,player.getUUID(),context.godId(),root,context.audience()).isEmpty())return false;
        if(!roomRecoveryContext(player,context))return true;
        var room=ConversationRooms.INSTANCE.memberships(player).stream().filter(r->r.roomId().equals(context.interactionId())
                &&context.equals(ConversationRooms.INSTANCE.memoryContext(player,r,ResourceLocation.parse(context.godId())))).findFirst().orElse(null);
        return room!=null&&!room.type().isPublic()&&room.godIds().stream().allMatch(god->
                CourierRumorService.heardOne(server,player.getUUID(),god,root,context.audience()).isPresent());
    }
    /** Preview for a future opted-in relationship consumer, NOT a replacement for existing affinity getters.
     * Caller must derive the audience from the current game context. No player command exposes this data. */
    public static Judgement judgement(MinecraftServer server,UUID subject,ResourceLocation god,Set<UUID> audience) {
        ReputationSavedData.requireThread(server);audience=Set.copyOf(audience);
        if(audience.isEmpty()||audience.size()>16||!audience.contains(subject))throw new IllegalArgumentException("judgment audience");
        var profiles=PlayerMythDataService.get(server);
        if(!profiles.isReady()||!GodDefinitionManager.INSTANCE.definitions().containsKey(god))return new Judgement(subject,god,audience,null,ReputationEngine.View.inactive(ReputationEngine.Status.UNAVAILABLE,0));
        int base=profiles.find(subject).map(p->p.affinities().getOrDefault(god,0)).orElse(0);
        var runtime=current(server);if(runtime==null)return new Judgement(subject,god,audience,null,ReputationEngine.View.inactive(ReputationEngine.Status.OFF,base));
        if(!ready(runtime))return new Judgement(subject,god,audience,null,ReputationEngine.View.inactive(ReputationEngine.Status.UNAVAILABLE,base));
        var scope=audience;
        var view=runtime.data().access(server,runtime.rumors().worldId(),ledger->engine(server,runtime,ledger,scope).preview(subject,god.toString(),base));
        return new Judgement(subject,god,audience,runtime.rumors().worldId(),view);
    }
    /** Recheck after an async consumer returns; new policy, evidence, audience or raw affinity invalidates it. */
    public static boolean stillCurrent(MinecraftServer server,UUID subject,ResourceLocation god,Set<UUID> audience,Judgement view) {
        return view.view().status()==ReputationEngine.Status.READY&&judgement(server,subject,god,audience).equals(view);
    }
    /** Only a private room's currently shared, received assessments can enter a speaker prompt.
     * A rumor disclosure audience is not a public-publication grant; public rooms remain UNKNOWN. */
    static RoomSocialContextProvider.ProviderSnapshot roomSnapshot(MinecraftServer server,ResourceLocation providerId,
            RoomSocialContext.Scope scope) {
        var runtime=current(server);
        if(runtime==null||!ready(runtime))return new RoomSocialContextProvider.ProviderSnapshot(providerId,1,scope,List.of());
        long rumorRevision=runtime.rumors().access(server,RumorLedger::revision);
        long revision=Math.addExact(Math.addExact(Math.addExact(1,rumorRevision),runtime.data().snapshot().revision()),
                GodDefinitionManager.INSTANCE.generation());
        if(scope.roomType().isPublic()||scope.purpose()!=RoomSocialContext.Purpose.TURN
                ||scope.audiencePlayerIds().size()>16)return new RoomSocialContextProvider.ProviderSnapshot(providerId,revision,scope,List.of());
        var facts=new ArrayList<RoomSocialContextProvider.Fact>();
        var publication=new RoomSocialContextProvider.Publication(false,scope.audiencePlayerIds(),scope.participantGodIds());
        for(var subject:scope.participantPlayerIds().stream().sorted().toList()) {
            var heard=runtime.rumors().heard(server,subject,scope.speakerGodId().toString(),scope.audiencePlayerIds());
            for(var rumor:heard.stream().sorted(Comparator.comparing(RumorLedger.HeardRumor::rootId)).toList()) {
                if("UNASSESSED".equals(rumor.assessment())||!scope.participantGodIds().stream().allMatch(god->
                        CourierRumorService.heardOne(server,subject,god.toString(),rumor.rootId(),scope.audiencePlayerIds()).isPresent()))continue;
                var assessment=assessment(server,subject,scope.speakerGodId().toString(),rumor.rootId(),scope.audiencePlayerIds());
                if(assessment==null||assessment.version()!=rumor.assessmentVersion())continue;
                String statement=switch(assessment.decision().outcome()) {
                    case ACCEPTED,DOUBTFUL,DISPUTED -> "Current God assessment of an unverified rumor ("+rumor.assessment()+"): "+rumor.text();
                    case IGNORED,RECOVERED,RETRACTED -> "A previously received rumor is assessed "+rumor.assessment()+"; it is not a current accepted world fact.";
                };
                if(statement.length()>256)continue; // Never truncate a claim into a different meaning.
                facts.add(new RoomSocialContextProvider.Fact(rumor.rootId().toString(),subject,
                        RoomSocialContextProvider.Kind.REPUTATION,statement,providerId,rumor.rootId().toString(),
                        assessment.version(),Set.of(scope.speakerGodId()),publication,Optional.empty()));
                if(facts.size()==RoomSocialContext.MAX_VISIBLE_FACTS)break;
            }
            if(facts.size()==RoomSocialContext.MAX_VISIBLE_FACTS)break;
        }
        return new RoomSocialContextProvider.ProviderSnapshot(providerId,revision,scope,facts);
    }
    private static void warn(MinecraftServer server,ReputationSavedData data) {
        long now=server.overworld().getGameTime();if(now<WARNINGS.getOrDefault(server,0L)||data.snapshot().entries().size()<Math.ceil(ReputationLedger.LIMIT*.9))return;
        String message="[MythAI] 평판 판단 기록이 보관 상한의 90%에 도달했습니다. 관리자는 보관 용량 증설을 검토해 주세요. 자동 삭제하지 않습니다.";
        MythicTrpg.LOGGER.warn(message);server.getPlayerList().broadcastSystemMessage(Component.literal(message),false);WARNINGS.put(server,now+1200);
    }
}
