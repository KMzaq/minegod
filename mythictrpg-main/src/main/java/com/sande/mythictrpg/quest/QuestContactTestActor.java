package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.godavatar.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** GameTest-only physical fixture; records contact through the same entity/scope gate as a click. */
final class QuestContactTestActor implements AutoCloseable {
    private final java.lang.reflect.Field definitions;
    private final Object previous;
    final GodAvatarEntity avatar;

    @SuppressWarnings("unchecked")
    QuestContactTestActor(GameTestHelper helper, ServerPlayer player, ResourceLocation god) throws Exception {
        var manager = GodAvatarDefinitionManager.INSTANCE;
        definitions = manager.getClass().getDeclaredField("definitions"); definitions.setAccessible(true);
        previous = definitions.get(manager);
        var values = new HashMap<>((Map<ResourceLocation, GodAvatarDefinition>) previous);
        values.put(god, new GodAvatarDefinition(god, new GodAvatarDefinition.Appearance(0, 1),
                new GodAvatarDefinition.Stats(20, .25, 2, 0, 16),
                new GodAvatarDefinition.Movement(true, false, true, 1, 32, 32),
                new GodAvatarDefinition.Combat(false, false, false, false),
                new GodAvatarDefinition.Placement(false, 0), 4));
        definitions.set(manager, Map.copyOf(values));
        var feet = helper.absolutePos(new BlockPos(1, 2, 1));
        for (int dx = 0; dx < 4; dx++) {
            helper.getLevel().setBlockAndUpdate(feet.offset(dx, -1, 0), Blocks.STONE.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(feet.offset(dx, 0, 0), Blocks.AIR.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(feet.offset(dx, 1, 0), Blocks.AIR.defaultBlockState());
        }
        player.setPos(Vec3.atBottomCenterOf(feet.offset(2, 0, 0)));
        try { avatar = GodAvatarService.INSTANCE.spawn(helper.getLevel(), god, Vec3.atBottomCenterOf(feet)).orElseThrow(); }
        catch (Exception failure) { definitions.set(manager, previous); throw failure; }
    }

    void meet(ServerPlayer player, ConversationRoomSnapshot room) {
        var god = avatar.godId().orElseThrow();
        var scope = ConversationRooms.INSTANCE.actionScope(player, room.roomId(), room.revision(), god).orElseThrow();
        if (!QuestContactService.met(player, avatar, scope)) throw new AssertionError("Actual nearby NPC contact was rejected");
    }

    @Override public void close() throws Exception {
        GodAvatarService.INSTANCE.despawn(avatar);
        definitions.set(GodAvatarDefinitionManager.INSTANCE, previous);
    }
}
