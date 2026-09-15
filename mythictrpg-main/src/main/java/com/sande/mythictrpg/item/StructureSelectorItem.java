package com.sande.mythictrpg.item;

import com.sande.mythictrpg.quest.structure.StructureEvaluationState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

final class StructureSelectorItem extends Item {
    StructureSelectorItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        boolean second = player.isShiftKeyDown();
        StructureEvaluationState.get(player.server).setPoint(player.getUUID(), player.serverLevel().dimension(),
                context.getClickedPos(), second);
        player.displayClientMessage(Component.literal((second ? "[건축 선택] 지점 2: " : "[건축 선택] 지점 1: ")
                + context.getClickedPos().toShortString()).withStyle(ChatFormatting.AQUA), false);
        return InteractionResult.SUCCESS;
    }
}
