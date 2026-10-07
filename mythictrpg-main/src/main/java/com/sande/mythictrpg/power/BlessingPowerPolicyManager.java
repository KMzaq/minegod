package com.sande.mythictrpg.power;

import com.google.gson.JsonParser;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.io.Reader;

/** One datapack-overridable score table. Invalid reloads never silently retain old scores. */
public final class BlessingPowerPolicyManager extends SimplePreparableReloadListener<BlessingPowerPolicyManager.Loaded> {
    public static final BlessingPowerPolicyManager INSTANCE = new BlessingPowerPolicyManager();
    public record Loaded(BlessingPowerPolicy policy, String reason) { }
    private volatile Loaded current = new Loaded(null, "BLESSING_POWER_NOT_LOADED");
    public Loaded current() { return current; }
    @Override protected Loaded prepare(ResourceManager resources, ProfilerFiller profiler) {
        var converter = FileToIdConverter.json("mythictrpg/blessing_power");
        var files = converter.listMatchingResources(resources);
        if (files.size() != 1) return new Loaded(null, files.isEmpty() ? "BLESSING_POWER_MISSING" : "MULTIPLE_BLESSING_POWER_TABLES");
        var entry = files.entrySet().iterator().next();
        try (Reader reader = entry.getValue().openAsReader()) {
            StringBuilder text = new StringBuilder(); char[] buffer = new char[4096]; int count;
            while ((count = reader.read(buffer)) != -1) {
                if (text.length() + count > 262144) throw new IllegalArgumentException("Score table too large");
                text.append(buffer, 0, count);
            }
            return new Loaded(BlessingPowerPolicy.parse(converter.fileToId(entry.getKey()).toString(),
                    JsonParser.parseString(text.toString()).getAsJsonObject()), "READY");
        } catch (Exception invalid) { return new Loaded(null, "INVALID_BLESSING_POWER:" + invalid.getClass().getSimpleName()); }
    }
    @Override protected void apply(Loaded loaded, ResourceManager resources, ProfilerFiller profiler) { current = loaded; }
}
