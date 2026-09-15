package com.sande.mythaiaicontent;

import com.mojang.logging.LogUtils;
import com.sande.mythaiaicontent.content.AiContentRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Static-content-only addon.  It deliberately has no MythicTRPG dependency so the RPG mod remains independently
 * replaceable; integrations consume {@link AiContentRegistry}'s public read API by an agreed contract.
 */
@Mod(MythAiContentRegistryMod.MOD_ID)
public final class MythAiContentRegistryMod {
    public static final String MOD_ID = "mythaiaicontent";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MythAiContentRegistryMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(AiContentRegistry.INSTANCE::onAddReloadListeners);
        LOGGER.info("MythAI Content Registry initialized. Waiting for datapack reload.");
    }
}
