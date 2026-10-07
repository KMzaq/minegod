package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.memorycontract.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.room.ConversationRoomLedger;
import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.ai.room.RoomTurnPolicy;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.ai.social.RoomSocialContext;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.watch.RewardWatchSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Game-owned producer, bounded review dispatcher and final validator. OFF has no model or storage work. */
public final class SocialRuntime {
    private static final Map<MinecraftServer,SocialRuntime> INSTANCES=new IdentityHashMap<>();
    private final MinecraftServer server;
    private final SocialSettings settings;
    private final RewardWatchSettings barriers;
    private final Map<UUID,Turn> turns=new HashMap<>();
    private final Map<UUID,Turn> roomTurns=new HashMap<>();
    private final LinkedHashSet<UUID> roomPlayerReceipts=new LinkedHashSet<>();
    private final Set<UUID> privateInteractions=new HashSet<>();
    private final Map<String,CompletableFuture<SocialReview.Answer>> inFlight=new HashMap<>();
    private final Map<String,Integer> attempts=new HashMap<>();
    private final Map<String,Long> retryAt=new HashMap<>();
    private final Map<UUID,DialogueRecovery.Proposal> approvedRecovery=new HashMap<>();
    private final Map<UUID,ConversationMemoryContext> testPublications=new HashMap<>();
    private int receiptCursor;
    private static final ResourceLocation ROOM_REPUTATION_PROVIDER=ResourceLocation.fromNamespaceAndPath("mythictrpg","room_reputation");
    private static final class Turn {
        final ConversationMemoryContext context; final long number;
        final UUID roomTurnId;
        final long roomRevision;
        final Set<String> roomGods;
        final Set<UUID> openingAudience;
        final boolean roomTest;
        final List<SocialReview.Line> conversation;
        final List<CourierEngine.CandidateInput> captured=new ArrayList<>();
        CourierRumorService.DialogueWitness witness;
        Set<UUID> recoveryTopics=Set.of();
        final Set<UUID> recoveryDone=new HashSet<>();
        boolean delivered;
        Turn(ConversationMemoryContext c,long n,List<SocialReview.Line> lines){this(c,n,lines,null,0,Set.of(),Set.of(),false);}
        Turn(ConversationMemoryContext c,long n,List<SocialReview.Line> lines,UUID roomTurnId,long roomRevision,
                Set<String> roomGods,Set<UUID> openingAudience,boolean roomTest){
            context=c;number=n;conversation=new ArrayList<>(lines);this.roomTurnId=roomTurnId;this.roomRevision=roomRevision;
            this.roomGods=Set.copyOf(roomGods);this.openingAudience=Set.copyOf(openingAudience);this.roomTest=roomTest;
        }
        boolean room(){return roomTurnId!=null;}
    }
    private SocialRuntime(MinecraftServer server,SocialSettings settings,RewardWatchSettings barriers){this.server=server;this.settings=settings;this.barriers=barriers;}
    public static void started(ServerStartedEvent event) {
        var server=event.getServer();requireThread(server);
        try {
            var path=server.getServerDirectory().resolve("config/mythictrpg/social-rumor.json");
            var settings=SocialSettings.load(path);
            if(!settings.enabled()||MemoryFoundationSettings.mode()!=MemoryFoundationSettings.Mode.RUMOR_TEST)return;
            var runtime=new SocialRuntime(server,settings,RewardWatchSettings.load(path.resolveSibling("reward-watch.json")));
            INSTANCES.put(server,runtime);
            // The reviewer is owned by this game service; only a validated, single-use prepared verdict can pass.
            boolean reviewerInstalled=ReputationService.installDialogueReviewer(server,new DialogueRecovery.Reviewer(){
                public long currentTurn(UUID player){var t=runtime.turns.get(player);return t==null?-1:t.number;}
                public long currentTurn(ConversationMemoryContext context){
                    if(context==null)return -1;
                    var t=runtime.roomTurns.get(context.interactionId());
                    return t!=null&&t.context.equals(context)&&runtime.roomCurrent(t)?t.number:currentTurn(context.playerId());
                }
                public DialogueRecovery.Verdict review(DialogueRecovery.Proposal proposal,ReputationLedger.Entry entry){
                    boolean approved=proposal.equals(runtime.approvedRecovery.remove(proposal.id()));
                    return new DialogueRecovery.Verdict(approved,"mythictrpg:contextual_dialogue_persuasion");
                }
            });
            if(reviewerInstalled)RoomSocialContext.register(server,ROOM_REPUTATION_PROVIDER,
                    scope->ReputationService.roomSnapshot(server,ROOM_REPUTATION_PROVIDER,scope));
        } catch(RuntimeException invalid){MythicTrpg.LOGGER.error("Social rumor pipeline disabled; existing dialogue unchanged",invalid);}
    }
    public static void stopped(ServerStoppedEvent event) {
        var r=INSTANCES.remove(event.getServer());if(r!=null){
            RoomSocialContext.unregister(event.getServer(),ROOM_REPUTATION_PROVIDER);
            r.inFlight.values().forEach(f->f.cancel(true));r.inFlight.clear();r.turns.clear();r.roomTurns.clear();r.approvedRecovery.clear();
        }
    }
    private static void requireThread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("social game thread");}
    private static SocialRuntime current(MinecraftServer server){requireThread(server);return MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.RUMOR_TEST?INSTANCES.get(server):null;}
    /** Content/admin marks a whole current interaction private before confidential speech. AI text cannot do this. */
    public static boolean privateConversation(ServerPlayer player,boolean value) {
        var r=current(player.server);var c=AiConversationRuntimeService.INSTANCE.memoryContext(player).orElse(null);
        if(r==null||c==null)return false;
        if(value) {if(r.privateInteractions.size()>=128&&!r.privateInteractions.contains(c.interactionId()))return false;r.privateInteractions.add(c.interactionId());}
        else r.privateInteractions.remove(c.interactionId());
        // Discard unpublished observations from the current exchange; no retroactive erasure of delivered rumors.
        r.turns.values().stream().filter(t->t.context.interactionId().equals(c.interactionId())).forEach(t->
                t.captured.forEach(i->CourierRumorService.revokeUnpublished(player.server,i.rootId())));
        r.turns.entrySet().removeIf(e->e.getValue().context.interactionId().equals(c.interactionId()));
        return true;
    }
    private boolean obscured(ServerPlayer player,BlockPos point) {
        String dimension=player.level().dimension().location().toString();
        return settings.blocked(dimension,point.getX(),point.getY(),point.getZ())
                ||barriers.barriers().stream().anyMatch(b->SocialSettings.inside(b.area(),dimension,point.getX(),point.getY(),point.getZ()));
    }
    /** Called only for an accepted, real player turn by the production dialogue adapter, not model output. */
    public static void playerTurn(ServerPlayer player,long number,String text) {
        var r=current(player.server);if(r==null)return;
        var c=AiConversationRuntimeService.INSTANCE.memoryContext(player).orElse(null);
        if(c==null||!AiConversationRuntimeService.INSTANCE.recordingAllowed(player,c)
                ||number<1||text==null||text.isBlank()||text.length()>1200){r.turns.remove(player.getUUID());return;}
        var old=r.turns.get(player.getUUID());
        if(old!=null&&old.context.equals(c)&&old.number>=number)return;
        var lines=new ArrayList<SocialReview.Line>();
        if(old!=null&&old.context.equals(c)&&old.delivered)lines.addAll(old.conversation);
        while(lines.size()>4)lines.removeFirst();
        lines.add(new SocialReview.Line("PLAYER",text));
        var turn=new Turn(c,number,lines);r.turns.put(player.getUUID(),turn);
        if(!r.settings.ordinaryDialogueObservable()||r.settings.privateGods().contains(c.godId())
                ||r.privateInteractions.contains(c.interactionId())||r.obscured(player,player.blockPosition())||text.length()>500)return;
        // Freeze real visibility now; the same observer must also witness the delivered reply.
        UUID source=UUID.nameUUIDFromBytes((c.worldId()+"/"+c.playerId()+"/"+c.generation()+"/"+number).getBytes(StandardCharsets.UTF_8));
        turn.witness=CourierRumorService.witnessDialogue(player,source,text,c.audience());
    }
    public static void cancelPlayer(UUID player) { for(var r:INSTANCES.values()){requireThread(r.server);r.turns.remove(player);} }
    /** Game-issued accepted room input; no selected-UI-room or player-only legacy scope is consulted. */
    public static void roomPlayerPublished(MinecraftServer server,ConversationRoomSnapshot room,
            ConversationRoomLedger.TurnLease lease,RoomDialogueEvent event) {
        var r=current(server);if(r==null||!roomInputMatches(room,lease,event)
                ||!ConversationRooms.INSTANCE.socialTurnCurrent(room.roomId(),room.revision(),lease.sequence()))return;
        if(!r.roomPlayerReceipts.add(event.messageId()))return;
        while(r.roomPlayerReceipts.size()>8192)r.roomPlayerReceipts.remove(r.roomPlayerReceipts.iterator().next());
        var player=server.getPlayerList().getPlayer(lease.playerId());
        if(player==null||event.text().length()>1200||event.text().isBlank())return;
        var context=ConversationRooms.INSTANCE.memoryContext(player,room,ResourceLocation.parse(lease.godId()));
        if(context==null||!AiConversationRuntimeService.INSTANCE.recordingAllowed(player,context))return;
        var receivers=new LinkedHashSet<>(event.fullTextReceiverIds());receivers.add(player.getUUID());
        var old=r.roomTurns.get(room.roomId());
        var lines=new ArrayList<SocialReview.Line>();
        if(old!=null&&old.context.equals(context)&&old.delivered)lines.addAll(old.conversation);
        while(lines.size()>4)lines.removeFirst();
        lines.add(new SocialReview.Line("PLAYER",event.text()));
        var turn=new Turn(context,lease.sequence(),lines,lease.turnId(),room.revision(),room.godIds(),receivers,room.recordingScope().isTest());
        r.roomTurns.put(room.roomId(),turn);
        // A private room is never an ordinary-dialogue rumor source, even if an admin did not mark it confidential.
        if(!room.type().isPublic()||!r.settings.ordinaryDialogueObservable()||receivers.size()>16
                ||r.settings.privateGods().stream().anyMatch(room.godIds()::contains)
                ||r.obscured(player,player.blockPosition())||event.text().length()>500)return;
        turn.witness=CourierRumorService.witnessDialogue(player,event.messageId(),event.text(),Set.copyOf(receivers));
    }
    /** Actual complete speech bundle; a first page or first of several speeches cannot settle a social turn. */
    public static void roomReplyCompleted(ServerPlayer player,ConversationRoomSnapshot room,
            ConversationRoomLedger.TurnLease lease,List<RoomDialogueEvent> speeches) {
        var r=current(player.server);if(r==null||room==null||lease==null||speeches==null||speeches.isEmpty())return;
        var turn=r.roomTurns.get(room.roomId());
        if(turn==null||turn.delivered||!turn.room()||!turn.roomTurnId.equals(lease.turnId())
                ||turn.number!=lease.sequence()||!turn.context.playerId().equals(player.getUUID())
                ||!turn.context.godId().equals(lease.godId())||!r.roomCurrent(turn)
                ||speeches.size()>8||speeches.stream().map(RoomDialogueEvent::messageId).distinct().count()!=speeches.size())return;
        if(!roomSpeechMatches(room,lease,speeches,player.getUUID()))return;
        String reply=String.join("\n",speeches.stream().map(RoomDialogueEvent::text).toList());
        if(reply.isBlank()||reply.length()>1200)return;
        turn.conversation.add(new SocialReview.Line("GOD",reply));turn.delivered=true;
        if(turn.witness!=null&&speeches.stream().allMatch(e->e.fullTextReceiverIds().containsAll(turn.openingAudience))
                &&!r.obscured(player,player.blockPosition())) {
            String exchange="플레이어 발언: "+turn.witness.opening().excerpt()+"\n신의 공개 답변: "+reply;
            if(exchange.length()<=600&&(!turn.roomTest||r.testPublications.size()<4096)) {
                var captured=CourierRumorService.captureDialogue(player,turn.witness,exchange);
                turn.captured.addAll(captured);
                if(turn.roomTest)captured.forEach(input->r.testPublications.put(input.rootId(),turn.context));
            }
        }
        r.recover(player,turn);
    }
    /** Selected roots came from this exact room's bounded memory retrieval, not an LLM target list. */
    public static void roomRecoveryTopics(ServerPlayer player,RoomConversationEngine.Request request,Collection<UUID> roots) {
        var r=current(player.server);if(r==null||request==null||roots==null||roots.size()>3
                ||request.secondary()||request.publicRoom()||!request.recording()
                ||!request.playerId().equals(player.getUUID()))return;
        var turn=r.roomTurns.get(request.roomId());
        if(turn==null||turn.delivered||!turn.room()||!turn.roomTurnId.equals(request.turnId())
                ||!turn.context.godId().equals(request.speakerGodId().toString())
                ||!turn.context.equals(request.speakerState().memoryContext())||!r.roomCurrent(turn))return;
        turn.recoveryTopics=roots.stream().filter(root->root!=null&&r.roomRootAllowed(turn,root))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private boolean roomCurrent(Turn turn) {
        if(!turn.room())return false;
        var player=server.getPlayerList().getPlayer(turn.context.playerId());
        return player!=null&&ConversationRooms.INSTANCE.socialTurnCurrent(turn.context.interactionId(),turn.roomRevision,turn.number)
                &&ConversationRooms.INSTANCE.memoryCurrent(player,turn.context)
                &&AiConversationRuntimeService.INSTANCE.recordingAllowed(player,turn.context);
    }
    private boolean roomRootAllowed(Turn turn,UUID root) {
        if(!turn.room()||turn.context.audience().size()>16)return false;
        return turn.roomGods.stream().allMatch(god->CourierRumorService.heardOne(server,turn.context.playerId(),god,root,
                turn.context.audience()).isPresent());
    }
    static boolean roomInputMatches(ConversationRoomSnapshot room,ConversationRoomLedger.TurnLease lease,RoomDialogueEvent event) {
        return room!=null&&lease!=null&&event!=null&&event.recordingScope().recordingAllowed()
                &&room.recordingScope()==event.recordingScope()&&room.roomId().equals(event.roomId())
                &&room.revision()==event.revision()&&room.roomId().equals(lease.roomId())
                &&room.revision()==lease.revision()&&event.turnId().filter(lease.turnId()::equals).isPresent()
                &&event.turnSequence()==lease.sequence()&&"PLAYER".equals(event.role())
                &&lease.playerId().toString().equals(event.speakerId())&&room.godIds().contains(lease.godId())
                &&event.heardGodIds().contains(lease.godId());
    }
    static boolean roomSpeechMatches(ConversationRoomSnapshot room,ConversationRoomLedger.TurnLease lease,
            List<RoomDialogueEvent> speeches,UUID player) {
        if(room==null||lease==null||speeches==null||speeches.isEmpty()||speeches.size()>8||player==null
                ||speeches.stream().map(RoomDialogueEvent::messageId).distinct().count()!=speeches.size())return false;
        for(var speech:speeches)if(!room.roomId().equals(speech.roomId())||room.revision()!=speech.revision()
                ||!speech.turnId().filter(lease.turnId()::equals).isPresent()||speech.turnSequence()!=lease.sequence()
                ||!"NPC".equals(speech.role())||!lease.godId().equals(speech.speakerId())
                ||!RoomTurnPolicy.fullyDispatched(speech,player))return false;
        return true;
    }
    /** Only authorized rumors actually selected for this turn, never an LLM-chosen target or global rumor search. */
    public static void recoveryTopics(ServerPlayer player,long number,Collection<UUID> roots) {
        var r=current(player.server);if(r==null)return;var t=r.turns.get(player.getUUID());
        if(t==null||t.number!=number||t.delivered||roots.size()>3
                ||!AiConversationRuntimeService.INSTANCE.recordingAllowed(player,t.context))return;
        t.recoveryTopics=roots.stream().filter(root->CourierRumorService.heardOne(player.server,player.getUUID(),t.context.godId(),root,t.context.audience()).isPresent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    /** Central speaker delivery only; listener mirrors and quest acknowledgements do not complete another player's turn. */
    public static void delivered(ServerPlayer player,String god,String text) {
        var r=current(player.server);if(r==null)return;var t=r.turns.get(player.getUUID());
        if(t==null||t.delivered||!t.context.godId().equals(god)||!AiConversationRuntimeService.INSTANCE.recordingAllowed(player,t.context)
                ||text==null||text.isBlank()||text.length()>1200)return;
        t.conversation.add(new SocialReview.Line("GOD",text));t.delivered=true;
        if(t.witness!=null&&!r.privateInteractions.contains(t.context.interactionId())&&!r.obscured(player,player.blockPosition())) {
            String exchange="플레이어 발언: "+t.witness.opening().excerpt()+"\n신의 공개 답변: "+text;
            boolean test=AiConversationRuntimeService.INSTANCE.isTestConversation(player);
            if(exchange.length()<=600&&(!test||r.testPublications.size()<4096)){
                var captured=CourierRumorService.captureDialogue(player,t.witness,exchange);
                t.captured.addAll(captured);
                if(test)
                    captured.forEach(input->r.testPublications.put(input.rootId(),t.context));
            }
        }
        r.recover(player,t);
    }
    /** Approved important result adapter. The original event remains administrator-only; only authored public text leaves it. */
    public static void important(MinecraftServer server,ActionRecord.Draft event) {
        var r=current(server);if(r==null||event.position()==null||event.gameTick()!=server.overworld().getGameTime())return;
        var player=server.getPlayerList().getPlayer(event.actorId());
        if(player==null||!player.level().dimension().location().toString().equals(event.dimensionId()))return;
        var p=event.position();var pos=new BlockPos(p.x(),p.y(),p.z());if(r.obscured(player,pos))return;
        for(var route:r.settings.eventRoutes())if(route.matches(event))
            CourierRumorService.captureBound(player,event.occurrenceId(),event.sourceRevision(),ResourceLocation.parse(route.eventType()),
                    CourierSettings.Source.GAME_EVENT,pos,route.publicDescription(),Set.of(player.getUUID()));
    }
    public static void tick(ServerTickEvent.Post event) {
        var r=current(event.getServer());if(r==null||event.getServer().getTickCount()%20!=0)return;
        r.turns.entrySet().removeIf(e->{var p=r.server.getPlayerList().getPlayer(e.getKey());return p==null||!AiConversationRuntimeService.INSTANCE.recordingAllowed(p,e.getValue().context);});
        r.roomTurns.entrySet().removeIf(e->!r.roomCurrent(e.getValue()));
        r.privateInteractions.removeIf(id->r.turns.values().stream().noneMatch(t->t.context.interactionId().equals(id))
                &&r.server.getPlayerList().getPlayers().stream().noneMatch(p->AiConversationRuntimeService.INSTANCE.memoryContext(p).map(c->c.interactionId().equals(id)).orElse(false)));
        r.attempts.keySet().removeIf(key->key.startsWith("recover/")&&!r.inFlight.containsKey(key)
                &&java.util.stream.Stream.concat(r.turns.values().stream(),r.roomTurns.values().stream())
                        .noneMatch(t->key.startsWith("recover/"+t.context.generation()+"/"+t.number+"/")));
        r.retryAt.keySet().retainAll(r.attempts.keySet());
        // Busy/preempted recovery is retried only while this exact delivered turn remains current.
        for(var entry:r.turns.entrySet())if(entry.getValue().delivered&&!entry.getValue().recoveryTopics.isEmpty()){
            var player=r.server.getPlayerList().getPlayer(entry.getKey());if(player!=null)r.recover(player,entry.getValue());
        }
        for(var turn:r.roomTurns.values())if(turn.delivered&&!turn.recoveryTopics.isEmpty()){
            var player=r.server.getPlayerList().getPlayer(turn.context.playerId());if(player!=null)r.recover(player,turn);
        }
        for(var input:CourierRumorService.pendingCandidates(r.server,32)) {
            if(!r.testPublicationAllowed(input.rootId()))continue;
            boolean awaiting=r.turns.values().stream().anyMatch(t->!t.delivered&&t.captured.contains(input));
            if(awaiting)continue;
            String rule=CourierRumorService.ruleId(r.server,input.rootId());
            var request=new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RUMOR,"",0,input.excerpt(),List.of(),
                    r.settings.publicationGuidance().getOrDefault(rule,""));
            r.submit("publish/"+input.rootId(),request,answer->{
                if(!r.testPublicationAllowed(input.rootId())||!SocialReview.valid(request,answer))return;
                boolean committed=answer.verdict()==SocialReview.Verdict.SKIP?CourierRumorService.revoke(r.server,input.rootId())
                        :CourierRumorService.publishCandidate(r.server,new CourierEngine.Candidate(input,answer.quote(),answer.claim(),answer.epithet()));
                if(committed)r.testPublications.remove(input.rootId());
            });
        }
        r.receive();
    }
    private void receive() {
        var data=RumorSavedData.get(server);if(!data.ready())return;
        var receipts=data.snapshot().receipts();if(receipts.isEmpty())return;
        int count=Math.min(16,receipts.size());
        for(int i=0;i<count;i++){
            var receipt=receipts.get(Math.floorMod(receiptCursor+i,receipts.size()));
            var source=data.access(server,l->l.evidence(receipt.rootId()));
            if(source==null||source.proof()==null)continue;
            var player=server.getPlayerList().getPlayer(source.subject());
            var active=roomTurns.values().stream().filter(t->t.delivered&&t.context.playerId().equals(source.subject())
                    &&receptionRelevant(t.context,true,t.recoveryTopics,source.subject(),receipt.godId(),receipt.rootId())
                    &&roomCurrent(t)&&roomRootAllowed(t,receipt.rootId())).findFirst().orElse(null);
            if(active==null)active=turns.get(source.subject());
            final Turn selected=active;
            // Do not fan out one model judgment per god merely because a rumor was delivered.
            // Review only an actually selected rumor after that god's current reply has finished.
            if(selected==null||player==null||!turnCurrent(player,selected)
                    ||!receptionRelevant(selected.context,selected.delivered,selected.recoveryTopics,
                    source.subject(),receipt.godId(),receipt.rootId()))continue;
            var audience=selected.room()?selected.context.audience():Set.of(source.subject());
            var heard=CourierRumorService.heardOne(server,source.subject(),receipt.godId(),receipt.rootId(),audience).orElse(null);
            var policy=ReputationService.receptionPolicy(server,source.subject(),receipt.godId(),receipt.rootId());
            if(heard==null||policy==null||ReputationService.assessment(server,source.subject(),receipt.godId(),receipt.rootId(),audience)!=null)continue;
            int affinity;try{affinity=ReputationService.affinity(server,source.subject(),receipt.godId());}catch(RuntimeException unavailable){continue;}
            if(affinity<policy.minimumBaseAffinity()||affinity>policy.maximumBaseAffinity())continue;
            var request=new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RECEPTION,receipt.godId(),affinity,heard.text(),List.of(),
                    "수신 태도: "+heard.reception()+". 전언은 세계의 확정 사실이 아니다.");
            submit("receive/"+receipt.rootId()+"/"+receipt.godId(),request,answer->{
                if(!turnCurrent(player,selected)||selected.room()&&!roomRootAllowed(selected,receipt.rootId()))return;
                if(generating(source.subject(),receipt.godId()))throw new SocialReview.Busy();
                ReputationService.receiveReviewed(server,source.subject(),receipt.godId(),audience,heard,policy,affinity,request,answer);
            });
        }
        receiptCursor=Math.floorMod(receiptCursor+count,receipts.size());
    }
    static boolean receptionRelevant(ConversationMemoryContext context,boolean delivered,Set<UUID> selected,
            UUID subject,String god,UUID root) {
        return delivered&&context.playerId().equals(subject)&&context.godId().equals(god)&&selected.contains(root);
    }
    private boolean generating(UUID subject,String god){
        var turn=turns.get(subject);
        return turn!=null&&!turn.delivered&&turn.context.godId().equals(god)
                ||roomTurns.values().stream().anyMatch(t->!t.delivered&&t.context.playerId().equals(subject)
                        &&t.context.godId().equals(god)&&roomCurrent(t));
    }
    private boolean turnCurrent(ServerPlayer player,Turn turn) {
        return turn.room()?roomTurns.get(turn.context.interactionId())==turn&&roomCurrent(turn)
                :turns.get(player.getUUID())==turn&&AiConversationRuntimeService.INSTANCE.recordingAllowed(player,turn.context);
    }
    private boolean testPublicationAllowed(UUID root) {
        var context=testPublications.get(root);
        if(context==null)return true;
        var player=server.getPlayerList().getPlayer(context.playerId());
        return player!=null&&AiConversationRuntimeService.INSTANCE.recordingAllowed(player,context);
    }
    private void recover(ServerPlayer player,Turn turn) {
        if(!turnCurrent(player,turn))return;
        var heard=RumorSavedData.get(server).heard(server,player.getUUID(),turn.context.godId(),turn.context.audience());
        int reviewed=0;
        for(var h:heard) {
            if(!turn.recoveryTopics.contains(h.rootId())||turn.recoveryDone.contains(h.rootId())
                    ||turn.room()&&!roomRootAllowed(turn,h.rootId()))continue;
            var entry=ReputationService.assessment(server,player.getUUID(),turn.context.godId(),h.rootId(),turn.context.audience());
            if(entry==null||entry.terminal()||entry.decision().outcome()==ReputationLedger.Outcome.IGNORED)continue;
            if(reviewed++>=2)break;
            int affinity;try{affinity=ReputationService.affinity(server,player.getUUID(),turn.context.godId());}catch(RuntimeException unavailable){return;}
            var request=new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RECOVERY,turn.context.godId(),affinity,h.text(),turn.conversation,
                    settings.recoveryGuidance().getOrDefault(entry.decision().policyId(),""));
            submit("recover/"+turn.context.generation()+"/"+turn.number+"/"+h.rootId(),request,answer->{
                turn.recoveryDone.add(h.rootId());
                if(!turnCurrent(player,turn)||!turn.delivered||!SocialReview.valid(request,answer)||answer.verdict()!=SocialReview.Verdict.RECOVER
                        ||turn.room()&&!roomRootAllowed(turn,h.rootId())
                        ||ReputationService.affinity(server,player.getUUID(),turn.context.godId())!=affinity)return;
                String explanation=answer.quote();
                var proposal=new DialogueRecovery.Proposal(request.id(),turn.context,turn.number,h.rootId(),entry.version(),explanation);
                approvedRecovery.put(proposal.id(),proposal);
                try {ReputationService.reviewDialogueRecovery(player,proposal);}finally{approvedRecovery.remove(proposal.id());}
            });
        }
    }
    private void submit(String key,SocialReview.Request request,Consumer<SocialReview.Answer> commit) {
        long now=server.overworld().getGameTime();
        if(inFlight.size()>=4||inFlight.containsKey(key)||attempts.getOrDefault(key,0)>=3||now<retryAt.getOrDefault(key,0L))return;
        if(attempts.size()>=8192&&!attempts.containsKey(key)&&!key.startsWith("recover/"))return;
        CompletableFuture<SocialReview.Answer> future;
        try{future=Objects.requireNonNull(SocialReview.submit(request));}catch(RuntimeException unavailable){return;}
        inFlight.put(key,future);attempts.merge(key,1,Integer::sum);retryAt.put(key,now+100);
        future.whenComplete((answer,failure)->server.execute(()->{
            if(INSTANCES.get(server)!=this||!inFlight.remove(key,future)||current(server)!=this)return;
            if(failure!=null){
                Throwable cause=failure;while(cause instanceof java.util.concurrent.CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                if(cause instanceof SocialReview.Busy)attempts.computeIfPresent(key,(k,n)->Math.max(0,n-1));
                MythicTrpg.LOGGER.debug("Social review deferred/failed: {}",request.kind());return;
            }
            attempts.put(key,3); // a completed semantic verdict is not polled again
            try{commit.accept(answer);}
            catch(SocialReview.Busy deferred){attempts.put(key,0);retryAt.put(key,server.overworld().getGameTime()+100);}
            catch(RuntimeException rejected){MythicTrpg.LOGGER.warn("Social review rejected; original gameplay unchanged",rejected);}
        }));
    }
    public static String status(MinecraftServer server){var r=current(server);return r==null?"Social pipeline OFF":"reviews="+r.inFlight.size()+", tracked="+r.attempts.size()+"; automatic spawn/respawn OFF";}
    public static void commands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("mythadmin").requires(s->s.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("social")
                        .then(net.minecraft.commands.Commands.literal("status").executes(c->{c.getSource().sendSuccess(()->net.minecraft.network.chat.Component.literal(status(c.getSource().getServer())),false);return 1;}))
                        .then(net.minecraft.commands.Commands.literal("private").then(net.minecraft.commands.Commands.argument("player",net.minecraft.commands.arguments.EntityArgument.player())
                                .then(net.minecraft.commands.Commands.argument("value",com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c->{
                                    boolean applied=privateConversation(net.minecraft.commands.arguments.EntityArgument.getPlayer(c,"player"),com.mojang.brigadier.arguments.BoolArgumentType.getBool(c,"value"));
                                    c.getSource().sendSuccess(()->net.minecraft.network.chat.Component.literal(applied?"현재 대화의 비밀 구간 설정을 반영했습니다.":"활성 시험 대화/소문 설정을 확인하세요."),false);return applied?1:0;
                                }))))));
    }
}
