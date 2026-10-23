package com.sande.mythictrpg.party;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.story.runtime.StoryTeamResolver;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("mythictrpg_party")
@PrefixGameTestTemplate(false)
public final class PartyGameTests {
    private PartyGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_party", template = "empty", batch = "wild_party", timeoutTicks = 100)
    public static void ordinaryPlayerCommandsRequireInvitationAndKeepTheExistingFtbStoryTeam(GameTestHelper helper) {
        helper.startSequence().thenExecute(() -> {
            try (var fixture = new Fixture(helper)) {
                var owner = fixture.connect("PartyOwner"); var member = fixture.connect("PartyMember"); var stranger = fixture.connect("PartyOther");
                helper.assertTrue(!PartyService.sameParty(owner, member), "Personal FTB teams became a party");
                fixture.command(owner, "mythparty create 야생 시험 파티");
                var party = PartyService.party(owner.server, owner.getUUID()).orElseThrow();
                fixture.rejected(() -> fixture.command(member, "mythparty join " + owner.getGameProfile().getName()), "Uninvited player joined");
                helper.assertTrue(!PartyService.sameParty(owner, member), "Rejected join mutated membership");
                fixture.command(owner, "mythparty invite " + member.getGameProfile().getName());
                helper.assertValueEqual(party.getRankForPlayer(member.getUUID()), TeamRank.INVITED, "Invitation not stored by FTB");
                helper.assertTrue(!PartyService.sameParty(owner, member), "Invitation alone granted party protection");
                fixture.command(member, "mythparty join " + owner.getGameProfile().getName());
                helper.assertTrue(PartyService.sameParty(owner, member), "Accepted FTB membership was not shared");
                helper.assertTrue(StoryTeamResolver.resolve(owner).stableTeamId().equals(party.getTeamId())
                                && StoryTeamResolver.resolve(member).frozenMembers().equals(Set.of(owner.getUUID(), member.getUUID())),
                        "Mythparty introduced an independent identity or failed to connect existing story teams");
                fixture.command(member, "mythparty list");
                fixture.rejected(() -> fixture.command(member, "mythparty invite " + stranger.getGameProfile().getName()), "Member invited without officer authority");
                fixture.rejected(() -> fixture.command(member, "mythparty kick " + owner.getGameProfile().getName()), "Member kicked the owner");
                fixture.rejected(() -> fixture.command(member, "mythparty disband"), "Member disbanded another player's party");
                fixture.rejected(() -> fixture.command(owner, "mythparty leave"), "Owner silently abandoned a multi-member party");
                fixture.command(member, "mythparty leave");
                helper.assertTrue(!PartyService.sameParty(owner, member) && fixture.manager.getTeamForPlayer(member).orElseThrow().isPlayerTeam(),
                        "Leaving did not restore the original FTB personal team");
                fixture.command(owner, "mythparty disband");
                helper.assertTrue(PartyService.party(owner.server, owner.getUUID()).isEmpty(), "Disband left party membership behind");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "mythictrpg_party", template = "empty", batch = "wild_party", timeoutTicks = 100)
    public static void offlineMembersReconnectAndExistingFtbCommandsUseTheSameParty(GameTestHelper helper) {
        helper.startSequence().thenExecute(() -> {
            try (var fixture = new Fixture(helper)) {
                var owner = fixture.connect("FtbOwner"); var member = fixture.connect("FtbMember"); var outsider = fixture.connect("FtbOutsider");
                fixture.command(owner, "ftbteams party create ExistingFtbParty");
                fixture.command(owner, "ftbteams party invite " + member.getGameProfile().getName());
                var party = (PartyTeam) PartyService.party(owner.server, owner.getUUID()).orElseThrow();
                fixture.command(member, "ftbteams party join " + party.getShortName());
                helper.assertTrue(PartyService.sameParty(owner, member), "Existing /ftbteams party not recognized");
                party.addAlly(owner.createCommandSourceStack(), List.of(outsider.getGameProfile()));
                helper.assertTrue(!PartyService.sameParty(owner, outsider), "FTB ALLY was mistaken for actual party membership");
                UUID id = member.getUUID();
                fixture.disconnect(member);
                helper.assertTrue(PartyService.sameParty(owner.server, owner.getUUID(), id), "Offline party member lost owner lookup");
                helper.assertTrue(PartyService.knownPlayer(owner.server, id)
                                && !PartyService.knownPlayer(owner.server, UUID.randomUUID()),
                        "Persisted player identity was lost or an unknown mob UUID was recognized as a player");
                var replacement = fixture.connect("FtbMemberReturn", id);
                helper.assertTrue(PartyService.sameParty(owner, replacement), "Reconnect lost existing FTB membership");
                PartyService.kick(owner, replacement.getGameProfile());
                helper.assertTrue(!PartyService.sameParty(owner, replacement), "Kick retained stale friendly-fire membership");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "mythictrpg_party", template = "empty", batch = "wild_party", timeoutTicks = 100)
    public static void queryFailureIsNotReportedAsAnAuthoritativeNonParty(GameTestHelper helper) {
        helper.startSequence().thenExecute(() -> {
            try (var fixture = new Fixture(helper)) {
                var owner = fixture.connect("LookupOwner"); var member = fixture.connect("LookupMember");
                PartyService.create(owner, "LookupParty");
                PartyService.invite(owner, member.getGameProfile());
                PartyService.join(member, owner.getUUID());
                var manager = TeamManagerImpl.INSTANCE;
                try {
                    TeamManagerImpl.INSTANCE = null;
                    helper.assertValueEqual(PartyService.relation(owner.server, owner.getUUID(), member.getUUID()), PartyService.Relation.UNAVAILABLE,
                            "Missing FTB manager silently allowed teammates to attack");
                } finally { TeamManagerImpl.INSTANCE = manager; }
                helper.assertValueEqual(PartyService.relation(owner.server, owner.getUUID(), UUID.randomUUID()), PartyService.Relation.DIFFERENT,
                        "Unknown non-player UUID was treated as a broken FTB manager");
                helper.assertTrue(PartyService.sameParty(owner, member), "Transient query failure destroyed FTB membership");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "mythictrpg_party", template = "empty", batch = "wild_party", timeoutTicks = 100)
    public static void transferAndOfflineKickRespectTheExistingFtbOwnerAndOfficerRoles(GameTestHelper helper) {
        helper.startSequence().thenExecute(() -> {
            try (var fixture = new Fixture(helper)) {
                var owner = fixture.connect("TransferOwner"); var successor = fixture.connect("TransferNext"); var member = fixture.connect("TransferMember");
                PartyService.create(owner, "TransferParty");
                PartyService.invite(owner, successor.getGameProfile()); PartyService.join(successor, owner.getUUID());
                PartyService.invite(owner, member.getGameProfile()); PartyService.join(member, owner.getUUID());
                fixture.rejected(() -> PartyService.transfer(member, successor.getGameProfile()), "Non-owner transferred another player's party");
                PartyService.transfer(owner, successor.getGameProfile());
                var party = PartyService.party(owner.server, owner.getUUID()).orElseThrow();
                helper.assertValueEqual(party.getOwner(), successor.getUUID(), "Ownership transfer did not update real FTB owner");
                fixture.rejected(() -> PartyService.disband(owner), "Former owner retained disband authority");
                GameProfile offline = member.getGameProfile(); fixture.disconnect(member);
                PartyService.kick(successor, offline);
                helper.assertTrue(!PartyService.sameParty(owner.server, owner.getUUID(), offline.getId()), "Offline kick retained effective membership");
                PartyService.disband(successor);
                helper.assertTrue(PartyService.party(owner.server, owner.getUUID()).isEmpty()
                        && PartyService.party(owner.server, successor.getUUID()).isEmpty(), "Disband failed to restore members' personal teams");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }).thenSucceed();
    }

    @FunctionalInterface private interface CheckedOperation { void run() throws Exception; }

    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        private final TeamManagerImpl manager;
        private final PartyProfileGameTestFixture profiles;
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<EmbeddedChannel> channels = new ArrayList<>();
        private final Set<UUID> ids = new LinkedHashSet<>();
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            this.manager = (TeamManagerImpl) FTBTeamsAPI.api().getManager();
            this.profiles = new PartyProfileGameTestFixture(helper.getLevel().getServer());
        }
        ServerPlayer connect(String name) { return connect(name, UUID.randomUUID()); }
        ServerPlayer connect(String name, UUID id) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(id, name), false);
            profiles.add(cookie.gameProfile());
            var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setNoGravity(true); players.add(player); ids.add(id); return player;
        }
        void disconnect(ServerPlayer player) { player.server.getPlayerList().remove(player); players.remove(player); }
        void command(ServerPlayer player, String command) throws CommandSyntaxException {
            int result = player.server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack().withPermission(0));
            helper.assertTrue(result > 0, "Player command failed: " + command);
        }
        void rejected(CheckedOperation operation, String message) throws Exception {
            try { operation.run(); }
            catch (CommandSyntaxException expected) { return; }
            throw new IllegalStateException(message);
        }
        @Override public void close() throws Exception {
            try {
                for (var team : List.copyOf(manager.getTeams())) {
                    if (team instanceof PartyTeam party && party.getMembers().stream().anyMatch(ids::contains)) {
                        if (!ids.containsAll(party.getMembers())) throw new IllegalStateException("Refusing cleanup of a non-fixture party");
                        party.forceDisband(helper.getLevel().getServer().createCommandSourceStack());
                    }
                }
                for (var player : List.copyOf(players)) disconnect(player);
                channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            } finally {
                profiles.close();
            }
        }
    }
}
