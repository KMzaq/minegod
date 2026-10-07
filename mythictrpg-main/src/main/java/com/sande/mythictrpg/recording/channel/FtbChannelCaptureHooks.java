package com.sande.mythictrpg.recording.channel;

import dev.ftb.mods.ftblibrary.util.TextComponentUtils;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import dev.ftb.mods.ftbteams.net.SendMessageResponseMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Optional FTB-only types stay here, never in the always-loaded vanilla channel capture core. */
public final class FtbChannelCaptureHooks {
    private FtbChannelCaptureHooks() { }

    /** Only the three version-qualified player-input mixins may open this scope. No general team send hook exists. */
    public static ChannelCaptureHooks.Scope begin(ServerPlayer author, Team team, Component body) {
        try {
            if (author == null || team == null || body == null || author.getServer() == null
                    || !author.getServer().isSameThread() || author.isRemoved()
                    || author.getServer().getPlayerList().getPlayer(author.getUUID()) != author) return noCapture();
            if (!(FTBTeamsAPI.api().getManager() instanceof TeamManagerImpl manager)) return noCapture();
            // UUID equality alone does not establish that this is the actual selected, current team object.
            if (manager.getServer() != author.getServer() || manager.getTeamForPlayer(author).orElse(null) != team)
                return noCapture();
            UUID senderId = author.getUUID(); Component snapshot = body.copy();
            Component expectedChat = Component.literal("<").append(manager.getPlayerName(senderId))
                    .append(" @").append(team.getName()).append("> ").append(snapshot);
            return ChannelCaptureHooks.beginExternal(author, snapshot.getString(), expectedChat,
                    packet -> uiText(packet, author, senderId, snapshot));
        } catch (RuntimeException | LinkageError unavailable) {
            // Recording is optional: no reflection fallback, substituted team, or failed gameplay send.
            return noCapture();
        }
    }

    private static String uiText(net.minecraft.network.protocol.Packet<?> packet, ServerPlayer author,
                                 UUID senderId, Component expected) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)
                || !custom.payload().type().id().equals(SendMessageResponseMessage.TYPE.id())) return null;
        try {
            SendMessageResponseMessage response;
            if (custom.payload() instanceof SendMessageResponseMessage direct) response = direct;
            else if (custom.payload() instanceof dev.architectury.impl.NetworkAggregator.BufCustomPacketPayload encoded) {
                // Architectury 13 wraps typed payloads before the real network write. Inspect a
                // bounded private view, never advance or modify the outgoing transport buffer.
                byte[] bytes = encoded.payload();
                if (bytes.length == 0 || bytes.length > 262_144) return null;
                var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes), author.registryAccess());
                try {
                    response = SendMessageResponseMessage.STREAM_CODEC.decode(buffer);
                    if (buffer.isReadable()) return null;
                } finally { buffer.release(); }
            } else return null;
            return response.senderId().equals(senderId) && response.text().equals(expected)
                    ? response.text().getString() : null;
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    /** Mirrors AbstractTeam's actual String overload conversion without changing its original argument. */
    public static ChannelCaptureHooks.Scope beginString(ServerPlayer author, Team team, String body) {
        try { return begin(author, team, TextComponentUtils.withLinks(body)); }
        catch (RuntimeException | LinkageError unavailable) { return noCapture(); }
    }

    /** CommandSourceStack's player identity is insufficient without the authenticated inbound command scope. */
    public static ChannelCaptureHooks.Scope beginCommand(CommandSourceStack source, Team team, UUID senderId, String body) {
        try {
            ServerPlayer author = ChannelCaptureHooks.authenticatedCommandPlayer(source);
            return author != null && author.getUUID().equals(senderId) ? beginString(author, team, body) : noCapture();
        } catch (RuntimeException | LinkageError unavailable) { return noCapture(); }
    }

    private static ChannelCaptureHooks.Scope noCapture() { return ChannelCaptureHooks.begin(null, null, null); }
}
