package com.sande.mythictrpg.quest.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StructureEvaluationPolicyManager extends SimplePreparableReloadListener<Map<ResourceLocation, StructureEvaluationPolicy>> {
    public static final StructureEvaluationPolicyManager INSTANCE = new StructureEvaluationPolicyManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/structure_evaluation_policies");
    private volatile Map<ResourceLocation, StructureEvaluationPolicy> policies = Map.of();
    private StructureEvaluationPolicyManager() {}

    public void onAddReloadListeners(AddReloadListenerEvent event) { event.addListener(this); }
    public Optional<StructureEvaluationPolicy> find(ResourceLocation id) { return Optional.ofNullable(policies.get(id)); }
    public Set<ResourceLocation> ids() { return policies.keySet(); }

    @Override protected Map<ResourceLocation, StructureEvaluationPolicy> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, StructureEvaluationPolicy> parsed = new LinkedHashMap<>(); List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) throw new IllegalStateException("Rejected structure policy reload with " + errors.size() + " error(s)");
        return Map.copyOf(parsed);
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, StructureEvaluationPolicy> destination, List<String> errors) {
        ResourceLocation contentId = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IllegalArgumentException("Root must be an object");
            StructureEvaluationPolicy policy = parsePolicy(contentId, root.getAsJsonObject());
            if (destination.putIfAbsent(contentId, policy) != null) throw new IllegalArgumentException("Duplicate policy ID");
        } catch (Exception exception) {
            errors.add(contentId + ": " + exception.getMessage());
            MythicTrpg.LOGGER.error("Structure evaluation policy {} failed", contentId, exception);
        }
    }

    public static StructureEvaluationPolicy parsePolicy(ResourceLocation contentId, JsonObject json) {
        rejectUnknown(json, Set.of("schemaVersion", "id", "godId", "region", "limits", "buildWeight",
                "environmentWeight", "allowDerived", "allowReuse", "criteria", "visualProfile"), "root");
        if (integer(json, "schemaVersion", "root") != 1) throw new IllegalArgumentException("Unsupported schemaVersion");
        ResourceLocation declared = id(string(json, "id", "root"));
        if (!declared.equals(contentId)) throw new IllegalArgumentException("Policy id must match its file ID");
        JsonObject region = object(json, "region", "root");
        rejectUnknown(region, Set.of("maxWidth", "maxDepth"), "region");
        JsonObject limits = object(json, "limits", "root");
        rejectUnknown(limits, Set.of("maxTrackedBlocks", "minimumPlayerPlacedBlocks", "maxSnapshotCells",
                "maxFloodFillCells", "maxEnvironmentSamples", "environmentHorizontalRadius",
                "environmentVerticalRadius"), "limits");
        JsonArray criteria = array(json, "criteria", "root"); List<StructureEvaluationPolicy.Criterion> parsed = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) parsed.add(parseCriterion(criteria.get(i), i));
        return new StructureEvaluationPolicy(declared, id(string(json, "godId", "root")),
                new StructureEvaluationPolicy.RegionLimit(integer(region, "maxWidth", "region"), integer(region, "maxDepth", "region")),
                new StructureEvaluationPolicy.Limits(integer(limits, "maxTrackedBlocks", "limits"),
                        integer(limits, "minimumPlayerPlacedBlocks", "limits"), integer(limits, "maxSnapshotCells", "limits"),
                        integer(limits, "maxFloodFillCells", "limits"), integer(limits, "maxEnvironmentSamples", "limits"),
                        integer(limits, "environmentHorizontalRadius", "limits"), integer(limits, "environmentVerticalRadius", "limits")),
                integer(json, "buildWeight", "root"), integer(json, "environmentWeight", "root"),
                bool(json, "allowDerived", false), bool(json, "allowReuse", false), parsed,
                parseVisualProfile(json));
    }

    private static Optional<StructureEvaluationPolicy.VisualProfile> parseVisualProfile(JsonObject root) {
        if (!root.has("visualProfile")) return Optional.empty();
        JsonObject json = object(root, "visualProfile", "root");
        rejectUnknown(json, Set.of("preferredStyles", "favoredTypes", "guidance",
                "visualWeight", "minimumConfidence"), "visualProfile");
        return Optional.of(new StructureEvaluationPolicy.VisualProfile(
                requiredStringList(json, "preferredStyles", "visualProfile"),
                stringList(json, "favoredTypes"), string(json, "guidance", "visualProfile"),
                json.has("visualWeight") ? integer(json, "visualWeight", "visualProfile") : 30,
                json.has("minimumConfidence") ? decimal(json, "minimumConfidence", "visualProfile") : 0.68D));
    }

    private static StructureEvaluationPolicy.Criterion parseCriterion(JsonElement raw, int index) {
        if (!raw.isJsonObject()) throw new IllegalArgumentException("criteria[" + index + "] must be an object");
        JsonObject json = raw.getAsJsonObject(); String at = "criteria[" + index + "]";
        rejectUnknown(json, Set.of("id", "scope", "type", "weight", "tag", "blocks", "biomes", "biomeTag",
                "minimum", "target", "maximum", "curve", "components"), at);
        StructureEvaluationPolicy.Scope scope = StructureEvaluationPolicy.Scope.valueOf(string(json, "scope", at).toUpperCase(java.util.Locale.ROOT));
        StructureEvaluationPolicy.Curve curve = json.has("curve")
                ? StructureEvaluationPolicy.Curve.valueOf(string(json, "curve", at).toUpperCase(java.util.Locale.ROOT))
                : StructureEvaluationPolicy.Curve.LINEAR;
        return new StructureEvaluationPolicy.Criterion(string(json, "id", at), scope, string(json, "type", at),
                decimal(json, "weight", at), optionalId(json, "tag"), idSet(json, "blocks"), idSet(json, "biomes"),
                optionalId(json, "biomeTag"), optionalDecimal(json, "minimum", 0.0D),
                optionalDecimal(json, "target", 1.0D), optionalDecimal(json, "maximum", Double.MAX_VALUE),
                curve, stringList(json, "components"));
    }

    @Override protected void apply(Map<ResourceLocation, StructureEvaluationPolicy> prepared, ResourceManager resources, ProfilerFiller profiler) {
        policies = prepared; MythicTrpg.LOGGER.info("Loaded {} structure evaluation policies", policies.size());
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed, String at) { json.keySet().forEach(k -> { if (!allowed.contains(k)) throw new IllegalArgumentException("Unknown field " + at + "." + k); }); }
    private static JsonObject object(JsonObject json, String key, String at) { if (!json.has(key) || !json.get(key).isJsonObject()) throw new IllegalArgumentException("Missing object " + at + "." + key); return json.getAsJsonObject(key); }
    private static JsonArray array(JsonObject json, String key, String at) { if (!json.has(key) || !json.get(key).isJsonArray()) throw new IllegalArgumentException("Missing array " + at + "." + key); return json.getAsJsonArray(key); }
    private static String string(JsonObject json, String key, String at) { if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Missing string " + at + "." + key); String v=json.get(key).getAsString().trim(); if(v.isEmpty()) throw new IllegalArgumentException("Blank string " + at + "." + key); return v; }
    private static int integer(JsonObject json, String key, String at) { double v=decimal(json,key,at); if(v!=(int)v) throw new IllegalArgumentException(at+"."+key+" must be integer"); return (int)v; }
    private static double decimal(JsonObject json, String key, String at) { if(!json.has(key)||!json.get(key).isJsonPrimitive()||!json.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Missing number "+at+"."+key); double v=json.get(key).getAsDouble(); if(!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite number"); return v; }
    private static double optionalDecimal(JsonObject json,String key,double fallback){ return json.has(key)?decimal(json,key,"criterion"):fallback; }
    private static boolean bool(JsonObject json,String key,boolean fallback){ if(!json.has(key)) return fallback; if(!json.get(key).isJsonPrimitive()||!json.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException(key+" must be boolean"); return json.get(key).getAsBoolean(); }
    private static ResourceLocation id(String raw){ ResourceLocation id=ResourceLocation.tryParse(raw); if(id==null||!raw.contains(":")) throw new IllegalArgumentException("Invalid namespaced ID "+raw); return id; }
    private static Optional<ResourceLocation> optionalId(JsonObject json,String key){ return json.has(key)?Optional.of(id(string(json,key,"criterion"))):Optional.empty(); }
    private static Set<ResourceLocation> idSet(JsonObject json,String key){ if(!json.has(key)) return Set.of(); JsonArray a=array(json,key,"criterion"); Set<ResourceLocation> out=new LinkedHashSet<>(); for(JsonElement e:a) out.add(id(e.getAsString())); return Set.copyOf(out); }
    private static List<String> stringList(JsonObject json,String key){ if(!json.has(key)) return List.of(); JsonArray a=array(json,key,"criterion"); List<String> out=new ArrayList<>(); for(JsonElement e:a) out.add(e.getAsString()); return List.copyOf(out); }
    private static List<String> requiredStringList(JsonObject json,String key,String at){ JsonArray a=array(json,key,at); List<String> out=new ArrayList<>(); for(JsonElement e:a){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException(at+"."+key+" must contain strings");out.add(e.getAsString());} return List.copyOf(out); }
}
