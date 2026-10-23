package com.sande.mythictrpg.client.weaponskill;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Client-only optional API linkage, isolated from both dedicated servers and no-mod clients. */
public final class BetterCombatClientBridge {
    private BetterCombatClientBridge() { }

    public static void cancelLockedUpswing() {
        if (WeaponSkillClient.attacksLocked() && ModList.get().isLoaded("bettercombat")) Loaded.cancelUpswing();
    }

    private static final class Loaded {
        static void cancelUpswing() {
            Object client = Minecraft.getInstance();
            if (client instanceof net.bettercombat.api.MinecraftClient_BetterCombat combat) combat.cancelUpswing();
        }
    }
}
