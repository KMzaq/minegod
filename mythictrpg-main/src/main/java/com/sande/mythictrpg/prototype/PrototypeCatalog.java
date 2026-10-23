package com.sande.mythictrpg.prototype;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** First combat prototype identities. These are authored variants, not new item registry entries. */
public final class PrototypeCatalog {
    public static final ResourceLocation STEADY_BLADE = id("steady_blade");
    public static final ResourceLocation WAVE_BLADE = id("wave_blade");
    public static final ResourceLocation GUARD_BLADE = id("guard_blade");
    public static final ResourceLocation BLADE_WAVE = id("blade_wave");
    public static final ResourceLocation TIMED_PARRY = id("timed_parry");
    public static final ResourceLocation SHORT_DODGE = id("short_dodge");
    public static final ResourceLocation CLEAR_MANA_STONE = id("clear_mana_stone");
    public static final ResourceLocation BLADE_KNOT = id("blade_knot");

    public record ItemSpec(ResourceLocation id, NpcRewardEntry reward) { }

    private PrototypeCatalog() { }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("proto_cbt", path);
    }

    public static List<ItemSpec> items() { return Bundled.ITEMS; }

    public static ItemSpec item(String path) { return item(path.contains(":") ? ResourceLocation.parse(path) : id(path)); }

    public static ItemSpec item(ResourceLocation id) {
        return items().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown prototype item: " + id));
    }

    /** One canonical component source is shared by the reward and the power-policy variant.
     * Loaded once from the built JAR, not the live datapack policy. Component edits require rebuild/restart
     * and a receipt/item migration decision. See docs/BALANCE_TUNING.md before tuning item attributes. */
    private static final class Bundled {
        private static final List<ItemSpec> ITEMS = load();

        private static List<ItemSpec> load() {
            try (var input = PrototypeCatalog.class.getResourceAsStream("/data/mythictrpg/mythictrpg/combat_power/default.json")) {
                if (input == null) throw new IllegalStateException("Missing bundled combat power policy");
                JsonObject policy = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                List<ItemSpec> result = new ArrayList<>();
                for (var value : policy.getAsJsonArray("items")) {
                    JsonObject row = value.getAsJsonObject();
                    var components = row.getAsJsonObject("components");
                    var customData = components.getAsJsonObject("minecraft:custom_data");
                    if (customData == null || !customData.has("prototype_id")) continue;
                    var id = ResourceLocation.parse(customData.get("prototype_id").getAsString());
                    if (!id.getNamespace().equals("proto_cbt")) continue;
                    if (result.stream().anyMatch(entry -> entry.id().equals(id)))
                        throw new IllegalStateException("Duplicate prototype catalog identity: " + id);
                    result.add(new ItemSpec(id, new NpcRewardEntry(ResourceLocation.parse(row.get("itemId").getAsString()), 1,
                            (CompoundTag) JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, components))));
                }
                if (result.size() != 11) throw new IllegalStateException("Incomplete first combat prototype catalog");
                return List.copyOf(result);
            } catch (IOException invalid) {
                throw new IllegalStateException("Unreadable bundled combat prototype catalog", invalid);
            }
        }
    }
}
