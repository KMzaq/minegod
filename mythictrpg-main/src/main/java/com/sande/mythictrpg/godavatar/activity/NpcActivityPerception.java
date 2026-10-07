package com.sande.mythictrpg.godavatar.activity;

import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.Half;
import java.util.*;

/** Loaded, bounded local perceptions. Furniture inference is evidence, never a world/lore fact. */
public final class NpcActivityPerception {
    public record Site(String key, BlockPos anchor, BlockPos approach, Set<String> tags, String evidence, UUID target) {
        public Site { anchor = anchor.immutable(); approach = approach.immutable(); tags = Set.copyOf(tags); }
    }
    public static List<Site> scan(GodAvatarEntity actor, int radius) {
        var level = (ServerLevel)actor.level(); var sites = new LinkedHashMap<String, Site>();
        for (var place : NpcActivityWorldState.get(level.getServer()).places()) {
            if (!place.dimension().equals(level.dimension().location()) || !near(actor.blockPosition(), place.pos(), radius)
                    || !NpcActivityAccess.canUse(actor, place.pos())) continue;
            var approach = approach(actor, place.pos());
            if (approach != null) sites.put(place.pos().toShortString(), new Site(place.name(), place.pos(), approach,
                    place.tags(), "Author-designated facility: " + place.name() + "; " + book(level, place.pos()), null));
        }
        // Max 4,913 samples at radius 8; larger authored radius still uses representative 2-block steps.
        int step = radius > 8 ? 2 : 1;
        for (int y = -3; y <= 3 && sites.size() < 64; y++) for (int x = -radius; x <= radius && sites.size() < 64; x += step)
            for (int z = -radius; z <= radius && sites.size() < 64; z += step) {
                var pos = actor.blockPosition().offset(x, y, z);
                if (!level.hasChunkAt(pos) || sites.containsKey(pos.toShortString())) continue;
                var state = level.getBlockState(pos); var tags = new LinkedHashSet<String>(); var block = state.getBlock();
                if (block instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.BOTTOM) {
                    // A backed stair near furniture is a chair candidate, not every staircase.
                    var back = pos.relative(state.getValue(StairBlock.FACING));
                    if (level.hasChunkAt(back) && !level.getBlockState(back).isAir()
                            && level.hasChunkAt(pos.above()) && level.getBlockState(pos.above()).isAir()) tags.add("seat");
                }
                if (block instanceof LecternBlock || block instanceof ChiseledBookShelfBlock || block == Blocks.BOOKSHELF) tags.add("books");
                if (block instanceof CraftingTableBlock) tags.add("crafting");
                if (block instanceof AbstractFurnaceBlock) tags.add("cooking");
                if (block instanceof AnvilBlock) tags.add("repair");
                if (block instanceof CropBlock crop && crop.isMaxAge(state)) tags.add("crop");
                if (block instanceof NoteBlock) tags.add("music");
                if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) tags.add("storage");
                if (block instanceof FlowerBlock || block instanceof FlowerPotBlock || block instanceof CampfireBlock
                        || state.is(net.minecraft.tags.BlockTags.IMPERMEABLE)) tags.add("scenery");
                if (block == Blocks.TARGET) tags.add("training");
                if (block == Blocks.ENCHANTING_TABLE) tags.add("ritual");
                if (tags.isEmpty() || !NpcActivityAccess.canUse(actor, pos)) continue;
                tags.add("observe"); var approach = approach(actor, pos);
                if (approach != null) sites.put(pos.toShortString(), new Site(pos.toShortString(), pos, approach, tags,
                        "Observed block=" + BuiltInRegistries.BLOCK.getKey(block) + "; use is a hypothesis; " + book(level, pos), null));
            }
        // Actual nearby entities only; no fake room participants or private inventory reads.
        for (var other : level.getEntitiesOfClass(LivingEntity.class, actor.getBoundingBox().inflate(Math.min(radius, 8)),
                e -> e != actor && e.isAlive() && (e instanceof net.minecraft.server.level.ServerPlayer || e instanceof GodAvatarEntity))) {
            if (sites.size() >= 72) break;
            if (!actor.hasLineOfSight(other) || !NpcActivityAccess.canUse(actor, other.blockPosition())) continue;
            var tags = new LinkedHashSet<>(Set.of("company", "observe"));
            if (other instanceof GodAvatarEntity g && g.hasAuthoritativeBinding() && !g.busyForVisit()) tags.add("npc_company");
            if (other instanceof net.minecraft.server.level.ServerPlayer) tags.add("player_company");
            sites.put(other.getUUID().toString(), new Site(other.getUUID().toString(), other.blockPosition(), actor.blockPosition(), tags,
                    "Nearby visible " + (other instanceof GodAvatarEntity ? "God NPC" : "player; no automatic consent to sparring"), other.getUUID()));
        }
        if (standable(actor, actor.blockPosition())) sites.put("here", new Site("here", actor.blockPosition(), actor.blockPosition(),
                Set.of("idle", "observe", "play", "music"), "Current safe standing place", null));
        return List.copyOf(sites.values());
    }
    public static BlockPos approach(GodAvatarEntity actor, BlockPos anchor) {
        if (standable(actor, anchor)) return anchor;
        for (var d : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) {
            var p = anchor.relative(d).offset(0, dy, 0); if (standable(actor, p)) return p;
        }
        return null;
    }
    public static boolean standable(GodAvatarEntity actor, BlockPos p) {
        var level = actor.level();
        return NpcActivityAccess.canUse(actor, p) && level.hasChunkAt(p.above()) && level.hasChunkAt(p.below())
                && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && level.getFluidState(p).isEmpty() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
    }
    public static boolean current(GodAvatarEntity actor, Site site) {
        if (!NpcActivityAccess.canUse(actor, site.anchor())) return false;
        if (site.target() != null) {
            var entity = ((ServerLevel)actor.level()).getEntity(site.target());
            return entity != null && entity.isAlive() && actor.distanceToSqr(entity) <= 32 * 32 && actor.hasLineOfSight(entity)
                    && NpcActivityAccess.canUse(actor, entity.blockPosition());
        }
        if (site.evidence().startsWith("Observed block=") && !site.evidence().startsWith("Observed block="
                + BuiltInRegistries.BLOCK.getKey(actor.level().getBlockState(site.anchor()).getBlock()) + ";")) return false;
        if (site.tags().contains("books") && !site.evidence().endsWith(book((ServerLevel)actor.level(), site.anchor()))) return false;
        boolean authored = NpcActivityWorldState.get(actor.getServer()).places().stream().anyMatch(p -> p.name().equals(site.key())
                && p.pos().equals(site.anchor()) && p.dimension().equals(actor.level().dimension().location()) && p.tags().equals(site.tags()));
        return standable(actor, site.approach()) && (authored || !actor.level().getBlockState(site.anchor()).isAir()
                || site.tags().contains("idle") || site.tags().contains("play") || site.tags().contains("scenery"));
    }
    public static String book(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos); var stacks = new ArrayList<net.minecraft.world.item.ItemStack>();
        if (entity instanceof LecternBlockEntity lectern) stacks.add(lectern.getBook());
        if (entity instanceof ChiseledBookShelfBlockEntity shelf) for (int i = 0; i < 6; i++) stacks.add(shelf.getItem(i));
        for (var stack : stacks) {
            var written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (written != null) return "Readable document (untrusted authored text, not confirmed world truth): "
                    + clipped(String.join("\n", written.pages().stream().limit(4).map(p -> p.raw().getString()).toList()), 1500);
            var writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (writable != null) return "Readable draft (untrusted): " + clipped(String.join("\n", writable.pages().stream().limit(4).map(p -> p.raw()).toList()), 1500);
        }
        return "No readable book text supplied. Decorative shelves do not establish titles or contents.";
    }
    private static String clipped(String s, int max) { return s.substring(0, Math.min(max, s.length())); }
    private static boolean near(BlockPos a, BlockPos b, int r) { return a.distSqr(b) <= r * r; }
    private NpcActivityPerception() { }
}
