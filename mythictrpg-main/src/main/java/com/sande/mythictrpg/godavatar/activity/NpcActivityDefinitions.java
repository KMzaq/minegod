package com.sande.mythictrpg.godavatar.activity;

import com.google.gson.*;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.io.Reader;
import java.util.*;

/** Atomic activity library + per-God allowed repertoire; no duplicate God identity or persona DB. */
public final class NpcActivityDefinitions extends SimplePreparableReloadListener<NpcActivityDefinitions.Data> {
    public static final NpcActivityDefinitions INSTANCE = new NpcActivityDefinitions();
    public record Policy(List<ResourceLocation> activities, boolean autonomous, int radius, int decisionIntervalTicks) {
        public Policy {
            activities = List.copyOf(activities);
            if (activities.isEmpty() || activities.size() > 64 || new HashSet<>(activities).size() != activities.size()
                    || radius < 2 || radius > 16 || decisionIntervalTicks < 100 || decisionIntervalTicks > 24000)
                throw new IllegalArgumentException("Invalid activity policy bounds");
        }
    }
    public record Data(Map<ResourceLocation, NpcActivityDefinition> activities, Map<ResourceLocation, Policy> policies) {
        public Data { activities = Map.copyOf(activities); policies = Map.copyOf(policies); }
    }
    private volatile Data data = new Data(Map.of(), Map.of());
    private long generation;
    public Data data() { return data; }
    public long generation() { return generation; }
    public Optional<Policy> policy(ResourceLocation god) { return Optional.ofNullable(data.policies().get(god)); }
    public Optional<NpcActivityDefinition> find(ResourceLocation id) { return Optional.ofNullable(data.activities().get(id)); }
    public void onReload(net.neoforged.neoforge.event.AddReloadListenerEvent event) { event.addListener(this); }
    @Override protected Data prepare(ResourceManager manager, ProfilerFiller profiler) {
        var activities = new LinkedHashMap<ResourceLocation, NpcActivityDefinition>();
        var policies = new LinkedHashMap<ResourceLocation, Policy>();
        read(manager, "mythictrpg/npc_activities", (id, json) -> activities.put(id, decode(id, json)));
        read(manager, "mythictrpg/god_activities", (id, json) -> policies.put(id, decodePolicy(json)));
        for (var p : policies.values()) for (var id : p.activities()) if (!activities.containsKey(id))
            throw new IllegalArgumentException("Unknown NPC activity " + id);
        return new Data(activities, policies);
    }
    @Override protected void apply(Data prepared, ResourceManager manager, ProfilerFiller profiler) {
        data = prepared; generation++;
    }
    public static NpcActivityDefinition decode(ResourceLocation id, JsonObject j) {
        fields(j, "formatVersion", "kind", "mode", "siteTags", "durationTicks", "parameters"); version(j);
        var params = new LinkedHashMap<String, String>();
        if (j.has("parameters")) {
            if (!j.get("parameters").isJsonObject()) throw new IllegalArgumentException("parameters must be object");
            j.getAsJsonObject("parameters").entrySet().forEach(e -> params.put(e.getKey(), string(e.getValue())));
        }
        var kind = ActivityKind.valueOf(string(j.get("kind")));
        var allowed = new HashSet<>(Set.of("prop", "guidance"));
        allowed.addAll(switch (kind) {
            case EAT, DRINK, REPAIR -> Set.of("item_id", "recipe_id", "grid");
            case CRAFT -> Set.of("recipe_id", "grid");
            case COOK -> Set.of("recipe_id", "input_id", "fuel_id");
            case FARM -> Set.of("crop_id");
            case OFFERING -> Set.of("item_id", "count");
            case RITUAL -> Set.of("template_id");
            case PERFORM -> Set.of("notes", "note_interval_ticks");
            case PLAY -> Set.of("game");
            default -> Set.of();
        });
        if (!allowed.containsAll(params.keySet())) throw new IllegalArgumentException("Unknown activity parameters for " + kind);
        var definition = new NpcActivityDefinition(id, kind, NpcActivityDefinition.Mode.valueOf(string(j.get("mode"))),
                new LinkedHashSet<>(strings(j.get("siteTags"))), integer(j, "durationTicks"), params);
        validateParameters(definition);
        return definition;
    }
    private static void validateParameters(NpcActivityDefinition definition) {
        var p = definition.parameters();
        for (String key : List.of("prop", "item_id", "recipe_id", "input_id", "fuel_id", "crop_id", "template_id"))
            if (p.containsKey(key)) id(p.get(key));
        if (p.containsKey("grid")) {
            String[] cells=p.get("grid").split(",",-1);
            if(cells.length!=9)throw new IllegalArgumentException("Craft grid requires exactly nine cells");
            boolean ingredient=false;
            for(String cell:cells)if(!cell.equals("_")) {
                var value=id(cell);
                if(value.equals(ResourceLocation.parse("minecraft:air")))throw new IllegalArgumentException("Use _ for empty grid cell");
                ingredient=true;
            }
            if(!ingredient)throw new IllegalArgumentException("Craft grid requires an ingredient");
        }
        if(p.containsKey("count"))parameterInteger(p,"count",1,64);
        if(p.containsKey("note_interval_ticks"))parameterInteger(p,"note_interval_ticks",4,200);
        if(p.containsKey("notes")) {
            String[] notes=p.get("notes").split(",",-1);
            if(notes.length<1 || notes.length>64)throw new IllegalArgumentException("At most 64 notes");
            for(String note:notes)canonicalInteger(note,0,24);
        }
        if(p.containsKey("game") && !Set.of("conversation","dice").contains(p.get("game")))
            throw new IllegalArgumentException("Supported games are conversation or dice");
        // The schema key list is shared for compatibility, but eating is never recipe execution.
        if((definition.kind()==ActivityKind.EAT || definition.kind()==ActivityKind.DRINK)
                && (p.containsKey("recipe_id") || p.containsKey("grid")))
            throw new IllegalArgumentException("EAT/DRINK use item_id, not a crafting recipe");
        if(definition.kind()==ActivityKind.CRAFT || definition.kind()==ActivityKind.REPAIR) {
            if(p.containsKey("recipe_id")!=p.containsKey("grid"))throw new IllegalArgumentException("recipe_id and grid must be supplied together");
            if(definition.kind()==ActivityKind.REPAIR && p.containsKey("item_id") && p.containsKey("recipe_id"))
                throw new IllegalArgumentException("Choose item repair or explicit crafting recipe, not both");
        }
        if(definition.mode()!=NpcActivityDefinition.Mode.REAL)return;
        String[] required=switch(definition.kind()) {
            case EAT, DRINK -> new String[]{"item_id"};
            case CRAFT -> new String[]{"recipe_id","grid"};
            case REPAIR -> p.containsKey("recipe_id") ? new String[]{"recipe_id","grid"} : new String[]{"item_id"};
            case COOK -> new String[]{"recipe_id","input_id","fuel_id"};
            case OFFERING -> new String[]{"item_id","count"};
            case RITUAL -> new String[]{"template_id"};
            default -> new String[0];
        };
        for(String key:required)if(!p.containsKey(key))throw new IllegalArgumentException("REAL "+definition.kind()+" requires "+key);
    }
    private static int parameterInteger(Map<String,String> p,String key,int min,int max) {
        return canonicalInteger(p.get(key),min,max);
    }
    private static int canonicalInteger(String text,int min,int max) {
        try {
            int value=Integer.parseInt(text);
            if(value<min || value>max || !Integer.toString(value).equals(text))throw new IllegalArgumentException("Canonical bounded integer required");
            return value;
        }catch(NumberFormatException invalid){throw new IllegalArgumentException("Canonical bounded integer required",invalid);}
    }
    public static Policy decodePolicy(JsonObject j) {
        fields(j, "formatVersion", "activities", "autonomous", "radius", "decisionIntervalTicks"); version(j);
        var auto = j.get("autonomous");
        if (auto == null || !auto.isJsonPrimitive() || !auto.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("autonomous must be boolean");
        return new Policy(strings(j.get("activities")).stream().map(NpcActivityDefinitions::id).toList(),
                auto.getAsBoolean(), integer(j, "radius"), integer(j, "decisionIntervalTicks"));
    }
    public static ResourceLocation id(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null || id.getPath().isEmpty() || id.getNamespace().isEmpty() || !id.toString().equals(value)) throw new IllegalArgumentException("Canonical resource ID required");
        return id;
    }
    private static void read(ResourceManager rm, String path, java.util.function.BiConsumer<ResourceLocation, JsonObject> consumer) {
        var converter = FileToIdConverter.json(path);
        for (var entry : converter.listMatchingResources(rm).entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                consumer.accept(converter.fileToId(entry.getKey()), JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) { throw new IllegalArgumentException("Rejected NPC activity reload " + entry.getKey(), e); }
        }
    }
    private static void fields(JsonObject j, String... allowed) {
        if (!Set.of(allowed).containsAll(j.keySet())) throw new IllegalArgumentException("Unknown activity field");
    }
    private static void version(JsonObject j) { if (integer(j, "formatVersion") != 1) throw new IllegalArgumentException("Unsupported activity format"); }
    private static String string(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        return e.getAsString();
    }
    private static List<String> strings(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() > 64) throw new IllegalArgumentException("Expected bounded list");
        return e.getAsJsonArray().asList().stream().map(NpcActivityDefinitions::string).toList();
    }
    private static int integer(JsonObject j, String field) {
        var e = j.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer " + field);
        double n = e.getAsDouble();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < 0 || n > 100000) throw new IllegalArgumentException("Integer out of range");
        return (int) n;
    }
    private NpcActivityDefinitions() { }
}
