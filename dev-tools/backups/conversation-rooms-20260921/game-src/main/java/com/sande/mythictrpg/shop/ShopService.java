package com.sande.mythictrpg.shop;

import com.sande.mythictrpg.economy.CurrencyService;
import com.sande.mythictrpg.network.ShopCatalogPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public final class ShopService {
    public static final ShopService INSTANCE = new ShopService();

    private ShopService() {
    }

    public boolean isUnlocked(MinecraftServer server, ShopDefinition shop, ShopProductDefinition product) {
        return product.unlockedByDefault()
                || GlobalShopState.get(server).isUnlocked(new ShopProductKey(shop.id(), product.id()));
    }

    public boolean unlock(MinecraftServer server, ShopProductKey key) {
        if (ShopCatalogManager.INSTANCE.product(key).isEmpty()) return false;
        return GlobalShopState.get(server).unlock(key);
    }

    public boolean lock(MinecraftServer server, ShopProductKey key) {
        ShopProductDefinition product = ShopCatalogManager.INSTANCE.product(key).orElse(null);
        if (product == null || product.unlockedByDefault()) return false;
        return GlobalShopState.get(server).lock(key);
    }

    public void open(ServerPlayer player, ShopType type) {
        List<ShopCatalogPayload.Product> products = new ArrayList<>();
        for (ShopDefinition shop : ShopCatalogManager.INSTANCE.shops(type)) {
            for (ShopProductDefinition product : shop.products()) {
                if (!isUnlocked(player.server, shop, product)) continue;
                try {
                    ItemStack stack = product.createStack(player.registryAccess());
                    int available = type == ShopType.SELL
                            ? ShopTransactionService.INSTANCE.availableLots(player, product) : -1;
                    products.add(new ShopCatalogPayload.Product(shop.id(), product.id(), shop.displayName(),
                            product.displayName(), stack, product.price(), available));
                } catch (RuntimeException exception) {
                    // A broken component payload disables only this product, not the whole catalog.
                }
            }
        }
        PacketDistributor.sendToPlayer(player, new ShopCatalogPayload(type,
                CurrencyService.INSTANCE.balance(player.server, player.getUUID()), products));
    }
}
