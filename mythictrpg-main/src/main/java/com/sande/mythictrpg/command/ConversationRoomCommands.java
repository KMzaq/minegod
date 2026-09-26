package com.sande.mythictrpg.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.ai.room.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** /s never falls back to public chat, even on a stale selection or unknown room. */
public final class ConversationRoomCommands {
    private ConversationRoomCommands() {}
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("s").then(Commands.argument("message",StringArgumentType.greedyString())
                .executes(c->run(c.getSource().getPlayerOrException(),p->ConversationRooms.INSTANCE.privateText(p,StringArgumentType.getString(c,"message"))))));
        e.getDispatcher().register(Commands.literal("mythroom")
                .then(Commands.literal("list").executes(c->run(c.getSource().getPlayerOrException(),ConversationRoomCommands::list)))
                .then(Commands.literal("select").then(Commands.argument("room",StringArgumentType.word())
                        .executes(c->run(c.getSource().getPlayerOrException(),p->{var r=member(p,StringArgumentType.getString(c,"room"));ConversationRooms.INSTANCE.selectPrivate(p,r.roomId());list(p);}))))
                .then(Commands.literal("leave").then(Commands.argument("room",StringArgumentType.word())
                        .executes(c->run(c.getSource().getPlayerOrException(),p->ConversationRooms.INSTANCE.leave(p,member(p,StringArgumentType.getString(c,"room")),"COMMAND_DISCONNECT")))))
                .then(Commands.literal("confirm").then(Commands.argument("room",StringArgumentType.word())
                        .then(Commands.argument("god",StringArgumentType.word()).then(Commands.argument("quest",StringArgumentType.word())
                                .executes(c->run(c.getSource().getPlayerOrException(),p->{
                                    var room=member(p,StringArgumentType.getString(c,"room"));
                                    var god=ResourceLocation.parse(StringArgumentType.getString(c,"god"));
                                    var scope=ConversationRooms.INSTANCE.actionScope(p,room.roomId(),room.revision(),god)
                                            .orElseThrow(()->new IllegalArgumentException("이 방에서 해당 신에게 퀘스트를 제출할 수 없습니다."));
                                    var result=com.sande.mythictrpg.quest.QuestParticipationService.INSTANCE.confirmRoom(p,
                                            ResourceLocation.parse(StringArgumentType.getString(c,"quest")),scope);
                                    p.sendSystemMessage(Component.literal(result.toString()));
                                }))))))
                .then(Commands.literal("accept").then(Commands.argument("invitation",UuidArgument.uuid())
                        .executes(c->run(c.getSource().getPlayerOrException(),p->ConversationRooms.INSTANCE.accept(p,UuidArgument.getUuid(c,"invitation"))))))
                .then(Commands.literal("open").requires(s->s.hasPermission(2))
                        .executes(c->moved(c.getSource()))
                        .then(Commands.argument("legacy",StringArgumentType.greedyString()).executes(c->moved(c.getSource()))))
                .then(Commands.literal("invite_test").requires(s->s.hasPermission(2))
                        .then(Commands.argument("room",StringArgumentType.word()).then(Commands.argument("player",EntityArgument.player())
                                .executes(c->run(c.getSource().getPlayerOrException(),p->{
                                    var r=member(p,StringArgumentType.getString(c,"room"));
                                    if(!r.recordingScope().isTest())throw new IllegalArgumentException("시험 방에서만 초대를 모의할 수 있습니다.");
                                    ConversationRooms.INSTANCE.invite(r,ResourceLocation.parse(r.godIds().iterator().next()),EntityArgument.getPlayer(c,"player"));
                                }))))));
    }
    private static int moved(net.minecraft.commands.CommandSourceStack source) {
        source.sendFailure(Component.literal("대화 시작 명령은 /ai_call <public|mobile|private> <on|off> <신 ID> [신 ID ...]입니다. 전체 ID만 사용하세요."));
        return 0;
    }
    private static ConversationRoomSnapshot member(ServerPlayer p,String value) {
        return ConversationRooms.INSTANCE.resolveMember(p,value).orElseThrow(()->new IllegalArgumentException("참여 중인 대화방이 아닙니다."));
    }
    private static void list(ServerPlayer p) {
        var rooms=ConversationRooms.INSTANCE.memberships(p);
        if(rooms.isEmpty())p.sendSystemMessage(Component.literal("참여 중인 대화방이 없습니다."));
        for(var r:rooms) {
            var text=Component.literal("["+r.code()+"] "+r.type());
            if(r.type()==RoomType.PRIVATE)text.append(Component.literal(" [선택]").withStyle(s->s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/mythroom select "+r.roomId()))));
            p.sendSystemMessage(text);
        }
    }
    @FunctionalInterface private interface Action {void run(ServerPlayer player) throws Exception;}
    private static int run(ServerPlayer p,Action action) {
        try {action.run(p);return 1;}catch(Exception failure){p.sendSystemMessage(Component.literal("[대화방] "+failure.getMessage()));return 0;}
    }
}
