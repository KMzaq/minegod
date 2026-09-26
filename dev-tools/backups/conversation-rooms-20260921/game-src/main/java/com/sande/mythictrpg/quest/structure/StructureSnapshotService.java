package com.sande.mythictrpg.quest.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-thread snapshot capture with sparse reads and explicit work caps. */
public final class StructureSnapshotService {
    private StructureSnapshotService() {}

    public static Capture capture(ServerLevel level, StructureBuildRecord build,
            StructureEvaluationPolicy policy) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Snapshot capture must run on server thread");
        List<TrackedBlock> blocks = new ArrayList<>(); List<String> notes = new ArrayList<>();
        Map<Long, PlacementRecord> ledger = build.placements();
        int eligibleEntries = (int) ledger.values().stream()
                .filter(value -> policy.allowDerived() || value.source() != PlacementSource.DERIVED).count();
        int stride = Math.max(1, (int) Math.ceil(eligibleEntries / (double) policy.limits().maxTrackedBlocks()));
        int playerPlaced = 0, derived = 0, eligibleIndex = 0, unloaded = 0;
        for (Map.Entry<Long, PlacementRecord> entry : ledger.entrySet()) {
            PlacementRecord placement = entry.getValue();
            if (placement.source() == PlacementSource.DERIVED && !policy.allowDerived()) continue;
            if (placement.source() == PlacementSource.PLAYER_PLACED) playerPlaced++; else derived++;
            boolean selected = eligibleIndex++ % stride == 0 && blocks.size() < policy.limits().maxTrackedBlocks();
            if (!selected) continue;
            BlockPos pos = BlockPos.of(entry.getKey());
            if (!level.hasChunkAt(pos)) { unloaded++; continue; }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            blocks.add(new TrackedBlock(pos.immutable(), state, placement.source()));
        }
        if (blocks.isEmpty()) return Capture.reject("기록된 건축 블록이 없습니다");
        if (playerPlaced < policy.limits().minimumPlayerPlacedBlocks())
            return Capture.reject("플레이어 직접 설치 블록이 최소 " + policy.limits().minimumPlayerPlacedBlocks() + "개보다 적습니다");
        if (eligibleEntries > blocks.size()) notes.add("전체 추적 블록 " + eligibleEntries + "개 중 "
                + blocks.size() + "개를 대표 표본으로 분석했습니다");
        if (unloaded > 0) notes.add("청크를 강제 로드하지 않고 미로드 표본 " + unloaded + "개를 제외했습니다");

        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        Map<ResourceLocation,Integer> blockCounts=new LinkedHashMap<>(); Set<Long> occupied=new HashSet<>();
        for (TrackedBlock block:blocks) {
            BlockPos p=block.pos(); minX=Math.min(minX,p.getX()); minY=Math.min(minY,p.getY()); minZ=Math.min(minZ,p.getZ());
            maxX=Math.max(maxX,p.getX()); maxY=Math.max(maxY,p.getY()); maxZ=Math.max(maxZ,p.getZ()); occupied.add(p.asLong());
            blockCounts.merge(BuiltInRegistries.BLOCK.getKey(block.state().getBlock()),1,Integer::sum);
        }
        StructureSnapshot.Bounds bounds=new StructureSnapshot.Bounds(minX,minY,minZ,maxX,maxY,maxZ);
        Set<ResourceLocation> requestedTags=new HashSet<>(); Set<ResourceLocation> requestedBiomeTags=new HashSet<>();
        policy.criteria().forEach(c->{c.tag().ifPresent(requestedTags::add);c.biomeTag().ifPresent(requestedBiomeTags::add);});
        Map<ResourceLocation,Integer>tagCounts=new HashMap<>();Map<ResourceLocation,Integer>tagDiversities=new HashMap<>();
        for(ResourceLocation id:requestedTags){ TagKey<Block>tag=TagKey.create(Registries.BLOCK,id); int count=0;Set<ResourceLocation> kinds=new HashSet<>();for(TrackedBlock b:blocks)if(b.state().is(tag)){count++;kinds.add(BuiltInRegistries.BLOCK.getKey(b.state().getBlock()));}tagCounts.put(id,count);tagDiversities.put(id,kinds.size()); }
        Map<ResourceLocation,Double>tagRatios=new HashMap<>(); tagCounts.forEach((id,count)->tagRatios.put(id,count/(double)blocks.size()));

