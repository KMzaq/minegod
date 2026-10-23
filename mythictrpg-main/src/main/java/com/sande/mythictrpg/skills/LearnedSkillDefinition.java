package com.sande.mythictrpg.skills;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;

public record LearnedSkillDefinition(ResourceLocation id,String name,int manaCost,int cooldownTicks,
        WeaponSkillDefinition.Action action,double intrinsicPower) {
    public LearnedSkillDefinition {
        new WeaponSkillDefinition(id,name,manaCost,cooldownTicks,action);
        if(!Double.isFinite(intrinsicPower)||intrinsicPower<0||intrinsicPower>1_000_000||id.toString().length()>220)
            throw new IllegalArgumentException("Invalid learned skill power/id");
    }
    public static LearnedSkillDefinition parse(ResourceLocation id,JsonObject json) {
        if(!json.keySet().equals(Set.of("schemaVersion","name","manaCost","cooldownTicks","action","intrinsicPower")))
            throw new IllegalArgumentException("Missing/unknown learned skill fields");
        var power=json.get("intrinsicPower");
        if(!power.isJsonPrimitive()||!power.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected intrinsicPower number");
        var base=json.deepCopy();base.remove("intrinsicPower");
        var skill=WeaponSkillDefinition.parse(id,base);
        return new LearnedSkillDefinition(id,skill.name(),skill.manaCost(),skill.cooldownTicks(),skill.action(),power.getAsDouble());
    }
}
