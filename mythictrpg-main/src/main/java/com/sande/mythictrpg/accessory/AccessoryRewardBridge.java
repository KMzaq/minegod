package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Bound accessory rights are committed together with the original reward claim. */
public final class AccessoryRewardBridge {
    public record Entitlement(UUID id,ResourceLocation definitionId) { }
    private AccessoryRewardBridge() { }
    public static boolean isReady(ServerPlayer player) { return SkillRewardBridge.isReady(player); }
    public static List<Entitlement> entitlements(ServerPlayer player) {
        if(!isReady(player)) return List.of();
        return RewardClaimState.get(player.server).progressionFor(player.getUUID()).stream()
                .filter(value -> value.kind()==ProgressionRewardEntry.Kind.ACCESSORY)
                .map(value -> new Entitlement(value.entitlementId(),value.definitionId())).toList();
    }
}
