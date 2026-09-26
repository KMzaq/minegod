package com.sande.mythictrpg.quest.structure;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StructureEvaluationGameTests {
    private static final String TEMPLATE="bastion/mobs/empty";
    private StructureEvaluationGameTests(){}

    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    public static void regionHasNoHorizontalSizeLimit(GameTestHelper helper){
        var dimension=helper.getLevel().dimension();
        StructureRegion region=StructureRegion.between(dimension,new BlockPos(-1024,0,-768),new BlockPos(1024,300,768));
        helper.assertValueEqual(region.width(),2049,"Large region width was clipped");
        helper.assertValueEqual(region.depth(),1537,"Large region depth was clipped");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    public static void sparseSnapshotUsesLedgerAndUnlimitedVerticalBounds(GameTestHelper helper){
        var level=helper.getLevel();UUID owner=UUID.randomUUID();ResourceLocation quest=id("test_vertical");
        BlockPos low=new BlockPos(helper.absolutePos(BlockPos.ZERO).getX(),level.getMinBuildHeight()+5,helper.absolutePos(BlockPos.ZERO).getZ());
        BlockPos high=new BlockPos(low.getX(),level.getMaxBuildHeight()-6,low.getZ());
        level.setBlockAndUpdate(low,Blocks.WHITE_CONCRETE.defaultBlockState());level.setBlockAndUpdate(high,Blocks.GLASS.defaultBlockState());
        level.setBlockAndUpdate(low.east(),Blocks.COBBLESTONE.defaultBlockState());
        StructureBuildRecord build=new StructureBuildRecord(owner,quest,new StructureRegion(level.dimension(),low.getX(),low.getX()+1,low.getZ(),low.getZ()),Set.of(owner),0);
        build.record(low,new PlacementRecord(owner,1,PlacementSource.PLAYER_PLACED),10);
        build.record(high,new PlacementRecord(owner,2,PlacementSource.PLAYER_PLACED),10);
        var capture=StructureSnapshotService.capture(level,build,policy(10,1));
        helper.assertTrue(capture.succeeded(),"Sparse vertical snapshot was rejected: "+capture.rejectionReason());
        helper.assertTrue(capture.snapshot().bounds().height()>300,"Full dimension-height structure was clipped");
        helper.assertFalse(capture.snapshot().blockCounts().containsKey(ResourceLocation.withDefaultNamespace("cobblestone")),"Untracked existing block entered BUILD score");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    public static void ledgerRejectsNonContributorAndSamplesPastPolicyLimit(GameTestHelper helper){
        var level=helper.getLevel();UUID owner=UUID.randomUUID(),outsider=UUID.randomUUID();BlockPos a=helper.absolutePos(BlockPos.ZERO),b=a.above();
        level.setBlockAndUpdate(a,Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(b,Blocks.STONE.defaultBlockState());
        StructureBuildRecord build=new StructureBuildRecord(owner,id("limit"),new StructureRegion(level.dimension(),a.getX(),a.getX(),a.getZ(),a.getZ()),Set.of(owner),0);
        helper.assertFalse(build.record(a,new PlacementRecord(outsider,1,PlacementSource.PLAYER_PLACED),10),"Non-contributor entered ledger");
        build.record(a,new PlacementRecord(owner,1,PlacementSource.PLAYER_PLACED),10);build.record(b,new PlacementRecord(owner,2,PlacementSource.PLAYER_PLACED),10);
        var capture=StructureSnapshotService.capture(level,build,policy(1,1));
        helper.assertTrue(capture.succeeded(),"Oversized ledger should be sampled instead of rejected");
        int sampled=capture.snapshot().blockCounts().values().stream().mapToInt(Integer::intValue).sum();
        helper.assertValueEqual(sampled,1,"Representative sample limit was not applied");
        helper.assertValueEqual(capture.snapshot().totalBlocks(),2,"Full provenance count should remain in evidence");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    public static void fortunaModernPolicyIsDatapackDrivenAndDiscriminates(GameTestHelper helper){
        ResourceLocation policyId=id("fortuna_modern");var policy=StructureEvaluationPolicyManager.INSTANCE.find(policyId).orElse(null);
        helper.assertTrue(policy!=null,"Bundled Fortuna policy was not loaded");
        helper.assertValueEqual(policy.godId(),id("fortuna"),"Fortuna policy has wrong God ID");
        helper.assertTrue(policy.visualProfile().isPresent(),"Fortuna visual preference profile was not loaded");
        helper.assertValueEqual(policy.visualProfile().orElseThrow().visualWeight(),30,
                "Visual score contribution must be capped at 30 percent");
        ResourceLocation modern=id("fortuna_modern_materials"),glazing=id("modern_glazing"),functional=id("functional_interior");
        StructureSnapshot good=snapshot(Map.of(modern,.55,glazing,.2),Map.of(modern,110,glazing,40,functional,14),Map.of("palette_cohesion",.9,"symmetry",.9,"open_space",180D,"interior_quality",.85,"lighting",20D,"sky_visibility",.8,"ground_contact",.8));
        StructureSnapshot poor=snapshot(Map.of(modern,0D,glazing,0D),Map.of(modern,0,glazing,0,functional,0),Map.of("palette_cohesion",.15,"symmetry",.1,"open_space",0D,"interior_quality",0D,"lighting",0D,"sky_visibility",.4,"ground_contact",.4));
        int goodScore=StructureCriterionRegistry.evaluate(good,policy).score(),poorScore=StructureCriterionRegistry.evaluate(poor,policy).score();
        helper.assertTrue(goodScore>poorScore+40,"Modern design did not substantially outscore the plain structure");
        helper.assertTrue(goodScore>=0&&goodScore<=100&&poorScore>=0&&poorScore<=100,"Score escaped 0..100");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    public static void headlessRendererProducesFivePngViews(GameTestHelper helper){
        var level=helper.getLevel();UUID owner=UUID.randomUUID();BlockPos origin=helper.absolutePos(BlockPos.ZERO);
        StructureBuildRecord build=new StructureBuildRecord(owner,id("render"),
                new StructureRegion(level.dimension(),origin.getX(),origin.getX()+2,origin.getZ(),origin.getZ()+2),Set.of(owner),0);
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)for(int y=0;y<2;y++){
            BlockPos pos=origin.offset(x,y,z);level.setBlockAndUpdate(pos,(x==1&&z==1?Blocks.GLASS:Blocks.WHITE_CONCRETE).defaultBlockState());
            build.record(pos,new PlacementRecord(owner,1,PlacementSource.PLAYER_PLACED),100);
        }
        var capture=StructureSnapshotService.capture(level,build,policy(100,1));
        helper.assertTrue(capture.succeeded(),"Render snapshot failed: "+capture.rejectionReason());
        var views=StructureVoxelRenderService.render(StructureVoxelRenderService.capture(level,build,capture.snapshot()));
        helper.assertValueEqual(views.size(),5,"Renderer did not produce four isometric views and one top view");
        try{
            for(var view:views){var image=ImageIO.read(new ByteArrayInputStream(view.pngBytes()));
                helper.assertTrue(image!=null&&image.getWidth()==512&&image.getHeight()==512,"Invalid rendered PNG "+view.name());
                Set<Integer> colors=new java.util.HashSet<>();for(int x=0;x<512;x+=8)for(int y=0;y<512;y+=8)colors.add(image.getRGB(x,y));
                helper.assertTrue(colors.size()>1,"Rendered PNG appears blank "+view.name());}
        }catch(java.io.IOException exception){helper.fail("Could not decode rendered PNG: "+exception);return;}
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    public static void visualScoreIsBoundedAndLowConfidenceFallsBack(GameTestHelper helper){
        var server=helper.getLevel().getServer();var state=PlayerConstructionState.get(server);
        UUID owner=UUID.randomUUID();ResourceLocation policyId=id("fortuna_modern");
        StructureSnapshot snapshot=snapshot(Map.of(),Map.of(),Map.of());
        StructureEvaluationReport report=new StructureEvaluationReport(policyId,id("fortuna"),50,50,50,List.of(),snapshot,"objective");
        String source="free:"+UUID.randomUUID();state.recordEvaluation(source,owner,Set.of(owner),report,helper.getLevel().getGameTime());
        var profile=new StructureEvaluationPolicy.VisualProfile(List.of("modern"),List.of("HOUSE"),"modern geometry",30,.68);
        var confident=new StructureVisualAssessment(StructureVisualAssessment.BuildingType.HOUSE,"modern house",
                List.of("modern"),.9,85,90,80,List.of("clean facade"),List.of(),"test");
        helper.assertTrue(state.applyVisualEvaluation(source,policyId,"test",confident,profile),"Confident visual result was rejected");
        helper.assertValueEqual(state.evaluation(source,policyId).orElseThrow().score(),62,
                "70/30 objective and visual score was not applied");
        String lowSource="free:"+UUID.randomUUID();state.recordEvaluation(lowSource,owner,Set.of(owner),report,helper.getLevel().getGameTime());
        var uncertain=new StructureVisualAssessment(StructureVisualAssessment.BuildingType.UNKNOWN,"",List.of(),.3,100,100,100,List.of(),List.of(),"test");
        helper.assertTrue(state.applyVisualEvaluation(lowSource,policyId,"test",uncertain,profile),"Low-confidence result should still be stored");
        helper.assertValueEqual(state.evaluation(lowSource,policyId).orElseThrow().score(),50,
                "Low-confidence visual result changed authoritative score");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template=TEMPLATE)
    public static void strictPolicyParserRejectsUnknownFields(GameTestHelper helper){
        String json="{\"schemaVersion\":1,\"id\":\"mythictrpg:x\",\"godId\":\"mythictrpg:fortuna\",\"region\":{\"maxWidth\":48,\"maxDepth\":48},\"limits\":{\"maxTrackedBlocks\":10,\"minimumPlayerPlacedBlocks\":1,\"maxSnapshotCells\":1000,\"maxFloodFillCells\":1000,\"maxEnvironmentSamples\":4,\"environmentHorizontalRadius\":1,\"environmentVerticalRadius\":1},\"buildWeight\":100,\"environmentWeight\":0,\"criteria\":[{\"id\":\"a\",\"scope\":\"BUILD\",\"type\":\"block_count\",\"weight\":1}],\"unknown\":true}";
        try{StructureEvaluationPolicyManager.parsePolicy(id("x"),JsonParser.parseString(json).getAsJsonObject());helper.fail("Unknown policy field was accepted");return;}catch(IllegalArgumentException expected){}
        helper.succeed();
    }

    private static StructureEvaluationPolicy policy(int max,int minimum){return new StructureEvaluationPolicy(id("test_policy"),id("fortuna"),new StructureEvaluationPolicy.RegionLimit(48,48),new StructureEvaluationPolicy.Limits(max,minimum,500000,500000,4,1,1),100,0,true,false,List.of(new StructureEvaluationPolicy.Criterion("size",StructureEvaluationPolicy.Scope.BUILD,"block_count",1,Optional.empty(),Set.of(),Set.of(),Optional.empty(),0,1,50000,StructureEvaluationPolicy.Curve.LINEAR,List.of())));}
    private static StructureSnapshot snapshot(Map<ResourceLocation,Double>ratios,Map<ResourceLocation,Integer>counts,Map<String,Double>features){return new StructureSnapshot(200,0,0,new StructureSnapshot.Bounds(0,0,0,9,4,9),Map.of(ResourceLocation.withDefaultNamespace("white_concrete"),200),ratios,counts,Map.of(),Map.of(),features,List.of(),"test");}
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,path);}
}
