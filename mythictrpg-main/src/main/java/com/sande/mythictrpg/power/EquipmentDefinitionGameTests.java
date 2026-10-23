package com.sande.mythictrpg.power;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.equipment.EquipmentCatalog;
import com.sande.mythictrpg.equipment.EquipmentDefinition;
import com.sande.mythictrpg.quest.dynamic.CombatPowerAssessment;
import com.sande.mythictrpg.quest.dynamic.CombatPowerService;
import com.sande.mythictrpg.quest.reward.*;
import com.sande.mythictrpg.weaponskill.WeaponSkillRuntime;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

@GameTestHolder("mythictrpg_prototype_catalog")
@PrefixGameTestTemplate(false)
public final class EquipmentDefinitionGameTests {
    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_definition")
    public static void sharedAppearancesHaveDistinctCanonicalDefinitionsAndOnePowerSource(GameTestHelper h) {
        var loaded=CombatPowerPolicyManager.INSTANCE.current();var baseline=CombatPowerPolicy.parse("fixture",source("/data/mythictrpg/mythictrpg/combat_power/default.json"));
        h.assertTrue(loaded.policy()!=null&&loaded.policy().items().subList(0,baseline.items().size()).equals(baseline.items()),"equipment loading changed original power rows");
        int expected=4+(com.sande.mythictrpg.equipment.SimplySwordAppearances.enabled()?4:0);
        h.assertTrue(EquipmentCatalog.all().size()==expected&&loaded.equipmentDefinitions().size()==expected,"catalog and policy did not publish all enabled definitions together");
        var steel=definition("steel_training");var wave=definition("steel_wave");var duelist=definition("duelist_training");var guard=definition("duelist_guard");
        h.assertTrue(steel.reward().itemId().equals(wave.reward().itemId())&&duelist.reward().itemId().equals(guard.reward().itemId())
                &&!steel.name().equals(wave.name())&&!duelist.name().equals(guard.name()),"definitions could not share appearances with different names");
        try(var f=new Fixture(h)) {
            var p=f.player("EquipmentShape");var sharp=p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
            for(var entry:EquipmentCatalog.all()) {
                var stack=entry.reward().createStack(p.registryAccess());
                h.assertTrue(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString(EquipmentDefinition.ID_KEY).equals(entry.id().toString()),"canonical equipment lost its stable definition marker");
                h.assertTrue(stack.getHoverName().getString().equals(entry.name())&&stack.getMaxDamage()==1561&&stack.getMaxStackSize()==1,"name or durability diverged from authored definition");
                h.assertTrue(EnchantmentHelper.getItemEnchantmentLevel(sharp,stack)==entry.rule().sharpnessLevel(),"real enchantment did not match its derived power rule");
                var evaluated=loaded.policy().stateGear(List.of(RewardPowerState.identity(stack,p.registryAccess())),p.registryAccess());
                h.assertTrue(evaluated.size()==1&&evaluated.getFirst().attackDamage()==entry.rule().attackDamage(),"canonical reward was not uniquely recognized by its generated rule");
                p.setItemSlot(EquipmentSlot.MAINHAND,stack);p.doTick();
                close(h,p.getAttributeValue(Attributes.ATTACK_DAMAGE),1+entry.rule().attackDamage(),"real item attack modifiers");
                close(h,p.getAttributeValue(Attributes.ATTACK_SPEED),1.6,"real item attack speed");
            }
            var a=EquipmentDefinition.parse(ResourceLocation.parse("test:identical_a"),weapon("steel_training"));
            var b=EquipmentDefinition.parse(ResourceLocation.parse("test:identical_b"),weapon("steel_training"));
            h.assertTrue(!RewardPowerState.identity(a.reward().createStack(p.registryAccess()),p.registryAccess()).equals(
                    RewardPowerState.identity(b.reward().createStack(p.registryAccess()),p.registryAccess())),"identical appearance/name/stats collapsed two equipment definition IDs");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_definition")
    public static void definitionsRejectCombatOverridesWrongAppearanceAndInvalidBounds(GameTestHelper h) {
        for(String key:List.of("minecraft:custom_data","minecraft:custom_name","minecraft:attribute_modifiers","minecraft:enchantments",
                "minecraft:max_damage","minecraft:damage","minecraft:max_stack_size","!minecraft:custom_data","minecraft:unbreakable","minecraft:food","!minecraft:lore")) {
            var json=weapon("steel_training");json.getAsJsonObject("components").add(key,new JsonObject());
            rejects(h,()->EquipmentDefinition.parse(ResourceLocation.parse("test:override"),json),"reserved/non-cosmetic component accepted: "+key);
        }
        var protection=weapon("steel_training");protection.getAsJsonObject("enchantments").addProperty("minecraft:protection",1);
        rejects(h,()->EquipmentDefinition.parse(ResourceLocation.parse("test:protection"),protection),"held-sword protection inflated armor power");
        var name=weapon("steel_training");name.addProperty("name","x".repeat(129));rejects(h,()->EquipmentDefinition.parse(ResourceLocation.parse("test:name"),name),"oversized name accepted");
        var speed=weapon("steel_training");speed.addProperty("attackSpeed",0);rejects(h,()->EquipmentDefinition.parse(ResourceLocation.parse("test:speed"),speed),"zero-speed weapon accepted");
        var badSkill=weapon("steel_training");badSkill.addProperty("rightClickSkill","proto_cbt:blade_wave");rejects(h,()->EquipmentDefinition.parse(ResourceLocation.parse("test:skill"),badSkill),"right-click skill omitted its power entry");
        var diamond=weapon("steel_training");diamond.addProperty("itemId","minecraft:diamond");
        rejects(h,()->EquipmentCatalog.load(resources(Map.of(file("test:nonweapon"),diamond.toString()))),"non-SwordItem bypassed weapon classification");
        var unknown=weapon("steel_training");unknown.addProperty("itemId","test:missing_sword");
        rejects(h,()->EquipmentCatalog.load(resources(Map.of(file("test:unknown"),unknown.toString()))),"unknown item appearance accepted");
        var excessive=new LinkedHashMap<ResourceLocation,String>();for(int i=0;i<=EquipmentCatalog.MAX_DEFINITIONS;i++)excessive.put(file("test:overflow_"+i),weapon("steel_training").toString());
        rejects(h,()->EquipmentCatalog.load(resources(excessive)),"unbounded equipment resource count accepted");h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_definition")
    public static void reloadPublishesPolicyAndDefinitionsAtomicallyAndFailureCannotLeakPartialCatalog(GameTestHelper h) {
        var manager=CombatPowerPolicyManager.INSTANCE;var previous=manager.current();
        try {
            Map<ResourceLocation,String> files=new LinkedHashMap<>();files.put(ResourceLocation.parse("mythictrpg:mythictrpg/combat_power/default.json"),source("/data/mythictrpg/mythictrpg/combat_power/default.json").toString());
            files.put(file("reuse_demo:steel_training"),weapon("steel_training").toString());
            var prepared=manager.prepare(resources(files),null);
            h.assertTrue(manager.current()==previous&&EquipmentCatalog.all().size()==previous.equipmentDefinitions().size(),"prepare mutated the live catalog before apply");
            manager.apply(prepared,null,null);
            h.assertTrue(manager.current().policy()!=null&&EquipmentCatalog.all().size()==1&&EquipmentCatalog.find(ResourceLocation.parse("reuse_demo:steel_training")).isPresent()
                    &&manager.current().policy().items().size()==12,"policy and definition map did not change in one snapshot");
            var invalid=weapon("steel_training");invalid.addProperty("itemId","minecraft:diamond");files.put(file("reuse_demo:steel_training"),invalid.toString());
            var rejected=manager.prepare(resources(files),null);
            h.assertTrue(manager.current()==prepared&&EquipmentCatalog.all().size()==1,"invalid prepare partially overwrote the current catalog");
            manager.apply(rejected,null,null);
            h.assertTrue(manager.current().policy()==null&&EquipmentCatalog.all().isEmpty(),"failed policy reload left active definitions without matching power rules");
            h.assertTrue(new CombatPowerPolicyManager.Loaded(previous.policy(),"compatibility fixture").equipmentDefinitions().isEmpty(),"two-argument test snapshot constructor was not preserved");
        }finally{manager.apply(previous,null,null);}h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_definition_history",timeoutTicks=300)
    public static void realDeliveryAuthenticatesDefinitionNotAppearanceAndKeepsHistoricalPowerAndSkillsSeparate(GameTestHelper h) {
        h.startSequence().thenWaitUntil(()->h.assertTrue(CombatPowerRuntime.captureReady(h.getLevel().getServer()),"equipment power startup fence"))
                .thenExecute(()->{
                    try(var f=new Fixture(h)) {
                        var owner=f.player("EquipmentOwner");var other=f.player("EquipmentOther");
                        var training=definition("steel_training");var wave=definition("steel_wave");var guard=definition("duelist_guard");
                        f.grant(owner,training.reward());f.grant(owner,wave.reward());f.grant(owner,guard.reward());
                        var history=RewardPowerState.get(owner.server).history(owner.getUUID()).orElseThrow();
                        h.assertTrue(history.complete()&&history.items().size()==3,"same-appearance definitions collapsed or lacked complete actual-delivery history");
                        owner.setItemSlot(EquipmentSlot.MAINHAND,wave.reward().createStack(owner.registryAccess()));
                        h.assertTrue(WeaponSkillRuntime.heldSkill(owner).orElseThrow().id().equals(ResourceLocation.parse("proto_cbt:blade_wave")),"wave definition did not bind its own acquired weapon skill");
                        other.setItemSlot(EquipmentSlot.MAINHAND,owner.getMainHandItem().copy());
                        h.assertTrue(WeaponSkillRuntime.heldSkill(other).isEmpty(),"borrowed appearance inherited another player's acquisition");
                        owner.getMainHandItem().setDamageValue(20);
                        h.assertTrue(WeaponSkillRuntime.heldSkill(owner).isPresent(),"normal durability use invalidated definition ownership");
                        owner.setItemSlot(EquipmentSlot.MAINHAND,guard.reward().createStack(owner.registryAccess()));
                        h.assertTrue(WeaponSkillRuntime.heldSkill(owner).orElseThrow().id().equals(ResourceLocation.parse("proto_cbt:timed_parry")),"second appearance did not bind its distinct skill");
                        owner.setItemSlot(EquipmentSlot.MAINHAND,training.reward().createStack(owner.registryAccess()));
                        h.assertTrue(CombatPowerRuntime.registeredAcquiredGear(owner,owner.getMainHandItem()).isPresent()&&WeaponSkillRuntime.heldSkill(owner).isEmpty(),"same-appearance plain definition inherited the wave skill");
                        var fake=training.reward().createStack(owner.registryAccess());var data=fake.get(DataComponents.CUSTOM_DATA).copyTag();data.putString(EquipmentDefinition.ID_KEY,wave.id().toString());fake.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
                        owner.setItemSlot(EquipmentSlot.MAINHAND,fake);
                        h.assertTrue(CombatPowerRuntime.registeredAcquiredGear(owner,fake).isEmpty(),"definition marker alone authorized forged actual stats/name");
                        owner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(BuiltInRegistries.ITEM.get(wave.reward().itemId())));
                        h.assertTrue(CombatPowerRuntime.registeredAcquiredGear(owner,owner.getMainHandItem()).isEmpty()&&WeaponSkillRuntime.heldSkill(owner).isEmpty(),"bare registered appearance obtained reward stats or skill ownership");
                        var assessment=CombatPowerService.INSTANCE.assess(owner);var loaded=CombatPowerPolicyManager.INSTANCE.current();
                        var expected=loaded.policy().evaluate(CombatPowerRuntime.captureState(RewardPowerState.get(owner.server),loaded.policy(),owner),Map.of());
                        h.assertValueEqual(assessment.status(),CombatPowerAssessment.Status.AVAILABLE,"new canonical gear made power unavailable");
                        close(h,assessment.actualPower(),expected.actualPower(),"historical gear power changed with raw held appearance");
                        var restored=RewardPowerState.load(RewardPowerState.get(owner.server).save(new CompoundTag(),owner.registryAccess()),owner.registryAccess());
                        h.assertValueEqual(restored.history(owner.getUUID()).orElseThrow().items(),history.items(),"save/load changed stable equipment definition acquisition identities");
                    }
                }).thenSucceed();
    }

    private static EquipmentDefinition definition(String path){return EquipmentCatalog.find(ResourceLocation.parse("reuse_demo:"+path)).orElseThrow();}
    private static ResourceLocation file(String id){var resource=ResourceLocation.parse(id);return resource.withPath("mythictrpg/equipment/"+resource.getPath()+".json");}
    private static JsonObject weapon(String name){return source("/data/reuse_demo/mythictrpg/equipment/"+name+".json");}
    private static JsonObject source(String path){try(var input=EquipmentDefinitionGameTests.class.getResourceAsStream(path)){if(input==null)throw new IllegalStateException("missing fixture "+path);return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}}
    private static void close(GameTestHelper h,double actual,double expected,String message){h.assertTrue(Math.abs(actual-expected)<.000001,message+": "+actual+" != "+expected);}
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void rejects(GameTestHelper h,Work work,String message){boolean rejected=false;try{work.run();}catch(IllegalArgumentException failure){rejected=true;}catch(Exception failure){throw new IllegalStateException(failure);}h.assertTrue(rejected,message);}
    private static ResourceManager resources(Map<ResourceLocation,String> text){
        var entries=new LinkedHashMap<ResourceLocation,Resource>();text.forEach((id,value)->entries.put(id,new Resource(null,()->new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)))));
        return new ResourceManager(){
            @Override public Set<String> getNamespaces(){var namespaces=new HashSet<String>();entries.keySet().forEach(id->namespaces.add(id.getNamespace()));return namespaces;}
            @Override public Optional<Resource> getResource(ResourceLocation id){return Optional.ofNullable(entries.get(id));}
            @Override public List<Resource> getResourceStack(ResourceLocation id){return getResource(id).map(List::of).orElse(List.of());}
            @Override public Map<ResourceLocation,Resource> listResources(String path,Predicate<ResourceLocation> filter){var result=new LinkedHashMap<ResourceLocation,Resource>();entries.forEach((id,value)->{if(id.getPath().startsWith(path+"/")&&filter.test(id))result.put(id,value);});return result;}
            @Override public Map<ResourceLocation,List<Resource>> listResourceStacks(String path,Predicate<ResourceLocation> filter){var result=new LinkedHashMap<ResourceLocation,List<Resource>>();listResources(path,filter).forEach((id,value)->result.put(id,List.of(value)));return result;}
            @Override public Stream<PackResources> listPacks(){return Stream.empty();}
        };
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final List<ServerPlayer> players=new ArrayList<>();final List<EmbeddedChannel> channels=new ArrayList<>();Fixture(GameTestHelper h){this.h=h;}
        ServerPlayer player(String name){var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),name),false);
            var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);p.server.getPlayerList().placeNewPlayer(connection,p,cookie);p.setNoGravity(true);p.setGameMode(GameType.SURVIVAL);players.add(p);return p;}
        void grant(ServerPlayer player,NpcRewardEntry reward){var claims=RewardClaimState.get(player.server);var source=ResourceLocation.parse("test:equipment_fixture/"+UUID.randomUUID());
            var receipt=claims.create(new RewardClaim(UUID.randomUUID(),player.getUUID(),ResourceLocation.parse("test:equipment_fixture_source"),source,"equipment definition fixture",List.of(reward),List.of(),false,Optional.empty(),player.server.overworld().getGameTime(),RewardOrigin.LEGACY_UNSPECIFIED));
            claims.markAutomaticGranted(receipt.claimId());var result=RewardExecutionService.grant(player,receipt.godId(),List.of(reward),RewardGrantPurpose.QUEST);h.assertTrue(result.granted(),"equipment actual reward delivery: "+result.reason());}
        @Override public void close(){players.forEach(p->p.server.getPlayerList().remove(p));channels.forEach(EmbeddedChannel::finishAndReleaseAll);}
    }
}