        Map<String,Double> features=new LinkedHashMap<>();
        tagDiversities.forEach((id,count)->features.put("tag_diversity:"+id,count.doubleValue()));
        features.put("height",(double)bounds.height()); features.put("material_diversity",(double)blockCounts.size());
        features.put("palette_cohesion",paletteCohesion(blockCounts,blocks.size()));
        features.put("symmetry",symmetry(occupied,bounds)); features.put("connectivity",connectivity(occupied));
        int lights=0,water=0,waterAdjacent=0,underwater=0,ground=0;
        for(TrackedBlock block:blocks){ BlockPos p=block.pos(); BlockState state=block.state();
            if(state.getLightEmission(level,p)>0)lights++;
            if(!state.getFluidState().isEmpty()&&state.getFluidState().is(Fluids.WATER))water++;
            boolean adjacent=false; for(Direction d:Direction.values())if(level.getFluidState(p.relative(d)).is(Fluids.WATER)){adjacent=true;break;}
            if(adjacent)waterAdjacent++; if(level.getFluidState(p.above()).is(Fluids.WATER))underwater++;
            if(!occupied.contains(p.below().asLong())&&!level.getBlockState(p.below()).isAir())ground++;
        }
        features.put("lighting",(double)lights); features.put("water_ratio",water/(double)blocks.size());
        features.put("water_presence",water>0?1.0D:0.0D); features.put("water_adjacency",waterAdjacent/(double)blocks.size());
        features.put("underwater_ratio",underwater/(double)blocks.size()); features.put("ground_contact",ground/(double)blocks.size());
        int liveDecorations=(int)build.decorations().keySet().stream().filter(id->level.getEntity(id)!=null).count();
        int displayedItems=0;
        for(java.util.UUID entityId:build.decorations().keySet()){
            var entity=level.getEntity(entityId);
            if(entity instanceof ItemFrame frame&&!frame.getItem().isEmpty())displayedItems++;
            if(entity instanceof ArmorStand stand){for(var stack:stand.getHandSlots())if(!stack.isEmpty())displayedItems++;for(var stack:stand.getArmorSlots())if(!stack.isEmpty())displayedItems++;}
        }
        features.put("decoration_density",liveDecorations/(double)Math.max(1,blocks.size()));
        features.put("decoration_count",(double)liveDecorations);
        features.put("displayed_item_count",(double)displayedItems);
        int edgeBlocks=0;for(TrackedBlock block:blocks){BlockPos p=block.pos();if(p.getX()==bounds.minX()||p.getX()==bounds.maxX()||p.getZ()==bounds.minZ()||p.getZ()==bounds.maxZ())edgeBlocks++;}
        double perimeter=Math.max(1D,2D*(bounds.width()+bounds.depth())*Math.max(1,Math.min(4,bounds.height())));
        features.put("defensive_structure",Math.min(1D,edgeBlocks/perimeter*.7D+Math.min(1D,bounds.height()/8D)*.3D));
        analyzeInterior(occupied,bounds,policy.limits(),features,notes);

