package com.sande.mythictrpg.godavatar;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Explicit OP controls only; ordinary players enter conversation by right-clicking the entity. */
public final class GodAvatarCommands {
    private GodAvatarCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythavatar").requires(source -> source.hasPermission(2))
                .then(Commands.literal("summon")
                        .then(Commands.argument("god", StringArgumentType.word())
                                .executes(context -> summon(context.getSource(),
                                        StringArgumentType.getString(context, "god")))))
                .then(Commands.literal("id")
                        .then(Commands.argument("avatar", EntityArgument.entity())
                                .executes(context -> id(context.getSource(),
                                        EntityArgument.getEntity(context, "avatar")))))
                .then(Commands.literal("despawn")
                        .then(Commands.argument("avatar", EntityArgument.entity())
                                .executes(context -> despawn(context.getSource(),
                                        EntityArgument.getEntity(context, "avatar")))))
                .then(Commands.literal("move")
                        .then(Commands.argument("avatar", EntityArgument.entity())
                                .then(Commands.argument("position", Vec3Argument.vec3())
                                        .executes(context -> move(context.getSource(),
                                                EntityArgument.getEntity(context, "avatar"),
                                                BlockPos.containing(Vec3Argument.getVec3(context, "position")))))))
                .then(Commands.literal("visit")
                        .then(Commands.argument("avatar", EntityArgument.entity())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> visit(context.getSource(),
                                                EntityArgument.getEntity(context, "avatar"),
                                                EntityArgument.getPlayer(context, "player")))))));
    }

    private static int summon(CommandSourceStack source, String rawGodId) {
        ResourceLocation godId = ResourceLocation.tryParse(rawGodId);
        if (godId == null || !rawGodId.contains(":")) return failure(source, "전체 namespaced 신 ID가 필요합니다.");
        var avatar = GodAvatarService.INSTANCE.spawn(source.getLevel(), godId, source.getPosition());
        if (avatar.isEmpty()) return failure(source, "소환 실패: God/아바타 정의, 위치, 기존 단일 실체 또는 raid 예약을 확인하세요.");
        source.sendSuccess(() -> Component.literal("[신 아바타] 소환 완료: " + godId
                + " / entity=" + avatar.orElseThrow().getUUID()), true);
        return 1;
    }

    private static int id(CommandSourceStack source, net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof GodAvatarEntity avatar) || avatar.godId().isEmpty())
            return failure(source, "신 아바타가 아닙니다.");
        source.sendSuccess(() -> Component.literal("[신 아바타] entity=" + avatar.getUUID()
                + " / God ID=" + avatar.godId().orElseThrow()
                + " / 유효등록=" + avatar.hasAuthoritativeBinding()), false);
        return 1;
    }

    private static int despawn(CommandSourceStack source, net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof GodAvatarEntity avatar) || !GodAvatarService.INSTANCE.despawn(avatar))
            return failure(source, "등록된 신 아바타가 아닙니다.");
        source.sendSuccess(() -> Component.literal("[신 아바타] 실체를 제거했습니다. God 정의와 관계 기록은 유지됩니다."), true);
        return 1;
    }

    private static int move(CommandSourceStack source, net.minecraft.world.entity.Entity entity, BlockPos position) {
        if (!(entity instanceof GodAvatarEntity avatar) || !GodAvatarService.INSTANCE.move(avatar, position))
            return failure(source, "이동 요청 실패: 등록·작성형 이동 정책·거리·경로를 확인하세요.");
        source.sendSuccess(() -> Component.literal("[신 아바타] 이동 요청을 수락했습니다. 도착은 아직 확정되지 않았습니다."), false);
        return 1;
    }

    private static int visit(CommandSourceStack source, net.minecraft.world.entity.Entity entity,
            ServerPlayer player) {
        if (!(entity instanceof GodAvatarEntity avatar) || !GodAvatarService.INSTANCE.visit(avatar, player))
            return failure(source, "방문 요청 실패: 등록·작성형 방문 정책·차원·거리·경로를 확인하세요.");
        source.sendSuccess(() -> Component.literal("[신 아바타] 방문 이동을 시작했습니다. 도착은 아직 확정되지 않았습니다."), false);
        return 1;
    }

    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[신 아바타] " + message));
        return 0;
    }
}
