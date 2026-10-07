package com.sande.mythictrpg.godavatar.activity;

import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/** Player speech is social input. OP exclusions and installed protection vetoes are hard limits. */
public final class NpcActivityAccess {
    private static final List<BiPredicate<GodAvatarEntity, BlockPos>> VETO_GATES = new CopyOnWriteArrayList<>();
    public static void registerGate(BiPredicate<GodAvatarEntity, BlockPos> allowed) { VETO_GATES.add(java.util.Objects.requireNonNull(allowed)); }
    public static boolean canUse(GodAvatarEntity avatar, BlockPos pos) {
        if (!(avatar.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !avatar.isAlive()
                || !avatar.hasAuthoritativeBinding() || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !NpcActivityWorldState.get(level.getServer()).allowed(level.dimension().location(), pos)) return false;
        var be = level.getBlockEntity(pos);
        if (be != null) {
            var tag = be.saveWithoutMetadata(level.registryAccess());
            if (!tag.getString("Lock").isEmpty() || tag.contains("LootTable")) return false;
        }
        try { return VETO_GATES.stream().allMatch(gate -> gate.test(avatar, pos)); }
        catch (RuntimeException unknown) { return false; }
    }
    private NpcActivityAccess() { }
}
