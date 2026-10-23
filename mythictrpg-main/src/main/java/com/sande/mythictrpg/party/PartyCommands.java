package com.sande.mythictrpg.party;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;

/** Player-facing aliases over the existing persistent FTB party; usable in the overworld without OP. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class PartyCommands {
    private PartyCommands() { }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythparty").requires(source -> source.getPlayer() != null)
                .executes(PartyCommands::list)
                .then(Commands.literal("list").executes(PartyCommands::list))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> run(context, () -> PartyService.create(context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "name")), "파티를 만들었습니다. /mythparty invite <플레이어>로 초대하세요."))))
                .then(Commands.literal("invite").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(context -> run(context, () -> PartyService.invite(context.getSource().getPlayerOrException(),
                                profile(context)), "파티 초대를 보냈습니다. 상대가 /mythparty join <파티원>으로 수락해야 합니다."))))
                .then(Commands.literal("join").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(context -> run(context, () -> PartyService.join(context.getSource().getPlayerOrException(),
                                profile(context).getId()), "초대를 수락하고 파티에 참가했습니다."))))
                .then(Commands.literal("leave").executes(context -> run(context,
                        () -> PartyService.leave(context.getSource().getPlayerOrException()), "파티에서 탈퇴했습니다.")))
                .then(Commands.literal("kick").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(context -> run(context, () -> PartyService.kick(context.getSource().getPlayerOrException(),
                                profile(context)), "해당 플레이어를 파티에서 내보냈습니다."))))
                .then(Commands.literal("transfer").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(context -> run(context, () -> PartyService.transfer(context.getSource().getPlayerOrException(),
                                profile(context)), "파티장을 넘겼습니다."))))
                .then(Commands.literal("disband").executes(context -> run(context,
                        () -> PartyService.disband(context.getSource().getPlayerOrException()), "파티를 해산했습니다."))));
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        try {
            var party = PartyService.party(player.server, player.getUUID()).orElse(null);
            if (party == null) {
                context.getSource().sendSuccess(() -> Component.literal("파티 없음. /mythparty create <이름> 또는 초대받은 뒤 /mythparty join <파티원>"), false);
                return 1;
            }
            context.getSource().sendSuccess(() -> Component.literal("파티: " + party.getName().getString() + " · " + party.getMembers().size() + "명"), false);
            for (var member : party.getMembers().stream().sorted(Comparator.comparing(Object::toString)).toList()) {
                var online = player.server.getPlayerList().getPlayer(member);
                String name = online != null ? online.getGameProfile().getName() : player.server.getProfileCache() == null ? member.toString()
                        : player.server.getProfileCache().get(member).map(GameProfile::getName).orElse(member.toString());
                String role = member.equals(party.getOwner()) ? "파티장" : party.getRankForPlayer(member).isOfficerOrBetter() ? "관리자" : "파티원";
                String row = name + " · " + role + (online != null ? " · 접속 중" : " · 오프라인");
                context.getSource().sendSuccess(() -> Component.literal(row), false);
            }
            return 1;
        } catch (RuntimeException failure) {
            context.getSource().sendFailure(Component.literal("FTB 파티 정보를 아직 사용할 수 없습니다. 잠시 후 다시 시도하세요."));
            return 0;
        }
    }

    private static GameProfile profile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, "player");
        if (profiles.size() != 1) throw new SimpleCommandExceptionType(Component.literal("플레이어 한 명을 지정하세요.")).create();
        return profiles.iterator().next();
    }

    private static int run(CommandContext<CommandSourceStack> context, Operation operation, String success) throws CommandSyntaxException {
        try {
            operation.run();
            context.getSource().sendSuccess(() -> Component.literal(success), false);
            return 1;
        } catch (RuntimeException failure) {
            context.getSource().sendFailure(Component.literal("FTB 파티 명령을 처리할 수 없습니다. 잠시 후 다시 시도하세요."));
            return 0;
        }
    }

    @FunctionalInterface private interface Operation { void run() throws CommandSyntaxException; }
}
