package com.sande.mythictrpg.ai.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads the only item, reward, blessing, damage and world-event templates AI may select. */
public final class AiActionTemplateManager extends SimplePreparableReloadListener<Map<ResourceLocation, AiActionTemplate>> {
    public static final AiActionTemplateManager INSTANCE = new AiActionTemplateManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/ai_actions");
    private volatile Map<ResourceLocation, AiActionTemplate> templates = Map.of();

    private AiActionTemplateManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    Optional<AiActionTemplate> find(ResourceLocation templateId, ResourceLocation actionType,
            ResourceLocation godId) {
        AiActionTemplate template = templates.get(templateId);
        return template != null && template.actionType().equals(actionType) && template.godId().equals(godId)
                ? Optional.of(template) : Optional.empty();
    }

    List<AiActionTemplate> templatesFor(ResourceLocation godId) {
        return templates.values().stream().filter(template -> template.godId().equals(godId)).toList();
    }

    @Override
    protected Map<ResourceLocation, AiActionTemplate> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, AiActionTemplate> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected AI action template reload with " + errors.size() + " error(s)");
        }
        return Map.copyOf(parsed);
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, AiActionTemplate> destination, List<String> errors) {
        ResourceLocation id = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root must be an object");
            }
            JsonObject json = root.getAsJsonObject();
            int schema = integer(json, "schemaVersion");
            if (schema != 1) {
                throw new IllegalArgumentException("Unsupported schemaVersion " + schema);
            }
            String type = string(json, "type");
            ResourceLocation godId = id(string(json, "godId"), "godId");
            AiActionTemplate template = switch (type) {
                case "item_request" -> itemRequest(id, godId, json);
                case "reward_proposal" -> reward(id, godId, json);
                case "blessing_offer" -> blessing(id, godId, json);
                case "world_interaction" -> worldEvent(id, godId, json);
                case "player_damage" -> playerDamage(id, godId, json);
                default -> throw new IllegalArgumentException("Unknown AI action template type '" + type + "'");
            };
            if (destination.putIfAbsent(id, template) != null) {
                throw new IllegalArgumentException("Duplicate AI action template ID " + id);
            }
        } catch (Exception exception) {
            errors.add(id + ": " + exception.getMessage());
            MythicTrpg.LOGGER.error("AI action template {} failed", id, exception);
        }
    }

    private static AiActionTemplate itemRequest(ResourceLocation id, ResourceLocation godId, JsonObject json) {
        rejectUnknown(json, Set.of("schemaVersion", "type", "godId", "itemId", "count"));
        ResourceLocation itemId = id(string(json, "itemId"), "itemId");
        if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
            throw new IllegalArgumentException("Unknown requested item " + itemId);
        }
        return new ItemRequestTemplate(id, godId, itemId, integer(json, "count"));
    }

    private static AiActionTemplate reward(ResourceLocation id, ResourceLocation godId, JsonObject json) {
        rejectUnknown(json, Set.of("schemaVersion", "type", "godId", "rewardTableId", "tier"));
        return new RewardProposalTemplate(id, godId,
                id(string(json, "rewardTableId"), "rewardTableId"), integer(json, "tier"));
    }

    private static AiActionTemplate blessing(ResourceLocation id, ResourceLocation godId, JsonObject json) {
        rejectUnknown(json, Set.of("schemaVersion", "type", "godId", "effectId", "durationTicks", "amplifier"));
        ResourceLocation effectId = id(string(json, "effectId"), "effectId");
        if (!BuiltInRegistries.MOB_EFFECT.containsKey(effectId)) {
            throw new IllegalArgumentException("Unknown blessing effect " + effectId);
        }
        return new BlessingOfferTemplate(id, godId, effectId,
                integer(json, "durationTicks"), integer(json, "amplifier"));
    }

    private static AiActionTemplate worldEvent(ResourceLocation id, ResourceLocation godId, JsonObject json) {
        String kind = string(json, "eventType");
        if ("sound".equals(kind)) {
            rejectUnknown(json, Set.of("schemaVersion", "type", "godId", "eventType", "soundId", "volume", "pitch"));
            ResourceLocation soundId = id(string(json, "soundId"), "soundId");
            if (!BuiltInRegistries.SOUND_EVENT.containsKey(soundId)) {
                throw new IllegalArgumentException("Unknown sound event " + soundId);
            }
            return new WorldInteractionTemplate(id, godId, WorldInteractionTemplate.EventKind.SOUND,
                    soundId, 0, 0.0D, 0.0D, decimal(json, "volume").floatValue(),
                    decimal(json, "pitch").floatValue());
        }
        if ("particle".equals(kind)) {
            rejectUnknown(json, Set.of("schemaVersion", "type", "godId", "eventType", "particleId", "count", "spread", "speed"));
            ResourceLocation particleId = id(string(json, "particleId"), "particleId");
            if (!BuiltInRegistries.PARTICLE_TYPE.containsKey(particleId)
                    || !(BuiltInRegistries.PARTICLE_TYPE.get(particleId) instanceof SimpleParticleType)) {
                throw new IllegalArgumentException("Only registered simple particle types are allowed: " + particleId);
            }
            return new WorldInteractionTemplate(id, godId, WorldInteractionTemplate.EventKind.PARTICLE,
                    particleId, integer(json, "count"), decimal(json, "spread"),
                    decimal(json, "speed"), 0.0F, 1.0F);
        }
        throw new IllegalArgumentException("World eventType must be 'sound' or 'particle'");
    }

    private static AiActionTemplate playerDamage(ResourceLocation id, ResourceLocation godId, JsonObject json) {
        PlayerDamageTemplate.DamageMode mode;
        try {
            mode = PlayerDamageTemplate.DamageMode.valueOf(string(json, "damageMode")
                    .toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "damageMode must be flat, max_health_fraction, current_health_fraction, or lethal");
        }
        Set<String> allowed = mode == PlayerDamageTemplate.DamageMode.LETHAL
                ? Set.of("schemaVersion", "type", "godId", "damageMode", "damageType",
                        "allowDeath", "maxUsesPerSession", "cooldownTicks")
                : Set.of("schemaVersion", "type", "godId", "damageMode", "damageType", "amount",
                        "allowDeath", "maxUsesPerSession", "cooldownTicks");
        rejectUnknown(json, allowed);
        double amount = mode == PlayerDamageTemplate.DamageMode.LETHAL ? 0.0D : decimal(json, "amount");
        return new PlayerDamageTemplate(id, godId, mode,
                id(string(json, "damageType"), "damageType"), amount,
                bool(json, "allowDeath"), integer(json, "maxUsesPerSession"),
                integer(json, "cooldownTicks"));
    }

    @Override
    protected void apply(Map<ResourceLocation, AiActionTemplate> prepared,
            ResourceManager resources, ProfilerFiller profiler) {
        templates = prepared;
        MythicTrpg.LOGGER.info("Loaded {} AI action templates", templates.size());
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown AI action template field '" + field + "'");
            }
        });
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("Missing string '" + key + "'");
        }
        String value = json.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank string '" + key + "'");
        }
        return value;
    }

    private static int integer(JsonObject json, String key) {
        double value = decimal(json, key);
        if (value != (int) value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer");
        }
        return (int) value;
    }

    private static Double decimal(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing number '" + key + "'");
        }
        return json.get(key).getAsDouble();
    }

    private static boolean bool(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("Missing boolean '" + key + "'");
        }
        return json.get(key).getAsBoolean();
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in " + field + ": " + value);
        }
        return parsed;
    }
}
