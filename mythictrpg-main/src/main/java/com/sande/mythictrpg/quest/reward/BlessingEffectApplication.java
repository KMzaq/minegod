package com.sande.mythictrpg.quest.reward;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Applies game-authored effects through Minecraft and preserves the surviving hidden-effect chain. */
public final class BlessingEffectApplication {
    private BlessingEffectApplication() { }

    public enum Result { ACTIVE, DEFERRED, ALREADY_PRESENT, REJECTED }

    public static Result apply(ServerPlayer player, MobEffectInstance incoming) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Blessing application requires server thread");
        MobEffectInstance before = player.getEffect(incoming.getEffect());
        if (covers(player, before, incoming)) {
            normalizeHiddenEffects(player,incoming.getEffect());
            return Result.ALREADY_PRESENT;
        }
        float absorption = player.getAbsorptionAmount();
        boolean hiddenAbsorption = incoming.is(MobEffects.ABSORPTION)
                && before != null && before.getAmplifier() > incoming.getAmplifier();
        // addEffect may return false even after storing a weaker, longer hidden effect.
        player.addEffect(incoming);
        if (hiddenAbsorption) player.setAbsorptionAmount(absorption);
        normalizeHiddenEffects(player, incoming.getEffect());
        MobEffectInstance after = player.getEffect(incoming.getEffect());
        if (!covers(player, after, incoming)) return Result.REJECTED;
        return after.getAmplifier() > incoming.getAmplifier() ? Result.DEFERRED : Result.ACTIVE;
    }

    private static boolean covers(ServerPlayer player, MobEffectInstance actual, MobEffectInstance requested) {
        Tag encoded = encode(player, actual);
        while (encoded instanceof CompoundTag details) {
            int duration = details.getInt("duration");
            if (details.getInt("amplifier") >= requested.getAmplifier()
                    && lastsAtLeast(duration, requested.getDuration())) return true;
            encoded = details.get("hidden_effect");
        }
        return false;
    }

    private static boolean lastsAtLeast(int duration, int other) {
        return duration == MobEffectInstance.INFINITE_DURATION
                || other != MobEffectInstance.INFINITE_DURATION && duration >= other;
    }

    private static Tag encode(ServerPlayer player, MobEffectInstance effect) {
        if (effect == null) return null;
        return MobEffectInstance.CODEC.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), effect)
                .result().orElse(null);
    }

    /** Only prunes unreachable layers in an existing chain; never recreates or refreshes an effect. */
    static void normalizeHiddenEffects(ServerPlayer player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Blessing normalization requires server thread");
        MobEffectInstance actual = player.getEffect(effect);
        if (!(encode(player, actual) instanceof CompoundTag root)) return;
        CompoundTag cursor = root;
        boolean changed = false;
        while (cursor.get("hidden_effect") instanceof CompoundTag hidden) {
            if (lastsAtLeast(cursor.getInt("duration"), hidden.getInt("duration"))) {
                // A refreshed upper layer can outlive an old intermediate layer. Vanilla would
                // otherwise restore that expired layer and remove the still-live tail with it.
                Tag tail = hidden.get("hidden_effect");
                if (tail == null) cursor.remove("hidden_effect");
                else cursor.put("hidden_effect", tail.copy());
                changed = true;
            } else cursor = hidden;
        }
        if (!changed) return;
        MobEffectInstance normalized = MobEffectInstance.CODEC.parse(
                player.registryAccess().createSerializationContext(NbtOps.INSTANCE), root).result().orElse(null);
        // This retains the active level/timer and uses Minecraft's applicability check. It does
        // not emit Remove or call onEffectStarted, so milk suppression and spent absorption stay intact.
        if (normalized != null) player.forceAddEffect(normalized, null);
    }
}
