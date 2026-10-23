package com.sande.mythictrpg.power;

import com.google.gson.*;
import com.sande.mythictrpg.quest.dynamic.CombatPowerAssessment;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;

/** Relations and boundaries, not a lock on provisional balance constants. */
public final class CombatPowerEvaluatorTest {
    private static int checks;
    public static void main(String[] args) { run(); }
    static void run() {
        JsonObject json = defaultJson();
        var policy = CombatPowerPolicy.parse("test:v2",json);
        var model = policy.model();
        var naked = state(20, Map.of(), List.of(), 0);
        check(policy.evaluate(naked,Map.of()).status() == CombatPowerAssessment.Status.AVAILABLE, "v2 total needs no invented world recommendation");
        check(policy.evaluate(naked,Map.of()).recommendedPower() == 0 && policy.evaluate(naked,Map.of()).catchUpRewardTierBonus() == 0,
                "unconfigured recommendations cannot manufacture catch-up rewards");
        var weak = state(20,Map.of("strength",1),List.of(),0);
        check(value(weak,model) > value(naked,model), "actual active effects contribute separately");
        check(Math.abs(value(weak.withOwnedBlessingPower(37),model)-value(weak,model) - 37*model.ownedBlessingCoefficient()) < 1e-9, "intrinsic count is distinct from strongest effect");
        check(weak.withEffects(Map.of()).ownedBlessingIntrinsicPower() == 0, "effect replacement leaves ownership untouched");
        var owner = weak.withOwnedBlessingPower(37);
        check(owner.withEffects(Map.of()).ownedBlessingIntrinsicPower() == 37, "milk cannot erase acquired intrinsic score");

        double lowHpResistance = delta(naked,"resistance",1,model);
        double highHpResistance = delta(state(4000,Map.of(),List.of(),0),"resistance",1,model);
        check(highHpResistance > lowHpResistance, "resistance gain scales with health");
        double normalResistance = delta(state(100,Map.of(),List.of(),0),"resistance",2,model);
        double regenResistance = delta(state(100,Map.of("regeneration",10),List.of(),0),"resistance",2,model);
        check(regenResistance > normalResistance, "regeneration and resistance interact instead of fixed independent bonuses");
        var armor = new CombatPowerState.Gear("armor","body",5,0,0,120,Map.of());
        check(delta(state(100,Map.of(),List.of(armor),0),"resistance",2,model)>normalResistance, "protection and resistance share survival model");
        var sword = new CombatPowerState.Gear("sword","weapon",8,8,200,0,Map.of("test:skill",7.0));
        check(delta(state(20,Map.of(),List.of(sword),0),"strength",17,model)>delta(naked,"strength",17,model), "strength retains value at sharpness 200");
        check(delta(state(20,Map.of("strength",17),List.of(sword),0),"haste",17,model)>delta(state(20,Map.of(),List.of(sword),0),"haste",17,model), "haste interacts with strength/sharpness");
        check(CombatPowerEvaluator.evaluate(state(20,Map.of("health_boost",17),List.of(),0),model).evaluatedMaxHealth()
                ==20+17*model.healthBoostPerLevel(), "HP additions use state instead of historical appended scores");
        for (String id : List.of("fire_resistance","saturation"))
            check(value(state(20,Map.of(id,1),List.of(),0),model)==value(state(20,Map.of(id,17),List.of(),0),model), "boolean effect is amplifier insensitive: "+id);
        var sameSkill = new CombatPowerState.Gear("skillarmor","body",5,0,0,1,Map.of("test:skill",7.0));
        check(CombatPowerEvaluator.evaluate(state(20,Map.of(),List.of(sword,sameSkill),0),model).skills()==7*model.skillCoefficient(), "same skill is not counted through two items");
        var progression=new CombatPowerState(20,2,Map.of(),List.of(sword),0,0,Map.of("test:skill",10.0,"test:learned",3.0),23);
        var progressionScore=CombatPowerEvaluator.evaluate(progression,model);
        check(progressionScore.skills()==13*model.skillCoefficient(),"learned and weapon skills merge by ID instead of duplicate scoring");
        var withoutAccessories=new CombatPowerState(20,2,Map.of(),List.of(sword),0,0,progression.learnedSkills(),0);
        check(Math.abs(value(progression,model)-value(withoutAccessories,model)-23)<1e-9,"fixed accessory power is exact and independent of gear coefficients");
        check(progression.withEffects(Map.of()).accessoryFixedPower()==23&&progression.withOwnedBlessingPower(5).learnedSkills().equals(progression.learnedSkills()),
                "effect and blessing previews retain new progression inputs");
        var replacement = new CombatPowerState.Gear("weak","weapon",1,1,1,0,Map.of());
        check(value(state(20,Map.of(),List.of(sword,sword,replacement),0),model)==value(state(20,Map.of(),List.of(sword),0),model), "historical best slot and duplicates stay stable");
        var noIntrinsic = new CombatPowerState.Gear("strong","weapon",0,8,200,0,Map.of());
        check(value(state(20,Map.of(),List.of(noIntrinsic,replacement),0),model)==value(state(20,Map.of(),List.of(noIntrinsic),0),model), "slot-best considers interaction stats even with zero intrinsic gear points");
        var before = state(4000,Map.of("strength",16,"regeneration",12,"resistance",4),List.of(armor,sword),100);
        var after = before.withEffects(Map.of(id("strength"),17,id("regeneration"),12,id("resistance"),4));
        double gain = value(after,model)-value(before,model);
        var restored = state(4000,Map.of("resistance",4,"regeneration",12,"strength",17),List.of(sword,armor),100);
        check(value(restored,model)==value(after,model) && gain>0, "pre/candidate/post and input order use one deterministic evaluator");
        check(value(after,model)-value(before,model)==gain, "repeated evaluations cannot append candidate scores");
        rejects(()->state(20,Map.of("resistance",5),List.of(),0),"resistance V rejected by v2");
        rejects(()->state(20,Map.of("strength",18),List.of(),0),"unsupported effect level rejected");
        rejects(()->value(state(20,Map.of("unknown",1),List.of(),0),model),"unknown effect never silently scored zero");
        rejects(()->new CombatPowerState.Gear("bad","weapon",1,1,256,0,Map.of()),"enchantment 256 rejected");
        rejects(()->state(Double.POSITIVE_INFINITY,Map.of(),List.of(),0),"nonfinite input rejected");
        var duplicate = json.deepCopy(); duplicate.getAsJsonObject("model").getAsJsonObject("effectPointsPerLevel").addProperty("minecraft:strength",99);
        rejects(()->CombatPowerPolicy.parse("test:duplicate",duplicate),"strength cannot be counted in interaction and effect table");
        var unsafe = json.deepCopy(); unsafe.getAsJsonObject("model").addProperty("resistanceReductionPerLevel",0.25);
        rejects(()->CombatPowerPolicy.parse("test:unsafe",unsafe),"infinite resistance gain rejected");
        for (String key : List.of("healthBoostPerLevel","resistanceReductionPerLevel","strengthFlatPerLevel","sharpnessPerLevel","sharpnessBase")) {
            var changedPhysical = json.deepCopy();
            changedPhysical.getAsJsonObject("model").addProperty(key,json.getAsJsonObject("model").get(key).getAsDouble()/2);
            rejects(()->CombatPowerPolicy.parse("test:physical",changedPhysical),"rating must match vanilla physical input: "+key);
        }
        var ignore = item("IGNORE","none",0,0,0,0); ignore.addProperty("attackDamage",1);
        var policyWithIgnore = json.deepCopy(); policyWithIgnore.getAsJsonArray("items").add(ignore);
        rejects(()->CombatPowerPolicy.parse("test:ignore",policyWithIgnore),"countermeasure/ignored gear cannot supply hidden offense");
        var unbound = json.deepCopy(); unbound.getAsJsonArray("items").add(item("GEAR","weapon",3,1,1,0));
        rejects(()->CombatPowerPolicy.parse("test:unbound",unbound),"v2 requires explicit bound reward classification");
        System.out.println("CombatPowerEvaluatorTest: "+checks+" assertions passed");
    }
    static JsonObject defaultJson() {
        try { return JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/mythictrpg/mythictrpg/combat_power/default.json"))).getAsJsonObject(); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static JsonObject item(String classification,String slot,double score,double attack,int sharpness,int protection) {
        JsonObject item = new JsonObject(); item.addProperty("itemId","minecraft:iron_sword"); item.add("components",new JsonObject());
        item.addProperty("classification",classification); item.addProperty("slot",slot); item.addProperty("intrinsicPower",score);
        item.addProperty("attackDamage",attack); item.addProperty("sharpnessLevel",sharpness); item.addProperty("protectionLevel",protection);
        item.add("skills",new JsonObject()); return item;
    }
    private static CombatPowerState state(double hp,Map<String,Integer> effects,List<CombatPowerState.Gear> gear,double intrinsic) {
        Map<ResourceLocation,Integer> levels=new HashMap<>(); effects.forEach((key,value)->levels.put(id(key),value));
        return new CombatPowerState(hp,1,levels,gear,intrinsic,0);
    }
    private static ResourceLocation id(String id) { return ResourceLocation.parse("minecraft:"+id); }
    private static double value(CombatPowerState state,CombatPowerEvaluator.Model model) { return CombatPowerEvaluator.evaluate(state,model).total(); }
    private static double delta(CombatPowerState state,String effect,int level,CombatPowerEvaluator.Model model) {
        var next = new HashMap<>(state.effectLevels()); next.merge(id(effect),level,Math::max);
        return value(state.withEffects(next),model)-value(state,model);
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message);checks++; }
    private static void rejects(Runnable runnable,String message) { try { runnable.run(); } catch(RuntimeException expected){checks++;return;}throw new AssertionError(message); }
}
