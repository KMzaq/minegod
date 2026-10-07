package com.sande.mythictrpg.godavatar.activity.work;

import com.sande.mythictrpg.godavatar.*;
import com.sande.mythictrpg.godavatar.activity.*;
import com.sande.mythictrpg.ai.action.AiActionTemplateManager;
import com.sande.mythictrpg.ai.action.NpcRitualEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Actual vanilla recipes, material conservation and crop state; no LLM and no synthetic player inventory source. */
@GameTestHolder("mythictrpg_npc_work")
@PrefixGameTestTemplate(false)
public final class NpcActivityWorkGameTests {
    @GameTest(templateNamespace="mythictrpg_npc_work",template="empty")
    public static void resourcesCraftCookRepairConsumeAndHarvestWithoutDuplication(GameTestHelper helper) throws Exception {
        var level=helper.getLevel(); var site=helper.absolutePos(new BlockPos(2,2,2));
        var god=ResourceLocation.parse("mythictrpg:fortuna");
        var avatar=GodAvatarEntities.GOD_AVATAR.get().create(level);
        if(avatar==null)throw new AssertionError("avatar missing");
        var binding=GodAvatarEntity.class.getDeclaredMethod("bind",ResourceLocation.class);binding.setAccessible(true);binding.invoke(avatar,god);
        var registry=GodAvatarRegistryState.get(level.getServer());
        helper.assertTrue(registry.reserve(god,avatar.getUUID(),level.dimension().location()),"fixture God was already reserved");
        avatar.setPos(site.getX()+0.5,site.getY()+0.5,site.getZ()+0.5);
        var storagePos=site.east();level.setBlockAndUpdate(storagePos,Blocks.BARREL.defaultBlockState());
        var chest=(BarrelBlockEntity)level.getBlockEntity(storagePos);
        var npc=avatar.activityInventory();
        try {
            level.setBlockAndUpdate(site,Blocks.CRAFTING_TABLE.defaultBlockState());
            fill(npc,Items.COBBLESTONE);fill(chest,Items.COBBLESTONE);chest.setItem(0,new ItemStack(Items.OAK_LOG,64));
            var craft=def(ActivityKind.CRAFT,Map.of("recipe_id","minecraft:oak_planks","grid","minecraft:oak_log,_,_,_,_,_,_,_,_"));
            helper.assertTrue(!NpcActivityWork.available(avatar,site,craft),"full output storage reported available");
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,craft).success(),"full output storage crafted");
            helper.assertValueEqual(chest.getItem(0).getCount(),64,"failed craft consumed input");
            npc.setItem(0,ItemStack.EMPTY);
            helper.assertTrue(NpcActivityWork.available(avatar,site,craft),"vanilla crafting recipe unavailable");
            helper.assertValueEqual(chest.getItem(0).getCount(),64,"availability query consumed input");
            ok(helper,NpcActivityWork.execute(avatar,site,craft),"craft");
            helper.assertValueEqual(chest.getItem(0).getCount(),63,"craft input count");
            helper.assertValueEqual(count(npc,Items.OAK_PLANKS),4,"craft output count");

            npc.clearContent();chest.clearContent();chest.setItem(0,new ItemStack(Items.OAK_LOG,2));
            var nested=new java.util.concurrent.atomic.AtomicReference<NpcActivityWork.Outcome>();
            var nestedCalled=new java.util.concurrent.atomic.AtomicBoolean();
            ContainerListener recursive=c -> {if(count(c,Items.OAK_PLANKS)>0 && nestedCalled.compareAndSet(false,true))nested.set(NpcActivityWork.execute(avatar,site,craft));};
            npc.addListener(recursive);
            try{ok(helper,NpcActivityWork.execute(avatar,site,craft),"outer transaction with recursive listener");}
            finally{npc.removeListener(recursive);}
            helper.assertTrue(nested.get()!=null && !nested.get().success() && nested.get().detail().equals("REENTRANT_ACTIVITY_WORK_REJECTED"),"same-avatar recursive work was not rejected");
            helper.assertValueEqual(count(chest,Items.OAK_LOG),1,"recursive work spent an extra input");
            helper.assertValueEqual(count(npc,Items.OAK_PLANKS),4,"recursive work created extra output");

            // A normal exception from a supported store listener rolls back every input/output.
            npc.clearContent();chest.clearContent();chest.setItem(0,new ItemStack(Items.OAK_LOG));
            var throwOnce=new java.util.concurrent.atomic.AtomicBoolean(true);
            ContainerListener listener=c -> {if(count(c,Items.OAK_PLANKS)>0 && throwOnce.getAndSet(false))throw new IllegalStateException("fixture listener");};
            npc.addListener(listener);
            try {helper.assertTrue(!NpcActivityWork.execute(avatar,site,craft).success(),"listener failure reported success");}
            finally{npc.removeListener(listener);}
            helper.assertValueEqual(count(chest,Items.OAK_LOG),1,"failed transaction did not restore material");
            helper.assertValueEqual(count(npc,Items.OAK_PLANKS),0,"failed transaction retained output");

