package com.sande.mythictrpg.godavatar.activity;

import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Small, loaded-world-only bodily placement. Never places blocks or persists cosmetic items. */
public final class NpcActivityBody {
    private static final Map<UUID, Placement> PLACEMENTS = new HashMap<>();
    private NpcActivityBody() {}

    public static boolean begin(GodAvatarEntity avatar, BlockPos site, String pose, ItemStack prop) {
        if (!(avatar.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !avatar.isAlive() || !avatar.hasAuthoritativeBinding() || site == null || !level.hasChunkAt(site)) return false;
        end(avatar);
        boolean seated = pose.equals("SIT") || pose.equals("READ");
        Vec3 origin = avatar.position();
        Vec3 target = Vec3.atBottomCenterOf(site);
        var siteState = level.getBlockState(site);
        var shape = siteState.getCollisionShape(level, site, CollisionContext.of(avatar));
        net.minecraft.core.Direction chairFront = null;
        if (seated && siteState.getBlock() instanceof StairBlock && siteState.getValue(StairBlock.HALF) == Half.BOTTOM) {
            chairFront = siteState.getValue(StairBlock.FACING).getOpposite();
            // Sit on the low front tread, not the high chair back. Wide/corner shapes must still pass collision checks.
            double offset = avatar.getBbWidth() / 2D + .005;
            target = target.add(chairFront.getStepX() * offset, .5, chairFront.getStepZ() * offset);
        } else if (seated && !shape.isEmpty()) target = target.add(0, shape.max(net.minecraft.core.Direction.Axis.Y), 0);
        // Body positioning is a last step of walking, not a remote teleport capability.
        if (origin.distanceToSqr(target) > 2.25 || Math.abs(origin.y - target.y) > 1.01) return false;
        if (!safe(avatar, target, false)) return false; // retain headroom for a safe stand-up
        avatar.setActivityVisual(pose, prop);
        if (!safe(avatar, target, true)) { avatar.clearActivityVisual(); return false; }
        avatar.getNavigation().stop();
        avatar.setPos(target);
        if (chairFront != null) {
            float yaw = chairFront.toYRot();
            avatar.setYRot(yaw); avatar.setYBodyRot(yaw); avatar.setYHeadRot(yaw);
        }
        avatar.setDeltaMovement(Vec3.ZERO);
        PLACEMENTS.put(avatar.getUUID(), new Placement(avatar, site.immutable(), origin, target,
                level.getBlockState(site), level.getBlockState(BlockPos.containing(target).below())));
        return true;
    }

    public static boolean canContinue(GodAvatarEntity avatar, BlockPos site) {
        Placement held = PLACEMENTS.get(avatar.getUUID());
        if (held == null || held.avatar != avatar || !held.site.equals(site) || !avatar.isAlive()
                || !avatar.hasAuthoritativeBinding() || !(avatar.level() instanceof ServerLevel level)
                || !level.hasChunkAt(site) || !level.getBlockState(site).equals(held.siteState)
                || !level.getBlockState(BlockPos.containing(held.anchor).below()).equals(held.supportState)
                || avatar.position().distanceToSqr(held.anchor) > .36) return false;
        return safe(avatar, held.anchor, true);
    }

    public static void end(GodAvatarEntity avatar) {
        if (!(avatar.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        Placement held = PLACEMENTS.remove(avatar.getUUID());
        // Prefer current seat; otherwise a nearby grounded exit. Never jump back after a later move/order.
        Vec3 exit = avatar.position();
        if (avatar.activitySeated() && !safe(avatar, exit, false) && held != null && held.avatar == avatar) {
            if (exit.distanceToSqr(held.origin) <= 4 && safe(avatar, held.origin, false)) exit = held.origin;
            else for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Vec3 candidate = Vec3.atBottomCenterOf(avatar.blockPosition().offset(dx, 0, dz));
                if (safe(avatar, candidate, false)) { exit = candidate; break; }
            }
        }
        avatar.clearActivityVisual();
        if (safe(avatar, exit, false)) avatar.setPos(exit);
        avatar.getNavigation().stop();
    }

    private static boolean safe(GodAvatarEntity avatar, Vec3 position, boolean currentPose) {
        var level = avatar.level();
        BlockPos feet = BlockPos.containing(position);
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above(2)) || !level.hasChunkAt(feet.below())
                || !level.getWorldBorder().isWithinBounds(feet) || !level.getFluidState(feet).isEmpty()) return false;
        var dimensions = currentPose ? avatar.getDimensions(Pose.STANDING)
                : avatar.getType().getDimensions().scale(avatar.getScale());
        var box = dimensions.makeBoundingBox(position);
        // Tiny downward overlap establishes real support, including slabs/stairs.
        return level.noCollision(avatar, box.deflate(.001))
                && !level.noCollision(avatar, box.move(0, -.08, 0).deflate(.001));
    }

    private record Placement(GodAvatarEntity avatar, BlockPos site, Vec3 origin, Vec3 anchor,
            net.minecraft.world.level.block.state.BlockState siteState,
            net.minecraft.world.level.block.state.BlockState supportState) {}
}
