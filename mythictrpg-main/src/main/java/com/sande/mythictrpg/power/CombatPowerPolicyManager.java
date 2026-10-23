package com.sande.mythictrpg.power;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.equipment.EquipmentCatalog;
import com.sande.mythictrpg.equipment.EquipmentDefinition;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.io.Reader;

/** One authored world policy overrides the bundled, explicitly provisional v2 starting model. */
public final class CombatPowerPolicyManager extends SimplePreparableReloadListener<CombatPowerPolicyManager.Loaded> {
    public static final CombatPowerPolicyManager INSTANCE = new CombatPowerPolicyManager();
    public record Loaded(CombatPowerPolicy policy,String reason,java.util.Map<net.minecraft.resources.ResourceLocation,EquipmentDefinition> equipmentDefinitions) {
        public Loaded(CombatPowerPolicy policy,String reason){this(policy,reason,java.util.Map.of());}
        public Loaded {
            var ordered=new java.util.LinkedHashMap<net.minecraft.resources.ResourceLocation,EquipmentDefinition>();
            if(policy!=null)equipmentDefinitions.entrySet().stream().sorted(java.util.Comparator.comparing(e->e.getKey().toString()))
                    .forEach(entry->ordered.put(entry.getKey(),entry.getValue()));
            equipmentDefinitions=java.util.Collections.unmodifiableMap(ordered);
        }
    }
    private volatile Loaded current = new Loaded(null, "POLICY_NOT_AUTHORED");
    public Loaded current() { return current; }
    /** Each reload captures its own immutable lookup; no pending reload can replace another's registry context. */
    public SimplePreparableReloadListener<Loaded> forRegistries(HolderLookup.Provider registries) {
        java.util.Objects.requireNonNull(registries);
        return new SimplePreparableReloadListener<>() {
            @Override protected Loaded prepare(ResourceManager resources,ProfilerFiller profiler) {
                var loaded=CombatPowerPolicyManager.this.prepare(resources,profiler);
                if(loaded.policy()==null)return loaded;
                for(var definition:loaded.equipmentDefinitions().values()) {
                    try {
                        definition.reward().createStack(registries);
                        if(!definition.rule().enchantmentsMatch(registries))throw new IllegalArgumentException("Generated enchantments differ from the power rule");
                    } catch(RuntimeException invalid) {
                        return new Loaded(null,"INVALID_EQUIPMENT_DEFINITION:"+definition.id()+":"+invalid.getClass().getSimpleName());
                    }
                }
                return loaded;
            }
            @Override protected void apply(Loaded loaded,ResourceManager resources,ProfilerFiller profiler) {
                CombatPowerPolicyManager.this.apply(loaded,resources,profiler);
            }
        };
    }
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
            var definitions=EquipmentCatalog.load(resources);
            var policy=CombatPowerPolicy.parse(converter.fileToId(entry.getKey()).toString(),JsonParser.parseString(text.toString()).getAsJsonObject())
                    .withAdditionalItems(definitions.values().stream().map(EquipmentDefinition::rule).toList());
            return new Loaded(policy,"READY",definitions);
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