            var permissions=NpcActivityWorldState.get(level.getServer());
            permissions.deny("npc_work_fixture",new NpcActivityWorldState.Exclusion(level.dimension().location(),storagePos,0));
            try {
                helper.assertTrue(!NpcActivityWork.available(avatar,site,craft),"OP-protected material source was available");
                helper.assertTrue(!NpcActivityWork.execute(avatar,site,craft).success(),"OP-protected material source was consumed");
                helper.assertValueEqual(count(chest,Items.OAK_LOG),1,"hard protection did not preserve source");
            }finally{permissions.allow("npc_work_fixture");}

            // Adversarial callback: once credited, revoke all access so a legal rollback is impossible.
            // The work must not refund inputs while the recipient still retains its output.
            ContainerListener revokeOnCredit=c -> {if(count(c,Items.OAK_PLANKS)>0)permissions.deny("npc_work_reentry",new NpcActivityWorldState.Exclusion(level.dimension().location(),site,0));};
            npc.addListener(revokeOnCredit);
            NpcActivityWork.Outcome interrupted;
            try {interrupted=NpcActivityWork.execute(avatar,site,craft);}
            finally{npc.removeListener(revokeOnCredit);permissions.allow("npc_work_reentry");}
            helper.assertTrue(!interrupted.success() && interrupted.detail().equals("EXTERNAL_REENTRY_INTERRUPTED_TRANSACTION_NO_REFUND"),"foreign reentry was not reported explicitly");
            helper.assertValueEqual(count(chest,Items.OAK_LOG),0,"retained output was refunded into free items");
            helper.assertValueEqual(count(npc,Items.OAK_PLANKS),4,"debited craft lost its retained output");

            npc.clearContent();chest.clearContent();
            chest.setItem(0,new ItemStack(Items.MILK_BUCKET,1));chest.setItem(1,new ItemStack(Items.MILK_BUCKET));chest.setItem(2,new ItemStack(Items.MILK_BUCKET));
            chest.setItem(3,new ItemStack(Items.SUGAR,2));chest.setItem(4,new ItemStack(Items.EGG));chest.setItem(5,new ItemStack(Items.WHEAT,3));
            var cake=def(ActivityKind.CRAFT,Map.of("recipe_id","minecraft:cake","grid","minecraft:milk_bucket,minecraft:milk_bucket,minecraft:milk_bucket,minecraft:sugar,minecraft:egg,minecraft:sugar,minecraft:wheat,minecraft:wheat,minecraft:wheat"));
            ok(helper,NpcActivityWork.execute(avatar,site,cake),"craft with remaining containers");
            helper.assertValueEqual(count(npc,Items.CAKE),1,"cake output");helper.assertValueEqual(count(npc,Items.BUCKET),3,"craft bucket remainders lost or duplicated");

