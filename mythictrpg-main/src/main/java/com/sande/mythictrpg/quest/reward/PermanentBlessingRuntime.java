package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;

/** Projects game-owned permanent blessings into ordinary, milk-removable Minecraft effects. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class PermanentBlessingRuntime {
    private PermanentBlessingRuntime() { }

    /** No login restoration: removal remains in force until death-respawn or the player's explicit command. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void removed(MobEffectEvent.Remove event) {
        if (event.isCanceled() || event.getEffectInstance() == null
                || !(event.getEntity() instanceof ServerPlayer player) || !player.server.isSameThread()) return;
        var state = RewardClaimState.get(player.server);
        if (state.isWritable()) state.suppressBlessing(player.getUUID(),
                BuiltInRegistries.MOB_EFFECT.getKey(event.getEffect().value()));
    }

    @SubscribeEvent
    public static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        // Returning from the End also emits a respawn event and must not undo milk removal.
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) return;
        ReapplyResult result = reapply(player);
        if (!result.unavailable().isEmpty()) MythicTrpg.LOGGER.warn("Permanent blessing respawn restoration incomplete for {}: {}",
                player.getUUID(), result.unavailable());
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythblessing")
                .then(Commands.literal("reapply").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ReapplyResult result = reapply(player);
                    if (!result.unavailable().isEmpty()) context.getSource().sendFailure(Component.literal(
                            "[영구가호] 적용 확인 " + result.applied() + "종, 적용 보류: " + String.join(", ", result.unavailable())));
                    else context.getSource().sendSuccess(() -> Component.literal(
                            "[영구가호] 보유 가호 " + result.applied() + "종을 다시 적용했습니다."), false);
                    return result.unavailable().isEmpty() ? 1 : 0;
                })));
    }

    static void applyGranted(ServerPlayer player, ResourceLocation effectId) {
        requireServerThread(player);
        var state = RewardClaimState.get(player.server);
        if (state.isBlessingSuppressed(player.getUUID(), effectId)) return;
        Integer amplifier = state.ownedPermanentBlessingLevels(player.getUUID()).get(effectId);
        if (amplifier == null) throw new IllegalStateException("Permanent blessing ownership missing");
        // Ownership is the granted reward; another mod may decline its current effect application.
        if (!apply(player, effectId, amplifier)) MythicTrpg.LOGGER.warn("Owned permanent blessing {} could not be applied to {}",
                effectId, player.getUUID());
    }

    public static ReapplyResult reapply(ServerPlayer player) {
        requireServerThread(player);
        var state = RewardClaimState.get(player.server);
        if (!state.isWritable()) return new ReapplyResult(0, List.of("가호 저장소를 읽을 수 없습니다"));
        var levels = state.ownedPermanentBlessingLevels(player.getUUID());
        state.clearBlessingSuppression(player.getUUID());
        int applied = 0;
        List<String> unavailable = new ArrayList<>();
        for (var entry : levels.entrySet()) {
            if (apply(player, entry.getKey(), entry.getValue())) applied++;
            else unavailable.add(entry.getKey().toString());
        }
        return new ReapplyResult(applied, unavailable);
    }

    private static boolean apply(ServerPlayer player, ResourceLocation effectId, int amplifier) {
        var holder = BuiltInRegistries.MOB_EFFECT.getHolder(effectId).orElse(null);
        if (holder == null || holder.value().isInstantenous()) return false;
        // Repeating the command must not restart a present effect (e.g. replenish spent absorption hearts).
        if (hasInfiniteBaseline(player, player.getEffect(holder), amplifier)) return true;
        // Vanilla update preserves this weaker infinite baseline behind a stronger temporary potion.
        // addEffect can return false even when it successfully added a hidden baseline, so verify the result.
        player.addEffect(new MobEffectInstance(holder, MobEffectInstance.INFINITE_DURATION, amplifier, false, true, true));
        return hasInfiniteBaseline(player, player.getEffect(holder), amplifier);
    }

    private static boolean hasInfiniteBaseline(ServerPlayer player, MobEffectInstance actual, int amplifier) {
        if (actual == null) return false;
        Tag encoded = MobEffectInstance.CODEC.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), actual)
                .result().orElse(null);
        while (encoded instanceof CompoundTag details) {
            if (details.getInt("duration") == MobEffectInstance.INFINITE_DURATION && details.getInt("amplifier") >= amplifier) return true;
            encoded = details.get("hidden_effect");
        }
        return false;
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Blessing application requires server thread");
    }

    public record ReapplyResult(int applied, List<String> unavailable) {
        public ReapplyResult { unavailable = List.copyOf(unavailable); }
    }
}
