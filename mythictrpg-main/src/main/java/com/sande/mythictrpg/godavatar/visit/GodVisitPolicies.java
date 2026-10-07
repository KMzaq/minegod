package com.sande.mythictrpg.godavatar.visit;

import com.google.gson.JsonParser;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.util.*;

/** Atomic optional datapack catalog; the resource ID is the existing canonical God ID. */
public final class GodVisitPolicies extends SimplePreparableReloadListener<Map<ResourceLocation,GodVisitPolicy>> {
    public static final GodVisitPolicies INSTANCE = new GodVisitPolicies();
    private static final FileToIdConverter FILES = FileToIdConverter.json("mythictrpg/god_visits");
    private Map<ResourceLocation,GodVisitPolicy> policies = Map.of();
    private long generation;
    private GodVisitPolicies() { }
    public Map<ResourceLocation,GodVisitPolicy> snapshot() { return policies; }
    public long generation() { return generation; }
    public void onReload(AddReloadListenerEvent e) { e.addListener(this); }
    @Override protected Map<ResourceLocation,GodVisitPolicy> prepare(ResourceManager manager, ProfilerFiller profiler) {
        var parsed = new LinkedHashMap<ResourceLocation,GodVisitPolicy>();
        for (var entry : FILES.listMatchingResources(manager).entrySet()) {
            try (var reader = entry.getValue().openAsReader()) {
                var god = FILES.fileToId(entry.getKey());
                parsed.put(god, GodVisitPolicy.decode(god, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception invalid) { throw new IllegalArgumentException("Invalid God visit policy " + entry.getKey(), invalid); }
        }
        return Map.copyOf(parsed);
    }
    @Override protected void apply(Map<ResourceLocation,GodVisitPolicy> values, ResourceManager manager, ProfilerFiller profiler) {
        policies = values; generation++;
    }
}
