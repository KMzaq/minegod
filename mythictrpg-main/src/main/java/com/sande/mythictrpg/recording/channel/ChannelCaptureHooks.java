package com.sande.mythictrpg.recording.channel;

import com.sande.mythictrpg.recording.server.RecordingRuntime;
import com.sande.mythictrpg.recording.server.WorldRecordingService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit producer scopes only. No raw command capture and no blanket system/HUD listener. */
public final class ChannelCaptureHooks {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<ServerPlayer> COMMAND_AUTHOR = new ThreadLocal<>();
    private static final AtomicInteger PENDING_SCOPES = new AtomicInteger();
    private static final AtomicLong PENDING_BYTES = new AtomicLong();
    private static final int MAX_SCOPES = 64;
    private ChannelCaptureHooks() { }
    public static final class ReceiptAttempt {
        private final ChannelRecordingCapture.Dispatch dispatch;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private ReceiptAttempt(ChannelRecordingCapture.Dispatch dispatch) { this.dispatch = dispatch; }
        public void success() { result.complete(true); }
        public void failure() { result.complete(false); }
    }
    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final MinecraftServer server;
        private final ChannelRecordingCapture capture;
        private final ChannelRecordingCapture.Pending pending;
        private final PlayerChatMessage message;
        private final List<ReceiptAttempt> attempts = new ArrayList<>();
        private Component expectedExternalChat;
        private java.util.function.Function<Packet<?>, String> externalUi;
        private long reserved;
        private boolean closed;
        private Scope(MinecraftServer server, ChannelRecordingCapture capture, ChannelRecordingCapture.Pending pending,
                PlayerChatMessage message, long reserved) {
            previous = ACTIVE.get(); this.server = server; this.capture = capture; this.pending = pending;
            this.message = message; this.reserved = reserved; ACTIVE.set(this);
        }
        private ReceiptAttempt track(UUID recipient, String surface, String view, boolean full) {
            if (attempts.size() == ChannelRecordingCapture.MAX_RECEIPTS) { pending.reject("CHANNEL_RECEIPT_BUDGET"); return null; }
            long bytes = (long) view.length() * 3;
            if (view.length() > ChannelRecordingCapture.MAX_BODY_CHARS || !reserve(bytes)) { pending.reject("CHANNEL_PENDING_BYTES_BUDGET"); return null; }
            reserved += bytes;
            var attempt = new ReceiptAttempt(new ChannelRecordingCapture.Dispatch(recipient, surface, view, full)); attempts.add(attempt); return attempt;
        }
        @Override public void close() {
            if (closed) return; closed = true;
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            if (pending == null) return;
            var frozenAttempts = List.copyOf(attempts);
            // Network write success, not client UI acknowledgement. Unconfirmed writes never become receipts.
            CompletableFuture.allOf(frozenAttempts.stream().map(attempt -> attempt.result).toArray(CompletableFuture[]::new))
                    .orTimeout(5, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
                if (failure != null) capture.gap("CHANNEL_DISPATCH_CONFIRMATION_TIMEOUT");
                if (frozenAttempts.stream().anyMatch(attempt -> attempt.result.isDone() && !attempt.result.getNow(false)))
                    capture.gap("CHANNEL_DISPATCH_FAILED");
                for (var attempt : frozenAttempts) if (attempt.result.getNow(false)) {
                    var dispatch = attempt.dispatch;
                    pending.dispatched(dispatch.recipient(), dispatch.surface(), dispatch.view(), dispatch.fullOriginal());
                }
                var occurrence = pending.freeze();
                PENDING_BYTES.addAndGet(-reserved); PENDING_SCOPES.decrementAndGet();
                Runnable apply = () -> {
                    try { RecordingRuntime.captureChannel(server, capture, occurrence); }
                    catch (RuntimeException unavailable) { capture.gap("CHANNEL_ADAPTER_UNAVAILABLE"); }
                };
                if (server.isSameThread()) apply.run(); else if (server.isRunning()) server.execute(apply);
            });
        }
    }
    /** Identity only. The command string and arbitrary arguments never enter recording state. */
    public static final class CommandScope implements AutoCloseable {
        private final ServerPlayer previous = COMMAND_AUTHOR.get();
        private CommandScope(ServerPlayer player) { COMMAND_AUTHOR.set(player); }
        @Override public void close() { if (previous == null) COMMAND_AUTHOR.remove(); else COMMAND_AUTHOR.set(previous); }
    }
    public static CommandScope command(ServerPlayer player) { return new CommandScope(player); }
    public static ServerPlayer authenticatedCommandPlayer(CommandSourceStack source) {
        var player = source.getPlayer();
        return player != null && source.source == player && COMMAND_AUTHOR.get() == player ? player : null;
    }
    public static ServerPlayer commandPlayer(CommandSourceStack source, PlayerChatMessage message) {
        var player = source.getPlayer();
        return player != null && source.source == player && (COMMAND_AUTHOR.get() == player
                || !message.isSystem() && message.sender().equals(player.getUUID())) ? player : null;
    }
    public static Scope broadcast(ServerPlayer author, PlayerChatMessage message, ChatType.Bound type) {
        var channel = type.chatType().is(ChatType.CHAT) ? ChannelRecordingCapture.Channel.PUBLIC_CHAT
                : type.chatType().is(ChatType.SAY_COMMAND) ? ChannelRecordingCapture.Channel.PLAYER_SAY
                : type.chatType().is(ChatType.EMOTE_COMMAND) ? ChannelRecordingCapture.Channel.PLAYER_EMOTE : null;
        return begin(author, message, channel);
    }
    public static Scope begin(ServerPlayer author, PlayerChatMessage message, ChannelRecordingCapture.Channel channel) {
        return open(author, channel == null || message == null ? null : message.signedContent(), channel, message);
    }
    public static Scope beginExternal(ServerPlayer author, String rawBody, Component expectedChat,
            java.util.function.Function<Packet<?>, String> uiView) {
        var scope = open(author, rawBody, ChannelRecordingCapture.Channel.FTB_TEAM, null);
        if (scope.pending != null) { scope.expectedExternalChat = expectedChat.copy(); scope.externalUi = uiView; }
        return scope;
    }
    private static Scope open(ServerPlayer author, String body, ChannelRecordingCapture.Channel channel, PlayerChatMessage message) {
        if (author == null || body == null || channel == null || author.getServer() == null || !author.getServer().isSameThread()) return empty();
        ChannelRecordingCapture capture;
        try { capture = RecordingRuntime.channelCapture(author.getServer()).orElse(null); }
        catch (RuntimeException unavailable) { return empty(); }
        if (capture == null) return empty();
        long bytes = (long) body.length() * 3;
        if (body.length() > ChannelRecordingCapture.MAX_BODY_CHARS) { capture.gap("CHANNEL_BODY_BUDGET"); return empty(); }
        if (PENDING_SCOPES.incrementAndGet() > MAX_SCOPES) { PENDING_SCOPES.decrementAndGet(); capture.gap("CHANNEL_PENDING_SCOPE_BUDGET"); return empty(); }
        if (!reserve(bytes)) { PENDING_SCOPES.decrementAndGet(); capture.gap("CHANNEL_PENDING_BYTES_BUDGET"); return empty(); }
        try {
            var pending = capture.begin(author.getUUID(), channel, body);
            if (pending != null) return new Scope(author.getServer(), capture, pending, message, bytes);
        } catch (RuntimeException unavailable) { capture.gap("CHANNEL_ADAPTER_UNAVAILABLE"); }
        PENDING_BYTES.addAndGet(-bytes); PENDING_SCOPES.decrementAndGet(); return empty();
    }
    private static Scope empty() { return new Scope(null, null, null, null, 0); }
    private static boolean reserve(long bytes) {
        while (true) { long used = PENDING_BYTES.get(); if (bytes > WorldRecordingService.QUEUE_BYTES - used) return false;
            if (PENDING_BYTES.compareAndSet(used, used + bytes)) return true; }
    }
    /** Freeze only matching packet projections. The caller completes them from the actual write listener. */
    public static ReceiptAttempt prepare(ServerPlayer recipient, Packet<?> packet) {
        var scope = ACTIVE.get();
        if (scope == null || scope.pending == null || recipient.getServer() != scope.server || !scope.server.isSameThread()) return null;
        try {
            if (scope.externalUi != null) {
                if (packet instanceof ClientboundSystemChatPacket chat && !chat.overlay() && chat.content().equals(scope.expectedExternalChat))
                    return scope.track(recipient.getUUID(), "FTB_CHAT", chat.content().getString(), true);
                String ui = scope.externalUi.apply(packet);
                return ui == null ? null : scope.track(recipient.getUUID(), "FTB_UI", ui, true);
            }
            if (packet instanceof ClientboundPlayerChatPacket chat) {
                if (scope.message == null || !chat.sender().equals(scope.message.sender()) || !chat.body().content().equals(scope.message.signedContent()) || chat.filterMask().isFullyFiltered()) return null;
                Component content = chat.filterMask().isEmpty()
                        ? chat.unsignedContent() == null ? Component.literal(chat.body().content()) : chat.unsignedContent()
                        : chat.filterMask().applyWithFormatting(chat.body().content());
                return content == null ? null : scope.track(recipient.getUUID(), surface(chat.chatType()), chat.chatType().decorate(content).getString(),
                        chat.filterMask().isEmpty() && content.getString().equals(scope.message.signedContent()));
            }
            if (packet instanceof ClientboundDisguisedChatPacket chat && scope.message != null && scope.message.isSystem()
                    && chat.message().equals(scope.message.decoratedContent()))
                return scope.track(recipient.getUUID(), surface(chat.chatType()), chat.chatType().decorate(chat.message()).getString(), chat.message().getString().equals(scope.message.signedContent()));
        } catch (RuntimeException invalidView) { scope.pending.reject("CHANNEL_VIEW_UNAVAILABLE"); }
        return null;
    }
    private static String surface(ChatType.Bound type) {
        return type.chatType().is(ChatType.MSG_COMMAND_OUTGOING) || type.chatType().is(ChatType.TEAM_MSG_COMMAND_OUTGOING) ? "CHAT_ECHO" : "CHAT";
    }
}
