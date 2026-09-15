package com.sande.mythictrpg.shop;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ShopDefinition(ResourceLocation id, ShopType type, String displayName,
        List<ShopProductDefinition> products) {
    public ShopDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        products = List.copyOf(products);
        if (displayName.isBlank() || displayName.codePointCount(0, displayName.length()) > 80) {
            throw new IllegalArgumentException("Invalid shop display name");
        }
        if (products.size() > 256) throw new IllegalArgumentException("A shop may contain at most 256 products");
        Map<ResourceLocation, Boolean> ids = new LinkedHashMap<>();
        for (ShopProductDefinition product : products) {
            if (ids.putIfAbsent(product.id(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Duplicate shop product " + product.id());
            }
        }
    }

    public Optional<ShopProductDefinition> product(ResourceLocation productId) {
        return products.stream().filter(product -> product.id().equals(productId)).findFirst();
    }
}