            npc.clearContent();chest.clearContent();level.setBlockAndUpdate(site,Blocks.FURNACE.defaultBlockState());
            chest.setItem(0,new ItemStack(Items.POTATO));chest.setItem(1,new ItemStack(Items.COAL));
            var cook=def(ActivityKind.COOK,Map.of("recipe_id","minecraft:baked_potato","input_id","minecraft:potato","fuel_id","minecraft:coal"));
            ok(helper,NpcActivityWork.execute(avatar,site,cook),"cook");
            helper.assertValueEqual(count(chest,Items.POTATO)+count(chest,Items.COAL),0,"cooking did not consume food and fuel");
            helper.assertValueEqual(count(npc,Items.BAKED_POTATO),1,"cooked output absent");
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,cook).success(),"cooking repeated without inputs");
            chest.setItem(0,new ItemStack(Items.POTATO));
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,cook).success(),"cooking without fuel succeeded");
            helper.assertValueEqual(count(chest,Items.POTATO),1,"missing fuel consumed raw food");
            chest.setItem(1,new ItemStack(Items.LAVA_BUCKET));
            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.COOK,Map.of("recipe_id","minecraft:baked_potato","input_id","minecraft:potato","fuel_id","minecraft:lava_bucket"))),"cooking with fuel remainder");
            helper.assertValueEqual(count(npc,Items.BUCKET),1,"lava fuel bucket lost or duplicated");
            helper.assertValueEqual(count(npc,Items.BAKED_POTATO),2,"lava fuel cooked output count");

            npc.clearContent();chest.clearContent();level.setBlockAndUpdate(site,Blocks.CRAFTING_TABLE.defaultBlockState());
            var first=new ItemStack(Items.IRON_PICKAXE);first.setDamageValue(200);var second=first.copy();
            npc.setItem(0,first);chest.setItem(0,second);
            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.REPAIR,Map.of("item_id","minecraft:iron_pickaxe"))),"vanilla two-item repair");
            helper.assertValueEqual(count(npc,Items.IRON_PICKAXE)+count(chest,Items.IRON_PICKAXE),1,"repair duplicated tool");
            helper.assertTrue(npc.getItem(0).getDamageValue()<200,"repair did not restore durability");

            npc.clearContent();chest.clearContent();npc.setItem(0,new ItemStack(Items.MUSHROOM_STEW));
            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.EAT,Map.of("item_id","minecraft:mushroom_stew"))),"eat");
            helper.assertValueEqual(count(npc,Items.MUSHROOM_STEW),0,"food not consumed");helper.assertValueEqual(count(npc,Items.BOWL),1,"food bowl lost");
            npc.clearContent();var potion=new ItemStack(Items.POTION);potion.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.SWIFTNESS));npc.setItem(0,potion);
            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.DRINK,Map.of("item_id","minecraft:potion"))),"drink");
            helper.assertValueEqual(count(npc,Items.POTION),0,"NPC potion was not consumed");helper.assertValueEqual(count(npc,Items.GLASS_BOTTLE),1,"potion bottle missing");
            helper.assertTrue(!avatar.getActiveEffects().isEmpty(),"actual potion effect not applied");
            npc.setItem(1,new ItemStack(Items.MILK_BUCKET));
            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.DRINK,Map.of("item_id","minecraft:milk_bucket"))),"milk");
            helper.assertTrue(avatar.getActiveEffects().isEmpty(),"milk failed to clear effects");helper.assertValueEqual(count(npc,Items.BUCKET),1,"milk bucket missing");
            npc.clearContent();avatar.addEffect(new MobEffectInstance(MobEffects.POISON,200));avatar.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,200));
            npc.setItem(0,new ItemStack(Items.HONEY_BOTTLE));
            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.DRINK,Map.of("item_id","minecraft:honey_bottle"))),"honey");
            helper.assertTrue(!avatar.hasEffect(MobEffects.POISON) && avatar.hasEffect(MobEffects.MOVEMENT_SPEED),"honey did not preserve vanilla cure policy");
            helper.assertValueEqual(count(npc,Items.GLASS_BOTTLE),1,"honey bottle missing");
            npc.clearContent();var stew=new ItemStack(Items.SUSPICIOUS_STEW);
            stew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS,new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.JUMP,200))));npc.setItem(0,stew);
            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.EAT,Map.of("item_id","minecraft:suspicious_stew"))),"suspicious stew");
            helper.assertTrue(avatar.hasEffect(MobEffects.JUMP),"suspicious stew component effect missing");helper.assertValueEqual(count(npc,Items.BOWL),1,"suspicious stew bowl missing");
            npc.clearContent();npc.setItem(0,new ItemStack(Items.CHORUS_FRUIT));
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,def(ActivityKind.EAT,Map.of("item_id","minecraft:chorus_fruit"))).success(),"special teleport food was silently impersonated");
            helper.assertValueEqual(count(npc,Items.CHORUS_FRUIT),1,"unsupported special food consumed input");

            npc.clearContent();chest.clearContent();chest.setItem(0,new ItemStack(Items.GOLD_INGOT,2));
            var offering=def(ActivityKind.OFFERING,Map.of("item_id","minecraft:gold_ingot","count","2"));
            ok(helper,NpcActivityWork.execute(avatar,site,offering),"offering");
            helper.assertValueEqual(count(npc,Items.GOLD_INGOT),2,"offering NPC target missing");helper.assertValueEqual(count(chest,Items.GOLD_INGOT),0,"offering world source not consumed");
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,offering).success(),"NPC's own offerings were reaccepted as new gifts");
            helper.assertValueEqual(count(npc,Items.GOLD_INGOT),2,"offering duplicate payout");
            npc.clearContent();var player=helper.makeMockServerPlayerInLevel();player.getInventory().add(new ItemStack(Items.GOLD_INGOT,64));
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,offering).success(),"player inventory was treated as an activity source");
            helper.assertValueEqual(player.getInventory().countItem(Items.GOLD_INGOT),64,"activity consumed player items");

            npc.setItem(0,new ItemStack(Items.WHEAT_SEEDS,2));level.setBlockAndUpdate(site.below(),Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(site,((CropBlock)Blocks.WHEAT).getStateForAge(7));
            var farm=def(ActivityKind.FARM,Map.of("crop_id","minecraft:wheat"));
            helper.assertTrue(NpcActivityWork.available(avatar,site,farm),"mature crop unavailable");
            helper.assertValueEqual(((CropBlock)Blocks.WHEAT).getAge(level.getBlockState(site)),7,"availability mutated crop");
            ok(helper,NpcActivityWork.execute(avatar,site,farm),"farm");
            helper.assertValueEqual(((CropBlock)Blocks.WHEAT).getAge(level.getBlockState(site)),0,"harvest did not replant");
            helper.assertTrue(count(npc,Items.WHEAT)>=1,"harvest was not stored");
            int wheat=count(npc,Items.WHEAT);helper.assertTrue(!NpcActivityWork.execute(avatar,site,farm).success(),"immature crop duplicated loot");
            helper.assertValueEqual(count(npc,Items.WHEAT),wheat,"failed repeat created wheat");

            ok(helper,NpcActivityWork.execute(avatar,site,def(ActivityKind.RITUAL,Map.of("template_id","mythictrpg:fortuna_sparkles"))),"authored ritual presentation");
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,def(ActivityKind.RITUAL,Map.of("template_id","mythictrpg:fortuna_luck_blessing"))).success(),"ritual bypassed player blessing confirmation");
            rejectNonFiniteRituals(helper,avatar,site,god);
            var decorative=new NpcActivityDefinition(ResourceLocation.parse("test:decorative"),ActivityKind.OFFERING,NpcActivityDefinition.Mode.DECORATIVE,Set.of("test"),200,Map.of("item_id","minecraft:gold_ingot","count","1"));
            helper.assertTrue(!NpcActivityWork.execute(avatar,site,decorative).success(),"decorative activity consumed actual resources");
            helper.succeed();
        } finally {registry.release(god,avatar.getUUID());avatar.discard();}
    }
    private static NpcActivityDefinition def(ActivityKind kind,Map<String,String> parameters){return new NpcActivityDefinition(ResourceLocation.parse("test:"+kind.name().toLowerCase(Locale.ROOT)),kind,NpcActivityDefinition.Mode.REAL,Set.of("test"),400,parameters);}
    private static int count(Container inventory,Item item){int total=0;for(int i=0;i<inventory.getContainerSize();i++)if(inventory.getItem(i).is(item))total+=inventory.getItem(i).getCount();return total;}
    private static void fill(Container inventory,Item item){for(int i=0;i<inventory.getContainerSize();i++)inventory.setItem(i,new ItemStack(item,64));}
    private static void ok(GameTestHelper helper,NpcActivityWork.Outcome result,String label){helper.assertTrue(result.success(),label+": "+result.detail());}
    private static void rejectNonFiniteRituals(GameTestHelper helper,GodAvatarEntity avatar,BlockPos site,ResourceLocation god) throws Exception {
        // Fault-inject malformed authored templates without broadening the public template API.
        var manager=AiActionTemplateManager.INSTANCE;
        var field=AiActionTemplateManager.class.getDeclaredField("templates");field.setAccessible(true);
        Object original=field.get(manager);
        var templateClass=Class.forName("com.sande.mythictrpg.ai.action.WorldInteractionTemplate");
        var kindClass=Class.forName("com.sande.mythictrpg.ai.action.WorldInteractionTemplate$EventKind");
        var constructor=templateClass.getDeclaredConstructor(ResourceLocation.class,ResourceLocation.class,kindClass,
                ResourceLocation.class,int.class,double.class,double.class,float.class,float.class);constructor.setAccessible(true);
        Object particle=Arrays.stream(kindClass.getEnumConstants()).filter(v -> v.toString().equals("PARTICLE")).findFirst().orElseThrow();
        Object sound=Arrays.stream(kindClass.getEnumConstants()).filter(v -> v.toString().equals("SOUND")).findFirst().orElseThrow();
        try {
            for(int i=0;i<4;i++) {
                ResourceLocation id=ResourceLocation.parse("test:nonfinite_ritual_"+i);
                Object malformed=constructor.newInstance(id,god,i<2?particle:sound,
                        ResourceLocation.parse(i<2?"minecraft:happy_villager":"minecraft:entity.player.levelup"),1,
                        i==0?Double.NaN:0D,i==1?Double.NaN:0D,i==2?Float.NaN:1F,i==3?Float.NaN:1F);
                var values=new LinkedHashMap<ResourceLocation,Object>();
                ((Map<?,?>)original).forEach((key,value)->values.put((ResourceLocation)key,value));values.put(id,malformed);
                field.set(manager,Map.copyOf(values));
                helper.assertTrue(!NpcRitualEffects.available(avatar,site,id),"nonfinite ritual was available: "+i);
                helper.assertTrue(!NpcActivityWork.execute(avatar,site,def(ActivityKind.RITUAL,Map.of("template_id",id.toString()))).success(),"nonfinite ritual executed: "+i);
            }
        }finally{field.set(manager,original);}
    }
}
