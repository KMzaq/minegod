package com.sande.mythictrpg.quest.reward.luck;

import com.sande.mythictrpg.accessory.AccessoryRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;

/** One reward/event input, retaining the existing level XVII balance ceiling. */
public final class EffectiveLuck {
    private EffectiveLuck() { }
    public static int level(ServerPlayer player) {
        if(player==null)return 0;
        var effect=player.getEffect(MobEffects.LUCK);
        double total=(effect==null?0:effect.getAmplifier()+1)+AccessoryRuntime.effectiveLuckBonus(player);
        return LuckModel.level((int)Math.min(17,Math.floor(total)));
    }
}
