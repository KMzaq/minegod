package com.sande.mythictrpg.content;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Player-owned visit preference; missing entries preserve the existing authored visit behavior. */
public final class HomeVisitPreferences extends SavedData {
    private static final Factory<HomeVisitPreferences> FACTORY = new Factory<>(HomeVisitPreferences::new, HomeVisitPreferences::load);
    private final Set<UUID> blocked = new HashSet<>();
    private CompoundTag rejected;
    public static HomeVisitPreferences get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Visit preferences require server thread");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mythictrpg_home_visit_preferences");
    }
    public boolean allowed(UUID player) { return rejected == null && !blocked.contains(player); }
    public boolean setAllowed(UUID player, boolean allow) {
        if (rejected != null || !allow && !blocked.contains(player) && blocked.size() >= 4096) return false;
        boolean changed = allow ? blocked.remove(player) : blocked.add(player);
        if (changed) setDirty();
        return true;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejected != null) return rejected.copy();
        tag.putInt("version", 1); var list = new ListTag();
        blocked.stream().sorted().forEach(player -> { var value = new CompoundTag(); value.putUUID("player", player); list.add(value); });
        tag.put("blocked", list); return tag;
    }
    public static HomeVisitPreferences load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new HomeVisitPreferences();
        try {
            if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1 || !(tag.get("blocked") instanceof ListTag list)
                    || list.size() > 4096 || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException();
            for (var value : list) {
                var entry = (CompoundTag) value;
                if (!entry.hasUUID("player") || !result.blocked.add(entry.getUUID("player"))) throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) { result.blocked.clear(); result.rejected = tag.copy(); }
        return result;
    }
}
