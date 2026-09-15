package com.sande.mythictrpg.data.player;

import com.sande.mythictrpg.MythicTrpg;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ModAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MythicTrpg.MOD_ID);

    public static final Supplier<AttachmentType<PlayerMythDataView>> PLAYER_MYTH_VIEW = ATTACHMENT_TYPES.register(
            "player_myth_view",
            () -> AttachmentType.builder(holder -> {
                if (!(holder instanceof net.minecraft.world.entity.player.Player player)) {
                    throw new IllegalStateException("Player Myth view can only be attached to a player");
                }
                return new PlayerMythDataView(player.getUUID());
            }).build()
    );

    private ModAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
