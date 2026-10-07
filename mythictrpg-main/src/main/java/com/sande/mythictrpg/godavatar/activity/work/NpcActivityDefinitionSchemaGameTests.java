package com.sande.mythictrpg.godavatar.activity.work;

import com.google.gson.*;
import com.sande.mythictrpg.godavatar.activity.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.io.Reader;
import java.util.*;

/** Decodes actual loaded bundled resources plus adversarial authoring input, without spawning or calling an LLM. */
@GameTestHolder("mythictrpg_npc_activity_schema")
@PrefixGameTestTemplate(false)
public final class NpcActivityDefinitionSchemaGameTests {
    @GameTest(templateNamespace="mythictrpg_npc_activity_schema",template="empty")
    public static void authoredActivitiesFailClosedAndBundledExamplesResolve(GameTestHelper helper) throws Exception {
        var converter=FileToIdConverter.json("mythictrpg/npc_activities");
        var loaded=new LinkedHashMap<ResourceLocation,NpcActivityDefinition>();
        for(var entry:converter.listMatchingResources(helper.getLevel().getServer().getResourceManager()).entrySet()) {
            var id=converter.fileToId(entry.getKey());
            if(!id.getNamespace().equals("mythictrpg") || !(id.getPath().startsWith("decorative/") || id.getPath().startsWith("real/")))continue;
            try(Reader reader=entry.getValue().openAsReader()) {
                loaded.put(id,NpcActivityDefinitions.decode(id,JsonParser.parseReader(reader).getAsJsonObject()));
            }
        }
        helper.assertValueEqual(loaded.size(),29,"expected actual bundled activity definitions");
        Set<ActivityKind> kinds=new HashSet<>();
        loaded.values().stream().filter(d -> d.mode()==NpcActivityDefinition.Mode.DECORATIVE).forEach(d -> kinds.add(d.kind()));
        helper.assertValueEqual(kinds.size(),ActivityKind.values().length,"decorative library misses a supported kind");
        var policy=new JsonObject();policy.addProperty("formatVersion",1);policy.addProperty("autonomous",false);
        policy.addProperty("radius",8);policy.addProperty("decisionIntervalTicks",400);var refs=new JsonArray();
        loaded.keySet().forEach(id -> refs.add(id.toString()));policy.add("activities",refs);
        var decoded=NpcActivityDefinitions.decodePolicy(policy);
        helper.assertTrue(decoded.activities().stream().allMatch(loaded::containsKey),"sample policy reference did not resolve");

        for(ActivityKind kind:List.of(ActivityKind.EAT,ActivityKind.DRINK,ActivityKind.CRAFT,ActivityKind.REPAIR,
                ActivityKind.COOK,ActivityKind.OFFERING,ActivityKind.RITUAL)) reject(helper,base(kind,"REAL",Map.of()),"missing REAL parameters "+kind);
        for(String bad:List.of("apple","Minecraft:apple","minecraft:apple "," minecraft:apple","minecraft:",""))
            reject(helper,base(ActivityKind.EAT,"REAL",Map.of("item_id",bad)),"noncanonical item "+bad);
        reject(helper,base(ActivityKind.CRAFT,"REAL",Map.of("recipe_id","minecraft:oak_planks","grid","minecraft:oak_log,_")),"short grid");
        reject(helper,base(ActivityKind.CRAFT,"REAL",Map.of("recipe_id","minecraft:oak_planks","grid","_,_,_,_,_,_,_,_,_")),"empty grid");
        reject(helper,base(ActivityKind.CRAFT,"REAL",Map.of("recipe_id","minecraft:oak_planks","grid","minecraft:air,_,_,_,_,_,_,_,_")),"air ingredient");
        reject(helper,base(ActivityKind.CRAFT,"REAL",Map.of("recipe_id","minecraft:oak_planks","grid","oak_log,_,_,_,_,_,_,_,_")),"grid canonical item");
        reject(helper,base(ActivityKind.CRAFT,"DECORATIVE",Map.of("recipe_id","minecraft:oak_planks")),"partial recipe pair even when decorative");
        reject(helper,base(ActivityKind.REPAIR,"REAL",Map.of("item_id","minecraft:iron_pickaxe","recipe_id","minecraft:oak_planks","grid","minecraft:oak_log,_,_,_,_,_,_,_,_")),"ambiguous repair");
        reject(helper,base(ActivityKind.DRINK,"REAL",Map.of("item_id","minecraft:potion","recipe_id","minecraft:oak_planks","grid","minecraft:oak_log,_,_,_,_,_,_,_,_")),"drink is not crafting");
        reject(helper,base(ActivityKind.COOK,"REAL",Map.of("recipe_id","minecraft:baked_potato","input_id","minecraft:potato")),"fuel required");
        for(String value:List.of("0","65","-1","1.0","01","+1","2147483648"))
            reject(helper,base(ActivityKind.OFFERING,"REAL",Map.of("item_id","minecraft:apple","count",value)),"bounded count "+value);
        for(String value:List.of("","0,25","-1","1,","1.0",String.join(",",Collections.nCopies(65,"1"))))
            reject(helper,base(ActivityKind.PERFORM,"DECORATIVE",Map.of("notes",value)),"notes "+value);
        for(String value:List.of("3","201","12.0"," 12"))
            reject(helper,base(ActivityKind.PERFORM,"DECORATIVE",Map.of("note_interval_ticks",value)),"note interval");
        reject(helper,base(ActivityKind.PLAY,"DECORATIVE",Map.of("game","roulette")),"unsupported game");
        reject(helper,base(ActivityKind.OBSERVE,"DECORATIVE",Map.of("prop","book")),"noncanonical cosmetic prop");
        reject(helper,base(ActivityKind.RITUAL,"REAL",Map.of("template_id","fortuna_sparkles")),"noncanonical ritual template");
        reject(helper,base(ActivityKind.CRAFT,"REAL",Map.of("command","give @a diamond")),"arbitrary parameters");
        var numeric=base(ActivityKind.OFFERING,"REAL",Map.of("item_id","minecraft:apple","count","1"));numeric.getAsJsonObject("parameters").addProperty("count",1);
        reject(helper,numeric,"parameter values must be strings");
        var fractional=base(ActivityKind.OBSERVE,"DECORATIVE",Map.of());fractional.addProperty("durationTicks",40.5);reject(helper,fractional,"fractional duration");
        var badMode=base(ActivityKind.OBSERVE,"decorative",Map.of());reject(helper,badMode,"mode enum");
        var unknown=base(ActivityKind.OBSERVE,"DECORATIVE",Map.of());unknown.addProperty("reward",1);reject(helper,unknown,"unknown top-level field");
        var policyDuplicate=policy.deepCopy();policyDuplicate.getAsJsonArray("activities").add(refs.get(0));
        boolean policyRejected=false;try{NpcActivityDefinitions.decodePolicy(policyDuplicate);}catch(IllegalArgumentException expected){policyRejected=true;}
        helper.assertTrue(policyRejected,"duplicate activities accepted");

        // The actual runtime remains responsible for live item/recipe/template existence, access and material quantities.
        NpcActivityDefinitions.decode(ResourceLocation.parse("test:canonical_future"),base(ActivityKind.EAT,"REAL",Map.of("item_id","future_mod:food")));
        NpcActivityDefinitions.decode(ResourceLocation.parse("test:dice"),base(ActivityKind.PLAY,"REAL",Map.of("game","dice")));
        NpcActivityDefinitions.decode(ResourceLocation.parse("test:notes_boundary"),base(ActivityKind.PERFORM,"DECORATIVE",Map.of("notes","0,24","note_interval_ticks","4")));
        helper.succeed();
    }
    private static JsonObject base(ActivityKind kind,String mode,Map<String,String> parameters) {
        var j=new JsonObject();j.addProperty("formatVersion",1);j.addProperty("kind",kind.name());j.addProperty("mode",mode);
        var tags=new JsonArray();tags.add("idle");j.add("siteTags",tags);j.addProperty("durationTicks",200);
        var p=new JsonObject();parameters.forEach(p::addProperty);j.add("parameters",p);return j;
    }
    private static void reject(GameTestHelper helper,JsonObject value,String label) {
        boolean rejected=false;try{NpcActivityDefinitions.decode(ResourceLocation.parse("test:invalid"),value);}catch(IllegalArgumentException expected){rejected=true;}
        helper.assertTrue(rejected,"authored schema accepted "+label);
    }
    private NpcActivityDefinitionSchemaGameTests() { }
}
