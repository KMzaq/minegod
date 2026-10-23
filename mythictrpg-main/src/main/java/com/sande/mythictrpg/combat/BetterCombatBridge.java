package com.sande.mythictrpg.combat;

import com.sande.mythictrpg.weaponskill.WeaponSkillRuntime;
import net.bettercombat.network.Packets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.entity.PartEntity;

import java.util.Arrays;

/** Loaded only by the optional Better Combat mixin. Existing game state remains authoritative. */
public final class BetterCombatBridge {
    private BetterCombatBridge() { }

    public static boolean attackBlocked(ServerPlayer player) {
        return !player.server.isSameThread() || player.server.getPlayerList().getPlayer(player.getUUID()) != player
                || !player.isAlive() || player.isSpectator() || WeaponSkillRuntime.actionLocked(player);
    }

    /**
     * Better Combat clears hurt cooldowns before Player.attack and counts packet targets in its
     * sweep penalty. Remove allies before either side effect, keeping its normal range/hand rules.
     */
    public static Packets.C2S_AttackRequest filterTargets(Packets.C2S_AttackRequest request, ServerPlayer player) {
        int[] original = request.entityIds();
        int[] allowed = Arrays.stream(original).filter(id -> {
            var entity = player.serverLevel().getEntity(id);
            if (entity == null) entity = player.serverLevel().getEntityOrPart(id);
            LivingEntity living = entity instanceof LivingEntity target ? target
                    : entity instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity target ? target : null;
            return living == null || CombatTargeting.canDamage(player, living);
        }).toArray();
        if (allowed.length == original.length) return request;
        int cursor = Arrays.stream(allowed).anyMatch(id -> id == request.cursorTarget()) ? request.cursorTarget() : -1;
        return new Packets.C2S_AttackRequest(request.comboCount(), request.isSneaking(), request.selectedSlot(), cursor, allowed);
    }
}
