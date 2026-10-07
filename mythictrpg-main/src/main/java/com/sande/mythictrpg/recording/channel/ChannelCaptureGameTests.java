package com.sande.mythictrpg.recording.channel;

import com.sande.mythictrpg.recording.server.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Installed optional mixins + real recipient packet futures + SQLite, confined to an explicit RECORD_ONLY build fixture. */
@GameTestHolder("mythictrpg_recording_channels")
@PrefixGameTestTemplate(false)
public final class ChannelCaptureGameTests {
    private ChannelCaptureGameTests() { }
    @GameTest(templateNamespace = "mythictrpg_recording_channels", template = "empty", timeoutTicks = 20000)
    public static void actualAcceptedChannelsKeepRecipientIsolation(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        // GameTestServer runs ticks without the normal 50 ms pacing. Allow native JDBC and
        // network callbacks real wall-clock time; this is fixture-only, not a production wait.
        helper.onEachTick(() -> java.util.concurrent.locks.LockSupport.parkNanos(1_000_000));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(RecordingRuntime.channelCapture(helper.getLevel().getServer()).isPresent(), "RECORD_ONLY fixture store is not READY"))
                .thenExecute(fixture::start).thenWaitUntil(() -> helper.assertTrue(fixture.gui.isDone(), "FTB GUI queued input pending"))
                .thenExecute(fixture::afterGui).thenIdle(5).thenExecute(fixture::readSql)
                .thenWaitUntil(() -> helper.assertTrue(fixture.verified.isDone(), "Waiting for actual SQLite capture"))
                .thenExecute(() -> {
                    try { fixture.verified.join(); }
                    catch (CompletionException failure) { helper.fail("Channel capture: " + failure.getCause()); }
                    finally { fixture.close(); }
                }).thenSucceed();
    }
    private static final class Fixture {
        final GameTestHelper helper;
        final List<io.netty.channel.embedded.EmbeddedChannel> channels = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();
        ServerPlayer owner, peer, outsider, hidden;
        FtbChannelGameTestActions.Fixture ftb;
        CompletableFuture<Void> gui, verified;
        String teamName;
        Fixture(GameTestHelper helper) { this.helper = helper; }
        void start() {
            try {
                var root = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                require(root.toString().replace('\\', '/').contains("/build/"), "Only build-contained fixture worlds are allowed");
                owner = connect("CapOwner"); peer = connect("CapPeer"); outsider = connect("CapOutside"); hidden = connect("CapHidden");
                hidden.updateOptions(new ClientInformation("en_us", 8, ChatVisiblity.HIDDEN, true, 0, HumanoidArm.RIGHT, false, false));
                owner.server.getPlayerList().op(owner.getGameProfile());
                owner.server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(owner.getGameProfile(), 4, false));
                require(owner.hasPermissions(2), "Fixture requires operator chat command permission");
                var board = owner.getScoreboard(); teamName = "capture_" + UUID.randomUUID().toString().substring(0, 6);
                var team = board.addPlayerTeam(teamName); board.addPlayerToTeam(owner.getScoreboardName(), team); board.addPlayerToTeam(peer.getScoreboardName(), team);
                peer.addTag("capture_target"); outsider.addTag("capture_target");
                broadcast("channel_public", null, FilterMask.PASS_THROUGH);
                broadcast("!channel_bang", Component.literal("channel_bang"), FilterMask.PASS_THROUGH);
                inbound("msg @a[tag=capture_target] channel_whisper"); inbound("teammsg channel_scoreboard");
                inbound("say channel_say"); inbound("me channel_emote"); inbound("msg MissingCapturePlayer channel_rejected");
                owner.sendSystemMessage(Component.literal("channel_system_excluded"));
                owner.server.getCommands().performPrefixedCommand(owner.server.createCommandSourceStack(), "execute as CapOwner run say channel_console_excluded");
                owner.updateOptions(new ClientInformation("en_us", 8, ChatVisiblity.FULL, true, 0, HumanoidArm.RIGHT, true, false));
                var partial = new FilterMask("channel_partial".length()); partial.setFiltered(0);
                broadcast("channel_partial", null, partial); broadcast("channel_fullfilter", null, FilterMask.FULLY_FILTERED);
                owner.updateOptions(ClientInformation.createDefault());
                for (var p : List.of(owner, peer, outsider)) p.updateOptions(new ClientInformation("en_us", 8, ChatVisiblity.HIDDEN, true, 0, HumanoidArm.RIGHT, false, false));
                broadcast("channel_all_hidden", null, FilterMask.PASS_THROUGH);
                for (var p : List.of(owner, peer, outsider)) p.updateOptions(ClientInformation.createDefault());
                ftb = FtbChannelGameTestActions.open(owner, peer); gui = ftb.gui("channel_ftb_gui");
            } catch (Exception failure) { close(); throw new IllegalStateException("Channel fixture setup failed", failure); }
        }
        void afterGui() {
            try {
                gui.join(); ftb.redirectedChat("channel_ftb_redirect"); ftb.command("channel_ftb_command", this::inbound); ftb.excludedTraffic();
                // A real failed write disconnects that player; verify FTB sends before inducing the failure.
                failTransport(channels.get(1), false); failTransport(channels.get(2), true);
                broadcast("channel_transport_partial", null, FilterMask.PASS_THROUGH);
            } catch (Exception failure) { close(); throw new IllegalStateException("FTB actual entrypoint failed", failure); }
        }
        void readSql() {
            var store = RecordingRuntime.current(owner.server).orElseThrow();
            Path db = owner.server.getWorldPath(LevelResource.ROOT).resolve("mythictrpg-recording-v2").resolve(store.datasetId().orElseThrow().toString()).resolve("recording.sqlite");
            var ownerId = owner.getUUID().toString(); var peerId = peer.getUUID().toString(); var outsiderId = outsider.getUUID().toString(); var hiddenId = hidden.getUUID().toString();
            verified = CompletableFuture.runAsync(() -> {
                try {
                    for (int retry = 0; retry < 160; retry++) {
                        if (count(db, "SELECT count(*) FROM messages WHERE producer='accepted-channels-v2'") >= 13) break;
                        Thread.sleep(50);
                    }
                    require(count(db, "SELECT count(*) FROM messages WHERE producer='accepted-channels-v2'") == 13, "Expected exactly thirteen accepted channel occurrences");
                    require(count(db, "SELECT count(*) FROM message_parts WHERE body='channel_all_hidden'") == 1
                            && receipts(db, "channel_all_hidden", null) == 0, "Accepted but undelivered raw must not fabricate receipt");
                    require(receipts(db, "channel_public", null) == 3, "Hidden recipient must not get public receipt");
                    require(receipts(db, "channel_public", hiddenId) == 0, "Hidden recipient leaked");
                    require(receipts(db, "channel_whisper", null) == 3, "Multiple whisper targets plus grouped sender echo must be one raw / three recipients");
                    require(count(db, "SELECT count(*) FROM delivery_parts_resolved p JOIN deliveries d ON d.id=p.receipt_id JOIN message_parts m ON m.message_id=d.message_id WHERE m.body='channel_whisper' AND d.actor_id='" + ownerId + "'") == 2, "Both exact sender echo projections must survive");
                    require(receipts(db, "channel_scoreboard", outsiderId) == 0 && receipts(db, "channel_scoreboard", null) == 2, "Scoreboard team scope mixed with public/FTB scope");
                    require(receipts(db, "channel_fullfilter", null) == 1 && receipts(db, "channel_fullfilter", ownerId) == 1, "Fully filtered recipients got receipts");
                    require(count(db, "SELECT count(*) FROM delivery_views v JOIN message_parts m ON m.message_id=v.message_id WHERE m.body='channel_partial' AND v.plain_text LIKE '%#hannel_partial%'") >= 1, "Partial filtered view not exact");
                    require(receipts(db, "channel_transport_partial", null) == 1 && receipts(db, "channel_transport_partial", ownerId) == 1, "Failed/cancelled packet future fabricated a receipt");
                    for (String body : List.of("channel_ftb_gui", "channel_ftb_redirect", "channel_ftb_command")) {
                        require(receipts(db, body, null) == 4 && receipts(db, body, peerId) == 2, "FTB chat/UI must share one raw with four actual surfaces: " + body);
                        require(receipts(db, body, outsiderId) == 0 && receipts(db, body, hiddenId) == 0, "FTB privacy leaked");
                    }
                    require(count(db, "SELECT count(*) FROM deliveries WHERE actor_kind='GOD'") == 0, "Channels must not invent God knowledge");
                    require(count(db, "SELECT count(*) FROM message_parts WHERE body LIKE '%excluded%' OR body='channel_rejected'") == 0, "System/console/failed command was recorded");
                    require(count(db, "SELECT count(*) FROM messages WHERE producer='accepted-channels-v2' AND actor_id<>'" + ownerId + "'") == 0, "Wrong author identity");
                } catch (Exception failure) { throw new CompletionException(failure); }
            });
        }
        void inbound(String command) { owner.connection.handleChatCommand(new ServerboundChatCommandPacket(command)); }
        void broadcast(String body, Component decorated, FilterMask mask) {
            var message = PlayerChatMessage.unsigned(owner.getUUID(), body).filter(mask);
            if (decorated != null) message = message.withUnsignedContent(decorated);
            owner.server.getPlayerList().broadcastChatMessage(message, owner, ChatType.bind(ChatType.CHAT, owner));
        }
        ServerPlayer connect(String name) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
            var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new io.netty.channel.embedded.EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            // FTB uses Architectury's negotiated common channels, not NeoForge's payload registry.
            // Advertise the two real client channels on this mock transport before testing actual sends.
            var common = net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateCommonChannels(
                    connection, net.minecraft.network.ConnectionProtocol.PLAY);
            common.add(dev.ftb.mods.ftbteams.net.SendMessageResponseMessage.TYPE.id());
            common.add(dev.ftb.mods.ftbteams.net.SyncMessageHistoryMessage.TYPE.id());
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie); players.add(player); return player;
        }
        void close() {
            try { if (ftb != null && (gui == null || gui.isDone())) ftb.close(); }
            catch (Exception failure) { throw new IllegalStateException("FTB fixture cleanup failed", failure); }
            finally {
                if (owner != null) {
                    owner.server.getPlayerList().deop(owner.getGameProfile());
                    if (teamName != null && owner.getScoreboard().getPlayerTeam(teamName) != null) owner.getScoreboard().removePlayerTeam(owner.getScoreboard().getPlayerTeam(teamName));
                }
                for (var player : players) if (player.server.getPlayerList().getPlayer(player.getUUID()) == player) player.server.getPlayerList().remove(player);
                channels.forEach(io.netty.channel.embedded.EmbeddedChannel::finishAndReleaseAll);
            }
        }
    }
    private static void failTransport(io.netty.channel.embedded.EmbeddedChannel channel, boolean cancel) {
        channel.pipeline().addFirst("channel-test-failure", new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(io.netty.channel.ChannelHandlerContext context, Object message, io.netty.channel.ChannelPromise promise) throws Exception {
                if (message instanceof ClientboundPlayerChatPacket chat && chat.body().content().equals("channel_transport_partial")) {
                    if (cancel) promise.cancel(false); else promise.setFailure(new java.io.IOException("TEST_EXPECTED_PACKET_FAILURE"));
                    io.netty.util.ReferenceCountUtil.release(message);
                } else context.write(message, promise);
            }
        });
    }
    private static long receipts(Path db, String body, String actor) throws Exception {
        return count(db, "SELECT count(*) FROM deliveries d JOIN message_parts m ON m.message_id=d.message_id WHERE m.body='" + body + "'" + (actor == null ? "" : " AND d.actor_id='" + actor + "'"));
    }
    private static long count(Path db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON"); try (var rows = statement.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
        }
    }
    private static void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
