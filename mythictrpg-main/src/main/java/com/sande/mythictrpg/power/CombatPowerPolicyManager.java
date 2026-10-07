package com.sande.mythictrpg.power;

import com.google.gson.JsonParser;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.io.Reader;

/** One explicitly authored world policy; missing/ambiguous/invalid policies disable assessment, never invent defaults. */
public final class CombatPowerPolicyManager extends SimplePreparableReloadListener<CombatPowerPolicyManager.Loaded> {
    public static final CombatPowerPolicyManager INSTANCE = new CombatPowerPolicyManager();
    public record Loaded(CombatPowerPolicy policy, String reason) { }
    private volatile Loaded current = new Loaded(null, "POLICY_NOT_AUTHORED");
    public Loaded current() { return current; }
    @Override protected Loaded prepare(ResourceManager resources, ProfilerFiller profiler) {
        var converter = FileToIdConverter.json("mythictrpg/combat_power");
        var files = converter.listMatchingResources(resources);
        if (files.size() != 1) return new Loaded(null, files.isEmpty() ? "POLICY_NOT_AUTHORED" : "MULTIPLE_POWER_POLICIES");
        var entry = files.entrySet().iterator().next();
        try (Reader reader = entry.getValue().openAsReader()) {
            StringBuilder text = new StringBuilder(); char[] buffer = new char[8192]; int count;
            while ((count = reader.read(buffer)) != -1) { if (text.length() + count > 2_000_000) throw new IllegalArgumentException("Policy size"); text.append(buffer, 0, count); }
            return new Loaded(CombatPowerPolicy.parse(converter.fileToId(entry.getKey()).toString(), JsonParser.parseString(text.toString()).getAsJsonObject()), "READY");
        } catch (Exception invalid) { return new Loaded(null, "INVALID_POWER_POLICY:" + invalid.getClass().getSimpleName()); }
    }
    @Override protected void apply(Loaded loaded, ResourceManager resources, ProfilerFiller profiler) { current = loaded; }
}
