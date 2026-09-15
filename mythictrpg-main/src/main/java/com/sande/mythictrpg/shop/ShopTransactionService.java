package com.sande.mythictrpg.shop;

import com.sande.mythictrpg.data.player.PlayerMythHistoryService;
import com.sande.mythictrpg.economy.CurrencyService;
import com.sande.mythictrpg.economy.CurrencyState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Executes one infinite-stock transaction entirely on the server thread. */
public final class ShopTransactionService {
    public static final ShopTransactionService INSTANCE = new ShopTransactionService();
    public static final int MAX_LOTS_PER_REQUEST = 64;

    private ShopTransactionService() {
    }

    public Result transact(ServerPlayer player, ShopType requestedType, ShopProductKey key, int lots) {
        if (lots < 1 || lots > MAX_LOTS_PER_REQUEST) return Result.reject("수량은 1~64여야 합니다.");
        ShopDefinition shop = ShopCatalogManager.INSTANCE.find(key.shopId()).orElse(null);
        if (shop == null || shop.type() != requestedType) return Result.reject("존재하지 않는 상점입니다.");
        ShopProductDefinition product = shop.product(key.productId()).orElse(null);
        if (product == null) return Result.reject("존재하지 않는 상품입니다.");
        if (!ShopService.INSTANCE.isUnlocked(player.server, shop, product)) {
            return Result.reject("아직 해금되지 않은 상품입니다.");
        }

        ItemStack template;
        try {
            template = product.createStack(player.registryAccess());
        } catch (RuntimeException exception) {
            return Result.reject("상품 아이템 데이터를 해석하지 못했습니다.");
        }
        if (template.isEmpty()) return Result.reject("빈 상품은 거래할 수 없습니다.");

        int totalItems;
        long totalPrice;
        try {
            totalItems = Math.multiplyExact(template.getCount(), lots);
            totalPrice = Math.multiplyExact(product.price(), (long) lots);
        } catch (ArithmeticException exception) {
            return Result.reject("거래 수량 또는 금액이 너무 큽니다.");
        }
        return requestedType == ShopType.BUY
                ? buy(player, template, totalItems, totalPrice)
                : sell(player, template, totalItems, totalPrice);
    }

    public int availableLots(ServerPlayer player, ShopProductDefinition product) {
        try {
            ItemStack template = product.createStack(player.registryAccess());
            if (template.isEmpty()) return 0;
            return countMatching(player.getInventory(), template) / template.getCount();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static Result buy(ServerPlayer player, ItemStack template, int totalItems, long price) {
        CurrencyState currency = CurrencyState.get(player.server);
        if (!currency.isReady()) return Result.reject("화폐 저장 데이터가 읽기 전용입니다.");
        if (currency.balance(player.getUUID()) < price) return Result.reject("골드가 부족합니다.");
        Inventory inventory = player.getInventory();
        if (capacity(inventory, template) < totalItems) return Result.reject("인벤토리 공간이 부족합니다.");
        if (!currency.debit(player.getUUID(), price)) return Result.reject("결제 중 잔액이 변경되었습니다.");
        insert(inventory, template, totalItems);
        player.containerMenu.broadcastChanges();
        PlayerMythHistoryService.recordItemObtained(player, template.copy());
        return Result.success(price, totalItems);
    }

    private static Result sell(ServerPlayer player, ItemStack template, int totalItems, long price) {
        CurrencyState currency = CurrencyState.get(player.server);
        if (!currency.isReady()) return Result.reject("화폐 저장 데이터가 읽기 전용입니다.");
        long balance = currency.balance(player.getUUID());
        if (price > CurrencyState.MAX_BALANCE - balance) return Result.reject("최대 보유 골드를 초과합니다.");
        Inventory inventory = player.getInventory();
        if (countMatching(inventory, template) < totalItems) return Result.reject("판매할 아이템이 부족합니다.");
        remove(inventory, template, totalItems);
        if (!currency.credit(player.getUUID(), price)) {
            insert(inventory, template, totalItems);
            return Result.reject("판매 대금 지급에 실패했습니다.");
        }
        player.containerMenu.broadcastChanges();
        return Result.success(price, totalItems);
    }

    private static int capacity(Inventory inventory, ItemStack template) {
        long capacity = 0L;
        int maximum = template.getMaxStackSize();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty()) capacity += maximum;
            else if (ItemStack.isSameItemSameComponents(current, template)) capacity += maximum - current.getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    private static int countMatching(Inventory inventory, ItemStack template) {
        long total = 0L;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(current, template)) total += current.getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static void insert(Inventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        int maximum = template.getMaxStackSize();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, template)) {
                int moved = Math.min(remaining, maximum - current.getCount());
                current.grow(moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                ItemStack added = template.copy();
                int moved = Math.min(remaining, maximum);
                added.setCount(moved);
                inventory.setItem(slot, added);
                remaining -= moved;
            }
        }
        if (remaining != 0) throw new IllegalStateException("Prevalidated inventory insertion failed");
    }

    private static void remove(Inventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(current, template)) {
                int moved = Math.min(remaining, current.getCount());
                current.shrink(moved);
                remaining -= moved;
                if (current.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        if (remaining != 0) throw new IllegalStateException("Prevalidated inventory removal failed");
    }

    public record Result(boolean succeeded, String reason, long price, int itemCount) {
        static Result success(long price, int itemCount) {
            return new Result(true, "", price, itemCount);
        }

        static Result reject(String reason) {
            return new Result(false, reason, 0L, 0);
        }
    }
}
