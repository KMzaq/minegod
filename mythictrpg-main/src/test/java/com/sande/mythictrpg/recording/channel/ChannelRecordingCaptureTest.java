package com.sande.mythictrpg.recording.channel;

import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.recording.server.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Real SQLite acceptance/receipt tests. Optional transformed Minecraft/FTB hooks are separately GameTest-tested. */
public final class ChannelRecordingCaptureTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var parent = Path.of(args.length == 0 ? "build/channel-recording-test" : args[0]).toAbsolutePath().normalize();
        if (!parent.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent); var root = Files.createTempDirectory(parent, "channels-");
        var world = UUID.randomUUID(); var player = UUID.randomUUID(); var peer = UUID.randomUUID();
        var store = open(root, world); var capture = new ChannelRecordingCapture(store);
        String raw = "  한글 🌌\n 같은 말을 다시 해도 새 발생이다. ";
        var pending = capture.begin(player, ChannelRecordingCapture.Channel.PRIVATE_MSG, raw);
        pending.dispatched(player, "CHAT_ECHO", "You whisper to peer: " + raw, true);
        pending.dispatched(peer, "CHAT", "player whispers: " + raw, true);
        var whisper = pending.freeze();
        var first = await(capture.capture(whisper, true));
        check(first.status() == Status.STORED, "accepted private occurrence stored: " + first.reasonCode());
        check(await(store.statistics()).messages() == 1 && await(store.statistics()).deliveries() == 2, "echo and target receipts share one raw");
        check(await(store.inspectMessage(whisper.id(), 10000)).orElseThrow().equals(raw), "no raw command or Unicode/whitespace truncation");
        check(await(capture.capture(whisper, true)).status() == Status.DUPLICATE, "same occurrence retry idempotent");
        check(await(store.statistics()).messages() == 1 && await(store.statistics()).deliveries() == 2, "retry never duplicates deliveries");
        var repeated = capture.begin(player, ChannelRecordingCapture.Channel.PRIVATE_MSG, raw);
        repeated.dispatched(peer, "CHAT", raw, true);
        check(await(capture.capture(repeated.freeze(), true)).status() == Status.STORED && await(store.statistics()).messages() == 2, "same words again have independent occurrence");
        var notDelivered = capture.begin(player, ChannelRecordingCapture.Channel.PUBLIC_CHAT, "ACCEPTED_BUT_NOT_DELIVERED");
        check(await(capture.capture(notDelivered.freeze(), true)).status() == Status.STORED && await(store.statistics()).messages() == 3
                        && await(store.statistics()).deliveries() == 3,
                "accepted input with all writes absent/failed preserves raw but never fabricates a receipt");
        var partial = capture.begin(player, ChannelRecordingCapture.Channel.PUBLIC_CHAT, "partly secret");
        partial.dispatched(peer, "CHAT", "partly ######", false);
        var partialEvent = partial.freeze(); await(capture.capture(partialEvent, true));
        var db = root.resolve("mythictrpg-recording-v2").resolve(store.datasetId().orElseThrow().toString()).resolve("recording.sqlite");
        check(text(db, "SELECT context_json FROM message_contexts WHERE message_id='" + partialEvent.id() + "'").contains("\"fullAudience\":[]"),
                "filtered view remains a receipt but grants no full-original audience");
        check(text(db, "SELECT plain_text FROM delivery_views WHERE view_hash=(SELECT view_hash FROM deliveries WHERE message_id='" + partialEvent.id() + "')").equals("partly ######"),
                "exact filtered delivery view, not the original, is persisted");
        var ftb = capture.begin(player, ChannelRecordingCapture.Channel.FTB_TEAM, "team only");
        ftb.dispatched(peer, "FTB_CHAT", "<author @team> team only", true);
        ftb.dispatched(peer, "FTB_UI", "team only", true);
        var ftbEvent = ftb.freeze(); await(capture.capture(ftbEvent, true));
        check(number(db, "SELECT count(*) FROM messages WHERE id='" + ftbEvent.id() + "'") == 1
                && number(db, "SELECT count(*) FROM deliveries WHERE message_id='" + ftbEvent.id() + "'") == 2, "FTB chat/UI surfaces are one logical raw");
        check(number(db, "SELECT count(*) FROM deliveries WHERE actor_kind='GOD'") == 0, "public/private/team channels never invent God hearing");
        var overflow = capture.begin(player, ChannelRecordingCapture.Channel.FTB_TEAM, "oversized audience");
        for (int index = 0; index <= ChannelRecordingCapture.MAX_RECEIPTS; index++) overflow.dispatched(UUID.randomUUID(), "FTB_UI", "oversized audience", true);
        check(overflow.freeze().deliveries().size() == 256, "game-thread receipt allocation bounded");
        check(await(capture.capture(overflow.freeze(), true)).reasonCode().equals("CHANNEL_RECEIPT_BUDGET"), "over-bound audience fails explicitly instead of pretending full capture");
        var huge = capture.begin(player, ChannelRecordingCapture.Channel.PLAYER_SAY, "x".repeat(ChannelRecordingCapture.MAX_BODY_CHARS + 1));
        check(await(capture.capture(huge.freeze(), true)).reasonCode().equals("CHANNEL_BODY_BUDGET"), "oversized body explicit gap, no truncation");
        check(await(capture.capture(whisper, false)).reasonCode().equals("STALE_CHANNEL_SCOPE"), "closed runtime scope cannot apply late");
        var foreign = new ChannelRecordingCapture.Occurrence(UUID.randomUUID(), whisper.epoch(), whisper.id(), player, whisper.channel(), raw, whisper.acceptedAt(), whisper.deliveries(), "");
        check(await(capture.capture(foreign, true)).reasonCode().equals("STALE_CHANNEL_SCOPE"), "foreign world rejected");
        var foreignEpoch = new ChannelRecordingCapture.Occurrence(world, UUID.randomUUID(), whisper.id(), player, whisper.channel(), raw, whisper.acceptedAt(), whisper.deliveries(), "");
        check(await(capture.capture(foreignEpoch, true)).reasonCode().equals("STALE_CHANNEL_SCOPE"), "foreign runtime rejected");
        var threaded = CompletableFuture.supplyAsync(() -> { try { capture.begin(player, ChannelRecordingCapture.Channel.PUBLIC_CHAT, "worker"); return false; } catch (IllegalStateException expected) { return true; } });
        check(await(threaded), "capture and scope allocation reject non-game threads");
        long count = await(store.statistics()).messages(); await(store.closeAsync());
        var restarted = open(root, world); var reopenedCapture = new ChannelRecordingCapture(restarted);
        check(await(reopenedCapture.capture(whisper, true)).reasonCode().equals("STALE_CHANNEL_SCOPE"), "old epoch snapshot cannot replay after actual database restart");
        check(await(restarted.statistics()).messages() == count && await(restarted.inspectMessage(ftbEvent.id(), 100)).orElseThrow().equals("team only"), "actual SQLite reopen preserves accepted raw and does not import history");
        await(restarted.closeAsync());
        var offRoot = root.resolve("off"); Files.createDirectories(offRoot);
        var off = await(WorldRecordingService.open(offRoot, UUID.randomUUID(), new RecordingSettings(RecordingSettings.Mode.OFF, 128000000, 2000000, .9, .95), new WorldRecordingService.CutoverBoundary("off", Map.of())));
        check(!Files.exists(offRoot.resolve("mythictrpg-recording-v2")), "OFF creates no archive directory or database"); await(off.closeAsync());
        System.out.println("ChannelRecordingCaptureTest: " + checks + " checks passed; fixtures=" + root);
    }
    private static WorldRecordingService open(Path root, UUID world) throws Exception {
        var store = await(WorldRecordingService.open(root, world, new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY, 128000000, 2000000, .9, .95), new WorldRecordingService.CutoverBoundary("channel-fixture", Map.of())));
        check(store.health().state() == WorldRecordingService.State.READY, "native recording store ready: " + store.health().reasonCode()); return store;
    }
    private static String text(Path db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON"); try (var rows = statement.executeQuery(sql)) { if (!rows.next()) throw new AssertionError("Missing SQL row"); return rows.getString(1); }
        }
    }
    private static long number(Path db, String sql) throws Exception { return Long.parseLong(text(db, sql)); }
    private static <T> T await(CompletionStage<T> result) throws Exception { return result.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    private static void check(boolean success, String description) { checks++; if (!success) throw new AssertionError(description); }
}
