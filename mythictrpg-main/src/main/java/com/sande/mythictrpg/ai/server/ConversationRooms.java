package com.sande.mythictrpg.ai.server;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.region.*;
import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.network.ConversationRoomsPayload;
import com.sande.mythictrpg.rumor.RumorSavedData;
import com.sande.mythictrpg.relation.GodRelationRoomContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Game-owned room routing. A selected PRIVATE room is never an ambient gameplay authority. */
public final class ConversationRooms {
    public static final ConversationRooms INSTANCE=new ConversationRooms();
    private ConversationRoomLedger ledger=new ConversationRoomLedger();
    private MinecraftServer server;
    private ConnectedBiomeRegions regions;
    private FixedRoomReservations fixedReservations;
    private final Map<UUID,ConnectedBiomeRegions.Region> fixedRegions=new HashMap<>();
    private final Map<UUID,List<Line>> history=new HashMap<>();
    private record FeedbackKey(UUID room, ResourceLocation god, UUID player) {}
    private final Map<FeedbackKey,List<String>> feedback=new HashMap<>();
    private final Map<UUID,String> lastSpeakers=new HashMap<>();
    private final Set<UUID> splitting=new HashSet<>();
    private final Map<UUID,Long> changedAt=new HashMap<>();
    private final Map<UUID,ConversationRoomLedger.TurnLease> turns=new HashMap<>();
    private record Line(RoomConversationEngine.HistoryLine value,Set<UUID> audience,Set<String> gods) {}
    private record PreparedTurn(RoomConversationEngine.Request request, GodRelationRoomContext.Snapshot relations) {}
    private ConversationRooms() {}
    public static boolean enabled() { return RoomConversationEngineRouter.INSTANCE.available()
            && Boolean.parseBoolean(System.getProperty("mythictrpg.conversationRooms.enabled","true")); }

    private void attach(MinecraftServer value) {
        if(!value.isSameThread())throw new IllegalStateException("Room authority requires server thread");
        if(server!=value) {
            clear();server=value;regions=new ConnectedBiomeRegions(new MinecraftBiomeRegionSource(value));
            fixedReservations=new FixedRoomReservations(regions,ConversationRoomLedger.MAX_ROOMS);
        }
    }
    public void clear() {
        RoomConversationEngineRouter.INSTANCE.engine().stop();
        ledger=new ConversationRoomLedger(); fixedRegions.clear();history.clear();feedback.clear();lastSpeakers.clear();
        splitting.clear();changedAt.clear();turns.clear();
        if(fixedReservations!=null)fixedReservations.clear();fixedReservations=null;
        if(regions!=null)regions.invalidateAll();regions=null;server=null;
    }
    public boolean hasMembership(ServerPlayer p) {return ledger.activeRooms().stream().anyMatch(r->r.playerIds().contains(p.getUUID()));}
    public List<ConversationRoomSnapshot> memberships(ServerPlayer p) {
        attach(p.server);return ledger.activeRooms().stream().filter(r->r.playerIds().contains(p.getUUID())).toList();
    }
    public Optional<ConversationRoomSnapshot> resolveMember(ServerPlayer p,String idOrCode) {
        return memberships(p).stream().filter(r->r.code().equalsIgnoreCase(idOrCode)||r.roomId().toString().equals(idOrCode)).findFirst();
    }
    public Optional<ConversationRoomSnapshot> selectedPrivate(ServerPlayer p) {attach(p.server);return ledger.selectedPrivateRoom(p.getUUID());}
    public void selectPrivate(ServerPlayer p,UUID id) {attach(p.server);ledger.selectPrivate(p.getUUID(),id);sync(p);}

