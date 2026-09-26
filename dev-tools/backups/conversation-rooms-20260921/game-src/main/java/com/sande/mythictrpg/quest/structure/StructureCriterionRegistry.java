package com.sande.mythictrpg.quest.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Reusable criterion types. The registry knows features, never God IDs. */
public final class StructureCriterionRegistry {
    private static final Map<String, BiFunction<StructureSnapshot, StructureEvaluationPolicy.Criterion, Double>> TYPES = new LinkedHashMap<>();
    static {
        register("block_count", (s,c)->(double)s.totalBlocks());
        register("block_ratio", StructureCriterionRegistry::blockRatio);
        register("tag_count", StructureCriterionRegistry::tagCount);
        register("tag_ratio", StructureCriterionRegistry::tagRatio);
        register("material_diversity", feature("material_diversity"));
        register("flower_count", StructureCriterionRegistry::tagCount);
        register("crop_count", StructureCriterionRegistry::tagCount);
        register("crop_diversity", (s,c)->c.tag().map(id->s.features().getOrDefault("tag_diversity:"+id,0D)).orElse(0D));
        register("water_presence", feature("water_presence"));
        register("water_ratio", feature("water_ratio"));
        register("water_adjacency", feature("water_adjacency"));
        register("underwater_ratio", feature("underwater_ratio"));
        register("biome_match", StructureCriterionRegistry::biomeRatio);
        register("biome_ratio", StructureCriterionRegistry::biomeRatio);
        register("interior_volume", feature("interior_volume"));
        register("interior_quality", feature("interior_quality"));
        register("lighting", feature("lighting"));
        register("open_space", feature("open_space"));
        register("defensive_structure", feature("defensive_structure"));
        register("decoration_density", feature("decoration_density"));
        register("displayed_item_count", feature("displayed_item_count"));
        register("functional_block_count", StructureCriterionRegistry::functionalCount);
        register("height", feature("height"));
        register("symmetry", feature("symmetry"));
        register("connectivity", feature("connectivity"));
        register("environment_match", StructureCriterionRegistry::environmentMatch);
        register("palette_cohesion", feature("palette_cohesion"));
        register("sky_visibility", feature("sky_visibility"));
        register("ground_contact", feature("ground_contact"));
        register("environment_water_ratio", feature("environment_water_ratio"));
        register("environment_vegetation_ratio", feature("environment_vegetation_ratio"));
        register("underground_ratio", feature("underground_ratio"));
        register("environment_lava_ratio", feature("environment_lava_ratio"));
        register("environment_stone_ratio", feature("environment_stone_ratio"));
        register("environment_sand_ratio", feature("environment_sand_ratio"));
        register("environment_snow_ice_ratio", feature("environment_snow_ice_ratio"));
    }
    private StructureCriterionRegistry() {}

    public static synchronized void register(String id, BiFunction<StructureSnapshot, StructureEvaluationPolicy.Criterion, Double> evaluator) {
        if (id == null || id.isBlank() || evaluator == null || TYPES.putIfAbsent(id, evaluator) != null)
            throw new IllegalArgumentException("Criterion type is invalid or already registered: " + id);
    }
    public static Set<String> types() { return Set.copyOf(TYPES.keySet()); }

