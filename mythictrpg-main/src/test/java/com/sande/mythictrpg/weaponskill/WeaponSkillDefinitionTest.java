package com.sande.mythictrpg.weaponskill;

import com.google.gson.*;
import com.sande.mythictrpg.power.CombatPowerPolicy;
import com.sande.mythictrpg.skills.ManaTuning;
import net.minecraft.resources.ResourceLocation;

public final class WeaponSkillDefinitionTest {
    private static int checks;
    private static final ResourceLocation ID = ResourceLocation.parse("test:skill");
    public static void main(String[] args) throws Exception {
        long maximumMicroMana=200_000_000L;
        check(ManaTuning.BASE_MAX_MANA==200&&ManaTuning.REGEN_PERCENT_PER_SECOND==1.0,"initial shared mana tuning changed");
        check(ManaTuning.regenerationNumerator(maximumMicroMana,1.0)/2000*20==2_000_000L,"one percent must restore two mana per second at maximum 200");
        check(ManaTuning.regenerationNumerator(maximumMicroMana,0.5)/2000*20==1_000_000L,"half percent tuning must restore one mana per second");
        check(ManaTuning.regenerationNumerator(333_333_333L,0.5)==166_666_667L,"fractional percentage numerator must retain fixed-point rounding");
        check(ManaTuning.regenerationNumerator(maximumMicroMana,0)==0,"zero regeneration tuning must be supported");
        reject(()->ManaTuning.regenerationNumerator(maximumMicroMana,Double.NaN));
        reject(()->ManaTuning.regenerationNumerator(maximumMicroMana,-1));
        var json = json("""
                {"schemaVersion":1,"name":"시험 회복","manaCost":20,"cooldownTicks":100,
                 "action":{"type":"SELF_HEAL","amount":4}}
                """);
        var skill = WeaponSkillDefinition.parse(ID,json);
        check(skill.manaCost()==20 && skill.cooldownTicks()==100 && skill.action() instanceof WeaponSkillDefinition.SelfHeal,"cost/action");
        for (String key : new String[]{"manaCost","cooldownTicks"}) {
            var value=json.deepCopy(); value.addProperty(key,-1); reject(() -> WeaponSkillDefinition.parse(ID,value));
            value.addProperty(key,0.5); reject(() -> WeaponSkillDefinition.parse(ID,value));
            value.addProperty(key,key.equals("manaCost")?1_000_001:100000); reject(() -> WeaponSkillDefinition.parse(ID,value));
        }
        var expanded=json.deepCopy();expanded.addProperty("manaCost",300);
        check(WeaponSkillDefinition.parse(ID,expanded).manaCost()==300,"dynamic maximum can support authorable costs above 200");
        var unknown=json.deepCopy(); unknown.addProperty("surprise",true); reject(() -> WeaponSkillDefinition.parse(ID,unknown));
        var action=json.deepCopy(); action.add("action",json("{\"type\":\"AIMED_DAMAGE\",\"amount\":10,\"range\":32}"));
        check(WeaponSkillDefinition.parse(ID,action).action() instanceof WeaponSkillDefinition.AimedDamage,"aim action");
        action.getAsJsonObject("action").addProperty("range",33); reject(() -> WeaponSkillDefinition.parse(ID,action));
        var area=json.deepCopy();area.add("action",json("{\"type\":\"AREA_DAMAGE\",\"amount\":4,\"radius\":6,\"maximumTargets\":8}"));
        check(WeaponSkillDefinition.parse(ID,area).action() instanceof WeaponSkillDefinition.AreaDamage,"area action");
        area.getAsJsonObject("action").addProperty("maximumTargets",65);reject(()->WeaponSkillDefinition.parse(ID,area));
        var dot=json.deepCopy();dot.add("action",json("{\"type\":\"PERIODIC_DAMAGE\",\"amount\":2,\"range\":8,\"pulses\":3,\"intervalTicks\":12}"));
        check(WeaponSkillDefinition.parse(ID,dot).action() instanceof WeaponSkillDefinition.PeriodicDamage,"periodic action");
        dot.getAsJsonObject("action").addProperty("intervalTicks",0);reject(()->WeaponSkillDefinition.parse(ID,dot));
        var wave=json("""
                {"type":"BLADE_WAVE","firstDamage":8,"subsequentDamage":4,"speed":1,"range":10,"radius":0.25,
                 "maximumTargets":3,"windupTicks":4,"recoveryTicks":6}
                """);
        check(WeaponSkillDefinition.parseAction(wave) instanceof WeaponSkillDefinition.BladeWave,"moving blade action");
        wave.addProperty("maximumTargets",0);reject(()->WeaponSkillDefinition.parseAction(wave));wave.addProperty("maximumTargets",3);
        wave.addProperty("speed",5);reject(()->WeaponSkillDefinition.parseAction(wave));wave.addProperty("speed",1);
        wave.addProperty("radius",Double.NaN);reject(()->WeaponSkillDefinition.parseAction(wave));
        wave.addProperty("radius",0.25);wave.addProperty("lifetimeTicks",0);reject(()->WeaponSkillDefinition.parseAction(wave));
        wave.addProperty("lifetimeTicks",10);check(((WeaponSkillDefinition.BladeWave)WeaponSkillDefinition.parseAction(wave)).lifetimeTicks()==10,"blade lifetime independent from range/speed");
        var parry=json("""
                {"type":"TIMED_PARRY","windupTicks":2,"activeTicks":4,"recoveryTicks":8,"halfAngleDegrees":60,"range":4}
                """);
        check(WeaponSkillDefinition.parseAction(parry) instanceof WeaponSkillDefinition.TimedParry,"timed parry action");
        parry.addProperty("activeTicks",0);reject(()->WeaponSkillDefinition.parseAction(parry));parry.addProperty("activeTicks",4);
        parry.addProperty("halfAngleDegrees",180);reject(()->WeaponSkillDefinition.parseAction(parry));
        var dash=json("""
                {"type":"SHORT_DASH","windupTicks":1,"movementTicks":4,"evasionTicks":3,"recoveryTicks":3,"distance":3,"minimumDistance":0.25}
                """);
        check(WeaponSkillDefinition.parseAction(dash) instanceof WeaponSkillDefinition.ShortDash,"short dash action");
        dash.addProperty("evasionTicks",5);reject(()->WeaponSkillDefinition.parseAction(dash));dash.addProperty("evasionTicks",3);
        dash.addProperty("minimumDistance",4);reject(()->WeaponSkillDefinition.parseAction(dash));dash.addProperty("minimumDistance",0.25);
        dash.addProperty("distance",5);reject(()->WeaponSkillDefinition.parseAction(dash));
        var learned=json.deepCopy();learned.addProperty("intrinsicPower",12.5);
        check(com.sande.mythictrpg.skills.LearnedSkillDefinition.parse(ID,learned).intrinsicPower()==12.5,"learned intrinsic power");
        learned.addProperty("intrinsicPower",-1);reject(()->com.sande.mythictrpg.skills.LearnedSkillDefinition.parse(ID,learned));
        var effect=json.deepCopy(); effect.add("action",json("{\"type\":\"SELF_EFFECT\",\"effectId\":\"minecraft:resistance\",\"level\":4,\"durationTicks\":100}"));
        check(WeaponSkillDefinition.parse(ID,effect).action() instanceof WeaponSkillDefinition.SelfEffect,"resistance cap");
        effect.getAsJsonObject("action").addProperty("level",5); reject(() -> WeaponSkillDefinition.parse(ID,effect));
        effect.getAsJsonObject("action").addProperty("effectId","minecraft:speed"); effect.getAsJsonObject("action").addProperty("level",17);
        check(WeaponSkillDefinition.parse(ID,effect).action() instanceof WeaponSkillDefinition.SelfEffect,"other cap17");
        effect.getAsJsonObject("action").addProperty("level",18); reject(() -> WeaponSkillDefinition.parse(ID,effect));
        JsonObject policy;
        try (var input=WeaponSkillDefinitionTest.class.getResourceAsStream("/data/mythictrpg/mythictrpg/combat_power/default.json")) {
            policy=json(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        var row=json("""
                {"itemId":"minecraft:iron_sword","components":{},"classification":"BOUND_REWARD_GEAR","slot":"weapon",
                 "intrinsicPower":0,"attackDamage":6,"sharpnessLevel":0,"protectionLevel":0,"skills":{"test:skill":0}}
                """);
        policy.getAsJsonArray("items").add(row);
        check(CombatPowerPolicy.parse("test:policy",policy).items().getLast().rightClickSkill()==null,"scoring does not imply activation");
        row.addProperty("rightClickSkill","test:skill");
        check(CombatPowerPolicy.parse("test:policy",policy).items().getLast().rightClickSkill().equals(ID),"explicit optional binding");
        row.addProperty("rightClickSkill","test:absent"); reject(() -> CombatPowerPolicy.parse("test:policy",policy));
        row.addProperty("rightClickSkill","test:skill"); row.addProperty("slot","head"); reject(() -> CombatPowerPolicy.parse("test:policy",policy));
        row.addProperty("slot","weapon"); row.add("rightClickSkill",JsonNull.INSTANCE); reject(() -> CombatPowerPolicy.parse("test:policy",policy));
        System.out.println("WeaponSkillDefinitionTest: " + checks + " assertions passed");
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void reject(Runnable body) { boolean rejected=false; try { body.run(); } catch(RuntimeException expected) { rejected=true; } check(rejected,"invalid definition accepted"); }
    private static void check(boolean okay,String reason) { if(!okay)throw new AssertionError(reason); checks++; }
}
