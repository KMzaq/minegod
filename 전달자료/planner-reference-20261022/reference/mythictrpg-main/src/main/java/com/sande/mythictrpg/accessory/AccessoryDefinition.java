package com.sande.mythictrpg.accessory;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.*;

public record AccessoryDefinition(ResourceLocation id, String name, ResourceLocation itemId, CompoundTag components,
        double fixedPower, List<AccessoryEffect> effects) {
    public AccessoryDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(itemId); components = Objects.requireNonNull(components).copy();
        effects = List.copyOf(effects);
        if (name == null || name.isBlank() || name.length() > 128 || !Double.isFinite(fixedPower) || fixedPower < 0
                || fixedPower > 1_000_000 || effects.isEmpty() || effects.size() > 12
                || effects.stream().map(AccessoryEffect::kind).distinct().count() != effects.size())
            throw new IllegalArgumentException("Invalid accessory definition");
    }
    @Override public CompoundTag components() { return components.copy(); }
    public ItemStack displayStack(HolderLookup.Provider registries) { return new NpcRewardEntry(itemId,1,components).createStack(registries); }
    public Optional<AccessoryEffect> effect(AccessoryEffect.Kind kind) { return effects.stream().filter(e -> e.kind()==kind).findFirst(); }
    public static AccessoryDefinition parse(ResourceLocation id, JsonObject json) {
        fields(json,"schemaVersion","name","itemId","components","fixedPower","effects");
        if (integer(json,"schemaVersion")!=1) throw new IllegalArgumentException("Unsupported accessory schema");
        var componentJson = json.getAsJsonObject("components");
        if (componentJson == null || componentJson.toString().length()>16384) throw new IllegalArgumentException("Missing/bounded accessory components");
        var rows=json.getAsJsonArray("effects"); if(rows==null||rows.size()>12)throw new IllegalArgumentException("Accessory effects required");
        List<AccessoryEffect> effects=new ArrayList<>();
        for(var element:rows) {
            JsonObject row=element.getAsJsonObject(); var kind=AccessoryEffect.Kind.valueOf(string(row,"type"));
            Set<String> allowed=Set.of("type","amount","durationTicks","cooldownSeconds");
            if(!allowed.containsAll(row.keySet())||!row.has("amount"))throw new IllegalArgumentException("Unknown/missing accessory effect fields");
            long cooldown=row.has("cooldownSeconds")?integer(row,"cooldownSeconds"):switch(kind){case CLEANSE_CC->300;case EMERGENCY_HEAL->86400;default->0;};
            effects.add(new AccessoryEffect(kind,number(row,"amount"),row.has("durationTicks")?integer(row,"durationTicks"):0,Math.multiplyExact(cooldown,1000)));
        }
        return new AccessoryDefinition(id,string(json,"name"),namespaced(string(json,"itemId")),
                (CompoundTag)JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE,componentJson),number(json,"fixedPower"),effects);
    }
    private static void fields(JsonObject j,String...names){if(!j.keySet().equals(Set.of(names)))throw new IllegalArgumentException("Missing/unknown accessory fields");}
    private static String string(JsonObject j,String k){var v=j.get(k);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()||v.getAsString().isBlank())throw new IllegalArgumentException("Expected accessory string "+k);return v.getAsString();}
    private static double number(JsonObject j,String k){var v=j.get(k);if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||!Double.isFinite(v.getAsDouble()))throw new IllegalArgumentException("Expected accessory number "+k);return v.getAsDouble();}
    private static int integer(JsonObject j,String k){number(j,k);return j.get(k).getAsBigDecimal().intValueExact();}
    private static ResourceLocation namespaced(String value){if(!value.contains(":"))throw new IllegalArgumentException("Namespaced accessory item required");return ResourceLocation.parse(value);}
}
