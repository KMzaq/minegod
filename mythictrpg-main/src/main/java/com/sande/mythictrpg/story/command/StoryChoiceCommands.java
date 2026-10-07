package com.sande.mythictrpg.story.command;

import com.sande.mythictrpg.story.runtime.StoryChoiceUiService;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Reopens the authoritative pending Story view after a player defers a choice. */
public final class StoryChoiceCommands {
    private StoryChoiceCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythstory")
                .then(Commands.literal("choices").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    var page = StoryChoiceUiService.INSTANCE.browsePage(player, 0);
                    if (page == null) {
                        player.sendSystemMessage(Component.literal("[스토리] 잠시 후 다시 시도해 주세요."));
                    } else if (page.total() == 0) {
                        player.sendSystemMessage(Component.literal("[스토리] 현재 선택 가능한 사건이 없습니다."));
                    }
                    return 1;
                })));
    }
}