    /** Nonbinding readiness check. Durable encounter starts must use reserveFixed immediately before commit. */
    public boolean canStartFixed(ServerPlayer p,Collection<ResourceLocation> gods) {
        attach(p.server);
        if(!fixedStartAvailable(p,gods))return false;
        var probe=regions.open(MinecraftBiomeRegionSource.cell(p.serverLevel(),p.blockPosition()));
        if(probe==null)return false;
        regions.release(probe);return true;
    }
    private boolean fixedStartAvailable(ServerPlayer p,Collection<ResourceLocation> gods) {
        if(memberships(p).size()>=ConversationRoomLedger.MAX_ROOMS_PER_PLAYER)return false;
        if(gods.isEmpty()||gods.size()>ConversationRoomSnapshot.MAX_GODS
                ||new HashSet<>(gods).size()!=gods.size()
                ||gods.stream().anyMatch(g->g==null||GodDefinitionManager.INSTANCE.find(g).isEmpty()))return false;
        if(ledger.publicRoomForPlayer(p.getUUID()).isPresent()||AiConversationRuntimeService.INSTANCE.isTestConversation(p))return false;
        if(gods.stream().anyMatch(g->ledger.publicRoomForGod(g.toString()).isPresent()))return false;
        if(!fixedReservations.hasCapacity(ledger.activeRooms().size())
                ||!fixedReservations.participantsAvailable(p.getUUID(),gods.stream().map(Object::toString).toList()))return false;
        var cell=MinecraftBiomeRegionSource.cell(p.serverLevel(),p.blockPosition());
        for(var entry:fixedRegions.entrySet()) if(ledger.find(entry.getKey()).isPresent()
                && regions.membership(entry.getValue(),cell)!=ConnectedBiomeRegions.Membership.DIFFERENT)return false;
        return fixedReservations.regionAvailable(cell);
    }
    /** Reserves the actual loaded origin, room slot, player and Gods before encounter side effects. */
    public Optional<FixedRoomReservations.Reservation> reserveFixed(ServerPlayer p,UUID interactionId,
            Collection<ResourceLocation> gods,RecordingScope recording) {
        attach(p.server);
        if(!fixedStartAvailable(p,gods))return Optional.empty();
        return fixedReservations.reserve(interactionId,p.getUUID(),gods.stream().map(Object::toString).toList(),
                recording,MinecraftBiomeRegionSource.cell(p.serverLevel(),p.blockPosition()),ledger.activeRooms().size());
    }
    public void releaseFixed(FixedRoomReservations.Reservation reservation) {
        if(server!=null&&!server.isSameThread())throw new IllegalStateException("Room authority requires server thread");
        if(fixedReservations!=null)fixedReservations.release(reservation);
    }
    /** For biome edits/world-definition reloads; PRIVATE and MOBILE rooms retain their authority and selection. */
    public void invalidateFixedRegions() {
        if(server==null)return;
        if(!server.isSameThread())throw new IllegalStateException("Room authority requires server thread");
        fixedReservations.clear();
        for(var room:ledger.activeRooms())if(room.type()==RoomType.PUBLIC_FIXED) {
            ledger.end(room.roomId(),room.revision());removed(room);
        }
        fixedRegions.clear();regions.invalidateAll();
    }
    public ConversationRoomSnapshot create(ServerPlayer p,RoomType type,Collection<ResourceLocation> gods,RecordingScope recording) {
        attach(p.server);
        if(!enabled())throw new IllegalArgumentException("대화방 AI 엔진이 연결되지 않았습니다.");
        if(AiConversationRuntimeService.INSTANCE.isTestConversation(p))throw new IllegalArgumentException("기존 /ai_test 대화를 종료한 뒤 방을 여세요.");
        if(recording==RecordingScope.TEST_RECORDING&&MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.OFF)
            throw new IllegalArgumentException("기억 시스템이 OFF 상태라 기록 on 시험을 시작할 수 없습니다. off 시험을 사용하거나 운영 설정을 먼저 확인하세요.");
        if(gods.isEmpty()||gods.stream().anyMatch(g->GodDefinitionManager.INSTANCE.find(g).isEmpty()))throw new IllegalArgumentException("알 수 없는 신입니다.");
        if(type==RoomType.PUBLIC_FIXED) {
            var reservation=reserveFixed(p,UUID.randomUUID(),gods,recording)
                    .orElseThrow(()->new IllegalArgumentException("바이옴에 대화가 있거나 연결 영역을 확인 중입니다."));
            try{return createReserved(p,reservation);}finally{releaseFixed(reservation);}
        }
        ensureUnreservedMembershipCapacity(p);
        if(!fixedReservations.hasCapacity(ledger.activeRooms().size()))throw new IllegalArgumentException("대화방 수가 한도에 도달했습니다.");
        if(type.isPublic()&&!fixedReservations.participantsAvailable(p.getUUID(),gods.stream().map(Object::toString).toList()))
            throw new IllegalArgumentException("참가자가 공개대화를 시작하는 중입니다.");
        var r=ledger.create(type,p.getUUID(),gods.stream().map(Object::toString).toList(),"",recording);
        changed(r);notice(r,"대화를 시작했습니다.");return r;
    }
    private ConversationRoomSnapshot createReserved(ServerPlayer p,FixedRoomReservations.Reservation reservation) {
        if(!fixedReservations.find(reservation.interactionId()).filter(reservation::equals).isPresent()
                ||!reservation.playerId().equals(p.getUUID()))throw new IllegalArgumentException("공개대화 시작 예약이 만료되었습니다.");
        var r=ledger.create(RoomType.PUBLIC_FIXED,p.getUUID(),reservation.godIds(),
                Long.toString(reservation.region().id()),reservation.recordingScope());
        fixedRegions.put(r.roomId(),reservation.region());
        fixedReservations.consume(reservation);
        changed(r);notice(r,"대화를 시작했습니다.");return r;
    }
    public void startInteraction(MinecraftServer s,UUID interactionId,com.sande.mythictrpg.interaction.director.InteractionPlan plan,
                                 com.sande.mythictrpg.interaction.content.ValidatedInteractionContent content) {
        attach(s);
        var p=s.getPlayerList().getPlayer(plan.initiatingPlayerId()); if(p==null)return;
        var gods=new ArrayList<ResourceLocation>();gods.add(plan.participants().primaryGodId());gods.addAll(plan.participants().secondaryGodIds());
        var reservation=fixedReservations.find(interactionId)
                .filter(held->held.playerId().equals(p.getUUID())&&held.godIds().equals(gods.stream().map(Object::toString).toList())
                        &&held.recordingScope()==RecordingScope.STANDARD)
                .orElseThrow(()->new IllegalArgumentException("공개대화 시작 예약이 없습니다."));
        var r=createReserved(p,reservation);
        for(UUID id:plan.audience().recipientPlayerIds())if(!r.playerIds().contains(id)) {
            var peer=s.getPlayerList().getPlayer(id);
            if(peer!=null&&memberships(peer).size()<ConversationRoomLedger.MAX_ROOMS_PER_PLAYER
                    &&r.playerIds().size()<ConversationRoomSnapshot.MAX_PLAYERS&&ledger.publicRoomForPlayer(id).isEmpty()
                    &&fixedReservations.participantsAvailable(id,List.of())&&eligible(r,peer))
                r=ledger.join(r.roomId(),r.revision(),id,ConversationRoomLedger.Admission.PUBLIC_RANGE);
        }
        changed(r);
        // The existing Interaction output already sent HUD turns. Add each logical turn to chat once.
        for(var t:content.turns())publishGod(r,t.speakerGodId(),t.text().getString(),false);
    }
    private boolean eligible(ConversationRoomSnapshot r,ServerPlayer p) {
        if(r.type()==RoomType.PRIVATE)return false;
        if(r.type()==RoomType.PUBLIC_FIXED) {
            var region=fixedRegions.get(r.roomId());
            return region!=null&&regions.membership(region,MinecraftBiomeRegionSource.cell(p.serverLevel(),p.blockPosition()))==ConnectedBiomeRegions.Membership.SAME;
        }
        return r.playerIds().stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull).anyMatch(q->near(p,q));
    }
    private static boolean near(ServerPlayer a,ServerPlayer b) {return a.level()==b.level()&&a.distanceToSqr(b)<=256;}
    /** Returns true if it consumed the text; an ordinary unrelated global message remains vanilla chat. */
    public boolean publicText(ServerPlayer p,String text) {
        attach(p.server);if(text.isBlank()||text.startsWith("!"))return false;
        var r=ledger.publicRoomForPlayer(p.getUUID()).orElse(null);
        if(r==null) {
            var candidates=ledger.activeRooms().stream().filter(q->q.type().isPublic()&&eligible(q,p)).toList();
            if(candidates.isEmpty())return false;
            if(candidates.size()!=1) {p.sendSystemMessage(Component.literal("대화방 병합 처리 중입니다. 잠시 후 다시 말해 주세요."));return true;}
            r=candidates.getFirst();
            if(!fixedReservations.participantsAvailable(p.getUUID(),List.of()))return false;
            try {r=ledger.join(r.roomId(),r.revision(),p.getUUID(),ConversationRoomLedger.Admission.PUBLIC_RANGE);changed(r);}
            catch(IllegalArgumentException e){p.sendSystemMessage(Component.literal(e.getMessage()));return true;}
        }
        if(!eligible(r,p)) {p.sendSystemMessage(Component.literal("대화 범위를 벗어났습니다. 신의 이동 판단을 기다려 주세요."));return true;}
        submit(p,r,text);return true;
    }
    public void privateText(ServerPlayer p,String text) {
        attach(p.server);
        var r=ledger.selectedPrivateRoom(p.getUUID()).orElse(null);
        if(r==null){p.sendSystemMessage(Component.literal("선택된 비밀대화가 없습니다. H 키 또는 /mythroom list로 선택하세요."));return;}
        submit(p,r,text);
    }
    private void submit(ServerPlayer p,ConversationRoomSnapshot room,String text) {
        if(splitting.contains(room.roomId())){p.sendSystemMessage(Component.literal("대화 무리를 나누는 중입니다. 잠시 후 말해 주세요."));return;}
        if(text.isBlank()||text.length()>8192)return;
        String god=chooseSpeaker(room,text);
        if(!com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(p,ResourceLocation.parse(god))) {
            p.sendSystemMessage(Component.literal("그 신은 지금 대화할 수 없습니다."));return;
        }
        var scope=actionScope(p,room.roomId(),room.revision(),ResourceLocation.parse(god));
        if(scope.isPresent()&&com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.handleRoomAnswer(p,scope.get().sessionId(),ResourceLocation.parse(god),text)) {
            var oldTurn=turns.remove(room.roomId());if(oldTurn!=null)ledger.finishTurn(oldTurn);
            RoomConversationEngineRouter.INSTANCE.engine().invalidate(room.roomId());
            publishPlayer(room,p,text);return;
        }
        var lease=ledger.beginTurn(room.roomId(),room.revision(),p.getUUID(),god);
        turns.put(room.roomId(),lease);
        var before=visibleHistory(room,god);
        publishPlayer(room,p,text);
        try {runTurn(p,room,lease,prepareTurn(p,room,lease,ResourceLocation.parse(god),text,before,false),RoomTurnPolicy.reactionSpeakers(room,god));}
        catch(RuntimeException unavailable) {
            finishTurn(lease);RoomConversationEngineRouter.INSTANCE.engine().invalidate(room.roomId());
            p.sendSystemMessage(Component.literal("[대화 오류] 현재 대화 상태를 준비할 수 없습니다."));
            MythicTrpg.LOGGER.warn("Room turn preparation failed",unavailable);
        }
    }
    private PreparedTurn prepareTurn(ServerPlayer p,ConversationRoomSnapshot room,ConversationRoomLedger.TurnLease lease,
            ResourceLocation speaker,String text,List<RoomConversationEngine.HistoryLine> before,boolean secondary) {
        var relations=GodRelationRoomContext.capture(server,room,speaker,recipients(room));
        var states=new ArrayList<RoomConversationEngine.GodState>();
        // The AI receives the selected God's context, never another God's private constraints or memory.
        for(String id:List.of(speaker.toString())) {
            var rid=ResourceLocation.parse(id);
            String context="Room type="+room.type()+"; participants="+participantNames(room.playerIds())
                    +"\n"+relationshipContext(room.playerIds(),rid)
                    +"\n"+String.join("\n",secondary?List.of():feedback.getOrDefault(new FeedbackKey(room.roomId(),rid,p.getUUID()),List.of()))
                    +"\nOnly this room's supplied game context is authoritative. Never reuse another room's constraints or feedback."
                    +"\nA conversation move does not transfer or cancel watch entitlement."
                    +"\n"+relations.promptText();
            if(!secondary)context+="\nRoom-control capabilities: leave current speaker after genuine farewell; end your own attendance; invite a named online player to PRIVATE only."
                    +"\nEligible private invite targets (IDs/names, invitation still requires acceptance): "+participantNames(server.getPlayerList().getPlayers().stream().limit(64).map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                    +"\nNo quest/reward capability is implicitly granted by a room invitation.";
            else context+="\nThis is an optional reaction. Only explicitly supplied Story Hooks may be proposed; no other gameplay or room-control capabilities.";
            var godScope=actionScope(p,room.roomId(),room.revision(),rid);
            if(!secondary&&godScope.isPresent())context+="\n"+com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.contextForRoom(p,rid,godScope.get().sessionId());
            states.add(new RoomConversationEngine.GodState(rid,"R_NEUTRAL","E_NEUTRAL",context,memoryContext(p,room,rid)));
        }
        boolean readOnly=room.recordingScope().isTest()||MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.RUMOR_TEST;
        var request=new RoomConversationEngine.Request(room.roomId(),room.revision(),lease.turnId(),p.getUUID(),p.getGameProfile().getName(),
                room.godIds().stream().map(ResourceLocation::parse).toList(),speaker,text,before,readOnly,
                room.recordingScope().recordingAllowed(),room.type().isPublic(),states,secondary,recipients(room));
        return new PreparedTurn(request,relations);
    }
    private void finishTurn(ConversationRoomLedger.TurnLease lease) {
        if(ledger.finishTurn(lease))turns.remove(lease.roomId(),lease);
    }
    private void advanceReactions(ServerPlayer p,ConversationRoomSnapshot room,ConversationRoomLedger.TurnLease lease,List<String> remaining) {
        if(server!=p.server||!ledger.isCurrent(lease))return;
        for(int i=0;i<remaining.size();i++) {
            var god=ResourceLocation.parse(remaining.get(i));
            if(!com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(p,god))continue;
            try {
                var next=prepareTurn(p,room,lease,god,"",visibleHistory(room,god.toString()),true);
                runTurn(p,room,lease,next,List.copyOf(remaining.subList(i+1,remaining.size())));return;
            }catch(RuntimeException unavailable){MythicTrpg.LOGGER.warn("Room reaction preparation failed for {}",god,unavailable);}
        }
        finishTurn(lease);
    }
    private void failedTurn(ServerPlayer p,ConversationRoomSnapshot room,ConversationRoomLedger.TurnLease lease,
            RoomConversationEngine.Request request,List<String> remaining,String reason) {
        RoomConversationEngineRouter.INSTANCE.engine().invalidate(room.roomId());
        if(request.secondary())advanceReactions(p,room,lease,remaining);
        else {finishTurn(lease);p.sendSystemMessage(Component.literal("[대화 오류] "+reason).withStyle(ChatFormatting.RED));}
    }
    private void runTurn(ServerPlayer p,ConversationRoomSnapshot room,ConversationRoomLedger.TurnLease lease,PreparedTurn prepared,List<String> remaining) {
        var request=prepared.request();
        if(!com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(p,request.speakerGodId())) {
            failedTurn(p,room,lease,request,remaining,"그 신은 지금 대화할 수 없습니다.");return;
        }
        CompletableFuture<RoomConversationEngine.Result> pending;
        try {pending=RoomConversationEngineRouter.INSTANCE.engine().respond(request);}
        catch(RuntimeException unavailable) {failedTurn(p,room,lease,request,remaining,"AI 요청 실패");return;}
        pending.orTimeout(180,TimeUnit.SECONDS).whenComplete((result,failure)->p.server.execute(()-> {
            if(server!=p.server||!ledger.isCurrent(lease)||server.getPlayerList().getPlayer(p.getUUID())!=p)return;
            if(!com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(p,request.speakerGodId())) {
                failedTurn(p,room,lease,request,remaining,"그 신은 지금 대화할 수 없습니다.");return;
            }
            if(!GodRelationRoomContext.isCurrent(server,prepared.relations(),room,recipients(room))) {
                failedTurn(p,room,lease,request,remaining,"신들의 관계나 청중이 바뀌어 이전 응답을 취소했습니다. 다시 말해 주세요.");return;
            }
            if(failure!=null||result==null||!result.failure().isBlank()) {
                failedTurn(p,room,lease,request,remaining,result==null?"AI 요청 실패":result.failure());return;
            }
            if(!result.deliverableFor(request)) {
                failedTurn(p,room,lease,request,remaining,"현재 방·화자와 일치하지 않는 응답을 취소했습니다.");return;
            }
            try {
            var storyEvents=new ArrayList<RoomDialogueEvent>();
            if(result.storyContextId().isPresent()) {
                var disclosures=com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.prepareDisclosures(
                        p,result.storyContextId().get(),room.roomId(),room.revision(),request.speakerGodId(),result.storyStatementAliases());
                for(var line:disclosures) publishStory(p,room.roomId(),room.revision(),request.speakerGodId(),line.text(),List.of(line.evidence()))
                        .ifPresent(storyEvents::add);
            }
            boolean fullyDelivered=!result.speech().isEmpty();
            for(var speech:result.speech()) {
                var event=publishGod(room,speech.godId(),speech.text(),true,Optional.of(lease.turnId()));
                fullyDelivered&=event!=null&&RoomTurnPolicy.fullyDispatched(event,p.getUUID());
            }
            RoomConversationEngineRouter.INSTANCE.engine().delivered(request,result);
            if(result.storyContextId().isPresent())com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE
                    .commitDisclosures(p,result.storyContextId().get(),storyEvents);
            if(fullyDelivered&&!request.readOnly())dispatchProposals(p,room,request.speakerGodId(),result.proposalsJson(),request.currentText());
            for(var control:result.controls())try {applyControl(p,room,request.speakerGodId(),control);}
            catch(RuntimeException invalid){feedback.put(new FeedbackKey(room.roomId(),request.speakerGodId(),p.getUUID()),List.of("Room control rejected: "+invalid.getMessage()));}
            if(!ledger.isCurrent(lease))return;
            if(fullyDelivered||request.secondary())advanceReactions(p,room,lease,remaining);
            else finishTurn(lease);
            }catch(RuntimeException invalid){
                MythicTrpg.LOGGER.warn("Room response publication failed",invalid);
                failedTurn(p,room,lease,request,remaining,"응답의 공개 권한이 만료되었거나 전달에 실패했습니다.");
            }
        }));
    }
    private void dispatchProposals(ServerPlayer p,ConversationRoomSnapshot room,ResourceLocation god,String json,String playerText) {
        List<String> reports=new ArrayList<>();
        try {
            var proposals=com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            if(proposals.size()>8)throw new IllegalArgumentException("Too many proposals");
            for(var value:proposals) {
                if(!isCurrent(room.roomId(),room.revision()))break;
                var proposal=value.getAsJsonObject();var params=new LinkedHashMap<String,String>();
                if(proposal.has("parameters"))for(var e:proposal.getAsJsonObject("parameters").entrySet())params.put(e.getKey(),e.getValue().getAsString());
                var result=com.sande.mythictrpg.ai.action.AiActionGateway.submitRoom(p,room.roomId(),room.revision(),god,
                        proposal.get("type").getAsString(),proposal.has("title")?proposal.get("title").getAsString():"",
                        proposal.has("summary")?proposal.get("summary").getAsString():"",params,
                        com.sande.mythictrpg.ai.action.ItemReadinessPolicy.declared(playerText));
                reports.add("Game validator: "+result.actionType()+" status="+result.status()+" reason="+result.reason());
                p.sendSystemMessage(Component.literal("[게임 처리] "+result.status()+": "+result.reason()));
            }
        }catch(RuntimeException invalid){reports.add("Game validator: malformed or unsupported proposal rejected; no inferred execution.");}
        if(!reports.isEmpty())feedback.put(new FeedbackKey(room.roomId(),god,p.getUUID()),List.copyOf(reports));
    }
    private String chooseSpeaker(ConversationRoomSnapshot r,String text) {
        for(String god:r.godIds()) {
            var id=ResourceLocation.parse(god);String name=GodDefinitionManager.INSTANCE.find(id).map(d->d.displayName().getString()).orElse("");
            if(text.contains(god)||!name.isBlank()&&text.contains(name)){lastSpeakers.put(r.roomId(),god);return god;}
        }
        return lastSpeakers.computeIfAbsent(r.roomId(),id->r.godIds().iterator().next());
    }
    private void applyControl(ServerPlayer p,ConversationRoomSnapshot captured,ResourceLocation god,RoomConversationEngine.Control c) {
        var room=ledger.find(captured.roomId()).orElse(null);if(room==null||room.revision()!=captured.revision())return;
        switch(c.kind().toLowerCase(Locale.ROOT)) {
            case "leave", "conversation_leave" -> {if(c.targetId().isBlank()||c.targetId().equals(p.getUUID().toString()))leave(p,room,"FAREWELL");}
            case "end", "conversation_end" -> {
                var next=ledger.removeGod(room.roomId(),room.revision(),god.toString());
                if(next.isPresent())changed(next.get());else removed(room);
            }
            case "invite", "conversation_invite" -> {
                ServerPlayer target=server.getPlayerList().getPlayerByName(c.targetId());
                try {if(target==null)target=server.getPlayerList().getPlayer(UUID.fromString(c.targetId()));}catch(IllegalArgumentException ignored){}
                if(room.type()==RoomType.PRIVATE) {if(target!=null&&!room.playerIds().contains(target.getUUID()))invite(room,god,target);}
                else if(c.targetId().isBlank()||target!=null) {
                    var privateRoom=create(p,RoomType.PRIVATE,List.of(god),room.recordingScope());
                    if(target!=null&&target!=p)invite(privateRoom,god,target);
                }
            }
            default -> { /* Unknown model instructions have no execution authority. */ }
        }
    }
    public void invite(ConversationRoomSnapshot r,ResourceLocation god,ServerPlayer target) {
        var token=ledger.invite(r.roomId(),r.revision(),god.toString(),target.getUUID());
        target.sendSystemMessage(Component.literal("비밀대화 초대: ").append(GodIdentityService.INSTANCE.getDisplayName(server,target.getUUID(),god))
                .append(Component.literal(" [참여]").withStyle(style->style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/mythroom accept "+token.token())))));
    }
    public void accept(ServerPlayer p,UUID token) {
        if(AiConversationRuntimeService.INSTANCE.isTestConversation(p))throw new IllegalArgumentException("기존 /ai_test 대화를 종료한 뒤 참여하세요.");
        attach(p.server);var invitation=ledger.invitationsFor(p.getUUID()).stream().filter(i->i.token().equals(token)).findFirst().orElseThrow(()->new IllegalArgumentException("유효한 신의 초대가 없습니다."));
        ensureUnreservedMembershipCapacity(p);
        var r=ledger.acceptInvitation(invitation,p.getUUID());changed(r);notice(r,p.getGameProfile().getName()+"님이 합류했습니다.");
    }
    private void ensureUnreservedMembershipCapacity(ServerPlayer p) {
        int reserved=fixedReservations.participantsAvailable(p.getUUID(),List.of())?0:1;
        if(memberships(p).size()+reserved>=ConversationRoomLedger.MAX_ROOMS_PER_PLAYER)
            throw new IllegalArgumentException("플레이어의 대화방 수가 한도에 도달했습니다.");
    }
    public void leave(ServerPlayer p,ConversationRoomSnapshot r,String reason) {
        attach(p.server);
        var remaining=ledger.leave(r.roomId(),r.revision(),p.getUUID());
        if(remaining.isPresent()) {
            var next=remaining.get();changed(next);
            append(next,new RoomConversationEngine.HistoryLine("SYSTEM",p.getUUID().toString(),p.getGameProfile().getName(),
                    "Conversation departure: "+reason+". Evaluate etiquette using preceding context and persona, never automatically penalize."));
            notice(next,p.getGameProfile().getName()+"님이 대화에서 나갔습니다.");
        }else removed(r);
        sync(p);
    }
    public void loggedOut(ServerPlayer p) { for(var r:memberships(p))leave(p,r,"DISCONNECT_NOT_INTENTIONAL_DISRESPECT"); }
    public boolean isCurrent(UUID id,long revision) {return server!=null&&server.isSameThread()&&ledger.find(id).filter(r->r.revision()==revision).isPresent();}
    private static UUID generation(ConversationRoomSnapshot r) {return UUID.nameUUIDFromBytes((r.roomId()+"/"+r.revision()).getBytes(StandardCharsets.UTF_8));}
    public Optional<com.sande.mythictrpg.ai.action.AiActionScope> actionScope(ServerPlayer p,UUID roomId,long revision,ResourceLocation god) {
        if(server!=p.server||!server.isSameThread()||server.getPlayerList().getPlayer(p.getUUID())!=p
                ||MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.RUMOR_TEST
                ||!com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(p,god))return Optional.empty();
        return ledger.find(roomId).filter(r->r.revision()==revision&&!splitting.contains(roomId)&&!r.recordingScope().isTest()
                &&r.playerIds().contains(p.getUUID())&&r.godIds().contains(god.toString()))
                .map(r->new com.sande.mythictrpg.ai.action.AiActionScope(generation(r),god));
    }
    public boolean actionCurrent(ServerPlayer p,UUID sessionId,ResourceLocation god) {
        if(server!=p.server||!server.isSameThread())return false;
        return ledger.activeRooms().stream().filter(r->generation(r).equals(sessionId))
                .anyMatch(r->actionScope(p,r.roomId(),r.revision(),god).isPresent());
    }
    public Set<UUID> actionPlayers(ServerPlayer p,UUID sessionId,ResourceLocation god) {
        if(!actionCurrent(p,sessionId,god))return Set.of();
        return ledger.activeRooms().stream().filter(r->generation(r).equals(sessionId)).findFirst().map(ConversationRoomSnapshot::playerIds).orElse(Set.of());
    }
    public ConversationMemoryContext memoryContext(ServerPlayer p,ConversationRoomSnapshot r,ResourceLocation god) {
        if(!r.playerIds().contains(p.getUUID())||!r.godIds().contains(god.toString())||MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.OFF)return null;
        var data=RumorSavedData.get(p.server);if(!data.ready())return null;
        return new ConversationMemoryContext(data.worldId(),r.roomId(),generation(r),p.getUUID(),god.toString(),recipients(r),
                MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.RUMOR_TEST);
    }
    public boolean memoryCurrent(ServerPlayer p,ConversationMemoryContext context) {
        if(context==null||server!=p.server||!server.isSameThread()||server.getPlayerList().getPlayer(p.getUUID())!=p)return false;
        var r=ledger.find(context.interactionId()).orElse(null);
        return r!=null&&context.equals(memoryContext(p,r,ResourceLocation.parse(context.godId())));
    }
    public Optional<Boolean> recordingAllowed(ServerPlayer p,ConversationMemoryContext context) {
        if(context==null||server!=p.server||!server.isSameThread())return Optional.empty();
        return ledger.find(context.interactionId()).map(room->room.recordingScope().recordingAllowed()&&memoryCurrent(p,context));
    }
    private Set<UUID> recipients(ConversationRoomSnapshot r) {
        if(!r.type().isPublic())return r.playerIds();
        var ids=new LinkedHashSet<UUID>();for(var p:server.getPlayerList().getPlayers())ids.add(p.getUUID());return Set.copyOf(ids);
    }
    private void append(ConversationRoomSnapshot r,RoomConversationEngine.HistoryLine line) {
        append(r,line,recipients(r),r.godIds());
    }
    private void append(ConversationRoomSnapshot r,RoomConversationEngine.HistoryLine line,Set<UUID> audience,Set<String> gods) {
        var lines=history.computeIfAbsent(r.roomId(),id->new ArrayList<>());
        var sourced=new RoomConversationEngine.HistoryLine(line.role(),line.speakerId(),line.name(),line.text(),
                line.sourceRoomId()==null?r.roomId():line.sourceRoomId(),line.messageId());
        lines.add(new Line(sourced,Set.copyOf(audience),Set.copyOf(gods)));while(lines.size()>64)lines.removeFirst();
    }
    private List<RoomConversationEngine.HistoryLine> visibleHistory(ConversationRoomSnapshot r,String god) {
        var audience=recipients(r);
        return history.getOrDefault(r.roomId(),List.of()).stream().filter(l->l.gods().contains(god)
                &&l.gods().containsAll(r.godIds())&&l.audience().containsAll(audience)).map(Line::value).toList();
    }
    private Component prefix(ConversationRoomSnapshot r) {return Component.literal("│["+r.code()+"·"+(r.type()==RoomType.PRIVATE?"비밀":"공개")+"] ").withStyle(s->s.withColor(color(r)));}
    private static int color(ConversationRoomSnapshot r) {int[] colors={0x55FFFF,0xFFAA00,0xAAFF55,0xFF77AA,0xAAAAFF,0xFFFF55};return colors[Math.floorMod(r.code().hashCode(),colors.length)];}
    private void publishPlayer(ConversationRoomSnapshot r,ServerPlayer p,String text) {
        var event=publish(r,"PLAYER",p.getUUID().toString(),text,false,Optional.ofNullable(turns.get(r.roomId())).map(ConversationRoomLedger.TurnLease::turnId));
        var audience=completeAudience(event);audience.add(p.getUUID());
        append(r,new RoomConversationEngine.HistoryLine("PLAYER",p.getUUID().toString(),p.getGameProfile().getName(),text,r.roomId(),event.messageId()),audience,event.heardGodIds());
    }
    private void publishGod(ConversationRoomSnapshot r,ResourceLocation god,String text,boolean hud) {
        publishGod(r,god,text,hud,Optional.empty());
    }
    private RoomDialogueEvent publishGod(ConversationRoomSnapshot r,ResourceLocation god,String text,boolean hud,Optional<UUID> turnId) {
        if(text.isBlank())return null;
        var event=publish(r,"NPC",god.toString(),text,hud,turnId);
        var audience=completeAudience(event);
        if(!audience.isEmpty())appendGodHistory(r,god,event,audience);
        return event;
    }
    private void appendGodHistory(ConversationRoomSnapshot room,ResourceLocation god,RoomDialogueEvent event,Set<UUID> audience) {
        append(room,new RoomConversationEngine.HistoryLine("NPC",god.toString(),
                GodDefinitionManager.INSTANCE.find(god).map(d->d.displayName().getString()).orElse("????"),
                event.text(),room.roomId(),event.messageId()),audience,event.heardGodIds());
    }
    /** Canonical Story delivery uses an explicitly selected, already-authorized room; no automatic fan-out to other rooms. */
    public Optional<RoomDialogueEvent> publishStory(ServerPlayer anchor,UUID roomId,long revision,ResourceLocation speaker,
            String text,List<RoomEvidenceReference> evidence) {
        return publishStoryInternal(anchor,roomId,revision,speaker,text,evidence,Optional.empty());
    }
    public Optional<RoomDialogueEvent> publishStoryPresentation(ServerPlayer anchor,UUID roomId,long revision,ResourceLocation speaker,
            String text,List<RoomEvidenceReference> evidence,UUID presentationContextId) {
        return publishStoryInternal(anchor,roomId,revision,speaker,text,evidence,Optional.of(presentationContextId));
    }
    private Optional<RoomDialogueEvent> publishStoryInternal(ServerPlayer anchor,UUID roomId,long revision,ResourceLocation speaker,
            String text,List<RoomEvidenceReference> evidence,Optional<UUID> presentationContextId) {
        attach(anchor.server);
        var room=ledger.find(roomId).filter(r->r.revision()==revision&&r.playerIds().contains(anchor.getUUID())
                &&r.godIds().contains(speaker.toString())&&!splitting.contains(roomId)).orElse(null);
        if(room==null||text.isBlank())return Optional.empty();
        if(presentationContextId.isPresent()) {
            if(!com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.roomDeliveryAuthorized(
                    anchor,presentationContextId.get(),roomId,revision,speaker))return Optional.empty();
        } else if(!com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(anchor,speaker))return Optional.empty();
        var event=publish(room,"NPC",speaker.toString(),text,true,
                Optional.ofNullable(turns.get(roomId)).map(ConversationRoomLedger.TurnLease::turnId),evidence);
        var audience=completeAudience(event);
        if(!audience.isEmpty())appendGodHistory(room,speaker,event,audience);
        return Optional.of(event);
    }
    private static Set<UUID> completeAudience(RoomDialogueEvent event) {
        var audience=new LinkedHashSet<UUID>();
        for(UUID id:event.deliveries().keySet())if(RoomTurnPolicy.fullyDispatched(event,id))audience.add(id);
        return audience;
    }
    private RoomDialogueEvent publish(ConversationRoomSnapshot r,String role,String speaker,String text,boolean hud,Optional<UUID> turnId) {
        return publish(r,role,speaker,text,hud,turnId,List.of());
    }
    private RoomDialogueEvent publish(ConversationRoomSnapshot r,String role,String speaker,String text,boolean hud,Optional<UUID> turnId,List<RoomEvidenceReference> evidence) {
        var participants=new LinkedHashMap<UUID,String>();
        for(UUID id:r.playerIds()) {
            var p=server.getPlayerList().getPlayer(id);
            // Offline members remain participants, but never acquire a successful dispatch receipt.
            participants.put(id,p==null?id.toString():p.getGameProfile().getName());
        }
        var event=new RoomDialogueEvent(UUID.randomUUID(),r.roomId(),r.revision(),turnId,r.type(),r.recordingScope(),
                role,speaker,text,r.godIds(),participants,Map.of(),System.currentTimeMillis());
        var anchor=r.playerIds().stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull).findFirst();
        var heard=RoomTurnPolicy.heardGods(r,id->anchor.isPresent()
                &&com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.speakerAvailable(anchor.get(),ResourceLocation.parse(id)));
        event=event.withHeardGods(heard).withWorld(RumorSavedData.get(server).worldId()).withEvidence(evidence,Set.of());
        event=RoomConversationEngineRouter.INSTANCE.engine().preparePublication(event);
        var dispatched=RoomDialoguePublisher.publish(event,recipients(r),id-> {
            var p=server.getPlayerList().getPlayer(id);if(p==null)return null;
            boolean chat=false;int pages=0;
            var god="NPC".equals(role)?ResourceLocation.parse(speaker):null;
            Component name=god==null?Component.literal(participants.get(UUID.fromString(speaker)))
                    :GodIdentityService.INSTANCE.getDisplayName(server,id,god);
            if(p.getChatVisibility()!=net.minecraft.world.entity.player.ChatVisiblity.HIDDEN)try {
                p.sendSystemMessage(prefix(r).copy().append(name).append(Component.literal(": "+text).withStyle(ChatFormatting.WHITE)));chat=true;
            }catch(RuntimeException failed){recordingFailure(r.roomId(),failed);}
            if(hud&&god!=null&&r.playerIds().contains(id))for(String page:RoomHudText.pages(text))try {
                if(DialoguePresentationService.INSTANCE.sendTo(p,GodDialogueRequest.literal(god,page)).status()
                        ==com.sande.mythictrpg.dialogue.server.DialogueSendStatus.SENT)pages++;
            }catch(RuntimeException failed){recordingFailure(r.roomId(),failed);}
            return chat||pages>0?new RoomDialogueEvent.Delivery(p.getGameProfile().getName(),chat,pages):null;
        },e->RoomConversationEngineRouter.INSTANCE.engine().dialoguePublished(e),failure->recordingFailure(r.roomId(),failure));
        try {RoomConversationEngineRouter.INSTANCE.engine().dialogueObserved(dispatched);}
        catch(RuntimeException failure){recordingFailure(r.roomId(),failure);}
        return dispatched;
    }
    private static void recordingFailure(UUID room,RuntimeException failure) {
        MythicTrpg.LOGGER.warn("Room {} dispatch/recording callback failed; dialogue and game state are not rolled back",room,failure);
    }
    private void notice(ConversationRoomSnapshot r,String text) {for(UUID id:r.playerIds()){var p=server.getPlayerList().getPlayer(id);if(p!=null)p.sendSystemMessage(prefix(r).copy().append(text));}}
    public void sync(ServerPlayer p) {
        if(server==null)return;
        var entries=memberships(p).stream().limit(64).map(r->new ConversationRoomsPayload.Entry(r.roomId(),r.code(),r.type().name(),
                displayGods(p,r),color(r),r.playerIds().size())).toList();
        var selected=ledger.selectedPrivateRoom(p.getUUID()).map(ConversationRoomSnapshot::roomId);
        try {PacketDistributor.sendToPlayer(p,new ConversationRoomsPayload(entries,selected));}catch(UnsupportedOperationException ignored){}
    }
    private String displayGods(ServerPlayer player,ConversationRoomSnapshot room) {
        String names=String.join(" / ",room.godIds().stream().map(g->GodIdentityService.INSTANCE.getDisplayName(server,player.getUUID(),ResourceLocation.parse(g)).getString()).toList());
        return names.codePointCount(0,names.length())>500?names.substring(0,names.offsetByCodePoints(0,500))+"…":names;
    }
    private void changed(ConversationRoomSnapshot r) {
        feedback.keySet().removeIf(key->key.room().equals(r.roomId()));
        if(!r.godIds().contains(lastSpeakers.get(r.roomId())))lastSpeakers.remove(r.roomId());
        RoomConversationEngineRouter.INSTANCE.engine().invalidate(r.roomId());turns.remove(r.roomId());changedAt.put(r.roomId(),server.overworld().getGameTime());
        for(UUID id:r.playerIds()){var p=server.getPlayerList().getPlayer(id);if(p!=null)sync(p);}
    }
    private void removed(ConversationRoomSnapshot r) {
        RoomConversationEngineRouter.INSTANCE.engine().invalidate(r.roomId());turns.remove(r.roomId());history.remove(r.roomId());feedback.keySet().removeIf(key->key.room().equals(r.roomId()));lastSpeakers.remove(r.roomId());changedAt.remove(r.roomId());splitting.remove(r.roomId());
        var region=fixedRegions.remove(r.roomId());if(region!=null)regions.release(region);
        for(UUID id:r.playerIds()){var p=server.getPlayerList().getPlayer(id);if(p!=null)sync(p);}
    }
    private String participantNames(Set<UUID> players) {return String.join(", ",players.stream().map(id->{var p=server.getPlayerList().getPlayer(id);return id+":"+(p==null?"offline":p.getGameProfile().getName());}).toList());}
    private String relationshipContext(Set<UUID> players,ResourceLocation god) {
        var service=PlayerMythDataService.get(server);
        return "Game affinity values; numerical tier mapping UNDEFINED; do not fabricate a tier or intimacy.\n"
                +String.join("\n",players.stream().map(id->"player="+id+", affinity="+service.find(id).map(p->p.affinities().getOrDefault(god,0)).orElse(0)).toList());
    }

    public void tick(ServerTickEvent.Post event) {
        if(server==null||event.getServer()!=server)return;
        regions.advance(2048);
        if(server.overworld().getGameTime()%10!=0)return;
        for(var r:ledger.activeRooms()) {
            if(r.type()==RoomType.PRIVATE||splitting.contains(r.roomId())||server.overworld().getGameTime()-changedAt.getOrDefault(r.roomId(),0L)<20)continue;
            var groups=components(r);
            int retained=-1;
            if(r.type()==RoomType.PUBLIC_FIXED) {
                var region=fixedRegions.get(r.roomId());if(region==null)continue;
                var inside=new LinkedHashSet<UUID>();var outside=new LinkedHashSet<UUID>();boolean unknown=false;
                for(UUID id:r.playerIds()) {var p=server.getPlayerList().getPlayer(id);if(p==null)continue;
                    var membership=regions.membership(region,MinecraftBiomeRegionSource.cell(p.serverLevel(),p.blockPosition()));
                    if(membership==ConnectedBiomeRegions.Membership.UNKNOWN){unknown=true;break;}
                    (membership==ConnectedBiomeRegions.Membership.SAME?inside:outside).add(id);
                }
                if(unknown||outside.isEmpty())continue;
                groups=new ArrayList<>();if(!inside.isEmpty()){groups.add(Set.copyOf(inside));retained=0;}
                groups.addAll(components(outside));
            }else if(groups.size()<2)continue;
            split(r,groups,retained);
        }
        var publicRooms=ledger.activeRooms().stream().filter(r->r.type().isPublic()&&!splitting.contains(r.roomId())).toList();
        for(int i=0;i<publicRooms.size();i++)for(int j=i+1;j<publicRooms.size();j++) {
            var a=publicRooms.get(i);var b=publicRooms.get(j);
            if(!isCurrent(a.roomId(),a.revision())||!isCurrent(b.roomId(),b.revision())||a.type()==RoomType.PUBLIC_FIXED&&b.type()==RoomType.PUBLIC_FIXED)continue;
            if(server.overworld().getGameTime()-Math.max(changedAt.getOrDefault(a.roomId(),0L),changedAt.getOrDefault(b.roomId(),0L))<20)continue;
            boolean touch=a.type()==RoomType.PUBLIC_FIXED?b.playerIds().stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull).anyMatch(p->eligible(a,p)):
                    b.type()==RoomType.PUBLIC_FIXED?a.playerIds().stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull).anyMatch(p->eligible(b,p)):
                    a.playerIds().stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull).anyMatch(p->eligible(b,p));
            if(touch)try {
                var region=a.type()==RoomType.PUBLIC_FIXED?fixedRegions.get(a.roomId()):fixedRegions.get(b.roomId());
                List<Line> combined=new ArrayList<>(history.getOrDefault(a.roomId(),List.of()));combined.addAll(history.getOrDefault(b.roomId(),List.of()));
                var merged=ledger.merge(a.roomId(),a.revision(),b.roomId(),b.revision());
                // The fixed handle is transferred, not released. Per-line old audience remains intact.
                fixedRegions.remove(a.roomId());fixedRegions.remove(b.roomId());removed(a);removed(b);
                if(region!=null)fixedRegions.put(merged.roomId(),region);
                history.put(merged.roomId(),new ArrayList<>(combined.subList(Math.max(0,combined.size()-64),combined.size())));
                changed(merged);notice(merged,"공개대화가 합쳐졌습니다.");
            }catch(IllegalArgumentException ignored){ /* recording scope/capacity collision leaves rooms separate */ }
        }
    }
    private List<Set<UUID>> components(ConversationRoomSnapshot r){return components(r.playerIds());}
    private List<Set<UUID>> components(Set<UUID> ids) {
        var remaining=new LinkedHashSet<>(ids);List<Set<UUID>> result=new ArrayList<>();
        while(!remaining.isEmpty()) {
            var queue=new ArrayDeque<UUID>();var group=new LinkedHashSet<UUID>();queue.add(remaining.iterator().next());remaining.remove(queue.peek());
            while(!queue.isEmpty()) {UUID id=queue.remove();group.add(id);var p=server.getPlayerList().getPlayer(id);
                if(p==null)continue;
                for(UUID other:List.copyOf(remaining)){var q=server.getPlayerList().getPlayer(other);if(q!=null&&near(p,q)){remaining.remove(other);queue.add(other);}}
            }result.add(Set.copyOf(group));
        }return result;
    }
    private void split(ConversationRoomSnapshot r,List<Set<UUID>> groups,int fixedIndex) {
        splitting.add(r.roomId());
        var prior=turns.remove(r.roomId());if(prior!=null)ledger.finishTurn(prior);
        RoomConversationEngineRouter.INSTANCE.engine().invalidate(r.roomId());
        var futures=new LinkedHashMap<String,CompletableFuture<RoomConversationEngine.SplitResult>>();
        var relationSnapshots=new LinkedHashMap<String,GodRelationRoomContext.Snapshot>();
        for(String god:r.godIds()) {
            try {
            var rid=ResourceLocation.parse(god);var candidates=new ArrayList<RoomConversationEngine.Candidate>();
            var relations=GodRelationRoomContext.capture(server,r,rid,recipients(r));
            relationSnapshots.put(god,relations);
            for(int i=0;i<groups.size();i++)candidates.add(new RoomConversationEngine.Candidate(Integer.toString(i),List.copyOf(groups.get(i)),
                    participantNames(groups.get(i))+"\n"+relationshipContext(groups.get(i),rid)));
            var input=new RoomConversationEngine.SplitRequest(r.roomId(),r.revision(),rid,candidates,
                    "Players separated. Choose who you WANT to converse with, not necessarily your watch target. Watch ownership does not change. Empty key means leave.\n"+relations.promptText(),
                    visibleHistory(r,god),r.godIds().stream().map(ResourceLocation::parse).toList());
            futures.put(god,RoomConversationEngineRouter.INSTANCE.engine().chooseSplit(input).completeOnTimeout(null,90,TimeUnit.SECONDS));}
            catch(RuntimeException failed) {splitting.remove(r.roomId());changedAt.put(r.roomId(),server.overworld().getGameTime()+100);return;}
        }
        CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).whenComplete((ignored,failure)-> {
            MinecraftServer s=server;if(s==null)return;s.execute(()-> {
                splitting.remove(r.roomId());if(!isCurrent(r.roomId(),r.revision()))return;
                if(relationSnapshots.values().stream().anyMatch(snapshot->!GodRelationRoomContext.isCurrent(server,snapshot,r,recipients(r)))) {
                    changedAt.put(r.roomId(),server.overworld().getGameTime()+100);return;
                }
                // Recheck locations; do not apply a decision for groups that have already moved/rejoined.
                if(r.type()==RoomType.PUBLIC_MOBILE&&!new HashSet<>(components(r)).equals(new HashSet<>(groups)))return;
                if(r.type()==RoomType.PUBLIC_FIXED) {
                    var regionNow=fixedRegions.get(r.roomId());if(regionNow==null)return;
                    var insideNow=new LinkedHashSet<UUID>();var outsideNow=new LinkedHashSet<UUID>();
                    for(UUID playerId:r.playerIds()) {
                        var player=server.getPlayerList().getPlayer(playerId);if(player==null)return;
                        var membership=regions.membership(regionNow,MinecraftBiomeRegionSource.cell(player.serverLevel(),player.blockPosition()));
                        if(membership==ConnectedBiomeRegions.Membership.UNKNOWN)return;
                        (membership==ConnectedBiomeRegions.Membership.SAME?insideNow:outsideNow).add(playerId);
                    }
                    if(fixedIndex<0&&!insideNow.isEmpty()||fixedIndex>=0&&!groups.get(fixedIndex).equals(insideNow))return;
                    var fresh=new ArrayList<Set<UUID>>();if(!insideNow.isEmpty())fresh.add(Set.copyOf(insideNow));fresh.addAll(components(outsideNow));
                    if(!new HashSet<>(fresh).equals(new HashSet<>(groups)))return;
                }
                var destinations=new LinkedHashMap<String,Integer>();
                for(var entry:futures.entrySet()) {
                    RoomConversationEngine.SplitResult choice;
                    try {choice=entry.getValue().getNow(null);}catch(CompletionException failed){choice=null;}
                    int index=-1;
                    if(choice==null||!choice.failure().isBlank()) {notice(r,"신의 이동 판단에 실패했습니다. 잠시 후 다시 판단합니다.");changedAt.put(r.roomId(),server.overworld().getGameTime()+100);return;}
                    if(!choice.roomId().equals(r.roomId())||choice.revision()!=r.revision()||!choice.godId().toString().equals(entry.getKey()))return;
                    try{if(!choice.candidateKey().isBlank())index=Integer.parseInt(choice.candidateKey());}catch(NumberFormatException e){return;}
                    if(index>=groups.size()||index< -1)return;destinations.put(entry.getKey(),index);
                }
                var oldHistory=new ArrayList<>(history.getOrDefault(r.roomId(),List.of()));var region=fixedRegions.remove(r.roomId());
                try {
                    var children=ledger.split(r.roomId(),r.revision(),groups,destinations,fixedIndex);removed(r);
                    boolean retained=false;
                    for(var child:children) {history.put(child.roomId(),new ArrayList<>(oldHistory));
                        if(child.type()==RoomType.PUBLIC_FIXED&&region!=null){fixedRegions.put(child.roomId(),region);retained=true;}
                        changed(child);notice(child,"대화 무리가 나뉘었습니다.");}
                    if(!retained&&region!=null)regions.release(region);
                }catch(IllegalArgumentException e){if(region!=null)fixedRegions.put(r.roomId(),region);MythicTrpg.LOGGER.warn("Room split rejected: {}",e.getMessage());}
            });
        });
    }
}
