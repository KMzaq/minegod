package com.sande.mythictrpg.ai.action.protection;

import com.sande.mythictrpg.accessory.RewardAccessoryItems;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.power.RewardPowerState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Destructive/forced AI actions must query the actual stack immediately before committing. */
public final class AiProtectedItems {
    public static final TagKey<Item> PROTECTED = itemTag("ai_protected_items");
    public static final TagKey<Item> IMPORTANT = itemTag("important_items");
    public static final TagKey<Item> SOULBOUND = itemTag("soulbound_items");
    public static final String CUSTOM_DATA_KEY = "mythictrpg:ai_protection";
    private AiProtectedItems() { }

    /** Empty means verified mutable by this guard, not permission to take or destroy the item. */
    public static java.util.Optional<String> rejection(ServerPlayer owner, ItemStack stack) {
        if (!owner.server.isSameThread()) throw new IllegalStateException("Item protection requires game thread");
        return rejection(owner, owner.serverLevel(), stack, 0, new int[]{0});
    }

    /** NPC-owned/world storage has no player owner; static bound identities still must be protected. */
    public static java.util.Optional<String> rejection(net.minecraft.server.level.ServerLevel level,ItemStack stack) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Item protection requires game thread");
        return rejection(null,level,stack,0,new int[]{0});
    }

    private static java.util.Optional<String> rejection(ServerPlayer owner, net.minecraft.server.level.ServerLevel level, ItemStack stack, int depth, int[] inspected) {
        if (stack == null || stack.isEmpty()) return java.util.Optional.of("ITEM_UNAVAILABLE");
        if (depth > 16 || ++inspected[0] > 4096) return java.util.Optional.of("ITEM_PROTECTION_INSPECTION_LIMIT");
        var container = stack.get(DataComponents.CONTAINER);
        if (container != null) for (ItemStack contained : container.nonEmptyItems()) {
            var denied = rejection(owner, level, contained, depth + 1, inspected);
            if (denied.isPresent()) return java.util.Optional.of("PROTECTED_CONTAINER_CONTENT:" + denied.orElseThrow());
        }
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) for (ItemStack contained : bundle.items()) {
            if (contained.isEmpty()) continue;
            var denied = rejection(owner, level, contained, depth + 1, inspected);
            if (denied.isPresent()) return java.util.Optional.of("PROTECTED_CONTAINER_CONTENT:" + denied.orElseThrow());
        }
        if (stack.is(PROTECTED) || stack.is(IMPORTANT) || stack.is(SOULBOUND)) return java.util.Optional.of("PROTECTED_ITEM_TAG");
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (data.contains(CUSTOM_DATA_KEY)) {
            if (!data.contains(CUSTOM_DATA_KEY, Tag.TAG_COMPOUND)) return java.util.Optional.of("INVALID_ITEM_PROTECTION_METADATA");
            CompoundTag protection = data.getCompound(CUSTOM_DATA_KEY);
            // Unknown protection metadata cannot silently turn a protected item into a mutable one.
            if (protection.getAllKeys().stream().anyMatch(key -> !java.util.Set.of("important", "soulbound").contains(key)))
                return java.util.Optional.of("INVALID_ITEM_PROTECTION_METADATA");
            for (String key : protection.getAllKeys()) {
                if (!protection.contains(key, Tag.TAG_BYTE)) return java.util.Optional.of("INVALID_ITEM_PROTECTION_METADATA");
                if (protection.getBoolean(key)) return java.util.Optional.of("PROTECTED_ITEM_METADATA");
            }
        }
        if (RewardAccessoryItems.isToken(stack) && (data.hasUUID("accessoryEntitlement") || data.hasUUID("accessoryOwner")))
            return java.util.Optional.of("BOUND_ACCESSORY");
        if (owner!=null&&CombatPowerRuntime.registeredAcquiredGear(owner, stack).isPresent()) return java.util.Optional.of("REGISTERED_BOUND_GEAR");
        var policy = CombatPowerPolicyManager.INSTANCE.current().policy();
        if (policy == null) return java.util.Optional.of("ITEM_PROTECTION_AUTHORITY_UNAVAILABLE");
        if (policy.model() != null) {
            try {
                CompoundTag identity = withoutWear(RewardPowerState.identity(stack, level.registryAccess()));
                // V2 explicitly classifies every non-ignored rule as BOUND_REWARD_GEAR. Do not infer binding from reward history alone.
                for (var rule : policy.items()) if (!rule.ignored()
                        && withoutWear(rule.identity(level.registryAccess())).equals(identity))
                    return java.util.Optional.of("REGISTERED_BOUND_GEAR");
            } catch (RuntimeException unavailable) {
                return java.util.Optional.of("ITEM_PROTECTION_AUTHORITY_UNAVAILABLE");
            }
        }
        return java.util.Optional.empty();
    }

    private static CompoundTag withoutWear(CompoundTag identity) {
        CompoundTag copy = identity.copy();
        if (copy.contains("components", Tag.TAG_COMPOUND)) {
            CompoundTag components = copy.getCompound("components"); components.remove("minecraft:damage");
            if (components.isEmpty()) copy.remove("components");
        }
        return copy;
    }

    private static TagKey<Item> itemTag(String path) { return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("mythictrpg", path)); }
}
