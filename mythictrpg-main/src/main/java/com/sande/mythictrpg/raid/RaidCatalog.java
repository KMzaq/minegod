package com.sande.mythictrpg.raid;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Atomic raid + prebuilt-arena reload; invalid references never replace the last valid catalog. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RaidCatalog extends SimplePreparableReloadListener<RaidCatalog.Snapshot> {
    public static final RaidCatalog INSTANCE = new RaidCatalog();
    public record Snapshot(Map<ResourceLocation, RaidDefinition> raids, Map<ResourceLocation, RaidDefinition.Arena> arenas) {
        public Snapshot { raids = Map.copyOf(raids); arenas = Map.copyOf(arenas); }
    }
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    public Snapshot snapshot() { return snapshot; }
    public Optional<RaidDefinition> find(ResourceLocation id) { return Optional.ofNullable(snapshot.raids().get(id)); }
    @SubscribeEvent public static void register(AddReloadListenerEvent event) { event.addListener(INSTANCE); }

    @Override protected Snapshot prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, RaidDefinition.Arena> arenas = new LinkedHashMap<>();
        var ac = FileToIdConverter.json("mythictrpg/raid_arenas");
        for (var resource : ac.listMatchingResources(resources).entrySet()) try (var reader = resource.getValue().openAsReader()) {
            ResourceLocation id = ac.fileToId(resource.getKey());
            arenas.put(id, RaidDefinition.parseArena(id, JsonParser.parseReader(reader).getAsJsonObject()));
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid raid arena " + resource.getKey(), invalid); }
        var list = arenas.values().stream().toList();
        for (int i = 0; i < list.size(); i++) for (int j = i + 1; j < list.size(); j++) {
            var a = list.get(i); var b = list.get(j);
            if (a.dimension().equals(b.dimension()) && a.bounds().inflate(16).intersects(b.bounds()))
                throw new IllegalArgumentException("Raid arenas overlap or lack a 16-block buffer: " + a.id() + ", " + b.id());
        }
        Map<ResourceLocation, RaidDefinition> raids = new LinkedHashMap<>();
        var rc = FileToIdConverter.json("mythictrpg/raids");
        for (var resource : rc.listMatchingResources(resources).entrySet()) try (var reader = resource.getValue().openAsReader()) {
            ResourceLocation id = rc.fileToId(resource.getKey());
            var raid = RaidDefinition.parse(id, JsonParser.parseReader(reader).getAsJsonObject());
            if (!arenas.keySet().containsAll(raid.arenaIds())) throw new IllegalArgumentException("Missing raid arena");
            if (raid.boss().entityType().isPresent()
                    && !BuiltInRegistries.ENTITY_TYPE.containsKey(raid.boss().entityType().orElseThrow()))
                throw new IllegalArgumentException("Unknown raid boss entity type");
            for (var arenaId : raid.arenaIds()) {
                var arena = arenas.get(arenaId);
                for (var phase : raid.phases()) for (var spawn : phase.spawns())
                    if (!BuiltInRegistries.ENTITY_TYPE.containsKey(spawn.entityType()))
                        throw new IllegalArgumentException("Unknown raid phase entity type: " + spawn.entityType());
                    else if (!arena.bounds().deflate(1).contains(arena.bossSpawn().add(spawn.offset())))
                        throw new IllegalArgumentException("Reinforcement spawn is outside " + arenaId);
            }
            raids.put(id, raid);
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid raid " + resource.getKey(), invalid); }
        if (raids.size() > 256 || arenas.size() > 256) throw new IllegalArgumentException("Raid catalog exceeds 256 entries");
        return new Snapshot(raids, arenas);
    }
    @Override protected void apply(Snapshot prepared, ResourceManager resources, ProfilerFiller profiler) {
        snapshot = prepared;
        MythicTrpg.LOGGER.info("Loaded {} raids and {} prebuilt raid arenas", prepared.raids().size(), prepared.arenas().size());
    }
}