    public static StructureEvaluationReport evaluate(StructureSnapshot snapshot, StructureEvaluationPolicy policy) {
        Map<StructureEvaluationPolicy.Scope,Double> weights=new HashMap<>();
        policy.criteria().forEach(c->weights.merge(c.scope(),c.weight(),Double::sum));
        Map<String,Double> normalizedById=new HashMap<>(); List<StructureEvaluationReport.CriterionScore> scores=new java.util.ArrayList<>();
        double build=0D,environment=0D;
        for(StructureEvaluationPolicy.Criterion criterion:policy.criteria()){
            double raw;
            if("composite".equals(criterion.type())){
                raw=criterion.components().stream().map(normalizedById::get).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0D);
            }else{
                BiFunction<StructureSnapshot,StructureEvaluationPolicy.Criterion,Double> evaluator=TYPES.get(criterion.type());
                if(evaluator==null)throw new IllegalArgumentException("Unknown criterion type "+criterion.type());raw=evaluator.apply(snapshot,criterion);
            }
            if(!Double.isFinite(raw))raw=0D;double normalized=normalize(raw,criterion);normalizedById.put(criterion.id(),normalized);
            double scopePoints=criterion.weight()/weights.getOrDefault(criterion.scope(),1D)*100D;
            double awarded=scopePoints*normalized;scores.add(new StructureEvaluationReport.CriterionScore(criterion.id(),criterion.scope(),criterion.type(),raw,normalized,awarded,scopePoints));
            if(criterion.scope()==StructureEvaluationPolicy.Scope.BUILD)build+=awarded;else environment+=awarded;
        }
        double finalScore=build*policy.buildWeight()/100D+environment*policy.environmentWeight()/100D;
        scores.sort(java.util.Comparator.comparingDouble(StructureEvaluationReport.CriterionScore::normalizedValue).reversed());
        String evidence=evidence(snapshot,policy,build,environment,scores);
        return new StructureEvaluationReport(policy.id(),policy.godId(),(int)Math.round(finalScore),build,environment,scores,snapshot,evidence);
    }

    private static double normalize(double raw,StructureEvaluationPolicy.Criterion c){double clipped=Math.min(raw,c.maximum());double value=c.target()==c.minimum()?(clipped>=c.target()?1D:0D):(clipped-c.minimum())/(c.target()-c.minimum());value=Math.max(0D,Math.min(1D,value));return switch(c.curve()){case SQRT->Math.sqrt(value);case SQUARE->value*value;case LINEAR->value;};}
    private static BiFunction<StructureSnapshot,StructureEvaluationPolicy.Criterion,Double> feature(String key){return(s,c)->s.features().getOrDefault(key,0D);}
    private static double tagCount(StructureSnapshot s,StructureEvaluationPolicy.Criterion c){return c.tag().map(id->s.tagCounts().getOrDefault(id,0).doubleValue()).orElse(0D);}
    private static double tagRatio(StructureSnapshot s,StructureEvaluationPolicy.Criterion c){return c.tag().map(id->s.tagRatios().getOrDefault(id,0D)).orElse(0D);}
    private static double functionalCount(StructureSnapshot s,StructureEvaluationPolicy.Criterion c){return tagCount(s,c)+s.decorationEntities();}
    private static double blockRatio(StructureSnapshot s,StructureEvaluationPolicy.Criterion c){int count=c.blocks().stream().mapToInt(id->s.blockCounts().getOrDefault(id,0)).sum();return count/(double)Math.max(1,s.totalBlocks());}
    private static double biomeRatio(StructureSnapshot s,StructureEvaluationPolicy.Criterion c){double explicit=c.biomes().stream().mapToDouble(id->s.biomeRatios().getOrDefault(id,0D)).sum();double tagged=c.biomeTag().map(id->s.biomeTagRatios().getOrDefault(id,0D)).orElse(0D);return Math.min(1D,explicit+tagged);}
    private static double environmentMatch(StructureSnapshot s,StructureEvaluationPolicy.Criterion c){return(s.features().getOrDefault("sky_visibility",0D)+s.features().getOrDefault("ground_contact",0D)+s.features().getOrDefault("environment_vegetation_ratio",0D))/3D;}
    private static String evidence(StructureSnapshot s,StructureEvaluationPolicy p,double build,double env,List<StructureEvaluationReport.CriterionScore> scores){StringBuilder out=new StringBuilder();out.append("정책 ").append(p.id()).append(", BUILD ").append(Math.round(build)).append("/100, ENVIRONMENT ").append(Math.round(env)).append("/100. ");out.append("감지: 직접 설치 ").append(s.playerPlacedBlocks()).append("개, 파생 ").append(s.derivedBlocks()).append("개, 크기 ").append(s.bounds().width()).append('x').append(s.bounds().height()).append('x').append(s.bounds().depth()).append(". 주요 재료: ");s.blockCounts().entrySet().stream().sorted(Map.Entry.<ResourceLocation,Integer>comparingByValue().reversed()).limit(3).forEach(e->out.append(e.getKey()).append('(').append(e.getValue()).append(") "));out.append(". 강점: ");scores.stream().filter(v->v.normalizedValue()>=.65).limit(3).forEach(v->out.append(v.id()).append('(').append(Math.round(v.normalizedValue()*100)).append("%) "));out.append(". 보완: ");scores.stream().sorted(java.util.Comparator.comparingDouble(StructureEvaluationReport.CriterionScore::normalizedValue)).filter(v->v.normalizedValue()<.65).limit(3).forEach(v->out.append(v.id()).append('(').append(Math.round(v.normalizedValue()*100)).append("%) "));if(!s.notes().isEmpty())out.append(". 참고: ").append(String.join("; ",s.notes()));String text=out.toString().replaceAll("\\s+"," ").trim();return text.length()<=1200?text:text.substring(0,1200);}
}
