package com.sande.mythictrpg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sande.mythictrpg.combat.BetterCombatBridge;
import net.bettercombat.network.Packets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Optional: the target class does not exist in installations without Better Combat. */
@Pseudo
@Mixin(targets = "net.bettercombat.network.ServerNetwork", remap = false)
abstract class BetterCombatServerMixin {
    @WrapMethod(method = "handleAttackRequest", remap = false)
    private static void mythictrpg$guardAttack(Packets.C2S_AttackRequest request, MinecraftServer server,
            ServerPlayer player, ServerGamePacketListenerImpl handler, Operation<Void> original) {
        if (BetterCombatBridge.attackBlocked(player)) return;
        original.call(BetterCombatBridge.filterTargets(request, player), server, player, handler);
    }
}
