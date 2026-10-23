package com.sande.mythictrpg.equipment;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.power.CombatPowerPolicy;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A weapon definition owns its authored numbers; its registered item ID selects a reusable appearance. */
public record EquipmentDefinition(ResourceLocation id,String name,NpcRewardEntry reward,CombatPowerPolicy.ItemRule rule) {
    public static final String ID_KEY="equipment_definition";
    private static final int MAX_COMPONENT_CHARS=16_384,MAX_EFFECTS=64,MAX_DURABILITY=1_000_000;
    private static final double MAX_POWER=1_000_000,MAX_ATTACK_DAMAGE=2047,MIN_ATTACK_SPEED=.01,MAX_ATTACK_SPEED=1024;
    private static final Set<String> GENERATED_COMPONENTS=Set.of("minecraft:custom_data","minecraft:custom_name",
            "minecraft:attribute_modifiers","minecraft:enchantments","minecraft:max_damage","minecraft:damage","minecraft:max_stack_size");
    private static final Set<String> COSMETIC_COMPONENTS=Set.of("minecraft:lore","minecraft:enchantment_glint_override",
            "minecraft:rarity","minecraft:custom_model_data");
    public EquipmentDefinition {
        boundedId(id);if(name==null||name.isBlank()||name.length()>128)throw new IllegalArgumentException("Equipment name must be 1..128 characters");
        if(reward==null||rule==null||reward.count()!=1||rule.ignored()||!rule.slot().equals("weapon")
                ||!reward.itemId().equals(rule.itemId())||!reward.components().equals(rule.components())
                ||!reward.components().getCompound("minecraft:custom_data").getString(ID_KEY).equals(id.toString()))
            throw new IllegalArgumentException("Equipment reward/power identity must share one canonical definition");
    }

