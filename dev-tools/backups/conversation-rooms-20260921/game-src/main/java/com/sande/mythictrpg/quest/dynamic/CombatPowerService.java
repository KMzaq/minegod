package com.sande.mythictrpg.quest.dynamic;

import com.sande.mythictrpg.data.world.MythicWorldState;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Bounded adapter: no provider means no guessed power and no catch-up bonus. */
public final class CombatPowerService {
    public static final CombatPowerService INSTANCE = new CombatPowerService();
    private static final CombatPowerProvider UNAVAILABLE = (server, player, progress) ->
            CombatPowerAssessment.unavailable("No combat-power provider is installed");
    private volatile CombatPowerProvider provider = UNAVAILABLE;

    private CombatPowerService() {
    }

    public CombatPowerAssessment assess(ServerPlayer player) {
        try {
            return Objects.requireNonNull(provider.assess(player.server, player,
                    MythicWorldState.get(player.server).questProgress()), "provider result");
        } catch (RuntimeException exception) {
            return CombatPowerAssessment.unavailable("Combat-power provider failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    public void installProvider(CombatPowerProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public void clearProvider() {
        provider = UNAVAILABLE;
    }
}
