package com.sande.mythictrpg.party;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.FTBTUtils;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** FTB Teams is the only party store. No copied membership, invitation, or party identity is retained here. */
public final class PartyService {
    public enum Relation { SAME_PARTY, DIFFERENT, UNAVAILABLE }
    private PartyService() { }

    public static boolean sameParty(ServerPlayer a, ServerPlayer b) {
        return a != null && b != null && a.server == b.server
                && sameParty(a.server, a.getUUID(), b.getUUID());
    }

    public static boolean sameParty(MinecraftServer server, UUID a, UUID b) {
        return relation(server, a, b) == Relation.SAME_PARTY;
    }

    /** Identifies persisted player UUIDs without mistaking a projectile's offline mob owner for a player. */
    public static boolean knownPlayer(MinecraftServer server, UUID player) {
        if (player == null) return false;
        try { return manager(server).getKnownPlayerTeams().containsKey(player); }
        catch (RuntimeException failure) { return false; }
    }

    /** Includes offline owners. A query failure is separate from a known non-party; damage callers can fail closed. */
    public static Relation relation(MinecraftServer server, UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return Relation.DIFFERENT;
        try {
            TeamManager manager = manager(server);
            Team first = manager.getTeamForPlayerID(a).orElse(null);
            Team second = manager.getTeamForPlayerID(b).orElse(null);
            if (first == null || second == null) return Relation.DIFFERENT;
            if (!first.isValid() || !second.isValid()) return Relation.UNAVAILABLE;
            if (!first.isPartyTeam() || !second.isPartyTeam()
                    || !first.getTeamId().equals(second.getTeamId())) return Relation.DIFFERENT;
            if (!first.getMembers().contains(a) || !first.getMembers().contains(b)
                    || !second.getMembers().contains(a) || !second.getMembers().contains(b)
                    || !first.getRankForPlayer(a).isMemberOrBetter()
                    || !second.getRankForPlayer(b).isMemberOrBetter()) return Relation.UNAVAILABLE;
            return Relation.SAME_PARTY;
        } catch (RuntimeException failure) {
            return Relation.UNAVAILABLE;
        }
    }

    public static Optional<Team> party(MinecraftServer server, UUID player) {
        return manager(server).getTeamForPlayerID(player).filter(team -> team.isValid() && team.isPartyTeam()
                && team.getMembers().contains(player) && team.getRankForPlayer(player).isMemberOrBetter());
    }

    public static void create(ServerPlayer player, String name) throws CommandSyntaxException {
        requirePlayer(player, "create");
        String clean = name.strip();
        if (clean.isEmpty() || clean.length() > 64) throw error("파티 이름은 1~64자로 입력하세요.");
        // Use the real command because its API-only/third-party creation validators run ABOVE manager.createParty.
        // The name is the final greedy argument; no command parser, permission level, or source is substituted.
        int result = player.server.getCommands().getDispatcher().execute("ftbteams party create " + clean,
                player.createCommandSourceStack());
        if (result <= 0) throw error("파티를 만들 수 없습니다.");
    }

    public static void invite(ServerPlayer player, GameProfile target) throws CommandSyntaxException {
        requirePlayer(player, "invite");
        PartyTeam party = requireParty(player, TeamRank.OFFICER);
        if (target.getId().equals(player.getUUID())) throw error("자신을 초대할 수 없습니다.");
        Team current = manager(player.server).getTeamForPlayerID(target.getId()).orElse(null);
        if (current == null || !current.isPlayerTeam()) throw error("상대가 접속한 적이 없거나 이미 파티에 속해 있습니다.");
        party.invite(player, List.of(target));
    }

    public static void join(ServerPlayer player, UUID member) throws CommandSyntaxException {
        requirePlayer(player, "join");
        Team current = manager(player.server).getTeamForPlayerID(player.getUUID()).orElse(null);
        if (current == null || !current.isPlayerTeam()) throw error("현재 파티에서 먼저 탈퇴하세요.");
        Team team = party(player.server, member).orElseThrow(() -> error("해당 플레이어의 파티가 없습니다."));
        if (!(team instanceof PartyTeam target)) throw error("FTB 파티를 사용할 수 없습니다.");
        // PartyTeam.join is a low-level mutation and does NOT itself enforce invitation consent.
        if (target.getRankForPlayer(player.getUUID()) != TeamRank.INVITED)
            throw error("먼저 이 파티의 초대를 받아야 합니다.");
        target.join(player);
    }

    public static void leave(ServerPlayer player) throws CommandSyntaxException {
        requirePlayer(player, "leave");
        requireParty(player, TeamRank.MEMBER).leave(player.getUUID());
    }

    public static void kick(ServerPlayer player, GameProfile target) throws CommandSyntaxException {
        requirePlayer(player, "kick");
        PartyTeam party = requireParty(player, TeamRank.OFFICER);
        if (!party.getMembers().contains(target.getId())) throw error("같은 파티의 구성원이 아닙니다.");
        party.kick(player.createCommandSourceStack(), List.of(target));
    }

    public static void disband(ServerPlayer player) throws CommandSyntaxException {
        requirePlayer(player, "leave");
        // forceDisband has no owner check of its own. Never hand it an arbitrary team or elevated source.
        requireParty(player, TeamRank.OWNER).forceDisband(player.createCommandSourceStack());
    }

    public static void transfer(ServerPlayer player, GameProfile target) throws CommandSyntaxException {
        requirePlayer(player, "transfer_ownership");
        PartyTeam party = requireParty(player, TeamRank.OWNER);
        if (!party.getMembers().contains(target.getId()) || target.getId().equals(player.getUUID()))
            throw error("다른 파티원에게 파티장을 넘길 수 있습니다.");
        party.transferOwnership(player.createCommandSourceStack(), target);
    }

    private static PartyTeam requireParty(ServerPlayer player, TeamRank rank) throws CommandSyntaxException {
        Team team = party(player.server, player.getUUID()).orElseThrow(() -> error("현재 파티에 속해 있지 않습니다."));
        if (!(team instanceof PartyTeam party)) throw error("FTB 파티를 사용할 수 없습니다.");
        if (!party.getRankForPlayer(player.getUUID()).isAtLeast(rank))
            throw error(rank == TeamRank.OWNER ? "파티장만 사용할 수 있습니다." : "파티장 또는 관리자가 사용할 수 있습니다.");
        return party;
    }

    private static void requirePlayer(ServerPlayer player, String command) throws CommandSyntaxException {
        if (player == null || !player.server.isSameThread()
                || player.server.getPlayerList().getPlayer(player.getUUID()) != player)
            throw error("현재 접속 중인 플레이어만 사용할 수 있습니다.");
        manager(player.server);
        if (!FTBTUtils.canPlayerUseCommand(player, "ftbteams.party." + command)) throw error("서버 설정에서 이 파티 명령을 허용하지 않습니다.");
    }

    private static TeamManager manager(MinecraftServer server) {
        if (server == null || !server.isSameThread() || !FTBTeamsAPI.api().isManagerLoaded())
            throw new IllegalStateException("FTB party manager unavailable");
        TeamManager manager = FTBTeamsAPI.api().getManager();
        if (manager.getServer() != server) throw new IllegalStateException("FTB party manager belongs to another server");
        return manager;
    }

    private static CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
