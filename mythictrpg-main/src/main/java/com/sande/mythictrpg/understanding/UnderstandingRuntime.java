package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Server-thread admission, bounded async ledger reads/writes, connection/lease fenced completion. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class UnderstandingRuntime {
    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS = new IdentityHashMap<>();
    private static final class Session {
        final ServerPlayer player; final UUID token = UUID.randomUUID(); final long expires;
        boolean busy,advice; int lastRequest; long lastSequence; PlayerUnderstanding.View view;
        Session(ServerPlayer p) { player = p; expires = p.server.getTickCount() + 12000L; lastRequest = p.server.getTickCount(); }
    }
    private UnderstandingRuntime() { }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("mythprofile").executes(c -> { open(c.getSource().getPlayerOrException()); return 1; }));
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            var map = SESSIONS.get(p.server); if (map != null) map.remove(p.getUUID());
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e) { SESSIONS.remove(e.getServer()); }
    public static void open(ServerPlayer player) {
        var sessions = SESSIONS.computeIfAbsent(player.server, ignored -> new HashMap<>());
        var old = sessions.get(player.getUUID());
        if (old != null && player.server.getTickCount() - old.lastRequest < 20) return;
        var session = new Session(player); sessions.put(player.getUUID(), session);
        send(session, true, true, "원본 기록을 확인 중입니다.");
        refresh(session, "");
    }
    private static boolean current(Session s) {
        var map = SESSIONS.get(s.player.server);
        return map != null && map.get(s.player.getUUID()) == s && s.player.server.getTickCount() <= s.expires
                && s.player.server.getPlayerList().getPlayer(s.player.getUUID()) == s.player;
    }
    public static void receive(ServerPlayer player, UnderstandingRequest request) {
        var map = SESSIONS.get(player.server); var s = map == null ? null : map.get(player.getUUID());
        if (s == null || s.player != player || !s.token.equals(request.token()) || !current(s) || request.sequence() <= s.lastSequence) return;
        s.lastSequence = request.sequence();
        if (request.action() == UnderstandingRequest.Action.CLOSE) { map.remove(player.getUUID()); return; }
        if (s.busy) return;
        if (player.server.getTickCount() - s.lastRequest < 10) { send(s, false, false, "요청 간격이 너무 짧습니다. 잠시 후 다시 시도해 주세요."); return; }
        s.lastRequest = player.server.getTickCount();
        if (request.action() == UnderstandingRequest.Action.REFRESH) { refresh(s, ""); return; }
        if (request.action() == UnderstandingRequest.Action.ADVICE) { s.advice=!s.advice;refresh(s, "");return; }
        if (s.view == null) {
            send(s, false, false, "분석할 기록원이 아직 준비되지 않았습니다. 안내를 확인한 뒤 새로고침해 주세요."); return;
        }
        var r = ActionLedgerService.current(player.server);
        if (r == null || r.ledger() == null) { send(s, false, false, "행동 기록이 비활성화되었거나 준비되지 않았습니다."); return; }
        var payload = new LinkedHashMap<String, String>();
        ActionRecord.Type type;
        switch (request.action()) {
            case SHARING -> {
                if(!s.view.active())return;
                boolean allowed=s.view.cards().stream().anyMatch(c->c.key().equals(SupportSharing.KEY)&&c.assessment().equals("허용"));
                type=ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK;payload.put("key",SupportSharing.KEY);payload.put("operation",allowed?"DENY":"ALLOW");
            }
            case START, PAUSE -> {
                if (request.action() == UnderstandingRequest.Action.START == s.view.active()) return;
                type = ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL;
                payload.put("operation", request.action().name());
            }
            case DISPUTE, RESTORE -> {
                if (!s.view.active() || !PlayerUnderstanding.validFeedbackKey(request.key()) || "purpose".equals(request.key())||SupportSharing.KEY.equals(request.key())
                        || s.view.cards().stream().noneMatch(c -> c.key().equals(request.key()))) return;
                type = ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK;
                payload.put("key", request.key()); payload.put("operation", request.action().name());
            }
            case DECLARE, RETRACT -> {
                if (!s.view.active()) return;
                String text = request.text().strip().replaceAll("[\\p{Cc}\\p{Cf}§]", "");
                if (request.action() == UnderstandingRequest.Action.DECLARE && text.isBlank()) {
                    send(s, false, false, "현재 목적을 입력해 주세요. 목적을 지우려면 ‘목적 철회’를 사용하세요."); return;
                }
                type = ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK;
                payload.put("key", "purpose"); payload.put("operation", request.action().name()); payload.put("text", text);
            }
            default -> { return; }
        }
        var pos = player.blockPosition();
        if(request.action()==UnderstandingRequest.Action.PAUSE||"DENY".equals(payload.get("operation")))SupportSharing.revoke(player);
        long sharingRevision=SupportSharing.invalidate(player); // Revoke outstanding support BEFORE asynchronous consent/correction writes.
        MinecraftActivityCapture.flush(player);
        var draft = new ActionRecord.Draft(UUID.randomUUID(), r.captureSession(), r.nextCaptureOrder(),
                "mythictrpg:detail/" + type.name().toLowerCase(Locale.ROOT), 1, player.getUUID(),
                new ActionRecord.Subject("ACTIVITY", "mythictrpg:player_understanding", null), System.currentTimeMillis(),
                player.server.overworld().getGameTime(), player.level().getDayTime(), player.level().dimension().location().toString(),
                new ActionRecord.Position(pos.getX(), pos.getY(), pos.getZ()), type, "COMPLETED", payload, "mythictrpg:admin_only_unprojected");
        s.busy = true; send(s, false, true, "저장 확인 중입니다. 아직 반영 완료가 아닙니다.");
        r.ledger().submit(draft).durable().whenComplete((record, failure) -> player.server.execute(() -> {
            if(failure==null&&SupportSharing.KEY.equals(record.event().payload().get("key"))&&"ALLOW".equals(record.event().payload().get("operation"))
                    &&player.server.getPlayerList().getPlayer(player.getUUID())==player&&ActionLedgerService.current(player.server)==r)
                SupportSharing.allowCommitted(player,sharingRevision);
            if(failure==null && record.event().type()==ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL
                    && player.server.getPlayerList().getPlayer(player.getUUID())==player
                    && ActionLedgerService.current(player.server)==r)MinecraftActivityCapture.controlChanged(player,record);
            if (!current(s)) return;
            s.busy = false;
            if (failure != null) { send(s, false, false, "저장을 확인하지 못했습니다. 반영 여부는 새로고침으로 확인해 주세요."); return; }
            refresh(s, "저장 확인됨. 화면의 정정·목적은 NPC에게 자동 공유하지 않습니다.");
        }));
    }
    private static void refresh(Session s, String notice) {
        var r = ActionLedgerService.current(s.player.server);
        if (r == null || r.ledger() == null || r.worldId() == null) {
            s.view = null; send(s, false, false, "행동 원장이 비활성화되었거나 준비되지 않았습니다. 이 화면이 기록 설정을 자동 변경하지는 않습니다."); return;
        }
        s.busy = true; var world = r.worldId();
        MinecraftActivityCapture.flush(s.player);
        r.ledger().understandingSnapshot(s.player.getUUID()).whenComplete((snapshot, failure) -> s.player.server.execute(() -> {
            if (!current(s)) return;
            s.busy = false;
            if (ActionLedgerService.current(s.player.server) != r || failure != null) {
                s.view = null; send(s, false, false, "기록을 조회하지 못했습니다. 잠시 후 새로고침해 주세요."); return;
            }
            var activity = PlayerUnderstanding.project(world, s.player.getUUID(), snapshot.activity(), r.ledger().status().rejected() > 0);
            MinecraftActivityCapture.controlChanged(s.player,snapshot.activity().control());
            var statistics=SupportStatistics.append(activity,snapshot.statistics(),snapshot.activity().feedback(),s.advice);
            var sharing=SupportSharing.card(statistics,!SupportSharing.blocked(s.player)&&SupportSharing.allowed(world,s.player.getUUID(),snapshot));
            s.view = PurposeSelfView.append(sharing,world,s.player.getUUID(),snapshot.purposes(),s.advice);
            String coverage = " 분석 시작 이후 확인한 활동만 사용합니다."+MinecraftActivityCapture.presence(s.player).notice();
            send(s, false, false, (notice.isBlank() ? s.view.notice() : notice + " " + s.view.notice()) + coverage);
        }));
    }
    private static void send(Session s, boolean open, boolean pending, String notice) {
        if (!current(s)) return;
        PacketDistributor.sendToPlayer(s.player, new UnderstandingPayload(s.token, open,
                s.view != null && s.view.active(), pending, notice, s.view == null ? List.of() : s.view.cards()));
    }
}
