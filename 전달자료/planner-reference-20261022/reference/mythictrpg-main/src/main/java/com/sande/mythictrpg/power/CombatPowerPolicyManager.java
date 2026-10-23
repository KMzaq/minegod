package com.sande.mythictrpg.power;

import com.google.gson.JsonParser;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.io.Reader;

/** One authored world policy overrides the bundled, explicitly provisional v2 starting model. */
public final class CombatPowerPolicyManager extends SimplePreparableReloadListener<CombatPowerPolicyManager.Loaded> {
    public static final CombatPowerPolicyManager INSTANCE = new CombatPowerPolicyManager();
    public record Loaded(CombatPowerPolicy policy, String reason) { }
    private volatile Loaded current = new Loaded(null, "POLICY_NOT_AUTHORED");
    public Loaded current() { return current; }
    @Override protected Loaded prepare(ResourceManager resources, ProfilerFiller profiler) {
        var converter = FileToIdConverter.json("mythictrpg/combat_power");
        var files = new java.util.HashMap<>(converter.listMatchingResources(resources));
        var defaultFile = converter.idToFile(net.minecraft.resources.ResourceLocation.parse("mythictrpg:default"));
        if (files.size() > 1) files.remove(defaultFile);
        if (files.size() != 1) return new Loaded(null, files.isEmpty() ? "POLICY_NOT_AUTHORED" : "MULTIPLE_POWER_POLICIES");
        var entry = files.entrySet().iterator().next();
        try (Reader reader = entry.getValue().openAsReader()) {
            StringBuilder text = new StringBuilder(); char[] buffer = new char[8192]; int count;
            while ((count = reader.read(buffer)) != -1) { if (text.length() + count > 2_000_000) throw new IllegalArgumentException("Policy size"); text.append(buffer, 0, count); }
            return new Loaded(CombatPowerPolicy.parse(converter.fileToId(entry.getKey()).toString(), JsonParser.parseString(text.toString()).getAsJsonObject()), "READY");
        } catch (Exception invalid) { return new Loaded(null, "INVALID_POWER_POLICY:" + invalid.getClass().getSimpleName()); }
    }
    @Override protected void apply(Loaded loaded, ResourceManager resources, ProfilerFiller profiler) { current = loaded; }
    /** Used only for isolated adaptive previews when an authored legacy v1 policy is selected. */
    public static CombatPowerEvaluator.Model initialProposalModel() { return Initial.MODEL; }
    private static final class Initial {
        private static final CombatPowerEvaluator.Model MODEL = load();
        private static CombatPowerEvaluator.Model load() {
            try (var stream = CombatPowerPolicyManager.class.getResourceAsStream("/data/mythictrpg/mythictrpg/combat_power/default.json")) {
                if (stream == null) throw new IllegalStateException("MISSING_INITIAL_POWER_MODEL");
                return CombatPowerPolicy.parse("mythictrpg:default", JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject()).model();
            } catch (java.io.IOException invalid) { throw new IllegalStateException("INVALID_INITIAL_POWER_MODEL", invalid); }
        }
    }
}
