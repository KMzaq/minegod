package com.sande.mythictrpg.godavatar.activity.work;

import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import com.sande.mythictrpg.godavatar.activity.NpcActivityAccess;
import com.sande.mythictrpg.godavatar.activity.NpcActivityDefinition;
import com.sande.mythictrpg.ai.action.NpcRitualEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.EffectCures;

import java.util.*;
import java.util.function.Predicate;

/** Bounded real work. Player inventories, fake players and AI action sessions are never used. */
public final class NpcActivityWork {
    private static final Set<GodAvatarEntity> EXECUTING=Collections.newSetFromMap(new IdentityHashMap<>());
    private NpcActivityWork() { }
    public record Outcome(boolean success, String detail) { }

    public static boolean available(GodAvatarEntity avatar, BlockPos site, NpcActivityDefinition definition) {
        try {
            if (!guard(avatar, site) || !definition.mode().name().equals("REAL")) return false;
            if (definition.kind().name().equals("RITUAL")) return NpcRitualEffects.available(avatar, site, id(definition.parameters(), "template_id"));
            if (definition.kind().name().equals("FARM")) return crop(avatar, site, definition.parameters()) != null;
            return plan(avatar, site, definition) != null;
        } catch (RuntimeException unavailable) { return false; }
    }

    public static Outcome execute(GodAvatarEntity avatar, BlockPos site, NpcActivityDefinition definition) {
        if(avatar==null || !(avatar.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            return new Outcome(false,"SITE_UNAVAILABLE");
        // Server-thread-only identity guard: inventory/effect callbacks cannot recursively spend
        // the same NPC's partially updated transaction. Never retain a lease after this call ends.
        if(!EXECUTING.add(avatar))return new Outcome(false,"REENTRANT_ACTIVITY_WORK_REJECTED");
        try{return guard(avatar,site) ? executeOnce(avatar,site,definition) : new Outcome(false,"SITE_UNAVAILABLE");}
        finally{EXECUTING.remove(avatar);}
    }
    private static Outcome executeOnce(GodAvatarEntity avatar,BlockPos site,NpcActivityDefinition definition) {
        if (!definition.mode().name().equals("REAL")) return new Outcome(false, "DECORATIVE_HAS_NO_RESOURCE_EXECUTION");
        try {
            if (definition.kind().name().equals("RITUAL")) return new Outcome(
                    NpcRitualEffects.execute(avatar, site, id(definition.parameters(), "template_id")), "REGISTERED_PRESENTATION_ONLY");
            if (definition.kind().name().equals("FARM")) return farm(avatar, site, definition.parameters());
            Plan plan = plan(avatar, site, definition);
            if (plan == null) return new Outcome(false, "MATERIAL_CAPACITY_OR_ACCESS_CHANGED");
            if (!plan.pool.commit()) return new Outcome(false, plan.pool.rollbackComplete
                    ? "MATERIAL_CAPACITY_OR_ACCESS_CHANGED" : "EXTERNAL_REENTRY_INTERRUPTED_TRANSACTION_NO_REFUND");
            // Consumable effects happen only after all resources/return containers were committed.
            // Never refund an already-consumed item after a modded effect callback throws: that could duplicate its effect.
            if (plan.consumed != null) {
                try { consumeEffects(avatar, plan.consumed); }
                catch (RuntimeException effectFailure) { return new Outcome(false, "ITEM_CONSUMED_EFFECT_CALLBACK_FAILED_NO_REFUND"); }
            }
            return new Outcome(true, definition.kind().name() + "_COMMITTED");
        } catch (RuntimeException rejected) { return new Outcome(false, "INVALID_OR_UNSUPPORTED_WORK"); }
    }

    private static Plan plan(GodAvatarEntity avatar, BlockPos site, NpcActivityDefinition def) {
        Pool pool = new Pool(avatar, site); Map<String,String> p = def.parameters();
        return switch (def.kind().name()) {
            case "CRAFT" -> craft(pool, p);
            case "REPAIR" -> p.containsKey("recipe_id") ? craft(pool, p) : repair(pool, p);
            case "COOK" -> cook(pool, site, p, def.durationTicks());
            case "EAT", "DRINK" -> food(pool, p, def.kind().name().equals("DRINK"));
            case "OFFERING" -> offering(pool, p);
            default -> null;
        };
    }

    private static Plan craft(Pool pool, Map<String,String> p) {
        Recipe<?> raw = pool.level.getRecipeManager().byKey(id(p, "recipe_id")).map(RecipeHolder::value).orElse(null);
        // Foreign machines and special recipes are not ordinary nine-slot crafting.
        if (!(raw instanceof CraftingRecipe recipe) || recipe.isSpecial()
                || !(raw.getClass()==ShapedRecipe.class || raw.getClass()==ShapelessRecipe.class)
                || !pool.level.getBlockState(pool.site).is(Blocks.CRAFTING_TABLE)) return null;
        String[] grid = required(p, "grid").split(",", -1); if (grid.length != 9) return null;
        List<ItemStack> inputs = new ArrayList<>();
        for (String cell : grid) {
            if (cell.trim().equals("_")) inputs.add(ItemStack.EMPTY);
            else {
                ItemStack reference = new ItemStack(item(ResourceLocation.parse(cell.trim())));
                ItemStack found = pool.take(stack -> ItemStack.isSameItemSameComponents(stack, reference), false);
                if (found.isEmpty()) return null; inputs.add(found);
            }
        }
        CraftingInput input = CraftingInput.of(3, 3, inputs);
        if (!recipe.matches(input, pool.level)) return null;
        ItemStack output = recipe.assemble(input, pool.level.registryAccess());
        if (output.isEmpty() || !pool.put(output, false)) return null;
        var remains = recipe.getRemainingItems(input);
        if (remains.size() > 9) return null;
        for (ItemStack stack : remains) if (!pool.put(stack, false)) return null;
        return new Plan(pool, null);
    }

    private static Plan repair(Pool pool, Map<String,String> p) {
        Item kind = item(id(p, "item_id"));
        ItemStack first = pool.take(stack -> stack.is(kind) && stack.isDamaged() && stack.isRepairable(), false);
        ItemStack second = pool.take(stack -> stack.is(kind) && stack.isDamaged() && stack.isRepairable(), false);
        if (first.isEmpty() || second.isEmpty()) return null;
        var input = CraftingInput.of(2, 1, List.of(first, second));
        // Exactly Minecraft's two-item repair: its curse/normal enchantment behavior is retained.
        var recipe = new RepairItemRecipe(CraftingBookCategory.MISC);
        if (!recipe.matches(input, pool.level)) return null;
        ItemStack output = recipe.assemble(input, pool.level.registryAccess());
        if (output.isEmpty() || !pool.put(output, false)) return null;
        return new Plan(pool, null);
    }

    private static Plan cook(Pool pool, BlockPos site, Map<String,String> p, int duration) {
        Recipe<?> raw = pool.level.getRecipeManager().byKey(id(p, "recipe_id")).map(RecipeHolder::value).orElse(null);
        if (!(raw instanceof AbstractCookingRecipe recipe) || recipe.isSpecial()
                || !(raw.getClass()==SmeltingRecipe.class || raw.getClass()==SmokingRecipe.class || raw.getClass()==BlastingRecipe.class)) return null;
        Block block = pool.level.getBlockState(site).getBlock();
        boolean appliance = recipe.getType() == RecipeType.SMELTING && block == Blocks.FURNACE
                || recipe.getType() == RecipeType.SMOKING && block == Blocks.SMOKER
                || recipe.getType() == RecipeType.BLASTING && block == Blocks.BLAST_FURNACE;
        if (!appliance || duration < recipe.getCookingTime()) return null;
        ItemStack input = pool.take(stack -> stack.is(item(id(p, "input_id"))), false);
        var single = new SingleRecipeInput(input);
        if (input.isEmpty() || !recipe.matches(single, pool.level)) return null;
        ItemStack output = recipe.assemble(single, pool.level.registryAccess());
        // This activity is cooking food, not an alternative general ore-smelting reward engine.
        if (output.isEmpty() || !output.has(DataComponents.FOOD)) return null;
        Item fuelItem = item(id(p, "fuel_id"));
        ItemStack fuel = pool.take(stack -> stack.is(fuelItem) && stack.getBurnTime(recipe.getType()) >= recipe.getCookingTime(), false);
        if (fuel.isEmpty() || !pool.put(output, false) || !pool.put(fuel.getCraftingRemainingItem(), false)) return null;
        for (ItemStack remainder : recipe.getRemainingItems(single)) if (!pool.put(remainder, false)) return null;
        // One whole authored fuel unit is consumed; unused burn time is not invented/persisted as credit.
        return new Plan(pool, null);
    }

    private static Plan food(Pool pool, Map<String,String> p, boolean drink) {
        Item selected = item(id(p, "item_id"));
        ItemStack consumed = pool.take(stack -> stack.is(selected), false);
        if (consumed.isEmpty()) return null;
        ItemStack remainder;
        if (drink && consumed.is(Items.POTION)) remainder = new ItemStack(Items.GLASS_BOTTLE);
        else if (drink && consumed.is(Items.MILK_BUCKET)) remainder = new ItemStack(Items.BUCKET);
        else {
            var food = consumed.get(DataComponents.FOOD);
            if (food == null || drink != (consumed.getUseAnimation() == UseAnim.DRINK)) return null;
            // Unknown finishUsingItem overrides are not silently replaced with generic food effects.
            // In particular chorus fruit teleportation needs its own movement/protection contract.
            Class<?> type=consumed.getItem().getClass();
            if (!(type==Item.class
                    || type==HoneyBottleItem.class || type==SuspiciousStewItem.class)) return null;
            remainder = consumed.is(Items.HONEY_BOTTLE) ? new ItemStack(Items.GLASS_BOTTLE)
                    : food.usingConvertsTo().map(ItemStack::copy).orElse(ItemStack.EMPTY);
        }
        if (!pool.put(remainder, false)) return null;
        return new Plan(pool, consumed);
    }

    private static void consumeEffects(GodAvatarEntity avatar, ItemStack consumed) {
        if (consumed.is(Items.MILK_BUCKET)) avatar.removeEffectsCuredBy(EffectCures.MILK);
        else if (consumed.is(Items.POTION)) consumed.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .forEachEffect(effect -> apply(avatar, effect));
        else {
            var food = consumed.get(DataComponents.FOOD);
            if (food != null) for (var effect : food.effects()) if (avatar.getRandom().nextFloat() < effect.probability()) apply(avatar, effect.effect());
            if (consumed.is(Items.HONEY_BOTTLE)) avatar.removeEffectsCuredBy(EffectCures.HONEY);
            var stew=consumed.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
            if (consumed.is(Items.SUSPICIOUS_STEW) && stew!=null) for(var effect:stew.effects()) avatar.addEffect(effect.createEffectInstance());
            // NPCs have no Player FoodData; do not invent player hunger, healing or progression.
        }
        avatar.gameEvent(consumed.getUseAnimation() == UseAnim.DRINK ? GameEvent.DRINK : GameEvent.EAT);
    }
    private static void apply(GodAvatarEntity avatar, MobEffectInstance effect) {
        if (effect.getEffect().value().isInstantenous()) effect.getEffect().value().applyInstantenousEffect(avatar, avatar, avatar, effect.getAmplifier(), 1);
        else avatar.addEffect(new MobEffectInstance(effect));
    }

    private static Plan offering(Pool pool, Map<String,String> p) {
        Item selected = item(id(p, "item_id")); int count = Integer.parseInt(required(p, "count"));
        if (count < 1 || count > 64) return null;
        for (int i = 0; i < count; i++) {
            ItemStack unit = pool.take(stack -> stack.is(selected), true);
            if (unit.isEmpty() || !pool.put(unit, true)) return null;
        }
        return new Plan(pool, null); // A transfer only; no affinity/quest progress/reward minted here.
    }

    private static CropBlock crop(GodAvatarEntity avatar, BlockPos site, Map<String,String> p) {
        ServerLevel level = (ServerLevel)avatar.level(); BlockState state = level.getBlockState(site);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) return null;
        if (!(crop.getClass()==CropBlock.class || crop.getClass()==CarrotBlock.class || crop.getClass()==PotatoBlock.class || crop.getClass()==BeetrootBlock.class)) return null;
        if (p.containsKey("crop_id") && !BuiltInRegistries.BLOCK.getKey(crop).equals(id(p, "crop_id"))) return null;
        return crop;
    }
    private static Outcome farm(GodAvatarEntity avatar, BlockPos site, Map<String,String> p) {
        CropBlock crop = crop(avatar, site, p); if (crop == null) return new Outcome(false, "CROP_NOT_MATURE");
        ServerLevel level = (ServerLevel)avatar.level();
        if (!net.neoforged.neoforge.event.EventHooks.canEntityGrief(level, avatar)) return new Outcome(false, "MOB_GRIEFING_DENIED");
        BlockState original = level.getBlockState(site), replanted = crop.getStateForAge(0);
        if (!NpcActivityAccess.canUse(avatar, site.below()) || !level.hasChunkAt(site.below()) || !replanted.canSurvive(level, site)) return new Outcome(false, "CANNOT_REPLANT");
        ItemStack seed = crop.getCloneItemStack(level, site, original);
        if (seed.isEmpty()) return new Outcome(false, "NO_SEED_RULE");
        var drops = Block.getDrops(original, level, site, null, avatar, ItemStack.EMPTY);
        if (drops.size() > 64) return new Outcome(false, "LOOT_TOO_LARGE");
        Pool pool = new Pool(avatar, site); boolean seeded = false;
        for (ItemStack drop : drops) {
            ItemStack remaining = drop.copy();
            if (!seeded && ItemStack.isSameItemSameComponents(remaining, seed)) { remaining.shrink(1); seeded = true; }
            if (!pool.put(remaining, false)) return new Outcome(false, "HARVEST_STORAGE_FULL");
        }
        if (!seeded && pool.take(stack -> ItemStack.isSameItemSameComponents(stack, seed), false).isEmpty()) return new Outcome(false, "REPLANT_SEED_MISSING");
        if (!pool.current() || !level.getBlockState(site).equals(original)) return new Outcome(false, "HARVEST_CHANGED");
        // Replace, do not destroy-and-spawn: loot is stored once, never also emitted as item entities.
        if (!level.setBlock(site, replanted, 2)) return new Outcome(false, "REPLANT_FAILED");
        if (!pool.commit()) {
            // Never restore a harvest whose credited items could not be recalled after foreign re-entry.
            if (pool.rollbackComplete && guard(avatar, site) && level.getBlockState(site).equals(replanted)) level.setBlock(site, original, 2);
            return new Outcome(false,pool.rollbackComplete ? "HARVEST_COMMIT_FAILED" : "EXTERNAL_REENTRY_INTERRUPTED_HARVEST_NO_REFUND");
        }
        level.sendBlockUpdated(site, original, replanted, 3);
        return new Outcome(true, "HARVESTED_AND_REPLANTED");
    }

    private static boolean guard(GodAvatarEntity avatar, BlockPos site) {
        return avatar != null && site != null && avatar.level() instanceof ServerLevel level && level.getServer().isSameThread()
                && avatar.isAlive() && avatar.hasAuthoritativeBinding() && level.hasChunkAt(site)
                && avatar.distanceToSqr(site.getX()+0.5, site.getY()+0.5, site.getZ()+0.5) <= 25
                && NpcActivityAccess.canUse(avatar, site);
    }
    private static ResourceLocation id(Map<String,String> p, String key) { return ResourceLocation.parse(required(p, key)); }
    private static String required(Map<String,String> p, String key) { String value = p.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key); return value; }
    private static Item item(ResourceLocation id) { if (!BuiltInRegistries.ITEM.containsKey(id) || id.equals(ResourceLocation.parse("minecraft:air"))) throw new IllegalArgumentException("unknown item"); return BuiltInRegistries.ITEM.get(id); }
    private record Plan(Pool pool, ItemStack consumed) { }

