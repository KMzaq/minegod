package com.sande.mythictrpg.specialskill;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Every selected limit is ANDed; no deity's individual ability or balance is bundled. */
public record SpecialSkillDefinition(ResourceLocation id,String name,ResourceLocation providerGodId,int manaCost,
        UseLimits limits,Action action,Optional<ConditionNode> condition,double intrinsicPower) {
    public record UseLimits(int perRaidUses,long cooldownSeconds,List<NpcRewardEntry> consumables) {
        public UseLimits {
            consumables=List.copyOf(consumables);
            if(perRaidUses<0||perRaidUses>100||cooldownSeconds<0||cooldownSeconds>31_536_000||consumables.size()>16
                    ||perRaidUses==0&&cooldownSeconds==0&&consumables.isEmpty())
                throw new IllegalArgumentException("Select one or more special-skill use limits");
        }
    }
    public enum ActionType { LIGHTNING_STRIKE, AREA_DAMAGE, HEAL_ROSTER }
    public record Action(ActionType type,double amount,double range,double radius,int stunTicks) {
        public Action {
            Objects.requireNonNull(type);
            if(!Double.isFinite(amount)||amount<=0||amount>100000||!Double.isFinite(range)||range<0||range>64
                    ||!Double.isFinite(radius)||radius<0.5||radius>32||stunTicks<0||stunTicks>1200
                    ||type==ActionType.HEAL_ROSTER&&stunTicks!=0)throw new IllegalArgumentException("Invalid support action");
        }
    }
    public SpecialSkillDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(providerGodId); Objects.requireNonNull(limits); Objects.requireNonNull(action);
        Objects.requireNonNull(condition);
        if(name==null||name.isBlank()||name.length()>128||manaCost<0||manaCost>1_000_000
                ||!Double.isFinite(intrinsicPower)||intrinsicPower<0||intrinsicPower>1_000_000_000)
            throw new IllegalArgumentException("Invalid special skill");
    }
    public static SpecialSkillDefinition parse(ResourceLocation id,JsonObject json) {
        fields(json,"schemaVersion","name","providerGodId","manaCost","limits","action","condition","intrinsicPower");
        if(integer(json,"schemaVersion",1,1)!=1)throw new IllegalArgumentException("Unknown special skill schema");
        var limits=json.getAsJsonObject("limits"); fields(limits,"perRaidUses","cooldownSeconds","consumables");
        var items=new ArrayList<NpcRewardEntry>();
        if(limits.has("consumables"))for(var raw:limits.getAsJsonArray("consumables")) {
            var row=raw.getAsJsonObject(); fields(row,"itemId","count","components");
            var components=row.getAsJsonObject("components");
            if(components==null)throw new IllegalArgumentException("Explicit consumable components required");
            items.add(new NpcRewardEntry(id(string(row,"itemId")),integer(row,"count",1,64),
                    (CompoundTag)JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE,components)));
        }
        var use=new UseLimits(limits.has("perRaidUses")?integer(limits,"perRaidUses",1,100):0,
                limits.has("cooldownSeconds")?integer(limits,"cooldownSeconds",1,31_536_000):0,items);
        var action=json.getAsJsonObject("action"); fields(action,"type","amount","range","radius","stunTicks");
        var effect=new Action(ActionType.valueOf(string(action,"type")),number(action,"amount"),number(action,"range"),number(action,"radius"),
                action.has("stunTicks")?integer(action,"stunTicks",0,1200):0);
        Optional<ConditionNode> condition=json.has("condition")?Optional.of(new ConditionTreeParser(ConditionTypeRegistry.INSTANCE).parse(json.get("condition"))):Optional.empty();
        return new SpecialSkillDefinition(id,string(json,"name"),id(string(json,"providerGodId")),integer(json,"manaCost",0,1_000_000),
                use,effect,condition,number(json,"intrinsicPower"));
    }
    private static void fields(JsonObject value,String... names) {
        if(value==null||!Set.of(names).containsAll(value.keySet()))throw new IllegalArgumentException("Unknown special skill field");
    }
    private static String string(JsonObject value,String key) {
        var raw=value.get(key); if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected string "+key);
        return raw.getAsString();
    }
    private static double number(JsonObject value,String key) {
        var raw=value.get(key); if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber()||!Double.isFinite(raw.getAsDouble()))
            throw new IllegalArgumentException("Expected number "+key); return raw.getAsDouble();
    }
    private static int integer(JsonObject value,String key,int min,int max) {
        double v=number(value,key); if(v<min||v>max||v!=Math.floor(v))throw new IllegalArgumentException("Invalid integer "+key); return (int)v;
    }
    private static ResourceLocation id(String value) {
        if(!value.contains(":"))throw new IllegalArgumentException("Explicit namespace required"); return ResourceLocation.parse(value);
    }
}
