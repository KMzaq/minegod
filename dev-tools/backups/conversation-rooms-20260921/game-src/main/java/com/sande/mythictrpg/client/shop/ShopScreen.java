package com.sande.mythictrpg.client.shop;

import com.sande.mythictrpg.network.ShopCatalogPayload;
import com.sande.mythictrpg.network.ShopTransactionPayload;
import com.sande.mythictrpg.shop.ShopTransactionService;
import com.sande.mythictrpg.shop.ShopType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ShopScreen extends Screen {
    private static final int ROWS_PER_PAGE = 5;
    private final ShopCatalogPayload payload;
    private final Map<Integer, Integer> quantities = new HashMap<>();
    private int page;

    public ShopScreen(ShopCatalogPayload payload) {
        super(Component.translatable(payload.shopType() == ShopType.BUY
                ? "screen.mythictrpg.shop.buy" : "screen.mythictrpg.shop.sell"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int start = page * ROWS_PER_PAGE;
        int end = Math.min(payload.products().size(), start + ROWS_PER_PAGE);
        int startY = 52;
        for (int index = start; index < end; index++) {
            int productIndex = index;
            ShopCatalogPayload.Product product = payload.products().get(index);
            int row = index - start;
            int y = startY + row * 38;
            int quantity = quantities.getOrDefault(index, 1);
            addRenderableWidget(Button.builder(Component.literal("-"), button -> changeQuantity(productIndex, -1))
                    .bounds(width / 2 + 36, y + 8, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"), button -> changeQuantity(productIndex, 1))
                    .bounds(width / 2 + 82, y + 8, 20, 20).build());
            String action = payload.shopType() == ShopType.BUY ? "구매" : "판매";
            long total = safeMultiply(product.price(), quantity);
            addRenderableWidget(Button.builder(Component.literal(action + " " + total + "G"),
                    button -> transact(product, productIndex)).bounds(width / 2 + 110, y + 8, 100, 20).build());
        }
        int pages = pageCount();
        if (page > 0) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                page--;
                rebuild();
            }).bounds(width / 2 - 55, Math.min(height - 32, 250), 24, 20).build());
        }
        if (page + 1 < pages) {
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                page++;
                rebuild();
            }).bounds(width / 2 + 31, Math.min(height - 32, 250), 24, 20).build());
        }
    }

    private void changeQuantity(int index, int delta) {
        int value = quantities.getOrDefault(index, 1);
        int maximum = ShopTransactionService.MAX_LOTS_PER_REQUEST;
        ShopCatalogPayload.Product product = payload.products().get(index);
        if (payload.shopType() == ShopType.SELL) maximum = Math.min(maximum, Math.max(1, product.availableLots()));
        quantities.put(index, Math.max(1, Math.min(maximum, value + delta)));
        rebuild();
    }

    private void transact(ShopCatalogPayload.Product product, int index) {
        int quantity = quantities.getOrDefault(index, 1);
        PacketDistributor.sendToServer(new ShopTransactionPayload(payload.shopType(),
                product.shopId(), product.productId(), quantity));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal("보유 골드: " + payload.balance()),
                width / 2, 32, 0xFFD76A);
        int start = page * ROWS_PER_PAGE;
        int end = Math.min(payload.products().size(), start + ROWS_PER_PAGE);
        int startY = 52;
        for (int index = start; index < end; index++) {
            ShopCatalogPayload.Product product = payload.products().get(index);
            int y = startY + (index - start) * 38;
            ItemStack item = product.item();
            graphics.renderItem(item, width / 2 - 205, y + 8);
            graphics.renderItemDecorations(font, item, width / 2 - 205, y + 8);
            graphics.drawString(font, product.displayName(), width / 2 - 182, y + 4, 0xFFFFFF);
            String detail = product.shopName() + " · " + product.price() + "G/묶음";
            if (payload.shopType() == ShopType.SELL) detail += " · 보유 " + product.availableLots() + "묶음";
            graphics.drawString(font, detail, width / 2 - 182, y + 18, 0xAAAAAA);
            graphics.drawCenteredString(font, Component.literal(String.valueOf(quantities.getOrDefault(index, 1))),
                    width / 2 + 69, y + 14, 0xFFFFFF);
        }
        if (payload.products().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.mythictrpg.shop.empty"),
                    width / 2, height / 2, 0xAAAAAA);
        }
        graphics.drawCenteredString(font, Component.literal((page + 1) + " / " + pageCount()),
                width / 2, Math.min(height - 27, 255), 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
        for (int index = start; index < end; index++) {
            int y = startY + (index - start) * 38;
            if (mouseX >= width / 2 - 208 && mouseX < width / 2 - 188 && mouseY >= y + 6 && mouseY < y + 28) {
                graphics.renderTooltip(font, payload.products().get(index).item(), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int pageCount() {
        return Math.max(1, (payload.products().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    private static long safeMultiply(long price, int quantity) {
        try {
            return Math.multiplyExact(price, (long) quantity);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