    /** Copies a bounded set of vanilla storage slots; arbitrary mod IItemHandler semantics are not impersonated. */
    private static final class Pool {
        final GodAvatarEntity avatar; final ServerLevel level; final BlockPos site; final List<Store> stores = new ArrayList<>();
        boolean rollbackComplete=true;
        Pool(GodAvatarEntity avatar, BlockPos site) {
            this.avatar = avatar; this.level = (ServerLevel)avatar.level(); this.site = site.immutable();
            if (!guard(avatar, site)) throw new IllegalArgumentException("unavailable");
            stores.add(new Store(avatar.activityInventory(), null));
            for (BlockPos pos : BlockPos.betweenClosed(site.offset(-2,-2,-2), site.offset(2,2,2))) {
                if (stores.size() >= 9) break;
                if (!level.hasChunkAt(pos) || !NpcActivityAccess.canUse(avatar, pos)) continue;
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null || !(be.getClass() == ChestBlockEntity.class || be.getClass() == BarrelBlockEntity.class || be.getClass() == ShulkerBoxBlockEntity.class)) continue;
                var storage = (RandomizableContainerBlockEntity)be;
                if (storage.getLootTable() != null) continue; // Availability checks never open/generate loot.
                stores.add(new Store((Container)be, pos.immutable()));
            }
        }
        ItemStack take(Predicate<ItemStack> eligible, boolean worldOnly) {
            for (Store store : stores) {
                if (worldOnly && store.pos == null) continue;
                if (!store.access()) return ItemStack.EMPTY;
                for (ItemStack stack : store.after) if (!stack.isEmpty() && eligible.test(stack)) {
                    ItemStack found = stack.copyWithCount(1); stack.shrink(1); return found;
                }
            }
            return ItemStack.EMPTY;
        }
        boolean put(ItemStack input, boolean npcOnly) {
            if (input.isEmpty()) return true;
            ItemStack left = input.copy();
            if (left.getCount() < 0 || left.getCount() > 4096) return false;
            for (int pass = 0; pass < 2; pass++) for (Store store : stores) {
                if (npcOnly && store.pos != null) continue;
                if (!store.access()) return false;
                for (int slot = 0; slot < store.after.size() && !left.isEmpty(); slot++) {
                    ItemStack existing = store.after.get(slot);
                    if (!store.container.canPlaceItem(slot, left)) continue;
                    if (pass == 0 && !existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, left)) {
                        int n = Math.min(left.getCount(), Math.min(existing.getMaxStackSize(), store.container.getMaxStackSize()) - existing.getCount());
                        if (n > 0) { existing.grow(n); left.shrink(n); }
                    } else if (pass == 1 && existing.isEmpty()) {
                        int n = Math.min(left.getCount(), Math.min(left.getMaxStackSize(), store.container.getMaxStackSize()));
                        store.after.set(slot, left.copyWithCount(n)); left.shrink(n);
                    }
                }
            }
            return left.isEmpty();
        }
        boolean current() {
            if (!guard(avatar, site)) return false;
            for (Store store : stores) if (!store.access() || !store.matches(store.before)) return false;
            return true;
        }
        boolean commit() {
            if (!current()) return false;
            List<SlotWrite> debits=new ArrayList<>(), credits=new ArrayList<>();
            try {
                // Remove every input before exposing any output, including cross-container transfers.
                // Vanilla stores run synchronously; permission predicates must be pure. The journal
                // additionally fails closed on foreign callbacks without refunding retained outputs.
                for (Store store : stores) {
                    for(int slot=0;slot<store.before.size();slot++) {
                        ItemStack before=store.before.get(slot),after=store.after.get(slot);
                        ItemStack middle=ItemStack.isSameItemSameComponents(before,after)
                                ? before.copyWithCount(Math.min(before.getCount(),after.getCount())) : ItemStack.EMPTY;
                        if(!ItemStack.matches(before,middle)) applyWrite(debits,new SlotWrite(store,slot,before,middle));
                    }
                }
                for(Store store:stores) for(int slot=0;slot<store.after.size();slot++) {
                    ItemStack before=store.before.get(slot),after=store.after.get(slot);
                    ItemStack middle=ItemStack.isSameItemSameComponents(before,after)
                            ? before.copyWithCount(Math.min(before.getCount(),after.getCount())) : ItemStack.EMPTY;
                    if(!ItemStack.matches(middle,after)) applyWrite(credits,new SlotWrite(store,slot,middle,after));
                }
                for(Store store:stores) if(!store.matches(store.after))throw new IllegalStateException("storage changed after commit");
                return true;
            } catch (RuntimeException failed) {
                boolean outputsRemoved=restore(credits);
                // A denied/foreign-mutated output cannot be recalled safely. Refunding inputs then
                // would mint items. Report interruption instead of pretending atomic success.
                rollbackComplete=outputsRemoved && restore(debits);
                return false;
            }
        }
        private void applyWrite(List<SlotWrite> journal,SlotWrite write) {
            if(!write.store.access() || !ItemStack.matches(write.store.container.getItem(write.slot),write.before))throw new IllegalStateException("slot changed");
            journal.add(write); // Include a setItem that mutates then invokes a throwing listener.
            write.store.container.setItem(write.slot,write.after.copy());
            if(!write.store.access() || !ItemStack.matches(write.store.container.getItem(write.slot),write.after))throw new IllegalStateException("slot rejected write");
            write.store.container.setChanged();
        }
        private boolean restore(List<SlotWrite> journal) {
            boolean complete=true;
            for(int i=journal.size()-1;i>=0;i--) {
                SlotWrite write=journal.get(i);
                try {
                    if(!write.store.access()){complete=false;continue;}
                    ItemStack actual=write.store.container.getItem(write.slot);
                    if(ItemStack.matches(actual,write.before))continue; // Throw happened before mutation.
                    if(!ItemStack.matches(actual,write.after)){complete=false;continue;}
                    write.store.container.setItem(write.slot,write.before.copy());
                    if(!write.store.access() || !ItemStack.matches(write.store.container.getItem(write.slot),write.before))complete=false;
                    write.store.container.setChanged();
                }catch(RuntimeException foreignCallback){complete=false;}
            }
            return complete;
        }
        private record SlotWrite(Store store,int slot,ItemStack before,ItemStack after) { }
        final class Store {
            final Container container; final BlockPos pos; final List<ItemStack> before = new ArrayList<>(), after = new ArrayList<>();
            Store(Container container, BlockPos pos) {
                this.container = container; this.pos = pos;
                if (!access() || container.getContainerSize() > 54) throw new IllegalArgumentException("storage unavailable");
                for (int i=0; i<container.getContainerSize(); i++) { if(!access())throw new IllegalStateException("access revoked"); ItemStack stack=container.getItem(i); before.add(stack.copy()); after.add(stack.copy()); }
            }
            boolean access() { return guard(avatar, site) && (pos == null || level.hasChunkAt(pos) && NpcActivityAccess.canUse(avatar,pos) && level.getBlockEntity(pos) == container); }
            boolean matches(List<ItemStack> items) {
                if (!access() || container.getContainerSize()!=items.size()) return false;
                for (int i=0; i<items.size(); i++) if (!ItemStack.matches(container.getItem(i),items.get(i))) return false;
                return true;
            }
        }
    }
}