        Environment environment=sampleEnvironment(level,blocks,bounds,policy,requestedBiomeTags);
        features.putAll(environment.features()); notes.addAll(environment.notes());
        String fingerprint=fingerprint(blocks,bounds);
        return Capture.success(new StructureSnapshot(playerPlaced,derived,liveDecorations,bounds,
                blockCounts,tagRatios,tagCounts,environment.biomes(),environment.biomeTags(),features,notes,fingerprint));
    }

    private static void analyzeInterior(Set<Long> occupied, StructureSnapshot.Bounds b,
            StructureEvaluationPolicy.Limits limits, Map<String,Double> f,List<String>notes){
        long expanded=(long)(b.width()+2)*(b.height()+2)*(b.depth()+2);
        if(expanded>limits.maxSnapshotCells()||expanded>limits.maxFloodFillCells()){
            f.put("interior_volume",0D);f.put("interior_quality",0D);f.put("open_space",0D);
            notes.add("실제 경계 부피가 안전 상한을 넘어 내부 공간 분석을 생략했습니다");return;
        }
        int minX=b.minX()-1,minY=b.minY()-1,minZ=b.minZ()-1,maxX=b.maxX()+1,maxY=b.maxY()+1,maxZ=b.maxZ()+1;
        Set<Long> exterior=new HashSet<>();ArrayDeque<BlockPos>queue=new ArrayDeque<>();BlockPos start=new BlockPos(minX,minY,minZ);queue.add(start);exterior.add(start.asLong());
        while(!queue.isEmpty()){BlockPos p=queue.removeFirst();for(Direction d:Direction.values()){BlockPos n=p.relative(d);if(n.getX()<minX||n.getX()>maxX||n.getY()<minY||n.getY()>maxY||n.getZ()<minZ||n.getZ()>maxZ)continue;long key=n.asLong();if(!occupied.contains(key)&&exterior.add(key))queue.add(n);}}
        int interior=0;Set<Long>walkable=new HashSet<>();
        for(int x=b.minX();x<=b.maxX();x++)for(int y=b.minY();y<=b.maxY();y++)for(int z=b.minZ();z<=b.maxZ();z++){
            BlockPos p=new BlockPos(x,y,z);long key=p.asLong();if(!occupied.contains(key)&&!exterior.contains(key)){interior++;
                if(occupied.contains(p.below().asLong())&&!occupied.contains(p.above().asLong())&&!occupied.contains(p.above(2).asLong()))walkable.add(key);}}
        int open=largestHorizontalComponent(walkable);double enclosure=interior/(double)Math.max(1,b.volume()-occupied.size());
        f.put("interior_volume",(double)interior);f.put("open_space",(double)open);
        double lightScore=Math.min(1D,f.getOrDefault("lighting",0D)/Math.max(4D,interior/80D));
        double decoration=Math.min(1D,f.getOrDefault("decoration_count",0D)/8D);
        f.put("interior_quality",Math.min(1D,enclosure*.55D+lightScore*.3D+decoration*.15D));
    }

    private static Environment sampleEnvironment(ServerLevel level,List<TrackedBlock>blocks,StructureSnapshot.Bounds b,
            StructureEvaluationPolicy policy,Set<ResourceLocation> biomeTags){
        Map<String,Double>f=new LinkedHashMap<>();List<String>notes=new ArrayList<>();int max=policy.limits().maxEnvironmentSamples();
        List<TrackedBlock>sorted=blocks.stream().sorted(Comparator.comparingLong(v->v.pos().asLong())).toList();
        int samples=Math.min(max,sorted.size()),sky=0,water=0,vegetation=0,lava=0,stone=0,sand=0,snow=0,read=0;
        for(int i=0;i<samples;i++){BlockPos center=sorted.get((int)((long)i*sorted.size()/samples)).pos();
            if(level.canSeeSky(center.above()))sky++;
            int hr=policy.limits().environmentHorizontalRadius(),vr=policy.limits().environmentVerticalRadius();
            for(int dx=-hr;dx<=hr;dx+=2)for(int dz=-hr;dz<=hr;dz+=2)for(int dy=-vr;dy<=vr;dy+=2){BlockPos p=center.offset(dx,dy,dz);if(!level.hasChunkAt(p))continue;read++;BlockState s=level.getBlockState(p);if(s.getFluidState().is(Fluids.WATER))water++;if(s.getFluidState().is(Fluids.LAVA))lava++;ResourceLocation blockId=BuiltInRegistries.BLOCK.getKey(s.getBlock());String path=blockId.getPath();if(path.contains("leaves")||path.contains("flower")||path.contains("grass")||path.contains("vine"))vegetation++;if(path.contains("stone")||path.contains("deepslate"))stone++;if(path.contains("sand"))sand++;if(path.contains("snow")||path.contains("ice"))snow++;}}
        f.put("sky_visibility",sky/(double)Math.max(1,samples));f.put("underground_ratio",1D-sky/(double)Math.max(1,samples));f.put("environment_water_ratio",water/(double)Math.max(1,read));f.put("environment_vegetation_ratio",vegetation/(double)Math.max(1,read));f.put("environment_lava_ratio",lava/(double)Math.max(1,read));f.put("environment_stone_ratio",stone/(double)Math.max(1,read));f.put("environment_sand_ratio",sand/(double)Math.max(1,read));f.put("environment_snow_ice_ratio",snow/(double)Math.max(1,read));
        Map<ResourceLocation,Integer>counts=new LinkedHashMap<>();Map<ResourceLocation,Integer>tagCounts=new LinkedHashMap<>();int grid=5,total=0,midY=(b.minY()+b.maxY())/2;
        for(int gx=0;gx<grid;gx++)for(int gz=0;gz<grid;gz++){int x=grid==1?b.minX():b.minX()+(int)((long)gx*(b.maxX()-b.minX())/(grid-1));int z=grid==1?b.minZ():b.minZ()+(int)((long)gz*(b.maxZ()-b.minZ())/(grid-1));BlockPos p=new BlockPos(x,midY,z);if(!level.hasChunkAt(p))continue;Holder<Biome> biome=level.getBiome(p);ResourceLocation id=biome.unwrapKey().map(k->k.location()).orElse(ResourceLocation.withDefaultNamespace("unknown"));counts.merge(id,1,Integer::sum);for(ResourceLocation tag:biomeTags)if(biome.is(TagKey.create(Registries.BIOME,tag)))tagCounts.merge(tag,1,Integer::sum);total++;}
        Map<ResourceLocation,Double>ratios=new LinkedHashMap<>();Map<ResourceLocation,Double>tagRatios=new LinkedHashMap<>();int denominator=Math.max(1,total);counts.forEach((id,c)->ratios.put(id,c/(double)denominator));tagCounts.forEach((id,c)->tagRatios.put(id,c/(double)denominator));
        if(total<25)notes.add("로드되지 않은 청크 때문에 일부 바이옴 표본을 제외했습니다");return new Environment(f,ratios,tagRatios,notes);
    }

    private static double symmetry(Set<Long>occupied,StructureSnapshot.Bounds b){int matchesX=0,matchesZ=0;for(long key:occupied){BlockPos p=BlockPos.of(key);if(occupied.contains(new BlockPos(b.minX()+b.maxX()-p.getX(),p.getY(),p.getZ()).asLong()))matchesX++;if(occupied.contains(new BlockPos(p.getX(),p.getY(),b.minZ()+b.maxZ()-p.getZ()).asLong()))matchesZ++;}return Math.max(matchesX,matchesZ)/(double)Math.max(1,occupied.size());}
    private static double connectivity(Set<Long>occupied){if(occupied.isEmpty())return 0D;Set<Long>left=new HashSet<>(occupied);int largest=0;while(!left.isEmpty()){long first=left.iterator().next();left.remove(first);ArrayDeque<BlockPos>q=new ArrayDeque<>();q.add(BlockPos.of(first));int size=0;while(!q.isEmpty()){BlockPos p=q.removeFirst();size++;for(Direction d:Direction.values()){long n=p.relative(d).asLong();if(left.remove(n))q.add(BlockPos.of(n));}}largest=Math.max(largest,size);}return largest/(double)occupied.size();}
    private static int largestHorizontalComponent(Set<Long>positions){Set<Long>left=new HashSet<>(positions);int largest=0;while(!left.isEmpty()){long first=left.iterator().next();left.remove(first);ArrayDeque<BlockPos>q=new ArrayDeque<>();q.add(BlockPos.of(first));int size=0;while(!q.isEmpty()){BlockPos p=q.removeFirst();size++;for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}){long n=p.relative(d).asLong();if(left.remove(n))q.add(BlockPos.of(n));}}largest=Math.max(largest,size);}return largest;}
    private static double paletteCohesion(Map<ResourceLocation,Integer>counts,int total){if(total==0)return 0D;int diversity=counts.size(),largest=counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);double diversityScore=diversity<=6?diversity/6D:Math.max(0D,1D-(diversity-6)/18D);double dominant=largest/(double)total;double balance=dominant<=.5?1D:Math.max(0D,1D-(dominant-.5)/.45);return diversityScore*.45+balance*.55;}
    private static String fingerprint(List<TrackedBlock>blocks,StructureSnapshot.Bounds b){try{MessageDigest digest=MessageDigest.getInstance("SHA-256");blocks.stream().sorted(Comparator.comparingLong(v->v.pos().asLong())).forEach(v->{BlockPos p=v.pos();String row=(p.getX()-b.minX())+","+(p.getY()-b.minY())+","+(p.getZ()-b.minZ())+":"+BuiltInRegistries.BLOCK.getKey(v.state().getBlock())+";";digest.update(row.getBytes(StandardCharsets.UTF_8));});return java.util.HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}

    private record TrackedBlock(BlockPos pos,BlockState state,PlacementSource source){}
    private record Environment(Map<String,Double>features,Map<ResourceLocation,Double>biomes,Map<ResourceLocation,Double>biomeTags,List<String>notes){}
    public record Capture(StructureSnapshot snapshot,String rejectionReason){public static Capture success(StructureSnapshot snapshot){return new Capture(snapshot,"");}public static Capture reject(String reason){return new Capture(null,reason);}public boolean succeeded(){return snapshot!=null;}}
}