    /** Shape-only parsing. Item and enchantment registry references are validated by createStack during preflight. */
    public static EquipmentDefinition parse(ResourceLocation id,JsonObject json) {
        boundedId(id);
        var required=Set.of("schemaVersion","name","itemId","attackDamage","attackSpeed","durability","enchantments","intrinsicPower","skills","components");
        if(!json.keySet().containsAll(required)||json.keySet().stream().anyMatch(key->!required.contains(key)&&!key.equals("rightClickSkill")&&!key.equals("requiredMods")))
            throw new IllegalArgumentException("Missing/unknown equipment definition fields");
        requiredMods(json);
        if(integer(json,"schemaVersion",1,1)!=1)throw new IllegalArgumentException("Unsupported equipment schema");
        String name=string(json,"name");if(name.length()>128)throw new IllegalArgumentException("Equipment name exceeds 128 characters");
        ResourceLocation appearance=namespaced(string(json,"itemId"));
        if(appearance.getNamespace().equals("simplyswords"))
            throw new IllegalArgumentException("Use a clean mythictrpg Simply Swords appearance; native item classes retain their own abilities");
        double attack=number(json,"attackDamage",0,MAX_ATTACK_DAMAGE),speed=number(json,"attackSpeed",MIN_ATTACK_SPEED,MAX_ATTACK_SPEED);
        int durability=integer(json,"durability",1,MAX_DURABILITY);
        double intrinsic=number(json,"intrinsicPower",0,MAX_POWER);
        var authored=json.getAsJsonObject("components");
        if(authored==null||authored.toString().length()>MAX_COMPONENT_CHARS)throw new IllegalArgumentException("Equipment components must be a bounded object");
        for(String key:authored.keySet()) {
            String component=key.startsWith("!")?key.substring(1):key;namespaced(component);
            if(GENERATED_COMPONENTS.contains(component))throw new IllegalArgumentException("Equipment component is generated and cannot be overridden: "+key);
            if(!COSMETIC_COMPONENTS.contains(key))throw new IllegalArgumentException("Only explicit cosmetic equipment components are supported: "+key);
        }
        var enchantments=json.getAsJsonObject("enchantments");
        if(enchantments==null||enchantments.size()>MAX_EFFECTS)throw new IllegalArgumentException("Equipment enchantment limit");
        JsonObject levels=new JsonObject();
        for(String key:enchantments.keySet()){namespaced(key);levels.addProperty(key,integer(enchantments,key,0,255));}
        if(levels.has("minecraft:protection")&&levels.get("minecraft:protection").getAsInt()>0)
            throw new IllegalArgumentException("Protection does not protect a held sword and cannot contribute weapon power");
        var authoredSkills=json.getAsJsonObject("skills");
        if(authoredSkills==null||authoredSkills.size()>MAX_EFFECTS)throw new IllegalArgumentException("Equipment skill limit");
        Map<String,Double> skills=new LinkedHashMap<>();
        for(String key:authoredSkills.keySet()){namespaced(key);skills.put(key,number(authoredSkills,key,0,MAX_POWER));}
        ResourceLocation rightClick=json.has("rightClickSkill")?namespaced(string(json,"rightClickSkill")):null;
        if(rightClick!=null&&!skills.containsKey(rightClick.toString()))throw new IllegalArgumentException("Weapon skill requires its explicit intrinsic power entry");

        JsonObject components=authored.deepCopy(),customData=new JsonObject(),customName=new JsonObject();
        customData.addProperty(ID_KEY,id.toString());components.add("minecraft:custom_data",customData);
        customName.addProperty("text",name);customName.addProperty("italic",false);components.addProperty("minecraft:custom_name",customName.toString());
        components.addProperty("minecraft:max_stack_size",1);components.addProperty("minecraft:max_damage",durability);
        JsonObject attributes=new JsonObject();var modifiers=new com.google.gson.JsonArray();
        modifiers.add(modifier("minecraft:generic.attack_damage","minecraft:base_attack_damage",attack));
        modifiers.add(modifier("minecraft:generic.attack_speed","minecraft:base_attack_speed",speed-4));
        attributes.add("modifiers",modifiers);components.add("minecraft:attribute_modifiers",attributes);
        JsonObject enchanted=new JsonObject();enchanted.add("levels",levels);components.add("minecraft:enchantments",enchanted);
        CompoundTag encoded=(CompoundTag)JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE,components);
        var reward=new NpcRewardEntry(appearance,1,encoded);
        var rule=new CombatPowerPolicy.ItemRule(appearance,encoded,"weapon",intrinsic,false,attack,
                levels.has("minecraft:sharpness")?levels.get("minecraft:sharpness").getAsInt():0,
                levels.has("minecraft:protection")?levels.get("minecraft:protection").getAsInt():0,skills,rightClick);
        return new EquipmentDefinition(id,name,reward,rule);
    }
    /** Optional dependencies are loader metadata, never item components or a grant permission. */
    public static List<String> requiredMods(JsonObject json) {
        if(!json.has("requiredMods"))return List.of();
        var value=json.get("requiredMods");
        if(!value.isJsonArray()||value.getAsJsonArray().isEmpty()||value.getAsJsonArray().size()>16)
            throw new IllegalArgumentException("Equipment requiredMods must contain 1..16 distinct mod IDs");
        var mods=new LinkedHashSet<String>();
        for(var entry:value.getAsJsonArray()) {
            if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString()||!entry.getAsString().matches("[a-z][a-z0-9_]{1,63}")
                    ||!mods.add(entry.getAsString()))throw new IllegalArgumentException("Invalid or duplicate equipment requiredMods entry");
        }
        return List.copyOf(mods);
    }
    private static JsonObject modifier(String attribute,String id,double amount){
        var value=new JsonObject();value.addProperty("type",attribute);value.addProperty("id",id);
        value.addProperty("amount",amount);value.addProperty("operation","add_value");value.addProperty("slot","mainhand");return value;
    }
    private static String string(JsonObject json,String key){var value=json.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank())throw new IllegalArgumentException("Equipment string required: "+key);
        return value.getAsString();}
    private static double number(JsonObject json,String key,double minimum,double maximum){var value=json.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Equipment number required: "+key);
        double number=value.getAsDouble();if(!Double.isFinite(number)||number<minimum||number>maximum)throw new IllegalArgumentException("Equipment number outside allowed range: "+key);return number;}
    private static int integer(JsonObject json,String key,int minimum,int maximum){number(json,key,minimum,maximum);return json.get(key).getAsBigDecimal().intValueExact();}
    private static ResourceLocation namespaced(String value){if(!value.contains(":"))throw new IllegalArgumentException("Equipment ID must be namespaced");var id=ResourceLocation.parse(value);boundedId(id);return id;}
    private static void boundedId(ResourceLocation id){if(id==null||id.toString().length()>256)throw new IllegalArgumentException("Equipment ID limit");}
}
