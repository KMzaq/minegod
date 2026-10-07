package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.network.RaidPagePayload;
import com.sande.mythictrpg.network.RaidRequestPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Ephemeral bounded view grants. Existing RaidRuntime alone mutates game state. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RaidUiService {
    public static final RaidUiService INSTANCE = new RaidUiService();
    private static final long LEASE_TICKS = 600;
    private final Map<UUID, Grant> grants = new HashMap<>();
    private MinecraftServer server;
    private record Stamp(RaidDefinition definition, RaidState.Status status, Set<UUID> members) { }
    private record Visible(RaidPagePayload.Row row, Stamp stamp) { }
    private record Grant(UUID token, long expires, int page, List<Visible> rows) { }
    private RaidUiService() { }

    public void open(ServerPlayer player) { send(player, page(player, 0, true, "")); }
    public void receive(ServerPlayer player, RaidRequestPayload request) {
        RaidPagePayload response = handle(player, request);
        if (response != null) send(player, response);
    }
    private static void send(ServerPlayer p, RaidPagePayload response) { PacketDistributor.sendToPlayer(p, response); }
    private void thread(ServerPlayer p) {
        if (!p.server.isSameThread()) throw new IllegalStateException("Raid UI requires server thread");
        if (server != p.server) { grants.clear(); server = p.server; }
    }

    /** Package-visible seam exercises the same grant/authority flow without a client rendering harness. */
    RaidPagePayload page(ServerPlayer p, int requested, boolean open, String notice) {
        thread(p);
        long now = p.server.overworld().getGameTime();
        grants.entrySet().removeIf(entry -> entry.getValue().expires < now);
        List<Visible> visible = visible(p);
        int pages = Math.max(1, (visible.size() + RaidPagePayload.PAGE_SIZE - 1) / RaidPagePayload.PAGE_SIZE);
        int index = Math.clamp(requested, 0, pages - 1);
        List<Visible> rows = List.copyOf(visible.subList(index * RaidPagePayload.PAGE_SIZE, Math.min(visible.size(), (index + 1) * RaidPagePayload.PAGE_SIZE)));
        UUID token = UUID.randomUUID();
        grants.put(p.getUUID(), new Grant(token, now + LEASE_TICKS, index, rows));
        return new RaidPagePayload(token, open, index, pages, notice, rows.stream().map(Visible::row).toList());
    }

    RaidPagePayload handle(ServerPlayer p, RaidRequestPayload request) {
        thread(p);
        Grant grant = grants.get(p.getUUID());
        long now = p.server.overworld().getGameTime();
        if (grant == null || !grant.token.equals(request.token()) || grant.expires < now)
            return page(p, grant == null ? 0 : grant.page, false, "화면이 만료되었거나 이미 처리된 요청입니다. 현재 상태로 갱신했습니다.");
        grants.remove(p.getUUID()); // Consume before validation/execution: retry can never execute twice.
        if (request.action() == RaidRequestPayload.Action.CLOSE) return null;
        if (request.action().bit() == 0) {
            int offset = request.action() == RaidRequestPayload.Action.NEXT ? 1 : request.action() == RaidRequestPayload.Action.PREVIOUS ? -1 : 0;
            return page(p, grant.page + offset, false, "");
        }
        if (request.row() >= grant.rows.size()) return page(p, grant.page, false, "유효하지 않은 항목입니다.");
        Visible issued = grant.rows.get(request.row());
        Visible live = visible(p).stream().filter(row -> row.row.key().equals(issued.row.key())).findFirst().orElse(null);
        if (!issued.row.allows(request.action()) || live == null || !live.row.allows(request.action())
                || !issued.stamp.equals(live.stamp)) return page(p, grant.page, false, "참가자·상태·권한이 바뀌었습니다. 다시 확인하세요.");
        RaidRuntime.Result result;
        if (request.action() == RaidRequestPayload.Action.CREATE)
            result = RaidRuntime.INSTANCE.create(p, issued.stamp.definition.id());
        else {
            UUID id = UUID.fromString(issued.row.key().substring(2));
            result = switch (request.action()) {
                case JOIN -> RaidRuntime.INSTANCE.join(p, id);
                case START -> RaidRuntime.INSTANCE.start(p, id);
                case CANCEL -> RaidRuntime.INSTANCE.cancel(p, id);
                case LEAVE -> RaidRuntime.INSTANCE.leave(p, id);
                default -> RaidRuntime.Result.no("지원하지 않는 요청입니다.");
            };
        }
        return page(p, 0, false, (result.succeeded() ? "완료: " : "거절: ") + result.message());
    }

    private List<Visible> visible(ServerPlayer p) {
        RaidState state = RaidState.get(p.server);
        if (!state.ready()) return List.of();
        List<Visible> result = new ArrayList<>();
        List<RaidState.Attempt> attempts = state.attempts().stream()
                .sorted(Comparator.<RaidState.Attempt, Boolean>comparing(a -> !a.members.containsKey(p.getUUID()))
                        .thenComparing(a -> a.terminal()).thenComparing(Comparator.comparingLong((RaidState.Attempt a) -> a.createdAt).reversed())
                        .thenComparing(a -> a.id)).toList();
        for (RaidState.Attempt a : attempts) {
            boolean member = a.members.containsKey(p.getUUID()), leader = a.leader.equals(p.getUUID());
            boolean join = RaidRuntime.INSTANCE.canJoin(p, a.id).succeeded();
            if (!member && !p.hasPermissions(2) && !join) continue;
            int actions = join ? 2 : 0;
            if (leader && a.status == RaidState.Status.FORMING && a.members.size() >= a.definition.minimumPlayers()) actions |= 4;
            if (leader && !a.terminal() && a.status != RaidState.Status.CLEANUP_PENDING && a.status != RaidState.Status.VICTORY_PENDING) actions |= 8;
            if (member && !leader && a.status == RaidState.Status.FORMING) actions |= 16;
            String info = a.definition.mode() + " | 인원 " + a.members.size() + "/" + a.definition.maximumPlayers()
                    + " (최소 " + a.definition.minimumPlayers() + ")\n" + (leader ? "내가 리더" : member ? "참가 중" : "참가 가능한 모집")
                    + "\n시도: " + a.id;
            if (member) info += "\n남은 목숨: " + Math.max(0, a.definition.livesPerPlayer() - a.members.get(p.getUUID()).deaths)
                    + (a.members.get(p.getUUID()).eliminated ? " (참가 종료)" : "");
            long now = p.server.overworld().getGameTime();
            if (a.status == RaidState.Status.QUEUED) info += "\n대기 제한까지 " + Math.max(0, (a.definition.queueTimeoutTicks() - (now - a.queuedAt) + 19) / 20) + "초";
            if (a.status == RaidState.Status.ACTIVE) {
                info += "\n전투 제한까지 " + Math.max(0, (a.definition.combatTimeoutTicks() - (now - a.startedAt) + 19) / 20) + "초";
                var level = a.arena == null ? null : p.server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, a.arena.dimension()));
                var boss = level == null || a.bossId == null ? null : RaidRuntime.living(level.getEntity(a.bossId));
                if (boss != null) info += "\n보스 체력: " + (int)Math.ceil(boss.getHealth()) + "/" + (int)Math.ceil(boss.getMaxHealth());
            }
            if (!a.reason.isBlank()) info += "\n결과: " + a.reason;
            if (a.status == RaidState.Status.QUEUED || a.status == RaidState.Status.ACTIVE) info += "\n참가 명단 고정: 모집 탈퇴 불가";
            result.add(new Visible(new RaidPagePayload.Row("a:" + a.id, a.definition.displayName(), a.status.name(), clip(info, 1024), actions),
                    new Stamp(a.definition, a.status, Set.copyOf(a.members.keySet()))));
        }
        RaidCatalog.INSTANCE.snapshot().raids().values().stream().sorted(Comparator.comparing(d -> d.id().toString()))
                .filter(d -> RaidRuntime.INSTANCE.canCreate(p, d.id()).succeeded()).forEach(d ->
                        result.add(new Visible(new RaidPagePayload.Row("d:" + UUID.nameUUIDFromBytes(d.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), d.displayName(), "새 모집", clip(d.id() + "\n" + d.mode()
                                + "\n인원 " + d.minimumPlayers() + "~" + d.maximumPlayers() + "\n목숨 " + d.livesPerPlayer()
                                + "\n전투 제한 " + d.combatTimeoutTicks() / 20 + "초", 1024), 1), new Stamp(d, null, Set.of()))));
        // SavedData accepts at most 512 attempts and the catalog at most 256 definitions.
        // No retained history is deleted or modified by this bounded presentation layer.
        if (result.size() > 768) return List.copyOf(result.subList(0, 768));
        return result;
    }
    private static String clip(String text, int maximum) {
        if (text.length() <= maximum) return text;
        int end = maximum - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) INSTANCE.grants.remove(p.getUUID());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { INSTANCE.grants.clear(); INSTANCE.server = null; }
}
