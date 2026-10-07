package com.sande.mythictrpg.quest;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Server-captured assignment place or explicitly authored return area; never inferred on load. */
public record QuestContactLocation(ResourceLocation dimension, int x, int y, int z, double radius) {
    public QuestContactLocation {
        java.util.Objects.requireNonNull(dimension);
        if (!Double.isFinite(radius) || radius < 1 || radius > 64)
            throw new IllegalArgumentException("Quest contact radius must be 1..64");
    }
    public static QuestContactLocation capture(ServerPlayer player) {
        var pos = player.blockPosition();
        return new QuestContactLocation(player.level().dimension().location(), pos.getX(), pos.getY(), pos.getZ(),
                com.sande.mythictrpg.ai.room.ConversationRoomLedger.MOBILE_RADIUS_BLOCKS);
    }
    public boolean contains(ServerPlayer player) {
        return dimension.equals(player.level().dimension().location())
                && player.distanceToSqr(x + .5, y + .5, z + .5) <= radius * radius;
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("dimension", dimension.toString());
        tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z); tag.putDouble("radius", radius); return tag;
    }
    public static QuestContactLocation load(CompoundTag tag) {
        for (String field : java.util.List.of("x", "y", "z"))
            if (!tag.contains(field, Tag.TAG_INT)) throw new IllegalArgumentException("Missing integer contact location " + field);
        if (!tag.contains("radius", Tag.TAG_ANY_NUMERIC) || !tag.contains("dimension", Tag.TAG_STRING)
                || !tag.getString("dimension").contains(":")) throw new IllegalArgumentException("Missing contact dimension/radius");
        return new QuestContactLocation(ResourceLocation.parse(tag.getString("dimension")),
                tag.getInt("x"), tag.getInt("y"), tag.getInt("z"), tag.getDouble("radius"));
    }
    public static QuestContactLocation parse(JsonObject json) {
        if (!json.keySet().equals(java.util.Set.of("dimension", "x", "y", "z", "radius")))
            throw new IllegalArgumentException("Return location requires dimension/x/y/z/radius only");
        if (!json.get("dimension").isJsonPrimitive() || !json.getAsJsonPrimitive("dimension").isString()
                || !json.get("dimension").getAsString().contains(":"))
            throw new IllegalArgumentException("Return dimension requires a namespaced string ID");
        for (String field : java.util.List.of("x", "y", "z", "radius"))
            if (!json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isNumber())
                throw new IllegalArgumentException("Return location " + field + " requires a JSON number");
        return new QuestContactLocation(ResourceLocation.parse(json.get("dimension").getAsString()),
                json.get("x").getAsBigDecimal().intValueExact(), json.get("y").getAsBigDecimal().intValueExact(),
                json.get("z").getAsBigDecimal().intValueExact(), json.get("radius").getAsDouble());
    }
}
