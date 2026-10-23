package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.weaponskill.ManaAccount;
import com.sande.mythictrpg.weaponskill.WeaponSkillState;
import net.minecraft.server.level.ServerPlayer;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/** Shared authoritative resource for weapon, learned and special skills. */
public final class SkillMana {
    private static ToDoubleFunction<ServerPlayer> maximumProvider=ignored->ManaTuning.BASE_MAX_MANA;
    private SkillMana() { }
    public static void maximumProvider(ToDoubleFunction<ServerPlayer> provider) { maximumProvider=Objects.requireNonNull(provider); }
    public static double maximumMana(ServerPlayer player) {
        double value=maximumProvider.applyAsDouble(player);
        if(!Double.isFinite(value)||value<1||value>ManaAccount.AUTHORING_MAX_MANA)throw new IllegalStateException("Invalid equipment maximum mana");
        // The saved account uses micro-mana; expose the same cap so rounding cannot exceed a wire snapshot maximum.
        return Math.round(value*1_000_000.0)/1_000_000.0;
    }
    public static double mana(ServerPlayer player) {
        var state=WeaponSkillState.get(player.server);state.clamp(player.getUUID(),maximumMana(player));
        return state.account(player.getUUID()).map(ManaAccount::mana).orElse(0.0);
    }
    public static boolean canSpend(ServerPlayer player,int amount) {
        mana(player);
        return WeaponSkillState.get(player.server).canUse(player.getUUID(),"mythictrpg:special_mana",amount,0,now(player))==WeaponSkillState.Result.READY;
    }
    public static boolean spend(ServerPlayer player,int amount) {
        mana(player);
        return WeaponSkillState.get(player.server).trySpend(player.getUUID(),"mythictrpg:special_mana",amount,0,now(player))==WeaponSkillState.Result.SUCCESS;
    }
    public static double refund(ServerPlayer player,double amount) {
        mana(player);return WeaponSkillState.get(player.server).refund(player.getUUID(),amount,maximumMana(player));
    }
    public static long now(ServerPlayer player) { return player.server.overworld().getGameTime(); }
}
