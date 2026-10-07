package com.sande.mythictrpg.godavatar.activity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.godavatar.GodAvatarService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.LinkedHashSet;
import java.util.Set;

/** Thin command adapter. Activity authority, hard exclusions and practice rules remain in game services. */
public final class NpcActivityCommands {
    private NpcActivityCommands() { }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythnpc")
                .then(Commands.literal("activity").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start").then(Commands.argument("god", ResourceLocationArgument.id())
                                .then(Commands.argument("definition", ResourceLocationArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                                NpcActivityDefinitions.INSTANCE.data().activities().keySet(), builder))
                                        .executes(NpcActivityCommands::start))))
                        .then(Commands.literal("stop").then(Commands.argument("god", ResourceLocationArgument.id())
                                .executes(NpcActivityCommands::stop)))
                        .then(Commands.literal("status").then(Commands.argument("god", ResourceLocationArgument.id())
                                .executes(NpcActivityCommands::status))))
                .then(Commands.literal("place").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("tags", StringArgumentType.greedyString()).executes(NpcActivityCommands::addPlace))))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                                .executes(NpcActivityCommands::removePlace))))
                .then(Commands.literal("deny").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64)).executes(NpcActivityCommands::deny))))
                .then(Commands.literal("allow").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word()).executes(NpcActivityCommands::allow)))
                .then(Commands.literal("spar").requires(source -> source.getEntity() instanceof ServerPlayer)
                        .then(Commands.literal("invite").requires(source -> source.hasPermission(2))
                                .then(Commands.argument("god", ResourceLocationArgument.id())
                                .executes(context -> invite(context, null))
                                .then(Commands.argument("requiredHits", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> invite(context, IntegerArgumentType.getInteger(context, "requiredHits"))))))
                        .then(Commands.literal("accept").executes(context -> spar(context.getSource(),
                                NpcSparring.INSTANCE.accept(context.getSource().getPlayerOrException()))))
                        .then(Commands.literal("decline").executes(context -> spar(context.getSource(),
                                NpcSparring.INSTANCE.decline(context.getSource().getPlayerOrException()))))
                        .then(Commands.literal("leave").executes(context -> spar(context.getSource(),
                                NpcSparring.INSTANCE.leave(context.getSource().getPlayerOrException()))))
                        .then(Commands.literal("score").executes(NpcActivityCommands::score))));
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var actor = GodAvatarService.INSTANCE.findLoaded(source.getServer(), ResourceLocationArgument.getId(context, "god")).orElse(null);
        if (actor == null) return failure(source, "현재 로드된 유효한 신 실체를 찾을 수 없습니다. 자동 소환하지 않습니다.");
        var definition = ResourceLocationArgument.getId(context, "definition");
        try {
            if (!NpcActivityRuntime.INSTANCE.request(actor, definition))
                return failure(source, "활동 시작 거절: 신별 허용 목록, 장소·경로, 현재 명령·레이드·대련 상태와 금지 구역을 확인하세요.");
            return success(source, "활동 이동/시작 요청을 수락했습니다. 활동 완료나 생산 결과는 아직 확정되지 않았습니다.", true);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return failure(source, "활동 정의 또는 저장 상태를 확인하세요. 활동을 완료한 것으로 처리하지 않았습니다.");
        }
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var actor = GodAvatarService.INSTANCE.findLoaded(source.getServer(), ResourceLocationArgument.getId(context, "god")).orElse(null);
        if (actor == null) return failure(source, "현재 로드된 유효한 신 실체를 찾을 수 없습니다.");
        NpcActivityRuntime.interrupt(actor, "ADMIN_COMMAND_STOP");
        return success(source, "현재 생활활동/대기 판단에 중단 처리를 적용했습니다. 상시 출입 금지는 deny로 별도 지정합니다.", true);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var actor = GodAvatarService.INSTANCE.findLoaded(source.getServer(), ResourceLocationArgument.getId(context, "god")).orElse(null);
        if (actor == null) return failure(source, "현재 로드된 유효한 신 실체를 찾을 수 없습니다.");
        return success(source, "현재 활동: " + NpcActivityRuntime.INSTANCE.state(actor), false);
    }

    private static int addPlace(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource(); var player = source.getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        try {
            var tags = tags(StringArgumentType.getString(context, "tags"));
            var world = NpcActivityWorldState.get(source.getServer());
            world.place(new NpcActivityWorldState.Place(name, player.level().dimension().location(), player.blockPosition(), tags));
            return success(source, "활동 장소 등록: " + name + " / " + String.join(",", new java.util.TreeSet<>(tags))
                    + " / 현재 플레이어 위치. 기존 같은 이름은 갱신됩니다.", true);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return failure(source, "장소 등록 거절: 이름은 영문 소문자·숫자·_ 1~64자, 태그는 영문 소문자·_ 1~40자이며 쉼표로 1~16개를 구분하세요. 저장 상태도 확인하세요.");
        }
    }

    private static int removePlace(CommandContext<CommandSourceStack> context) {
        var source = context.getSource(); String name = StringArgumentType.getString(context, "name");
        try {
            var world = NpcActivityWorldState.get(source.getServer());
            if (world.places().stream().noneMatch(place -> place.name().equals(name))) return failure(source, "해당 이름의 등록 장소가 없습니다.");
            world.removePlace(name);
            return success(source, "활동 장소 등록 해제: " + name + ". 실제 블록·건축물은 삭제하지 않았습니다.", true);
        } catch (IllegalArgumentException | IllegalStateException rejected) { return failure(source, "장소 해제 거절: 저장 상태를 확인하세요."); }
    }

    private static int deny(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource(); var player = source.getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        int radius = IntegerArgumentType.getInteger(context, "radius");
        try {
            NpcActivityWorldState.get(source.getServer()).deny(name, new NpcActivityWorldState.Exclusion(
                    player.level().dimension().location(), player.blockPosition(), radius));
            return success(source, "생활활동 금지 구역 등록: " + name + " / 현재 위치 각 축 ±" + radius
                    + "블록. 자연어 부탁과 달리 AI가 우회할 수 없습니다.", true);
        } catch (IllegalArgumentException | IllegalStateException rejected) { return failure(source, "금지 구역 등록 거절: 이름·범위·저장 상태를 확인하세요."); }
    }

    private static int allow(CommandContext<CommandSourceStack> context) {
        var source = context.getSource(); String name = StringArgumentType.getString(context, "name");
        if (!name.matches("[a-z0-9_]{1,64}")) return failure(source, "구역 이름은 영문 소문자·숫자·_ 1~64자입니다.");
        try {
            NpcActivityWorldState.get(source.getServer()).allow(name);
            return success(source, "해당 이름의 활동 금지 설정을 해제했습니다: " + name + ". 다른 금지 구역과 접근 조건은 유지됩니다.", true);
        } catch (IllegalArgumentException | IllegalStateException rejected) { return failure(source, "금지 해제 거절: 저장 상태를 확인하세요."); }
    }

    private static int invite(CommandContext<CommandSourceStack> context, Integer requiredHits) throws CommandSyntaxException {
        var source = context.getSource(); var player = source.getPlayerOrException();
        var actor = GodAvatarService.INSTANCE.findLoaded(source.getServer(), ResourceLocationArgument.getId(context, "god")).orElse(null);
        if (actor == null) return failure(source, "현재 로드된 유효한 신 실체를 찾을 수 없습니다. 자동 소환하지 않습니다.");
        return spar(source, requiredHits == null ? NpcSparring.INSTANCE.invite(player, actor)
                : NpcSparring.INSTANCE.invite(player, actor, requiredHits));
    }
    private static int score(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource(); var player = source.getPlayerOrException();
        var score = NpcSparring.INSTANCE.score(player).orElse(null);
        if (score == null) return failure(source, "현재 대련 중이 아닙니다.");
        return success(source, "연습 잔여 점수: 나 " + Math.max(0, score.playerRemaining()) + " / 상대 " + Math.max(0, score.npcRemaining())
                + " / 목표 " + score.requiredHits() + "회. 실제 체력·장비·보상은 변하지 않습니다.", false);
    }
    private static int spar(CommandSourceStack source, NpcSparring.Result result) {
        return result.success() ? success(source, result.message(), false) : failure(source, result.message());
    }
    static Set<String> tags(String raw) {
        if (raw == null || raw.length() > 655) throw new IllegalArgumentException("Tag budget");
        var tags = new LinkedHashSet<String>();
        for (String token : raw.split(",", -1)) {
            String tag = token.trim();
            if (!tag.matches("[a-z_]{1,40}") || !tags.add(tag) || tags.size() > 16) throw new IllegalArgumentException("Activity tag format");
        }
        return Set.copyOf(tags);
    }
    private static int success(CommandSourceStack source, String text, boolean notifyOps) {
        source.sendSuccess(() -> Component.literal("[NPC 활동] " + text), notifyOps); return 1;
    }
    private static int failure(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal("[NPC 활동] " + text)); return 0;
    }
}
