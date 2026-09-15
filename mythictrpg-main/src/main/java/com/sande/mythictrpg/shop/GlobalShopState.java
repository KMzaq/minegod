package com.sande.mythictrpg.shop;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Set;

/** World-shared product unlocks. Default products remain definition-owned. */
public final class GlobalShopState extends SavedData {
    private static final int DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_shops";
    private static final Factory<GlobalShopState> FACTORY = new Factory<>(GlobalShopState::new, GlobalShopState::load);

    private Set<ShopProductKey> unlockedProducts = new LinkedHashSet<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static GlobalShopState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    static GlobalShopState load(CompoundTag tag, HolderLookup.Provider registries) {
        GlobalShopState state = new GlobalShopState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC) || tag.getInt("dataVersion") != DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported shop state dataVersion");
            }
            if (!tag.contains("unlockedProducts", Tag.TAG_LIST)) {
                throw new IllegalArgumentException("Missing unlockedProducts list");
            }
            Set<ShopProductKey> loaded = new LinkedHashSet<>();
            ListTag list = tag.getList("unlockedProducts", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entry = list.getCompound(index);
                ShopProductKey key = new ShopProductKey(id(entry.getString("shop")), id(entry.getString("product")));
                if (!loaded.add(key)) throw new IllegalArgumentException("Duplicate unlocked shop product " + key);
            }
            state.unlockedProducts = loaded;
        } catch (RuntimeException exception) {
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected global shop state without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    public boolean isReady() {
        return rejectedRawData == null;
    }

    public boolean isUnlocked(ShopProductKey key) {
        return unlockedProducts.contains(key);
    }

    public boolean unlock(ShopProductKey key) {
        ensureWritable();
        if (!unlockedProducts.add(key)) return false;
        setDirty();
        return true;
    }

    public boolean lock(ShopProductKey key) {
        ensureWritable();
        if (!unlockedProducts.remove(key)) return false;
        setDirty();
        return true;
    }

    public Set<ShopProductKey> unlockedProducts() {
        return Set.copyOf(unlockedProducts);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) return rejectedRawData.copy();
        tag.putInt("dataVersion", DATA_VERSION);
        ListTag list = new ListTag();
        unlockedProducts.stream().sorted().forEach(key -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("shop", key.shopId().toString());
            entry.putString("product", key.productId().toString());
            list.add(entry);
        });
        tag.put("unlockedProducts", list);
        return tag;
    }

    private void ensureWritable() {
        if (rejectedRawData != null) throw new IllegalStateException("Shop state is read-only: " + rejectionReason);
    }

    private static ResourceLocation id(String value) {
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null || !value.contains(":")) throw new IllegalArgumentException("Invalid shop state ID");
        return parsed;
    }
}
