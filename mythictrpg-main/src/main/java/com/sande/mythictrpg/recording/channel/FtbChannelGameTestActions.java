package com.sande.mythictrpg.recording.channel;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.ChatEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import dev.ftb.mods.ftblibrary.util.NetworkHelper;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.data.PlayerTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import dev.ftb.mods.ftbteams.net.SendMessageMessage;
import dev.ftb.mods.ftbteams.net.SyncMessageHistoryMessage;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Installed FTB APIs in the isolated GameTest world only; no replacement manager, team, or capture implementation. */
public final class FtbChannelGameTestActions {
    private FtbChannelGameTestActions() { }

    public static Fixture open(ServerPlayer author, ServerPlayer peer) throws Exception {
        requireGameThread(author);
        var world = author.server.getWorldPath(LevelResource.ROOT).toRealPath();
        // The caller also guards its run directory. A second guard keeps this mutation helper off a normal server world.
        boolean buildContained = false;
        for (var directory : world) if (directory.toString().equals("build")) buildContained = true;
        if (!(author.server instanceof net.minecraft.gametest.framework.GameTestServer) || !buildContained)
            throw new IllegalStateException("FTB capture fixture requires a build-contained GameTest world");
        if (author == peer || peer.server != author.server
                || author.server.getPlayerList().getPlayer(author.getUUID()) != author
                || author.server.getPlayerList().getPlayer(peer.getUUID()) != peer)
            throw new IllegalArgumentException("Two distinct registered test players required");
        if (!(FTBTeamsAPI.api().getManager() instanceof TeamManagerImpl manager) || manager.getServer() != author.server)
            throw new IllegalStateException("Installed FTB server manager unavailable");
        Team oldAuthor = manager.getTeamForPlayer(author).orElseThrow(), oldPeer = manager.getTeamForPlayer(peer).orElseThrow();
        if (!(oldAuthor instanceof PlayerTeam) || !(oldPeer instanceof PlayerTeam)
                || !oldAuthor.getId().equals(author.getUUID()) || !oldPeer.getId().equals(peer.getUUID()))
            throw new IllegalStateException("Fixture refuses pre-existing parties or non-personal player teams");
        Fixture fixture = new Fixture(author, peer, manager, oldAuthor, oldPeer);
        try {
            fixture.party = manager.createParty(author, "capture-" + UUID.randomUUID().toString().substring(0, 8));
            fixture.party.invite(author, List.of(peer.getGameProfile()));
            fixture.party.join(peer);
            fixture.checkTeam();
            return fixture;
        } catch (Exception failure) {
            try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static final class Fixture implements AutoCloseable {
        private final ServerPlayer author, peer;
        private final TeamManagerImpl manager;
        private final Team oldAuthor, oldPeer;
        private final boolean authorRedirected, peerRedirected;
        private PartyTeam party;
        private boolean closed;
        private int pendingGui;

        private Fixture(ServerPlayer author, ServerPlayer peer, TeamManagerImpl manager, Team oldAuthor, Team oldPeer) {
            this.author = author; this.peer = peer; this.manager = manager; this.oldAuthor = oldAuthor; this.oldPeer = oldPeer;
            authorRedirected = manager.isChatRedirected(author); peerRedirected = manager.isChatRedirected(peer);
        }
        public UUID teamId() { checkTeam(); return party.getTeamId(); }

        /** Actual GUI C2S handler and queued lambda; only the transport context is adapted to the connected test player. */
        public CompletableFuture<Void> gui(String body) {
            checkTeam(); CompletableFuture<Void> completion = new CompletableFuture<>(); pendingGui++;
            try {
                SendMessageMessage.handle(new SendMessageMessage(body), new NetworkManager.PacketContext() {
                    @Override public Player getPlayer() { return author; }
                    @Override public Env getEnvironment() { return Env.SERVER; }
                    @Override public RegistryAccess registryAccess() { return author.registryAccess(); }
                    @Override public void queue(Runnable task) {
                        author.server.execute(() -> {
                            Throwable problem = null;
                            try { checkTeam(); task.run(); }
                            catch (Throwable failure) { problem = failure; }
                            pendingGui--;
                            if (problem == null) completion.complete(null); else completion.completeExceptionally(problem);
                        });
                    }
                });
            } catch (RuntimeException failure) { pendingGui--; completion.completeExceptionally(failure); }
            return completion;
        }

        /** Dispatches through the registered real FTBTeams.chatReceived listener, not its private synthetic lambda. */
        public EventResult redirectedChat(String body) {
            checkTeam(); boolean previous = manager.isChatRedirected(author); manager.setChatRedirected(author, true);
            try {
                EventResult result = ChatEvent.RECEIVED.invoker().received(author, Component.literal(body));
                if (!result.interruptsFurtherEvaluation() || !result.isFalse())
                    throw new IllegalStateException("Installed FTB listener did not accept redirected chat");
                return result;
            } finally { manager.setChatRedirected(author, previous); }
        }

        /** The caller's helper must enter via the actual ServerGamePacketListener inbound command handler. */
        public void command(String body, Consumer<String> authenticatedInboundCommand) {
            checkTeam(); authenticatedInboundCommand.accept("ftbteams msg " + body);
        }

        /** These real sends must add NO authored turns: player-UUID automatic notices, then old history to both clients. */
        public void excludedTraffic() throws Exception {
            checkTeam();
            party.promote(author, List.of(peer.getGameProfile()));
            party.demote(author, List.of(peer.getGameProfile()));
            NetworkHelper.sendTo(author, SyncMessageHistoryMessage.forTeam(party));
            NetworkHelper.sendTo(peer, SyncMessageHistoryMessage.forTeam(party));
        }

        private void checkTeam() {
            requireGameThread(author);
            if (closed || party == null || manager.getTeamForPlayer(author).orElse(null) != party
                    || manager.getTeamForPlayer(peer).orElse(null) != party
                    || !party.getMembers().equals(Set.of(author.getUUID(), peer.getUUID())))
                throw new IllegalStateException("FTB fixture team membership changed");
        }

        @Override public void close() throws Exception {
            if (closed) return; requireGameThread(author);
            if (pendingGui != 0) throw new IllegalStateException("Wait for queued GUI completion before closing FTB fixture");
            if (party != null) {
                if (!Set.of(author.getUUID(), peer.getUUID()).containsAll(party.getMembers()))
                    throw new IllegalStateException("Refusing to remove a fixture party containing another player");
                if (manager.getTeamForPlayer(peer).orElse(null) == party) party.leave(peer.getUUID());
                if (manager.getTeamForPlayer(author).orElse(null) == party) party.leave(author.getUUID());
            }
            manager.setChatRedirected(author, authorRedirected); manager.setChatRedirected(peer, peerRedirected);
            if (manager.getTeamForPlayer(author).orElse(null) != oldAuthor || manager.getTeamForPlayer(peer).orElse(null) != oldPeer)
                throw new IllegalStateException("Original personal teams were not restored");
            closed = true;
        }
    }

    private static void requireGameThread(ServerPlayer player) {
        if (player == null || player.getServer() == null || !player.server.isSameThread())
            throw new IllegalStateException("FTB capture fixture requires its server thread");
    }
}
