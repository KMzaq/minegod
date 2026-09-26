package com.sande.mythictrpg.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
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
                        .then(Commands.argument("type",StringArgumentType.word()).suggests((c,b)->{b.suggest("private");b.suggest("fixed");b.suggest("mobile");return b.buildFuture();})
                        .then(Commands.argument("recording",StringArgumentType.word()).suggests((c,b)->{b.suggest("on");b.suggest("off");return b.buildFuture();})
                        .then(Commands.argument("gods",StringArgumentType.greedyString()).executes(c->run(c.getSource().getPlayerOrException(),p->{
                            String type=StringArgumentType.getString(c,"type"),record=StringArgumentType.getString(c,"recording");
                            RoomType t=switch(type){case "private"->RoomType.PRIVATE;case "fixed"->RoomType.PUBLIC_FIXED;case "mobile"->RoomType.PUBLIC_MOBILE;default->throw new IllegalArgumentException("private / fixed / mobile 중 하나를 지정하세요.");};
                            if(!record.equals("on")&&!record.equals("off"))throw new IllegalArgumentException("on / off를 지정하세요.");
                            var gods=new LinkedHashSet<ResourceLocation>();
                            for(String token:StringArgumentType.getString(c,"gods").split("[\\s,]+")) {
                                var matches=GodDefinitionManager.INSTANCE.definitions().entrySet().stream().filter(entry->entry.getKey().toString().equals(token)||entry.getKey().getPath().equals(token)||entry.getValue().displayName().getString().equals(token)).toList();
                                if(matches.size()!=1)throw new IllegalArgumentException("신 이름/ID가 없거나 모호합니다: "+token);gods.add(matches.getFirst().getKey());
                            }
                            ConversationRooms.INSTANCE.create(p,t,gods,record.equals("on")?RecordingScope.TEST_RECORDING:RecordingScope.TEST_EPHEMERAL);
                        }))))))
                .then(Commands.literal("invite_test").requires(s->s.hasPermission(2))
                        .then(Commands.argument("room",StringArgumentType.word()).then(Commands.argument("player",EntityArgument.player())
                                .executes(c->run(c.getSource().getPlayerOrException(),p->{
                                    var r=member(p,StringArgumentType.getString(c,"room"));
                                    if(!r.recordingScope().isTest())throw new IllegalArgumentException("시험 방에서만 초대를 모의할 수 있습니다.");
                                    ConversationRooms.INSTANCE.invite(r,ResourceLocation.parse(r.godIds().iterator().next()),EntityArgument.getPlayer(c,"player"));
                                }))))));
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
